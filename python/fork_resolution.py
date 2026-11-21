#!/usr/bin/env python3
"""How small a difference of Q between two actions of one decision is still real (docs/training.md, "Making the
analysis' outputs more accurate").

    python fork_resolution.py --data DIR... --actor A.pt [B.pt ...]

On the forks of hands no network was trained on (two actions of a decision, each finished on the same wall), by the
first network's predicted difference |dQ| (in points for a typical payment of 4, as the app's grades): the mean
realized difference in the predicted direction, with its standard error. Where the two agree, a difference that size
is real on average. And how far the networks of the list disagree on the same difference (their own noise).
"""
import argparse
import os
import sys

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.data import DEFAULT_LIB, Library, batches, list_files, to_torch  # noqa: E402
from mahjong.losses import outcome_labels, payment_unit  # noqa: E402
from mahjong.model import NUM_ACTIONS, NUM_EVENTS, compose_q, load_checkpoint  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("--data", nargs="+", required=True)
ap.add_argument("--actor", nargs="+", required=True)
ap.add_argument("--lib", default=DEFAULT_LIB)
ap.add_argument("--expand-threads", type=int, default=3)
args = ap.parse_args()
device = torch.device("cuda")
models = []
for p in args.actor:
    m, st = load_checkpoint(p, device)
    if "ema" in st:
        m.load_state_dict(st["ema"])
    models.append(m.eval())
lib = Library(args.lib, threads=args.expand_threads)
dps = [[] for _ in models]
drs, scales = [], []
with torch.no_grad():
    for batch in batches(lib, list_files(args.data), 2048, part="all", symmetries=False, drop_last=False, shuffle=False):
        b = to_torch(batch, device)
        known = b["known"] > 0
        other = known & (torch.nn.functional.one_hot(b["played"].long(), NUM_ACTIONS) == 0)
        has = other.any(-1)
        if not bool(has.any()):
            continue
        played = b["played"].long()[:, None]
        alt = other.float().argmax(-1, keepdim=True)
        coef = b["coef"].float()
        y, tl = outcome_labels(b)
        ql = compose_q(y[..., :NUM_EVENTS], tl, coef)
        scale = payment_unit(b).float() / 4
        drs.append(((ql.gather(1, played) - ql.gather(1, alt)).squeeze(1) / scale)[has].cpu().numpy())
        scales.append(scale[has].cpu().numpy())
        for i, m in enumerate(models):
            out = m(*m.inputs(b))
            q = compose_q(torch.sigmoid(out["outcomes"][..., :NUM_EVENTS].float()), out["tai"].float(), coef)
            dps[i].append(((q.gather(1, played) - q.gather(1, alt)).squeeze(1) / scale)[has].cpu().numpy())
dr = np.concatenate(drs).astype(np.float64)
dp = [np.concatenate(d).astype(np.float64) for d in dps]
print(f"{len(dr)} forks; realized difference rms {np.sqrt((dr ** 2).mean()):.3f}; points for a typical payment of 4")
edges = [0, 0.01, 0.02, 0.03, 0.05, 0.075, 0.1, 0.15, 0.25, 0.5, 1, 1e9]
for i, p in enumerate(args.actor):
    a = np.abs(dp[i])
    s = np.sign(dp[i])
    print(f"== {os.path.basename(p)}: by |dQ|: n, mean |dQ|, mean realized in its direction ± se, ratio"
          + ("; |dQ - dQ of the others| p50 p90" if len(dp) > 1 else ""))
    for lo, hi in zip(edges[:-1], edges[1:]):
        m = (a >= lo) & (a < hi)
        if m.sum() < 50:
            continue
        r = dr[m] * s[m]
        line = (f"  [{lo:.3g}, {hi:.3g}): n {m.sum():7d}  pred {a[m].mean():.4f}  real {r.mean():+.4f} ± "
                f"{r.std() / np.sqrt(m.sum()):.4f}  ratio {r.mean() / a[m].mean():.2f}")
        for j in range(len(dp)):
            if j != i:
                d = np.abs(dp[i][m] - dp[j][m])
                line += f"  vs {os.path.basename(args.actor[j])[:12]}: {np.percentile(d, 50):.4f} {np.percentile(d, 90):.4f}"
        print(line)
    # Sign agreement of the realized results where the network claims a difference
    big = a >= 0.1
    print(f"  pairs with |dQ| >= 0.1: {big.mean():.3f} of forks")
