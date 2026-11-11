#!/usr/bin/env python3
"""Takes a checkpoint of features version 1 to version 2 (declared ready hands), keeping what the network computes.

    python upgrade_features.py OLD.pt NEW.pt [--export NEW.onnx]

Version 2 adds (cpp/learn/features.h, actions.h): the action `declare` (the ninth special action, with its block of
the global vector after the other eight), two features of a seat (declared 天聽, 地聽), two rules (tianting, diting,
at the end of the rules) and two tai items (at the end). The new inputs get weights of zero, so every old position
gives the same outputs as before; the new action starts from the embedding of `tsumo` (an action taken nearly always
when it is legal); the new items of the tai head start at "does not happen". The optimizer's statistics are kept, with
zeros for the new weights.
"""

import argparse
import os
import sys

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.model import (FEATURES_VERSION, NUM_SPECIAL, NUM_TAI_ITEMS, MahjongNet, ModelConfig,  # noqa: E402
                           export_onnx, save_checkpoint)

OLD_SPECIAL = 8
OLD_TAI_ITEMS = 38
NEW_SEAT = 2
NEW_RULES = 2


def insert(t, dim, at, n, fill=0.0):
    shape = list(t.shape)
    shape[dim] = n
    new = torch.full(shape, fill, dtype=t.dtype)
    a, b = t.narrow(dim, 0, at), t.narrow(dim, at, t.shape[dim] - at)
    return torch.cat([a, new, b], dim=dim)


def upgrade_tensor(name, t, old, weights):
    """One parameter (weights=True) or a statistic of the optimizer for it (zeros where it grows)."""
    ga, rules_at = old["ga_size"], old["go_actions"] + OLD_SPECIAL * old["ga_size"]
    if name == "seat_in.weight":
        return insert(t, 1, old["seat_obs"], NEW_SEAT)
    if name == "global_in.0.weight":
        t = insert(t, 1, old["global_obs"], NEW_RULES)   # the later place first, so the earlier one stays valid
        return insert(t, 1, rules_at, ga)
    if name.endswith("special_emb"):
        row = t[0:1].clone() if weights else torch.zeros_like(t[0:1])
        return torch.cat([t, row], dim=0)
    if name == "me_out.weight":
        return insert(t, 0, OLD_TAI_ITEMS, NUM_TAI_ITEMS - OLD_TAI_ITEMS)
    if name == "me_out.bias":
        return insert(t, 0, OLD_TAI_ITEMS, NUM_TAI_ITEMS - OLD_TAI_ITEMS, -6.0 if weights else 0.0)
    return t


def upgrade_state(sd, old, weights=True):
    return {k: upgrade_tensor(k, v, old, weights) for k, v in sd.items()}


def upgrade(state):
    old = dict(state["config"])
    if old.get("features", 1) != 1:
        raise SystemExit(f"This checkpoint has features version {old.get('features')}, not 1")
    assert NUM_SPECIAL == OLD_SPECIAL + 1 and FEATURES_VERSION == 2
    config = dict(old)
    config["features"] = FEATURES_VERSION
    config["seat_obs"] = old["seat_obs"] + NEW_SEAT
    config["global_obs"] = old["global_obs"] + old["ga_size"] + NEW_RULES
    # The optimizer numbers the parameters as train0.py and train1.py group them: those with weight decay first.
    named = [(n, p.ndim) for n, p in MahjongNet(ModelConfig(**config)).named_parameters()]

    def decays(n, ndim):
        return ndim >= 2 and "emb" not in n and "att_bias" not in n
    names = [n for n, d in named if decays(n, d)] + [n for n, d in named if not decays(n, d)]
    out = dict(state)
    out["config"] = config
    out["model"] = upgrade_state(state["model"], old)
    if "ema" in state:
        out["ema"] = upgrade_state(state["ema"], old)
    if "optimizer" in state:
        # The optimizer keeps its statistics by the parameters' order.
        opt = state["optimizer"]
        ids = [i for g in opt["param_groups"] for i in g["params"]]
        if len(ids) == len(names):
            for i, name in zip(ids, names):
                st = opt["state"].get(i)
                if st:
                    for key, value in st.items():
                        if torch.is_tensor(value) and value.dim() > 0:
                            st[key] = upgrade_tensor(name, value.cpu(), old, False).to(value.device)
        else:
            del out["optimizer"]
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("old")
    ap.add_argument("new")
    ap.add_argument("--export", help="Also export the network (its averaged weights, when it has them) to .onnx")
    args = ap.parse_args()
    state = upgrade(torch.load(args.old, map_location="cpu", weights_only=False))
    model = MahjongNet(ModelConfig(**state["config"]))
    model.load_state_dict(state["model"])
    tmp = args.new + ".tmp"
    torch.save(state, tmp)
    os.replace(tmp, args.new)
    if args.export:
        if "ema" in state:
            model.load_state_dict(state["ema"])
        export_onnx(model.eval(), args.export)
    print(f"{args.old} -> {args.new}: {model.num_parameters()} parameters", flush=True)


if __name__ == "__main__":
    main()
