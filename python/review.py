#!/usr/bin/env python3
"""What a review of hands by the network is worth (docs/analysis.md), from the lines of `mahjong review`.

    review.py losses FILE        what the review says the reviewed role lost, beside its actual results
    review.py heads FILE         the opponent heads (ready, danger) against what was hidden
    review.py list FILE          (--full files) the largest losses, by kind of decision
    review.py show FILE H T      (--full files) one decision as the app might show it: hand H, decision T
    review.py hand FILE H        (--full files) the reviewed seat's whole hand, decision by decision
    review.py aux DIR [N]        the training-only heads' baselines by counting alone, on self-play data, to set
                                 beside the held-out losses of train.jsonl

Results are in points (the base and the tai, as the rules pay them). "max Q" is the best action's expected result
by the network's outcome events, "V" the policy's mean of them; the loss of a decision is max Q - Q(played).
"""

import glob
import json
import math
import os
import sys
from collections import defaultdict

import numpy as np

HONORS = "東南西北白發中"
FLOWERS = "春夏秋冬梅蘭菊竹"
SUITS = {"m": "萬", "p": "筒", "s": "條"}
SEATS = ("自己", "下家", "對家", "上家")
FROM_READY = ("已聽", "一進聽", "二進聽", "三進聽以上")
VERBS = {"discard": "打", "tsumo": "自摸", "ron": "胡", "pass": "過", "pon": "碰", "kan": "槓", "minkan": "槓",
         "ankan": "暗槓", "kakan": "加槓", "chi": "吃"}


def load(path):
    """-> (the decisions by (hand, seat), the hands' end lines by hand)."""
    decisions, ends = defaultdict(list), {}
    with open(path) as f:
        for line in f:
            j = json.loads(line)
            if j.get("end"):
                ends[j["h"]] = j
            else:
                decisions[(j["h"], j["seat"])].append(j)
    return decisions, ends


def standard_error(x):
    x = np.asarray(x, float)
    return x.std(ddof=1) / math.sqrt(len(x)) if len(x) > 1 else float("nan")


def action_type(index):
    if index < 34:
        return "discard"
    if index < 68:
        return "kong"
    return ("tsumo", "ron", "pass", "pon", "minkan", "chi", "chi", "chi")[index - 68]


# ----- losses -----

def losses(path):
    decisions, ends = load(path)
    rows, sizes, first = [], [], []
    by_type = defaultdict(lambda: [0, 0.0])
    same_q = same_policy = same_greedy = best_greedy = count = 0
    for (h, seat), ds in decisions.items():
        result = ends[h]["points"][seat]
        by_max = by_policy = 0.0
        over = [0, 0, 0, 0]
        for d in ds:
            q, pol = np.array(d["q"]), np.array(d["pol"])
            played = d["played"]
            a = float(q.max() - q[played])
            by_max += a
            by_policy += float((pol * q).sum() - q[played])
            for i, limit in enumerate((0.05, 0.2, 0.5, 1.0)):
                over[i] += a > limit
            best = int(q.argmax())
            count += 1
            same_q += best == played
            same_policy += int(pol.argmax()) == played
            same_greedy += d["legal"][played] == d["greedy"]
            best_greedy += d["legal"][best] == d["greedy"]
            t = by_type[(action_type(d["legal"][best]), action_type(d["legal"][played]))]
            t[0] += 1
            t[1] += a
            sizes.append(a)
        q0 = np.array(ds[0]["q"])
        first.append((float(q0.max()), result))
        rows.append((result, by_max, by_policy, *over))
    rows = np.array(rows, float)
    n = len(rows)
    print(f"{path}: {n} hands of the reviewed roles, {count / n:.1f} decisions with a choice a hand")
    print(f"  actual result a hand               {rows[:, 0].mean():+.3f} +- {standard_error(rows[:, 0]):.3f}")
    print(f"  sum of max Q - Q(played) a hand     {rows[:, 1].mean():.3f} +- {standard_error(rows[:, 1]):.3f}")
    print(f"  sum of V - Q(played) a hand         {rows[:, 2].mean():+.3f} +- {standard_error(rows[:, 2]):.3f}")
    print(f"  the action played is the best by Q in {same_q / count:.3f}, the policy's first in "
          f"{same_policy / count:.3f}, greedy's in {same_greedy / count:.3f}; the best by Q is greedy's in "
          f"{best_greedy / count:.3f}")
    print("  decisions a hand that lose more than 0.05 / 0.2 / 0.5 / 1 point: "
          + " / ".join(f"{rows[:, 3 + i].mean():.2f}" for i in range(4)))
    sizes = np.sort(np.array(sizes))[::-1]
    if sizes.sum() > 0:
        cum = np.cumsum(sizes) / sizes.sum()
        print("  share of the loss in the largest 1% / 5% / 20% of the decisions: "
              + " / ".join(f"{cum[max(0, int(len(sizes) * p) - 1)]:.2f}" for p in (0.01, 0.05, 0.2)))
        print("  by (best action, action played): decisions a hand, loss a hand")
        for key, t in sorted(by_type.items(), key=lambda kv: -kv[1][1]):
            if t[1] / n >= 0.002:
                print(f"    {key[0]:8s} {key[1]:8s} {t[0] / n:6.2f} {t[1] / n:7.3f}")
    first = np.array(first)
    slope = np.polyfit(first[:, 0], first[:, 1], 1)
    print(f"  the hand's result against max Q at the seat's first decision: slope {slope[0]:.2f}, offset "
          f"{slope[1]:+.3f}, correlation {np.corrcoef(first[:, 0], first[:, 1])[0, 1]:.3f}")
    if rows[:, 1].std() > 0:
        print(f"  correlation of a hand's loss with its result: {np.corrcoef(rows[:, 1], rows[:, 0])[0, 1]:.3f}")


