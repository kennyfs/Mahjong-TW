#!/usr/bin/env python3
"""Checks a features upgrade (upgrade_features.py): the old network on random inputs (run with the old code), then the
upgraded one on the same inputs with zeros in the new places (run with the new code); prints the largest difference.

    python OLD_CODE/python/check_upgrade.py save OLD.pt OUT.npz
    python NEW_CODE/python/check_upgrade.py compare NEW.pt OUT.npz
"""
import os
import sys

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.model import load_checkpoint  # noqa: E402

mode, ckpt, path = sys.argv[1:4]
model, state = load_checkpoint(ckpt)
model.eval()
c = model.config
if mode == "save":
    g = torch.Generator().manual_seed(0)
    kind = torch.rand(64, 34, c.kind_obs, generator=g)
    seat = torch.rand(64, 4, c.seat_obs, generator=g)
    glob = torch.rand(64, c.global_obs, generator=g)
    with torch.no_grad():
        out = model(kind, seat, glob)
    np.savez(path, kind=kind.numpy(), seat=seat.numpy(), glob=glob.numpy(),
             **{"out_" + k: v.numpy() for k, v in out.items() if torch.is_tensor(v)})
else:
    d = np.load(path)
    kind = torch.from_numpy(d["kind"])
    seat, glob = torch.from_numpy(d["seat"]), torch.from_numpy(d["glob"])
    seat = torch.cat([seat, torch.zeros(seat.shape[0], 4, c.seat_obs - seat.shape[2])], dim=2)
    glob = torch.cat([glob, torch.zeros(glob.shape[0], c.global_obs - glob.shape[1])], dim=1)
    with torch.no_grad():
        out = model(kind, seat, glob)
    for k, v in out.items():
        if not torch.is_tensor(v) or "out_" + k not in d:
            continue
        old = torch.from_numpy(d["out_" + k])
        if v.shape != old.shape:
            # The tai items' head grew at the end of its items.
            print(k, "shape", tuple(old.shape), "->", tuple(v.shape))
            continue
        print(k, float((v - old).abs().max()))
