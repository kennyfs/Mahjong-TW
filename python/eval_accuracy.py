#!/usr/bin/env python3
"""How accurate the shipped outputs of actors are, on hands none of them was trained on (docs/training.md, "Making
the analysis' outputs more accurate").

    python eval_accuracy.py --data DATA/iter_223 --actor A.pt [B.pt ...] [--critic C.pt] [--out results.json]

For every marked decision (and a fork's second action, whose hand was finished too):

- the composed result Q of the actions whose end is known against the realized one, in typical payments: its bias
  and squared error, overall and by what the seat could see (its tiles from ready, melds, the decision's place in the
  hand, the tai it can see in its own ready hand) and by Q itself (a reliability table of Q);
- the tai head given a win of mine against the tai of that win, by the head's own prediction and by the tai of the
  ready hand in the inputs;
- every probability that ships (the ten events of the known actions, `ready`, `danger`, `ready_within`, `tai_items`):
  log loss, Brier score and the expected calibration error over 40 bins of equal count;
- the win actions, whose result is known exactly: the head's tai and P(win) against it;
- Q against the policy: how often their first choices agree, and what each first choice would have gained over the
  playing policy at that one decision (importance weights over the action played: the mean of
  (1[a = choice] / mu(a) - 1) (G - mean G)).
"""
import argparse
import json
import os
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.data import DEFAULT_LIB, Library, batches, list_files, to_torch  # noqa: E402
from mahjong.losses import loss_terms, outcome_labels, payment_unit  # noqa: E402
from mahjong.model import (A_SPECIAL, NUM_ACTIONS, NUM_EVENTS, NUM_TAI_ITEMS, TAI_CLASSES, TAI_SCALE,  # noqa: E402
                           compose_q, load_checkpoint)

# Feature indices (cpp/learn/features.h).
KO_DISCARD_READY = 4 + 1 + 1 + 24 + 32 + 4 + 3 + 5 + 1 + 5 + 1 + 2  # 83
GO_SHANTEN = 1 + 1 + 4 + 2 + 1 + 1    # 10
GO_ACTIONS = GO_SHANTEN + 6 + 1 + 4    # 21
GA_SIZE = 11
GO_RULES = GO_ACTIONS + 9 * GA_SIZE    # the flower mode first (3)
GA_READY = 1 + 5 + 1 + 1               # 8
SO_MELDS = 4 + 1 + 4
SO_DECLARED = 26
A_TSUMO, A_RON = A_SPECIAL, A_SPECIAL + 1