# ----- heads -----

def auc(score, label):
    order = np.argsort(score, kind="stable")
    ranks = np.empty(len(score))
    ranks[order] = np.arange(1, len(score) + 1)
    pos = label.sum()
    neg = len(label) - pos
    return float("nan") if pos == 0 or neg == 0 else (ranks[label == 1].sum() - pos * (pos + 1) / 2) / (pos * neg)


def log_loss(p, y):
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return float(-(y * np.log(p) + (1 - y) * np.log(1 - p)).mean())


def calibration(p, y, edges):
    for lo, hi in zip(edges[:-1], edges[1:]):
        m = (p >= lo) & (p < hi)
        if m.sum():
            print(f"      {lo:.3f}-{min(hi, 1):.3f}: {p[m].mean():.4f} -> {y[m].mean():.4f} ({m.sum()})")


def heads(path):
    decisions, _ = load(path)
    ready_p, ready_y, wall, classes, truth = [], [], [], [], []
    danger_p, danger_y, mine_p, mine_y = [], [], [], []
    top1 = top3 = num_ready = 0
    on_waits, on_all = [], []
    for ds in decisions.values():
        for d in ds:
            dn = np.array(d["danger"])
            masks = []
            for r in range(3):
                ready_p.append(d["ready"][r][0])
                ready_y.append(d["sh"][r] == 0)
                classes.append(d["ready"][r])
                truth.append(d["sh"][r])
                wall.append(d["wall"])
                w = d["waits"][r]
                y = np.array([(w >> k) & 1 for k in range(34)])
                masks.append(y)
                danger_p.append(dn[r])
                danger_y.append(y)
                if w:
                    num_ready += 1
                    order = np.argsort(-dn[r])
                    top1 += y[order[0]]
                    top3 += y[order[:3]].any()
                    on_waits.append(dn[r][y == 1].sum())
                    on_all.append(dn[r].sum())
            if d["phase"] == 0:
                # The seat's own discards: whether any opponent wins on the kind now.
                anyone = np.max(masks, axis=0)
                total = 1 - np.prod(1 - dn, axis=0)
                for index in d["legal"]:
                    if index < 34:
                        mine_p.append(total[index])
                        mine_y.append(anyone[index])
    ready_p, ready_y, wall = np.array(ready_p), np.array(ready_y, float), np.array(wall)
    print(f"{path}: {len(ready_p)} (decision, opponent) pairs; the opponent is ready in {ready_y.mean():.3f}")
    by_wall = np.zeros_like(ready_p)
    for w in np.unique(wall):
        m = wall == w
        by_wall[m] = ready_y[m].mean()
    print(f"  ready: AUC {auc(ready_p, ready_y):.3f} (by the tiles left in the wall alone {auc(by_wall, ready_y):.3f}); "
          f"log loss {log_loss(ready_p, ready_y):.4f} (the wall alone {log_loss(by_wall, ready_y):.4f})")
    print("    predicted -> real share (count)")
    calibration(ready_p, ready_y, [0, 0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0001])
    classes, truth = np.array(classes), np.array(truth)
    print(f"    from ready, four classes: right in {(classes.argmax(1) == truth).mean():.3f} (the commonest class "
          f"{np.bincount(truth).max() / len(truth):.3f})")
    danger_p, danger_y = np.concatenate(danger_p), np.concatenate(danger_y).astype(float)
    print(f"  danger: the opponent wins on the kind in {danger_y.mean():.4f}; AUC {auc(danger_p, danger_y):.3f}; log "
          f"loss {log_loss(danger_p, danger_y):.5f} (its rate alone "
          f"{log_loss(np.full_like(danger_p, danger_y.mean()), danger_y):.5f})")
    print(f"    with a ready opponent ({num_ready}): its most dangerous kind is a wait in {top1 / num_ready:.3f}, "
          f"one of the three most dangerous in {top3 / num_ready:.3f}; danger summed over its waits "
          f"{np.mean(on_waits):.3f}, over all kinds {np.mean(on_all):.3f}")
    print("    predicted -> real share (count)")
    edges = [0, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.3, 0.5, 1.0001]
    calibration(danger_p, danger_y, edges)
    mine_p, mine_y = np.array(mine_p), np.array(mine_y, float)
    print(f"  the seat's own discards ({len(mine_p)}): somebody wins on it in {mine_y.mean():.4f}; AUC "
          f"{auc(mine_p, mine_y):.3f}")
    print("    predicted (any opponent) -> real share (count)")
    calibration(mine_p, mine_y, edges)


