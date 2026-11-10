#!/usr/bin/env python3
"""Evaluates checkpoints on stage 0 data (every action's hand finished by greedy players on the same wall).

    python eval0.py --data DIR CHECKPOINT...

Per checkpoint (its EMA weights): the losses of the heads, and what its choices are worth. The result of the
network's choice minus the result of the action the plain greedy player would play, in points per decision, is an
unbiased estimate of what one decision by the network gains over greedy when everybody plays greedy afterwards;
it is also given by the kind of decision.
"""

import argparse
import os
import sys

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.data import DEFAULT_LIB, Library, batches, list_files, to_torch  # noqa: E402
from mahjong.losses import loss_terms, outcome_labels  # noqa: E402
from mahjong.model import NUM_EVENTS, compose_q, load_checkpoint  # noqa: E402

KINDS = ("own turn", "discard to answer", "kong to rob")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("checkpoints", nargs="+")
    ap.add_argument("--data", nargs="+", required=True)
    ap.add_argument("--lib", default=DEFAULT_LIB)
    ap.add_argument("--part", default="all", choices=("all", "held", "train"))
    ap.add_argument("--max-samples", type=int, default=400000)
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    ap.add_argument("--expand-threads", type=int, default=8)
    ap.add_argument("--policy-temp", type=float, default=0.03)
    args = ap.parse_args()
    device = torch.device(args.device)
    library = Library(args.lib, threads=args.expand_threads)
    data = []
    for batch in batches(library, list_files(args.data), 2048, part=args.part, symmetries=False, drop_last=False,
                         shuffle=False):
        data.append(batch)
        if sum(len(b["played"]) for b in data) >= args.max_samples:
            break
    total = sum(len(b["played"]) for b in data)
    print(f"{total} decisions", flush=True)

    for path in args.checkpoints:
        model, state = load_checkpoint(path, device)
        if "ema" in state:
            model.load_state_dict(state["ema"])
        model.eval()
        sums = {}
        # Per kind of decision: count, the network differs from greedy, gain of Q's choice, its square, of the policy's.
        by_kind = torch.zeros(len(KINDS), 5, dtype=torch.float64)
        neg = torch.finfo(torch.float32).min
        with torch.no_grad():
            for batch in data:
                b = to_torch(batch, device)
                out = model(*model.inputs(b))
                terms, metrics = loss_terms(out, b, args.policy_temp)
                for k, v in {**terms, **metrics}.items():
                    sums[k] = sums.get(k, 0.0) + float(v) * len(batch["played"])
                y, tai_label = outcome_labels(b)
                q_label = compose_q(y[..., :NUM_EVENTS], tai_label, b["coef"])
                q = compose_q(torch.sigmoid(out["outcomes"][..., :NUM_EVENTS].float()), out["tai"].float(), b["coef"])
                legal = b["legal"] > 0
                best_q = q.masked_fill(~legal, neg).argmax(-1, keepdim=True)
                best_pi = out["policy"].float().masked_fill(~legal, neg).argmax(-1, keepdim=True)
                greedy = b["greedy"].long().unsqueeze(-1)
                base = q_label.gather(1, greedy)
                gain_q = (q_label.gather(1, best_q) - base).squeeze(-1).double().cpu()
                gain_pi = (q_label.gather(1, best_pi) - base).squeeze(-1).double().cpu()
                differs = (best_q != greedy).squeeze(-1).double().cpu()
                kind = b["info"][:, 3].long().cpu()
                for k in range(len(KINDS)):
                    m = (kind == k).double()
                    by_kind[k] += torch.stack([m.sum(), (m * differs).sum(), (m * gain_q).sum(),
                                               (m * gain_q ** 2).sum(), (m * gain_pi).sum()])
        print(f"== {path}", flush=True)
        keys = ("events", "tai", "adv", "policy", "ready", "danger", "q_mse", "q_vs_greedy", "pi_vs_greedy",
                "q_is_greedy", "pi_is_greedy", "gain_q", "agree", "entropy")
        print("  " + " ".join(f"{k} {sums[k] / total:.4g}" for k in keys if k in sums))
        n_all, gain_all, sq_all = by_kind[:, 0].sum(), by_kind[:, 2].sum(), by_kind[:, 3].sum()
        se = ((sq_all / n_all - (gain_all / n_all) ** 2) / n_all).sqrt()
        print(f"  Q's choice against greedy's: {gain_all / n_all:+.4f} +- {se:.4f} points per decision; "
              f"the policy's {by_kind[:, 4].sum() / n_all:+.4f}")
        for k, name in enumerate(KINDS):
            n, d, g, sq, gp = by_kind[k].tolist()
            if n > 0:
                se = ((sq / n - (g / n) ** 2) / n) ** 0.5
                print(f"    {name}: {int(n)} decisions, differs from greedy in {d / n:.3f}, "
                      f"Q {g / n:+.4f} +- {se:.4f}, policy {gp / n:+.4f}")


if __name__ == "__main__":
    main()
