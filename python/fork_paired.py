#!/usr/bin/env python3
"""Which of several networks predicts the difference between two actions of a decision better, on the same forks
(docs/training.md, "Making the analysis' outputs more accurate").

    python fork_paired.py --data DIR... --critic CRITIC.pt REF=ref.pt NAME=a.pt NAME2=b.pt+c.pt ...

On the forks of hands no network was trained on (two actions of a decision, each finished on the same wall), with dQ
a network's predicted difference (played - other) and dr the realized one, both in points for a typical payment of 4
(the app's grades). Every network is compared with the first (REF) on the same forks, grouped by REF's |dQ| so that
each group is the same set of forks for all:

- mse: the mean of (dQ - dr)^2 - (dQ_ref - dr)^2, with its standard error. dr is the true difference plus luck that
  does not depend on what either network says, so this estimates how much smaller (negative) the network's squared
  error to the true difference is; the luck largely cancels, as it is multiplied by dQ - dQ_ref.
- gain: the mean of dr * (sign dQ - sign dQ_ref) / 2, the realized points per fork of taking the network's choice
  between the two actions instead of REF's.
- ratio: by the network's own |dQ| in [0.02, 0.1), the realized difference in its direction over the predicted one.

Then for each network, by its own |dQ|: the same ratio where its policy prefers the same of the two actions as its Q
and where it prefers the other (the app could use the policy to tell a real difference from noise); over all forks,
and over those where one of the two actions is the best by Q of all the legal ones (as in the app's grades, which
count the played action's loss to Q's best).

With --critic, the same with the critic's difference (it sees every hand) in place of dr: much less luck, but it is
only a proxy if the critic is right on average. Compare the two on REF before trusting it.

A NAME's checkpoints joined by + are averaged (their Q).
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

EDGES = ((0, 0.02), (0.02, 0.05), (0.05, 0.1), (0.02, 0.1), (0.1, 0.25), (0.25, 1e9), (0, 1e9))


def load(path, device):
    m, st = load_checkpoint(path, device)
    if "ema" in st:
        m.load_state_dict(st["ema"])
    return m.eval()


def report(names, dp, target, label):
    ref = dp[names[0]]
    a = np.abs(ref)
    print(f"== against {label}: by REF's |dQ|; mse difference (negative: better) and gain per fork, each ± se")
    for name in names:
        d = dp[name]
        parts = []
        for lo, hi in EDGES:
            m = (a >= lo) & (a < hi)
            if name == names[0]:
                parts.append(f"[{lo:g},{hi:g}) n{m.sum()} mse {((d[m] - target[m]) ** 2).mean():.4f}")
                continue
            x = (d[m] - target[m]) ** 2 - (ref[m] - target[m]) ** 2
            g = target[m] * (np.sign(d[m]) - np.sign(ref[m])) / 2
            parts.append(f"[{lo:g},{hi:g}) mse {x.mean():+.5f}±{x.std() / np.sqrt(m.sum()):.5f} "
                         f"gain {g.mean():+.4f}±{g.std() / np.sqrt(m.sum()):.4f}")
        own = np.abs(d)
        m = (own >= 0.02) & (own < 0.1)
        r = target[m] * np.sign(d[m])
        parts.append(f"own [0.02,0.1) n{m.sum()} ratio {r.mean() / own[m].mean():.2f}±{r.std() / np.sqrt(m.sum()) / own[m].mean():.2f}")
        print(f"{name:14s} " + " | ".join(parts), flush=True)


def report_policy(names, dp, lp, best, target):
    print("== by each network's own |dQ|: the realized difference in Q's direction over the predicted one (ratio ± se), "
          "all, where the policy agrees with Q and where it does not (with the share of forks)")
    for name in names:
        d, s = dp[name], np.sign(dp[name])
        agree = np.sign(lp[name]) == s
        a = np.abs(d)
        for label, sub in (("every fork", np.ones_like(agree)), ("Q's best", best[name])):
            parts = []
            for lo, hi in ((0, 0.05), (0.05, 0.1), (0.1, 0.15), (0.15, 0.25), (0.25, 0.5), (0.5, 1e9)):
                m = sub & (a >= lo) & (a < hi)
                cells = []
                for part, mm in (("all", m), ("agree", m & agree), ("disagree", m & ~agree)):
                    r = target[mm] * s[mm]
                    cells.append(f"{part} {mm.sum() / m.sum():.2f} {r.mean() / a[mm].mean():+.2f}±"
                                 f"{r.std() / np.sqrt(mm.sum()) / a[mm].mean():.2f}")
                parts.append(f"[{lo:g},{hi:g}) n{m.sum()} " + " ".join(cells))
            print(f"{name:10s} {label:10s} " + " | ".join(parts), flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", nargs="+", required=True)
    ap.add_argument("--critic")
    ap.add_argument("--lib", default=DEFAULT_LIB)
    ap.add_argument("--expand-threads", type=int, default=3)
    ap.add_argument("--save", help="Write every fork's differences to this .npz")
    ap.add_argument("--device", default="cuda", help="cuda, or cpu (threads by OMP_NUM_THREADS)")
    ap.add_argument("--fp16", action="store_true", help="Run the networks under float16 autocast (cuda)")
    ap.add_argument("nets", nargs="+", help="NAME=CKPT[+CKPT...]; the first is the reference")
    args = ap.parse_args()
    device = torch.device(args.device)
    specs, cache = [], {}
    for spec in args.nets:
        name, _, paths = spec.partition("=")
        for p in paths.split("+"):
            if p not in cache:
                cache[p] = load(p, device)
        specs.append((name, [cache[p] for p in paths.split("+")]))
    critic = load(args.critic, device) if args.critic else None
    lib = Library(args.lib, threads=args.expand_threads)
    dps = {n: [] for n, _ in specs}
    lps = {n: [] for n, _ in specs}
    bests = {n: [] for n, _ in specs}
    drs, dcs = [], []
    with torch.no_grad():
        for batch in batches(lib, list_files(args.data), 2048, part="all", symmetries=False, drop_last=False,
                             shuffle=False):
            b = to_torch(batch, device)
            known = b["known"] > 0
            other = known & (torch.nn.functional.one_hot(b["played"].long(), NUM_ACTIONS) == 0)
            has = other.any(-1)
            if not bool(has.any()):
                continue
            # Only the decisions with a fork (uint16 cannot be indexed on the GPU).
            b["events"] = b["events"].to(torch.int32)
            b = {k: v[has] for k, v in b.items()}
            other = other[has]
            has = torch.ones(len(other), dtype=torch.bool, device=device)
            pl = b["played"].long()[:, None]
            alt = other.float().argmax(-1, keepdim=True)
            coef = b["coef"].float()
            scale = payment_unit(b).float() / 4

            def diff(q):
                return ((q.gather(1, pl) - q.gather(1, alt)).squeeze(1) / scale)[has].cpu().numpy()

            y, tl = outcome_labels(b)
            drs.append(diff(compose_q(y[..., :NUM_EVENTS], tl, coef)))
            qs, pols = {}, {}
            for p, m in cache.items():
                with torch.autocast(device_type=device.type, dtype=torch.float16, enabled=args.fp16):
                    o = m(*m.inputs(b))
                qs[p] = compose_q(torch.sigmoid(o["outcomes"][..., :NUM_EVENTS].float()), o["tai"].float(), coef)
                if "policy" in o:
                    pols[p] = torch.log_softmax(o["policy"].float().masked_fill(b["legal"] == 0, -1e9), -1)
            for name, ms in specs:
                paths = name_paths(args.nets, name)
                q = torch.stack([qs[p] for p in paths]).mean(0)
                dps[name].append(diff(q))
                top = q.masked_fill(b["legal"] == 0, -1e9).max(-1, keepdim=True).values
                bests[name].append(((q.gather(1, pl) >= top) | (q.gather(1, alt) >= top)).squeeze(1)[has].cpu().numpy())
                if all(p in pols for p in paths):
                    # The policy's log ratio of the two actions (the mean log-probability for several).
                    lpi = torch.stack([pols[p] for p in paths]).mean(0)
                    lps[name].append((lpi.gather(1, pl) - lpi.gather(1, alt)).squeeze(1)[has].cpu().numpy())
            if critic is not None:
                with torch.autocast(device_type=device.type, dtype=torch.float16, enabled=args.fp16):
                    o = critic(*critic.inputs(b))
                dcs.append(diff(compose_q(torch.sigmoid(o["outcomes"][..., :NUM_EVENTS].float()), o["tai"].float(),
                                          coef)))
    dr = np.concatenate(drs).astype(np.float64)
    dp = {n: np.concatenate(v).astype(np.float64) for n, v in dps.items()}
    names = [n for n, _ in specs]
    print(f"{len(dr)} forks; realized difference rms {np.sqrt((dr ** 2).mean()):.3f}")
    report(names, dp, dr, "the realized results")
    lp = {n: np.concatenate(v).astype(np.float64) for n, v in lps.items() if v}
    best = {n: np.concatenate(v) for n, v in bests.items()}
    if lp:
        report_policy([n for n in names if n in lp], dp, lp, best, dr)
    if critic is not None:
        dc = np.concatenate(dcs).astype(np.float64)
        print(f"critic's difference rms {np.sqrt((dc ** 2).mean()):.3f}; its own [0.02,0.1) ratio "
              f"{ratio(dc, dr):.2f}")
        report(names, dp, dc, "the critic's difference")
    if args.save:
        np.savez_compressed(args.save, dr=dr, **({"dc": dc} if critic is not None else {}),
                            **{f"dp_{n}": v for n, v in dp.items()}, **{f"lp_{n}": v for n, v in lp.items()},
                            **{f"best_{n}": v for n, v in best.items()})


def name_paths(nets, name):
    for spec in nets:
        n, _, paths = spec.partition("=")
        if n == name:
            return paths.split("+")
    raise KeyError(name)


def ratio(d, target):
    a = np.abs(d)
    m = (a >= 0.02) & (a < 0.1)
    return (target[m] * np.sign(d[m])).mean() / a[m].mean()


if __name__ == "__main__":
    main()
