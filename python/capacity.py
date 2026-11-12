#!/usr/bin/env python3
"""Does a larger actor predict better? Networks of several sizes, from scratch, on the same stream of self-play
hands at once (expanding the samples is the slow part, and is shared), and their held-out losses.

    python capacity.py --data RUN/selfplay/iter_196 ... --held RUN/selfplay/iter_212 --minutes 35 \\
        --sizes 128x5 256x8 [--ref RUN/ckpt/actor_211.pt]

Each learns the outcome of the action played and the other heads' labels, as the actor does in stage 1 without the
policy's step and without the critic. SIZE is d_model x layers (the feed-forward width three times d_model, the
heads' hidden width d_model, 4 heads per 128 of width).
"""
import argparse
import json
import os
import sys
import time

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.data import DEFAULT_LIB, Library, Stream, batches, list_files, to_torch  # noqa: E402
from mahjong.losses import loss_terms, total_loss, weights_for  # noqa: E402
from mahjong.model import NUM_ACTIONS, MahjongNet, ModelConfig, load_checkpoint  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("--data", nargs="+", required=True)
ap.add_argument("--held", nargs="+", required=True)
ap.add_argument("--sizes", nargs="+", required=True)
ap.add_argument("--ref", help="A trained actor to report on the same held-out samples")
ap.add_argument("--minutes", type=float, default=35)
ap.add_argument("--batch", type=int, default=512)
ap.add_argument("--lr", type=float, default=3e-4)
ap.add_argument("--held-samples", type=int, default=100000)
ap.add_argument("--report-every", type=int, default=2000)
ap.add_argument("--expand-threads", type=int, default=10)
ap.add_argument("--lib", default=DEFAULT_LIB)
args = ap.parse_args()
device = torch.device("cuda")
library = Library(args.lib, threads=args.expand_threads)
weights = weights_for(full=False)
weights["policy"] = 0.0
weights["adv"] = 0.0
SHOW = ("events", "others", "tai", "q_mse", "ready", "danger", "win_tile", "ready_within", "final_hand")

nets = {}
for size in args.sizes:
    d, layers = (int(x) for x in size.split("x"))
    cfg = ModelConfig.for_library(library.sizes, d_model=d, num_layers=layers, num_heads=max(4, d // 32), ff=3 * d, head=d)
    model = MahjongNet(cfg).to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    nets[size] = (model, opt)
    print(f"{size}: {model.num_parameters()} parameters", flush=True)

held = []
for b in batches(library, list_files(args.held), 2048, part="all", symmetries=False, drop_last=True, shuffle=False):
    held.append(b)
    if len(held) * 2048 >= args.held_samples:
        break


def own(b):
    b = to_torch(b, device)
    b["known"] = torch.nn.functional.one_hot(b["played"].long(), NUM_ACTIONS).to(b["known"].dtype)
    return b


def report(model):
    model.eval()
    sums = {}
    with torch.no_grad():
        for hb in held:
            b = own(hb)
            with torch.autocast(device_type="cuda", dtype=torch.bfloat16):
                out = model(*model.inputs(b))
            terms, metrics = loss_terms(out, b)
            terms["q_mse"] = metrics["q_mse"]
            for k in SHOW:
                sums[k] = sums.get(k, 0.0) + float(terms[k])
    model.train()
    return {k: round(v / len(held), 4) for k, v in sums.items()}


if args.ref:
    ref, state = load_checkpoint(args.ref, device)
    if "ema" in state:
        ref.load_state_dict(state["ema"])
    print(json.dumps({"net": "ref", **report(ref)}), flush=True)
    del ref

start = time.time()
step = 0
spent = {size: 0.0 for size in nets}
stream = Stream(library, list_files(args.data), args.batch, seed=1, group=64)
for batch in stream:
    b = own(batch)
    step += 1
    warm = min(1.0, step / 500)
    for size, (model, opt) in nets.items():
        t = time.time()
        for g in opt.param_groups:
            g["lr"] = args.lr * warm
        with torch.autocast(device_type="cuda", dtype=torch.bfloat16):
            out = model(*model.inputs(b))
        terms, _ = loss_terms(out, b)
        loss = total_loss(terms, weights)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        spent[size] += time.time() - t
    over = time.time() - start > args.minutes * 60
    if step % args.report_every == 0 or over:
        for size, (model, _) in nets.items():
            print(json.dumps({"net": size, "step": step, "samples": step * args.batch, "minutes": round((time.time() - start) / 60, 1),
                              "gpu_s": round(spent[size]), **report(model)}), flush=True)
    if over:
        break
stream.close()
