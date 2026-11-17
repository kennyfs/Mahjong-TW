#!/usr/bin/env python3
"""Exports a checkpoint's actor again, with the outputs the engine reads now (mahjong/model.py OUTPUT_NAMES).

    python python/export_actor.py actor_221.pt actor_221.onnx [--raw]

The averaged weights (the checkpoint's "ema"), as training exports them, unless --raw. The app and the page ship the
file python/fp16_weights.py makes of it. Needs torch, onnx and (for the check) onnxruntime.
"""
import argparse
import os
import sys

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.model import NUM_KINDS, NUM_SEATS, check_onnx, export_onnx, load_checkpoint  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("checkpoint")
ap.add_argument("out")
ap.add_argument("--raw", action="store_true", help="The trained weights, not the averaged ones")
args = ap.parse_args()
model, state = load_checkpoint(args.checkpoint)
if not args.raw:
    if "ema" not in state:
        sys.exit(f"{args.checkpoint} has no averaged weights: --raw exports the trained ones")
    model.load_state_dict(state["ema"])
model.eval()
export_onnx(model, args.out)
c = model.config
g = torch.Generator().manual_seed(0)
batch = {"kind": torch.rand(64, NUM_KINDS, c.kind_obs, generator=g), "seat": torch.rand(64, NUM_SEATS, c.seat_obs, generator=g),
         "global": torch.rand(64, c.global_obs, generator=g)}
diff = check_onnx(model, args.out, batch)
print(f"Exported {args.out}" + ("" if diff is None else f" (largest difference from PyTorch on random inputs {diff:.2g})"))