# ----- examples (from --full lines) -----

def tile(t):
    if t is None:
        return "?"
    n, s = int(t[0]), t[1]
    return HONORS[n - 1] if s == "z" else FLOWERS[n - 1] if s == "f" else f"{n}{SUITS[s]}"


def tiles(s):
    """'123m55z' -> '123萬 白白'."""
    out, digits = [], ""
    for ch in s:
        if ch.isdigit():
            digits += ch
            continue
        if ch == "z":
            out.append("".join(HONORS[int(d) - 1] for d in digits))
        elif ch == "f":
            out.append("".join(FLOWERS[int(d) - 1] for d in digits))
        else:
            out.append(digits + SUITS[ch])
        digits = ""
    return " ".join(out)


def action_text(a):
    parts = a.split()
    verb = VERBS.get(parts[0], parts[0])
    if len(parts) == 1:
        return verb
    return verb + (tiles(parts[1]) if len(parts[1]) > 2 else tile(parts[1]))


def figures(d, i):
    """One action: the chances to win (and by self-draw), to deal in, of another's self-draw, and for a discard
    the chance that somebody wins on it now (and for each opponent)."""
    ev = d["ev"][i]
    index = d["legal"][i]
    dn = np.array(d["danger"])
    now = float(1 - np.prod(1 - dn[:, index])) if index < 34 else None
    return {"win": sum(ev[:4]), "tsumo": ev[0], "deal": sum(ev[4:7]), "others": sum(ev[7:]), "now": now,
            "each": [float(dn[r, index]) for r in range(3)] if index < 34 else None}


def show(d, end=None):
    pos = d["pos"]
    me, setup = pos["me"], pos["setup"]
    print(f"第 {d['h']} 局第 {d['t']} 個決策：{'莊家' if setup['dealer'] == me else '閒家'}（連莊 {setup['streak']}），"
          f"已打 {d['turn']} 張，牌牆剩 {d['wall']} 張")
    line = f"  手牌：{tiles(pos['hand'])}"
    if pos.get("drawn"):
        line += f"（含剛摸的 {tile(pos['drawn'])}）"
    if pos.get("claim"):
        line += f"　{SEATS[(pos['claim']['seat'] - me) % 4]}打出 {tile(pos['claim']['tile'])}"
    print(line)
    for r in range(4):
        p = pos["players"][(me + r) % 4]
        melds = " ".join("[" + "".join(tile(x) for x in m["tiles"]) + "]" if m.get("tiles") else "[暗槓]"
                         for m in p["melds"])
        discards = " ".join(tile(x["tile"]) + ("*" if x["called"] else "") for x in p["discards"])
        line = f"  {SEATS[r]}：" + (f"花 {tiles(p['flowers'])}　" if p["flowers"] else "") \
            + (f"副露 {melds}　" if melds else "") + f"捨牌 {discards}"
        if r > 0:
            waits = d["waits_tai"][r - 1]
            line += f"　｜聽牌機率 {d['ready'][r - 1][0] * 100:.0f}%，實際{FROM_READY[d['sh'][r - 1]]}" \
                + ("（聽 " + " ".join(tile(w[0]) for w in waits) + "）" if waits else "") \
                + f"，手牌 {tiles(d['hands'][(me + r) % 4].split(' [')[0])}"
        print(line)
    order = np.argsort(-np.array(d["q"]))
    best = d["q"][order[0]]
    efficiency = {e["a"]: e for e in d["eff"]}
    print("  動作          期望值    差距  選擇率  胡牌(自摸)  放槍  他家自摸  這張現在放槍(下/對/上)  牌效率")
    for i in order:
        f = figures(d, i)
        name = d["names"][i]
        e = efficiency.get(name)
        steps = ""
        if e:
            steps = "胡" if e["sh"] < 0 else f"聽牌 {e['uk']} 張" if e["sh"] == 0 else f"{e['sh']} 進聽 {e['uk']} 張"
        marks = ("★" if i == order[0] else "　") + ("●" if i == d["played"] else "　")
        now = "-" if f["now"] is None else \
            f"{f['now'] * 100:.1f}% (" + "/".join(f"{x * 100:.0f}" for x in f["each"]) + ")"
        print(f"  {marks}{action_text(name):<10s} {d['q'][i]:+7.2f} {d['q'][i] - best:+7.2f} {d['pol'][i] * 100:5.0f}%  "
              f"{f['win'] * 100:4.0f}% ({f['tsumo'] * 100:2.0f}%)  {f['deal'] * 100:3.0f}%  {f['others'] * 100:5.0f}%   "
              f"{now:<18s} {steps}")
    print("  ★ 期望值最高　● 實際打的")
    if end:
        print(f"  這一局的結果：{end['text']}")


