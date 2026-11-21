#!/usr/bin/env python3
"""How much of the critic's per-sample noise, as a target for the actor, is the order of the wall (docs/training.md,
"Making the analysis' outputs more accurate").

    python wall_share.py --data DIR... --critic C.pt --actor A.pt [--k 16]

The actor learns the critic's values of every action (distillation), each sample once. The critic sees every hand
and the order of the undrawn tiles; given the hands that order is uniformly random, so the mean of the critic over
reshuffled walls (data.full_reshuffled) is an unbiased target with less noise. On the forks of the
given hands (played action against the fork's), in points for a typical payment of 4:

- total: the mean of (dQ_critic - dQ_actor)^2 with the wall as recorded: the critic's spread around what the actor
  says (the hidden hands and wall, plus the actor's own error);
- wall: the mean over samples of the variance of dQ_critic over K reshuffled walls (corrected to an unbiased
  estimate of the variance over all walls);
- averaged: the mean of (mean over K walls of dQ_critic - dQ_actor)^2.
"""
import argparse
import os
import sys

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.data import DEFAULT_LIB, Hands, Library, full_reshuffled, list_files, to_torch  # noqa: E402
from mahjong.losses import payment_unit  # noqa: E402
from mahjong.model import NUM_ACTIONS, NUM_EVENTS, compose_q, load_checkpoint  # noqa: E402


def load(path, device):
    m, st = load_checkpoint(path, device)
    if "ema" in st:
        m.load_state_dict(st["ema"])
    return m.eval()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lib", default=DEFAULT_LIB)
    ap.add_argument("--data", nargs="+", required=True)
    ap.add_argument("--critic", required=True)
    ap.add_argument("--actor", required=True)
    ap.add_argument("--k", type=int, default=16)
    ap.add_argument("--marks", type=int, default=20000, help="Marked decisions to read (those with a fork count)")
    ap.add_argument("--threads", type=int, default=4)
    args = ap.parse_args()
    device = torch.device("cuda")
    lib = Library(args.lib, threads=args.threads)
    critic, actor = load(args.critic, device), load(args.actor, device)
    hands = Hands(list_files(args.data)[:4], lib.sizes["version"])
    marks = hands.marks[:args.marks]

    base, shuffled = [], []
    for i in range(0, len(marks), 4096):
        m = marks[i:i + 4096]
        base.append(lib.expand(hands.hands, hands.offsets, hands.rules, m[:, 0], m[:, 1]))
        shuffled.append(full_reshuffled(lib, hands.hands, hands.offsets, hands.rules, m[:, 0], m[:, 1], None, args.k,
                                        i + 1))

    def variant(v):
        return [dict(b, kind_full=s["kind_full_k"][:, v], seat_full=s["seat_full_k"][:, v],
                     global_full=s["global_full_k"][:, v]) for b, s in zip(base, shuffled)]

    def diffs(batches, model):
        res = []
        with torch.no_grad():
            for batch in batches:
                b = to_torch(batch, device)
                known = b["known"] > 0
                other = known & (torch.nn.functional.one_hot(b["played"].long(), NUM_ACTIONS) == 0)
                has = other.any(-1)
                pl = b["played"].long()[:, None]
                alt = other.float().argmax(-1, keepdim=True)
                coef = b["coef"].float()
                scale = payment_unit(b).float() / 4
                o = model(*model.inputs(b))
                q = compose_q(torch.sigmoid(o["outcomes"][..., :NUM_EVENTS].float()), o["tai"].float(), coef)
                d = ((q.gather(1, pl) - q.gather(1, alt)).squeeze(1) / scale)
                res.append(torch.where(has, d, torch.full_like(d, float("nan"))).cpu().numpy())
        return np.concatenate(res).astype(np.float64)

    da = diffs(base, actor)
    dc0 = diffs(base, critic)
    keep = ~np.isnan(da)
    dcs = np.stack([diffs(variant(v), critic) for v in range(args.k)])
    da, dc0, dcs = da[keep], dc0[keep], dcs[:, keep]
    total = np.mean((dc0 - da) ** 2)
    wall = np.mean(dcs.var(axis=0, ddof=1))
    mean_k = dcs.mean(axis=0)
    averaged = np.mean((mean_k - da) ** 2)
    # The mean over K walls still holds wall / K of the wall's variance: without it, the mean over every wall.
    limit = averaged - wall / args.k
    print(f"{keep.sum()} forks of {len(marks)} marked decisions; K = {args.k} reshuffled walls; points for a payment of 4")
    print(f"  actor dQ rms {np.sqrt(np.mean(da ** 2)):.3f}; critic dQ rms {np.sqrt(np.mean(dc0 ** 2)):.3f}")
    print(f"  total   (critic as recorded - actor)^2      {total:.4f}")
    print(f"  wall    variance of the critic over walls    {wall:.4f}  ({wall / total:.1%} of total)")
    print(f"  averaged (mean of K walls - actor)^2         {averaged:.4f}; over every wall {limit:.4f} "
          f"({limit / total:.1%} of total)")
    a = np.abs(da)
    for lo, hi in ((0, 0.02), (0.02, 0.1), (0.1, 0.25), (0.25, 1e9)):
        m = (a >= lo) & (a < hi)
        if m.sum() < 30:
            continue
        print(f"  by actor |dQ| [{lo:g},{hi:g}): n {m.sum():6d}  total {np.mean((dc0[m] - da[m]) ** 2):.4f}  "
              f"wall {np.mean(dcs[:, m].var(axis=0, ddof=1)):.4f}")


if __name__ == "__main__":
    main()
