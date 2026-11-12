#!/usr/bin/env python3
"""How well a run's networks predict, all on the same hands (docs/training.md, "Do the predictions get better").

    python eval_heads.py --data RUN/selfplay/iter_212 --ckpt RUN/ckpt --nets 0 60 100 140 180 200 211 [--critic]

Every marked decision of the hands (ones no network of the list was trained on), each network's averaged weights
(the ones exported), the trainer's own loss terms with the action played as the known outcome: `events` (the
outcome probabilities an analysis shows: win, self-draw, deal-in, ...), `q_mse` (the composed result against the
realized one, in typical payments squared), `ready`, `danger` and the other heads. The standard errors are of the
difference to the first network of the list, over batches of the same samples.
"""
import argparse
import os
import sys

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.data import DEFAULT_LIB, Library, batches, list_files, to_torch  # noqa: E402
from mahjong.losses import loss_terms  # noqa: E402
from mahjong.model import NUM_ACTIONS, load_checkpoint  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("--data", nargs="+", required=True)
ap.add_argument("--ckpt", required=True)
ap.add_argument("--nets", nargs="+", type=int, required=True)
ap.add_argument("--critic", action="store_true", help="The critics instead (the played action and a fork's)")
ap.add_argument("--lib", default=DEFAULT_LIB)
ap.add_argument("--max-samples", type=int, default=10 ** 9)
ap.add_argument("--expand-threads", type=int, default=10)
args = ap.parse_args()
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
models = {}
for k in args.nets:
    model, state = load_checkpoint(os.path.join(args.ckpt, f"{'critic' if args.critic else 'actor'}_{k}.pt"), device)
    if "ema" in state:
        model.load_state_dict(state["ema"])
    models[k] = model.eval()
rows = {k: [] for k in args.nets}
names = None
n = 0
library = Library(args.lib, threads=args.expand_threads)
with torch.no_grad():
    for batch in batches(library, list_files(args.data), 2048, part="all", symmetries=False, drop_last=True, shuffle=False):
        b = to_torch(batch, device)
        if not args.critic:
            b["known"] = torch.nn.functional.one_hot(b["played"].long(), NUM_ACTIONS).to(b["known"].dtype)
        for k, model in models.items():
            with torch.autocast(device_type=device.type, dtype=torch.bfloat16, enabled=device.type == "cuda"):
                out = model(*model.inputs(b))
            terms, metrics = loss_terms(out, b)
            terms.pop("policy", None)
            terms.pop("adv", None)
            terms["q_mse"] = metrics["q_mse"]
            if "entropy" in metrics:
                terms["entropy"] = metrics["entropy"]
            names = names or list(terms)
            rows[k].append([float(terms[x]) for x in names])
        n += len(batch["played"])
        if n >= args.max_samples:
            break
base = np.array(rows[args.nets[0]])
print(f"{n} samples; ± is the standard error of the difference to network {args.nets[0]}")
print("net   " + " ".join(f"{x[:12]:>12}" for x in names))
for k in args.nets:
    r = np.array(rows[k])
    print(f"{k:4d}  " + " ".join(f"{v:12.4f}" for v in r.mean(0)))
se = None
for k in args.nets[1:]:
    d = np.array(rows[k]) - base
    print(f"{k:4d}d " + " ".join(f"{v:+12.4f}" for v in d.mean(0)))
    se = d.std(0) / np.sqrt(len(d))
print("  se  " + " ".join(f"{v:12.4f}" for v in se))