def listing(path):
    decisions, _ = load(path)
    groups = defaultdict(list)
    for (h, _), ds in decisions.items():
        for d in ds:
            q = np.array(d["q"])
            loss = float(q.max() - q[d["played"]])
            if loss < 0.15:
                continue
            best_at, played_at = int(q.argmax()), d["played"]
            best, played = d["names"][best_at].split()[0], d["names"][played_at].split()[0]
            key = f"{best} > {played}"
            if best == played == "discard":
                efficiency = {e["a"]: e for e in d["eff"]}
                eb, ep = efficiency[d["names"][best_at]], efficiency[d["names"][played_at]]
                if eb["sh"] > ep["sh"]:
                    key += " (the best gives up a step)"
                elif figures(d, played_at)["now"] - figures(d, best_at)["now"] > 0.05:
                    key += " (as many steps, safer)"
                else:
                    key += " (as many steps, another tile)"
            groups[key].append((loss, h, d["t"]))
    for key, items in sorted(groups.items(), key=lambda kv: -sum(x[0] for x in kv[1])):
        items.sort(reverse=True)
        print(f"{key}: {len(items)} decisions, {sum(x[0] for x in items):.1f} points; the largest: "
              + ", ".join(f"{h} {t} ({loss:.2f})" for loss, h, t in items[:10]))


def hand(path, h):
    decisions, ends = load(path)
    for (hh, seat), ds in decisions.items():
        if hh != h:
            continue
        end = ends[h]
        print(f"第 {h} 局，座位 {seat}：結果 {end['points'][seat]:+.0f}（{end['text']}）")
        total = 0.0
        for d in ds:
            q = np.array(d["q"])
            loss = float(q.max() - q[d["played"]])
            total += loss
            print(f"  決策 {d['t']:3d}　牌牆 {d['wall']:2d}　期望值 {q.max():+6.2f}　打了 {action_text(d['names'][d['played']]):<8s}"
                  f"最佳 {action_text(d['names'][int(q.argmax())]):<8s}失分 {loss:5.2f}　對手聽牌機率 "
                  + " ".join(f"{x[0] * 100:3.0f}%" for x in d["ready"])
                  + "　實際 " + " ".join(FROM_READY[x] for x in d["sh"]))
        print(f"  決策失分合計 {total:.2f}")


# ----- the training-only heads against counting -----

def log_comb(n, k):
    n, k = np.broadcast_arrays(np.asarray(n, float), np.asarray(k, float))
    out = np.full(n.shape, -np.inf)
    ok = (k >= 0) & (k <= n)
    lgamma = np.vectorize(math.lgamma)
    out[ok] = lgamma(n[ok] + 1) - lgamma(k[ok] + 1) - lgamma(n[ok] - k[ok] + 1)
    return out