class Calib:
    """Binary predictions against outcomes: sums in 400 fine bins of the logit, then 40 bins of equal count."""

    EDGES = np.linspace(-14, 14, 401)

    def __init__(self):
        self.n = np.zeros(402)
        self.p = np.zeros(402)
        self.y = np.zeros(402)
        self.ll = 0.0
        self.brier = 0.0

    def add(self, p, y):
        p = p.reshape(-1).astype(np.float64)
        y = y.reshape(-1).astype(np.float64)
        p = np.clip(p, 1e-7, 1 - 1e-7)
        b = np.digitize(np.log(p) - np.log1p(-p), self.EDGES)
        self.n += np.bincount(b, minlength=402)
        self.p += np.bincount(b, p, minlength=402)
        self.y += np.bincount(b, y, minlength=402)
        self.ll += float(-(y * np.log(p) + (1 - y) * np.log1p(-p)).sum())
        self.brier += float(((p - y) ** 2).sum())

    def report(self, bins=40):
        total = self.n.sum()
        if total == 0:
            return {}
        cum = np.cumsum(self.n)
        groups = np.minimum((cum - self.n / 2) * bins // total, bins - 1).astype(int)
        n = np.bincount(groups, self.n, bins)
        p = np.bincount(groups, self.p, bins)
        y = np.bincount(groups, self.y, bins)
        keep = n > 0
        ece = float(np.abs(p[keep] - y[keep]).sum() / total)
        table = [(float(p[i] / n[i]), float(y[i] / n[i]), int(n[i])) for i in range(bins) if n[i] > 0]
        return {"n": int(total), "logloss": self.ll / total, "brier": self.brier / total, "ece": ece,
                "mean_p": float(self.p.sum() / total), "mean_y": float(self.y.sum() / total),
                "ece_rel": ece / max(float(self.y.sum() / total), 1e-9), "table": table}


class Groups:
    """Means of several values by a key."""

    def __init__(self):
        self.d = {}

    def add(self, key, **values):
        key = np.asarray(key)
        for k in np.unique(key):
            m = key == k
            row = self.d.setdefault(int(k) if np.issubdtype(key.dtype, np.integer) else float(k), {"n": 0})
            row["n"] += int(m.sum())
            for name, v in values.items():
                row[name] = row.get(name, 0.0) + float(np.asarray(v, np.float64)[m].sum())

    def report(self):
        out = {}
        for k in sorted(self.d):
            row = self.d[k]
            out[k] = {name: (v / row["n"] if name != "n" else v) for name, v in row.items()}
        return out


def tai_bucket(t):
    """0: 0-1, 1: 2-3, 2: 4-5, 3: 6-7, 4: 8-10, 5: 11-14, 6: 15 or more."""
    return np.digitize(t, [2, 4, 6, 8, 11, 15])


TAI_BUCKETS = ("0-1", "2-3", "4-5", "6-7", "8-10", "11-14", "15+")


def device_of(b):
    return b["played"].device


class Evaluator:
    def __init__(self, name):
        self.name = name
        self.calib = {k: Calib() for k in ("events_known", "win", "deal_in", "opp_tsumo", "ready", "danger",
                                           "ready_within", "tai_items", "win_action")}
        self.q_by = {k: Groups() for k in ("all", "shanten", "melds", "place", "ready_tai", "q_pred", "baoting_rule",
                                           "baoting_declared", "phase", "won_tai", "flowers")}
        self.tai_by = {k: Groups() for k in ("pred", "ready_tai", "real")}
        self.win_tai = Groups()
        self.danger_tai = Groups()
        self.danger_tai_pred = Groups()
        self.terms = {}
        self.nb = 0
        self.agree = []
        self.gain = {"q": [], "pi": []}
        self.g = []
        self.disagree_critic = []
        self.forks = []

    def add(self, out, b, critic_q=None):
        f = {k: v.float() for k, v in out.items()}
        own = dict(b)
        own["known"] = F.one_hot(b["played"].long(), NUM_ACTIONS).to(b["known"].dtype)
        terms, metrics = loss_terms(out, own)
        terms.pop("policy", None)
        terms.pop("adv", None)
        terms["q_mse"] = metrics["q_mse"]
        for k, v in terms.items():
            self.terms[k] = self.terms.get(k, 0.0) + float(v)
        self.nb += 1

        known = b["known"] > 0
        legal = b["legal"] > 0
        y, tai_label = outcome_labels(b)
        coef = b["coef"].float()
        unit = payment_unit(b).float()
        probs = torch.sigmoid(f["outcomes"][..., :NUM_EVENTS])
        tai = f["tai"]
        q = compose_q(probs, tai, coef)
        q_label = compose_q(y[..., :NUM_EVENTS], tai_label, coef)

        glob = b["global"].float()
        kind = b["kind"].float()
        shanten = glob[:, GO_SHANTEN:GO_SHANTEN + 6].argmax(-1) - 1     # -1 complete, 0 ready, ..., 4 or more
        melds = (b["seat"][:, 0, SO_MELDS].float() * 5).round().long()
        # The tiles left to draw (what the seat sees; info 5, the decision's place in the hand, is known only after).
        place = torch.bucketize(b["info"][:, 6].float().contiguous(), torch.tensor([8., 16., 24., 32., 40., 48.], device=device_of(b)))
        phase = b["info"][:, 3].long()
        # 報聽 (features version 3): the rule is the last rule feature; SO_DECLARED + 2 of a seat: it declared it.
        baoting_rule = (glob[:, -1] > 0).long()
        decl = b["seat"][:, :, SO_DECLARED + 2].float() > 0
        baoting_decl = torch.where(decl[:, 0], 1, torch.where(decl[:, 1:].any(-1), 2, 0))
        # The tai the seat can see in its own hand: the most of any action's (a discard that leaves it ready, or a
        # win), by discard or self-draw.
        dready = kind[:, :, KO_DISCARD_READY + 2:KO_DISCARD_READY + 4].amax(dim=(1, 2))
        ga = glob[:, GO_ACTIONS:GO_ACTIONS + 9 * GA_SIZE].reshape(-1, 9, GA_SIZE)
        gready = ga[:, :, GA_READY + 1:GA_READY + 3].amax(dim=(1, 2))
        ready_tai = (torch.maximum(dready, gready) * 8).round()

        # ----- Q of the known actions -----
        kn = known.cpu().numpy()
        rows, cols = np.nonzero(kn)
        err = ((q - q_label) / unit[:, None]).cpu().numpy()[rows, cols]
        qp = (q / unit[:, None]).cpu().numpy()[rows, cols]
        ql = (q_label / unit[:, None]).cpu().numpy()[rows, cols]
        # My win's tai in that action's hand (0 when I did not win).
        my_win = y[..., :4].sum(-1).clamp(max=1)
        won_tai = (tai_label[..., :4] * y[..., :4]).sum(-1)
        wt = won_tai.cpu().numpy()[rows, cols]
        mw = my_win.cpu().numpy()[rows, cols]
        vals = dict(err=err, sq=err ** 2, q=qp, real=ql)
        r = rows
        self.q_by["all"].add(np.zeros(len(r), np.int64), **vals)
        self.q_by["shanten"].add(shanten.cpu().numpy()[r], **vals)
        self.q_by["melds"].add(melds.cpu().numpy()[r], **vals)
        self.q_by["place"].add(place.cpu().numpy()[r], **vals)
        self.q_by["phase"].add(phase.cpu().numpy()[r], **vals)
        self.q_by["flowers"].add(glob[:, GO_RULES:GO_RULES + 3].argmax(-1).cpu().numpy()[r], **vals)
        self.q_by["baoting_rule"].add(baoting_rule.cpu().numpy()[r], **vals)
        self.q_by["baoting_declared"].add(baoting_decl.cpu().numpy()[r], **vals)
        self.q_by["ready_tai"].add(tai_bucket(ready_tai.cpu().numpy()[r]) * (shanten.cpu().numpy()[r] <= 1)
                                   - (shanten.cpu().numpy()[r] > 1), **vals)
        self.q_by["q_pred"].add(np.digitize(qp, [-1.5, -1, -0.5, -0.25, 0, 0.25, 0.5, 1, 1.5, 2, 3]), **vals)
        self.q_by["won_tai"].add(np.where(mw > 0, tai_bucket(wt), -1), **vals)

        # ----- Forks: two actions of one decision finished on the same wall; the difference of their results -----
        other = known & (F.one_hot(b["played"].long(), NUM_ACTIONS) == 0)
        has = other.any(-1)
        if bool(has.any()):
            played = b["played"].long()[:, None]
            alt = other.float().argmax(-1, keepdim=True)
            dp = ((q.gather(1, played) - q.gather(1, alt)).squeeze(1) / unit)[has]
            dr = ((q_label.gather(1, played) - q_label.gather(1, alt)).squeeze(1) / unit)[has]
            self.forks.append(torch.stack([dp, dr], 1).cpu().numpy())

        # ----- The tai head, given my win -----
        ev_rows, ev_cols, ev_e = np.nonzero((y[..., :4] > 0).cpu().numpy() & kn[..., None])
        if len(ev_rows):
            tp = tai.cpu().numpy()[ev_rows, ev_cols, ev_e]
            tr = tai_label.cpu().numpy()[ev_rows, ev_cols, ev_e]
            tv = dict(pred=tp, real=tr, err=tp - tr, sq=(tp - tr) ** 2)
            self.tai_by["pred"].add(tai_bucket(tp), **tv)
            self.tai_by["real"].add(tai_bucket(tr), **tv)
            self.tai_by["ready_tai"].add(tai_bucket(ready_tai.cpu().numpy()[ev_rows]), **tv)

        # ----- Probabilities -----
        pk = probs.cpu().numpy()[rows, cols]
        yk = y[..., :NUM_EVENTS].cpu().numpy()[rows, cols]
        self.calib["events_known"].add(pk, yk)
        self.calib["win"].add(pk[:, :4].sum(-1), yk[:, :4].sum(-1))
        self.calib["deal_in"].add(pk[:, 4:7].sum(-1), yk[:, 4:7].sum(-1))
        self.calib["opp_tsumo"].add(pk[:, 7:10].sum(-1), yk[:, 7:10].sum(-1))
        ready_p = torch.softmax(f["seats"][:, 1:, :4], dim=-1)[..., 0]
        self.calib["ready"].add(ready_p.cpu().numpy(), (b["opp_shanten"] == 0).cpu().numpy())
        dp = torch.sigmoid(f["pair"][:, 1:, :, 0])
        # Only the kinds that can be discarded now matter to the app; all of them are scored by the trainer.
        self.calib["danger"].add(dp.cpu().numpy(), b["danger"].cpu().numpy())
        dmask = (b["danger"] > 0).cpu().numpy()
        if dmask.any():
            dt_p = (f["pair"][:, 1:, :, 1] * TAI_SCALE).cpu().numpy()[dmask]
            dt_r = b["danger_tai"].float().cpu().numpy()[dmask]
            self.danger_tai.add(tai_bucket(dt_r), pred=dt_p, real=dt_r, sq=(dt_p - dt_r) ** 2)
            self.danger_tai_pred.add(tai_bucket(dt_p), pred=dt_p, real=dt_r, sq=(dt_p - dt_r) ** 2)
        o = NUM_TAI_ITEMS + TAI_CLASSES
        me = f["me"]
        self.calib["ready_within"].add(torch.sigmoid(me[:, o:o + 4]).cpu().numpy(), b["future"][:, 2:6].cpu().numpy())
        self.calib["tai_items"].add(torch.sigmoid(me[:, :NUM_TAI_ITEMS]).cpu().numpy(), b["tai_items"].cpu().numpy())

        # ----- The win actions: their result is known exactly -----
        for a, slot in ((A_TSUMO, 2), (A_RON, 1)):
            m = legal[:, a]
            if not bool(m.any()):
                continue
            exact = (ga[:, a - A_SPECIAL, GA_READY + slot] * 8).round()[m]   # capped at 32
            p_win = probs[m, a, :4].sum(-1)
            t_pred = (probs[m, a, :4] * tai[m, a, :4]).sum(-1) / p_win.clamp(min=1e-6)
            self.calib["win_action"].add(p_win.cpu().numpy(), np.ones(int(m.sum())))
            ex = exact.cpu().numpy()
            tp = t_pred.cpu().numpy()
            self.win_tai.add(tai_bucket(ex), exact=ex, pred=tp, err=tp - ex, sq=(tp - ex) ** 2,
                             p_win=p_win.cpu().numpy())

        # ----- Q against the policy -----
        neg = torch.finfo(torch.float32).min
        best_q = q.masked_fill(~legal, neg).argmax(-1)
        best_pi = f["policy"].masked_fill(~legal, neg).argmax(-1)
        multi = legal.sum(-1) > 1
        self.agree.append(((best_q == best_pi)[multi]).cpu().numpy())
        played = b["played"].long()
        mu = b["mu"].float().gather(1, played[:, None]).squeeze(1).clamp(min=1e-4)
        g = (b["info"][:, 2].float() / unit).cpu().numpy()
        self.g.append(g)
        for k, best in (("q", best_q), ("pi", best_pi)):
            ratio = ((best == played).float() / mu).cpu().numpy()
            self.gain[k].append(ratio)
        if critic_q is not None:
            dis = multi & (best_q != best_pi)
            if bool(dis.any()):
                cq = critic_q / unit[:, None]
                d = (cq.gather(1, best_q[:, None]) - cq.gather(1, best_pi[:, None])).squeeze(1)[dis]
                self.disagree_critic.append(d.cpu().numpy())

    def report(self):
        rep = {"name": self.name, "terms": {k: v / self.nb for k, v in self.terms.items()}}
        rep["calibration"] = {k: c.report() for k, c in self.calib.items()}
        rep["q_by"] = {k: g.report() for k, g in self.q_by.items()}
        rep["tai_by"] = {k: g.report() for k, g in self.tai_by.items()}
        rep["win_action_tai"] = self.win_tai.report()
        rep["danger_tai"] = self.danger_tai.report()
        rep["danger_tai_pred"] = self.danger_tai_pred.report()
        agree = np.concatenate(self.agree)
        g = np.concatenate(self.g)
        gc = g - g.mean()
        rep["q_vs_pi"] = {"agree": float(agree.mean()), "decisions": int(len(agree))}
        for k in ("q", "pi"):
            r = np.concatenate(self.gain[k])
            v = (r - 1.0) * gc
            rep["q_vs_pi"][f"gain_{k}"] = float(v.mean())
            rep["q_vs_pi"][f"gain_{k}_se"] = float(v.std() / np.sqrt(len(v)))
            # Differences of the two on the same samples.
        d = (np.concatenate(self.gain["q"]) - np.concatenate(self.gain["pi"])) * gc
        rep["q_vs_pi"]["q_minus_pi"] = float(d.mean())
        rep["q_vs_pi"]["q_minus_pi_se"] = float(d.std() / np.sqrt(len(d)))
        if self.forks:
            fk = np.concatenate(self.forks).astype(np.float64)
            dp, dr = fk[:, 0], fk[:, 1]
            rep["forks"] = {"n": int(len(fk)), "mse": float(((dp - dr) ** 2).mean()),
                            "mse_se": float(((dp - dr) ** 2).std() / np.sqrt(len(fk))),
                            "explains": float(1 - ((dp - dr) ** 2).mean() / (dr ** 2).mean()),
                            "slope": float((dp * dr).sum() / (dp ** 2).sum()),
                            "pred_rms": float(np.sqrt((dp ** 2).mean())), "real_rms": float(np.sqrt((dr ** 2).mean()))}
        if self.disagree_critic:
            dc = np.concatenate(self.disagree_critic)
            rep["q_vs_pi"]["critic_q_minus_pi_where_differ"] = float(dc.mean())
            rep["q_vs_pi"]["critic_prefers_q"] = float((dc > 0).mean())
        return rep


def summary(rep):
    t = rep["terms"]
    c = rep["calibration"]
    lines = [f"== {rep['name']}"]
    lines.append("  terms: " + " ".join(f"{k} {v:.4f}" for k, v in t.items()
                                       if k in ("events", "tai", "q_mse", "ready", "danger", "danger_tai",
                                                "ready_within", "tai_items", "my_tai")))
    for k in ("win", "deal_in", "opp_tsumo", "events_known", "ready", "danger", "ready_within", "tai_items",
              "win_action"):
        r = c[k]
        if r:
            lines.append(f"  {k:13s} n {r['n']:9d} logloss {r['logloss']:.5f} brier {r['brier']:.5f} "
                         f"ece {r['ece']:.5f} (rel {r['ece_rel']:.3f}) mean p {r['mean_p']:.4f} y {r['mean_y']:.4f}")
    a = rep["q_by"]["all"][0]
    lines.append(f"  Q: bias {a['err']:+.4f} mse {a['sq']:.4f} (n {a['n']})")
    for k in ("won_tai", "ready_tai", "q_pred", "shanten", "melds", "place", "phase", "flowers", "baoting_rule",
              "baoting_declared"):
        parts = []
        for key, row in rep["q_by"][k].items():
            parts.append(f"{key}: n{row['n']} b{row['err']:+.3f} m{row['sq']:.3f}")
        lines.append(f"  Q by {k}: " + "; ".join(parts))
    for k in ("pred", "ready_tai", "real"):
        parts = [f"{TAI_BUCKETS[key] if key >= 0 else key}: n{row['n']} p{row['pred']:.2f} r{row['real']:.2f}"
                 for key, row in rep["tai_by"][k].items()]
        lines.append(f"  tai|my win by {k}: " + "; ".join(parts))
    parts = [f"{TAI_BUCKETS[key]}: n{row['n']} exact{row['exact']:.2f} pred{row['pred']:.2f} pwin{row['p_win']:.3f}"
             for key, row in rep["win_action_tai"].items()]
    lines.append("  win actions: " + "; ".join(parts))
    parts = [f"{TAI_BUCKETS[key]}: n{row['n']} p{row['pred']:.2f} r{row['real']:.2f}"
             for key, row in rep["danger_tai"].items()]
    lines.append("  danger_tai by real: " + "; ".join(parts))
    parts = [f"{TAI_BUCKETS[key]}: n{row['n']} p{row['pred']:.2f} r{row['real']:.2f}"
             for key, row in rep["danger_tai_pred"].items()]
    lines.append("  danger_tai by pred: " + "; ".join(parts))
    if "forks" in rep:
        fk = rep["forks"]
        lines.append(f"  forks: n {fk['n']} mse {fk['mse']:.4f}±{fk['mse_se']:.4f} explains {fk['explains']:.4f} "
                     f"slope {fk['slope']:.3f} pred rms {fk['pred_rms']:.3f} real rms {fk['real_rms']:.3f}")
    v = rep["q_vs_pi"]
    lines.append(f"  Q vs policy: agree {v['agree']:.4f}; gain over mu: Q {v['gain_q']:+.5f}±{v['gain_q_se']:.5f}, "
                 f"pi {v['gain_pi']:+.5f}±{v['gain_pi_se']:.5f}, Q-pi {v['q_minus_pi']:+.5f}±{v['q_minus_pi_se']:.5f}"
                 + (f"; where they differ the critic prefers Q's {v['critic_prefers_q']:.3f}, by "
                    f"{v['critic_q_minus_pi_where_differ']:+.4f}" if "critic_prefers_q" in v else ""))
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", nargs="+", required=True)
    ap.add_argument("--actor", nargs="+", required=True, help="Actor checkpoints (their averaged weights)")
    ap.add_argument("--critic", help="A critic, to judge where Q and the policy choose differently")
    ap.add_argument("--lib", default=DEFAULT_LIB)
    ap.add_argument("--max-samples", type=int, default=10 ** 9)
    ap.add_argument("--expand-threads", type=int, default=6)
    ap.add_argument("--batch", type=int, default=2048)
    ap.add_argument("--out", help="Write the full reports here (JSON)")
    ap.add_argument("--raw", action="store_true", help="The live weights, not the averaged ones")
    args = ap.parse_args()
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    models = []
    for path in args.actor:
        model, state = load_checkpoint(path, device)
        if "ema" in state and not args.raw:
            model.load_state_dict(state["ema"])
        models.append((path, model.eval(), Evaluator(os.path.basename(path))))
    critic = None
    if args.critic:
        critic, _ = load_checkpoint(args.critic, device)
        critic.eval()
    library = Library(args.lib, threads=args.expand_threads)
    n = 0
    start = time.time()
    with torch.no_grad():
        for batch in batches(library, list_files(args.data), args.batch, part="all", symmetries=False,
                             drop_last=False, shuffle=False):
            b = to_torch(batch, device)
            cq = None
            if critic is not None:
                co = critic(*critic.inputs(b))
                cq = compose_q(torch.sigmoid(co["outcomes"][..., :NUM_EVENTS].float()), co["tai"].float(),
                               b["coef"].float())
            for _, model, ev in models:
                ev.add(model(*model.inputs(b)), b, cq)
            n += len(batch["played"])
            if n >= args.max_samples:
                break
    print(f"{n} samples in {time.time() - start:.0f} s", flush=True)
    reports = [ev.report() for _, _, ev in models]
    for rep in reports:
        print(summary(rep), flush=True)
    if args.out:
        with open(args.out, "w") as f:
            json.dump(reports, f)


if __name__ == "__main__":
    main()
