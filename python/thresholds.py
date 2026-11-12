#!/usr/bin/env python3
"""The thresholds of the analysis' colors and of the review's grades (docs/analysis.md, section 6), from the lines of
`mahjong review` (cpp/command/review.cpp).

    thresholds.py gaps FILE...         (--full files) for each figure a decision's list shows, how far the 2nd to
                                       6th action (by Q) are from the best: percentiles of |best - k-th|. The 75th is
                                       the gap worth a color, as Thirteen's NOTABLE_GAP.
    thresholds.py noise FILE_A FILE_B  the same hands reviewed by two networks (all four players greedy, the same
                                       seed): how much the loss of the same action differs between them, the floor
                                       under which a loss is noise.
    thresholds.py losses FILE...       a reviewed player's losses (max Q - Q(played)): how many a hand above each
                                       step, and the share of the loss they carry.

Points are the rules' payments; the grades' steps are in points under simple1 (a typical payment of 4 points:
Review.kt scales them by the rules' unit / 4).
"""

import json
import sys
from collections import defaultdict

import numpy as np

STEPS = [0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 0.75, 1.0, 1.5, 2.0]


def lines(path):
    with open(path) as f:
        for line in f:
            d = json.loads(line)
            if "legal" in d:
                yield d


def figures(d, i):
    """An action's figures as the list shows them (Review.kt figureItems)."""
    ev = d["ev"][i]
    out = {"q": d["q"][i], "win": sum(ev[:4]), "tsumo": ev[0], "deal": sum(ev[4:7]), "others": sum(ev[7:])}
    index = d["legal"][i]
    if index < 34:
        dn = np.array(d["danger"])
        out["now"] = float(1 - np.prod(1 - dn[:, index]))
    return out


def gaps(paths):
    diffs = defaultdict(list)
    n = 0
    for path in paths:
        for d in lines(path):
            if len(d["legal"]) < 2 or "ev" not in d:
                continue
            n += 1
            order = sorted(range(len(d["legal"])), key=lambda i: -d["q"][i])
            best = figures(d, order[0])
            for i in order[1:6]:
                f = figures(d, i)
                for k, v in f.items():
                    if k in best:
                        diffs[k].append(abs(v - best[k]))
    print(f"{n} decisions with a choice; |best - k-th| for k = 2..6 (by Q)")
    print(f"{'figure':8} {'n':>7} {'p50':>8} {'p75':>8} {'p90':>8}")
    for k, v in diffs.items():
        a = np.array(v)
        print(f"{k:8} {len(a):7d} {np.percentile(a, 50):8.4f} {np.percentile(a, 75):8.4f} {np.percentile(a, 90):8.4f}")


def loss_of(d, i):
    return max(d["q"]) - d["q"][i]


def noise(a, b):
    left = {(d["h"], d["seat"], d["t"]): d for d in lines(a)}
    played, others = [], []
    same = 0
    for d in lines(b):
        e = left.get((d["h"], d["seat"], d["t"]))
        if e is None or e["legal"] != d["legal"]:
            continue
        same += 1
        for i in range(len(d["legal"])):
            x = abs(loss_of(d, i) - loss_of(e, i))
            (played if i == d["played"] else others).append(x)
    print(f"{same} decisions matched ({len(left)} in the first file)")
    for name, v in (("the action played", played), ("every action", played + others)):
        a = np.array(v)
        print(f"  |loss_A - loss_B|, {name}: mean {a.mean():.4f}  p50 {np.percentile(a, 50):.4f}  p75 {np.percentile(a, 75):.4f}"
              f"  p90 {np.percentile(a, 90):.4f}  p95 {np.percentile(a, 95):.4f}")


def losses(paths):
    hands = set()
    all_losses = []
    for path in paths:
        for d in lines(path):
            hands.add((path, d["h"]))
            if len(d["legal"]) > 1:
                all_losses.append(loss_of(d, d["played"]))
    a = np.array(all_losses)
    h = len(hands)
    print(f"{h} hands, {len(a)} decisions with a choice, loss a hand {a.sum() / h:.3f}")
    print(f"{'step':>6} {'a hand':>8} {'share of decisions':>19} {'share of the loss':>18}")
    for s in STEPS:
        m = a > s
        print(f"{s:6.2f} {m.sum() / h:8.3f} {m.mean():19.4f} {a[m].sum() / a.sum():18.3f}")


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    {"gaps": lambda: gaps(args), "noise": lambda: noise(*args), "losses": lambda: losses(args)}[cmd]()