def aux(directory, samples=100000):
    """Baselines that know only how many tiles of each kind the seat has not seen, as the trainer reports the
    losses (python/mahjong/losses.py)."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from mahjong.data import Hands, Library

    ko_unseen, so_hand = 62, 13   # Features::KO_UNSEEN, SO_HAND (features version 1)
    lib = Library(threads=8)
    if lib.sizes["version"] != 1:
        raise ValueError("The baselines read features version 1")
    hands = Hands(sorted(glob.glob(os.path.join(directory, "*.npz")))[:4], lib.sizes["version"])
    marks = hands.marks[np.random.default_rng(0).permutation(len(hands.marks))[:samples]]
    b = lib.expand(hands.hands, hands.offsets, hands.rules, marks[:, 0], marks[:, 1])
    n = len(marks)
    unseen = b["kind"][:, :, ko_unseen:ko_unseen + 4].sum(-1)
    total = unseen.sum(-1)
    held = np.rint(b["seat"][:, 1:, so_hand] * 17)
    print(f"{n} decisions of {directory}: {total.mean():.1f} unseen tiles, {held.mean():.1f} in an opponent's hand")

    # Where the unseen tiles are: each opponent's share by the hypergeometric distribution.
    opp = np.minimum(b["opp_hand"], 4).astype(np.int64)
    u, big, m = unseen[:, None, :], total[:, None, None], held[:, :, None]
    p = np.exp(np.stack([log_comb(u, c) + log_comb(big - u, m - c) - log_comb(big, m) for c in range(5)], axis=-1))
    p = np.clip(p / p.sum(-1, keepdims=True), 1e-9, 1.0)
    ce = -np.log(np.take_along_axis(p, opp[..., None], axis=-1))[..., 0]
    share = np.array([(opp == c).mean() for c in range(5)])
    print(f"  opp_hand: by counting {ce.mean() * 34:.3f}; by the overall shares of 0 to 4 copies "
          f"{-(share * np.log(share + 1e-12)).sum() * 34:.3f}")

    # Whether the next player could chow a kind: from the chance that it holds each neighbour.
    has = 1 - np.exp(log_comb(big[:, 0] - unseen, m[:, 0]) - log_comb(big[:, 0], m[:, 0]))
    y = b["next_chow"].astype(float)
    pc = np.zeros_like(has)
    for k in range(27):
        rank = k % 9
        none = np.ones(n)
        for low in range(max(0, rank - 2), min(6, rank) + 1):
            both = np.ones(n)
            for i in range(low, low + 3):
                if i != rank:
                    both = both * has[:, k - rank + i]
            none = none * (1 - both)
        pc[:, k] = 1 - none
    pc = np.clip(pc, 1e-6, 1 - 1e-6)
    bce = -(y * np.log(pc) + (1 - y) * np.log(1 - pc))
    rate = y[:, :27].mean()
    print(f"  chow: by counting {bce[:, :27].sum(-1).mean():.3f}; by its rate ({rate:.3f}) "
          f"{-(rate * math.log(rate) + (1 - rate) * math.log(1 - rate)) * 27:.3f}")

    nd = b["next_discard"].astype(np.int64)
    pn = np.take_along_axis(np.repeat((unseen / total[:, None])[:, None], 3, 1), np.maximum(nd, 0)[..., None], -1)
    print(f"  next_discard: by the unseen tiles' shares {-np.log(np.clip(pn[..., 0][nd >= 0], 1e-9, 1)).mean():.3f}; "
          f"evenly {math.log(34):.3f}")

    future = b["future"].astype(float)
    rates = future[:, 2:6].mean(0)
    print(f"  ready_within: by its rates {np.round(rates, 3)} alone "
          f"{-(rates * np.log(rates) + (1 - rates) * np.log(1 - rates)).sum():.3f}")
    left, wall = future[:, 6], b["info"][:, 6]
    by_wall = np.zeros(n)
    for w in np.unique(wall):
        mk = wall == w
        by_wall[mk] = left[mk].mean()
    print(f"  draws_left: mean {left.mean():.1f}; by the tiles left in the wall, a root mean square error of "
          f"{np.sqrt(((left - by_wall) ** 2).mean()):.2f} draws (the head's Huber loss L is about 40 sqrt(2 L))")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    cmd, path = sys.argv[1], sys.argv[2]
    if cmd == "losses":
        losses(path)
    elif cmd == "heads":
        heads(path)
    elif cmd == "list":
        listing(path)
    elif cmd == "hand":
        hand(path, int(sys.argv[3]))
    elif cmd == "show":
        decisions, ends = load(path)
        h, t = int(sys.argv[3]), int(sys.argv[4])
        for (hh, _), ds in decisions.items():
            for d in ds:
                if hh == h and d["t"] == t:
                    show(d, ends[h])
    elif cmd == "aux":
        aux(path, int(sys.argv[3]) if len(sys.argv) > 3 else 100000)
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
