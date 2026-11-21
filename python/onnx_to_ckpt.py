#!/usr/bin/env python3
"""Rebuilds an actor checkpoint's weights from its exported .onnx, using a checkpoint of the same architecture whose
own export is known to find where each weight went (docs/training.md, "Making the analysis' outputs more accurate").

    python onnx_to_ckpt.py --ref actor_221.pt --ref-onnx actor_221.onnx actor_212.onnx actor_212_rebuilt.pt [...]

The export keeps some weights by name, stores others under generated names (transposed for MatMul), and folds the
kind-type embedding into a [34, d] table and each layer's attention biases into their gather. The mapping is found
on the reference by value and then applied to every other file (all exported by the same code). Heads that are not
exported (training only) keep the reference's weights, so only the shipped outputs of a rebuilt checkpoint mean
anything.
"""
import argparse
import os
import sys

import numpy as np
import onnx
import torch
from onnx import numpy_helper

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.model import kind_types, load_checkpoint, save_checkpoint  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("--ref", required=True)
ap.add_argument("--ref-onnx", required=True)
ap.add_argument("pairs", nargs="+", help="ONNX OUT.pt ONNX OUT.pt ...")
args = ap.parse_args()

model, state = load_checkpoint(args.ref)
sd = {k: v.numpy() for k, v in (state.get("ema") or state["model"]).items()}


def inits(path):
    return {i.name: numpy_helper.to_array(i) for i in onnx.load(path).graph.initializer}


ref = inits(args.ref_onnx)
types = np.array(kind_types())
mapping = {}   # onnx name -> (param name, how)
used = set()
for name, arr in ref.items():
    found = None
    for pn, pv in sd.items():
        if pn in used:
            continue
        if pv.shape == arr.shape and np.array_equal(pv, arr):
            found = (pn, "same")
        elif pv.ndim == 2 and pv.T.shape == arr.shape and np.array_equal(pv.T, arr):
            found = (pn, "T")
        if found:
            break
    if found is None and arr.shape == (34, sd["type_emb"].shape[1]) and np.allclose(arr, sd["type_emb"][types]):
        found = ("type_emb", "types")
    if found is None and "att_bias" in sd and arr.shape == sd["att_bias"].shape[1:]:
        for layer in range(sd["att_bias"].shape[0]):
            if np.array_equal(arr, sd["att_bias"][layer]) and ("att_bias", layer) not in used:
                found = ("att_bias", ("layer", layer))
                used.add(("att_bias", layer))
                break
    if found is None:
        # a strided slice of a weight (an output head cut at export)
        pass
    if found:
        mapping[name] = found
        if found[1] in ("same", "T"):
            used.add(found[0])
mapped = {v[0] for v in mapping.values()}
unmapped_onnx = [n for n in ref if n not in mapping]
missing = [k for k in sd if k not in mapped]
print(f"{len(mapping)} of {len(ref)} initializers mapped; not mapped: {unmapped_onnx[:8]}")
print(f"parameters kept from the reference (not exported or not found): {missing}")
for src, out in zip(args.pairs[0::2], args.pairs[1::2]):
    x = inits(src)
    new = {k: torch.from_numpy(v.copy()) for k, v in sd.items()}
    att = sd.get("att_bias").copy() if "att_bias" in sd else None
    for name, (pn, how) in mapping.items():
        a = x[name]
        if how == "same":
            new[pn] = torch.from_numpy(a.copy())
        elif how == "T":
            new[pn] = torch.from_numpy(a.T.copy())
        elif how == "types":
            emb = sd["type_emb"].copy()
            for t in range(emb.shape[0]):
                emb[t] = a[types == t][0]
            new[pn] = torch.from_numpy(emb)
        else:
            att[how[1]] = a
    if att is not None:
        new["att_bias"] = torch.from_numpy(att)
    model.load_state_dict(new)
    save_checkpoint(out, model, None, {"ema": model.state_dict(), "rebuilt_from": os.path.basename(src)})
    print(f"{src} -> {out}")
