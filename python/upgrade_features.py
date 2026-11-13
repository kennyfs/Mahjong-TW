#!/usr/bin/env python3
"""Takes a checkpoint to the current features version (one step after another), keeping what the network computes.

    python upgrade_features.py OLD.pt NEW.pt [--export NEW.onnx]

Version 2 adds (cpp/learn/features.h, actions.h): the action `declare` (the ninth special action, with its block of
the global vector after the other eight), two features of a seat (declared 天聽, 地聽), two rules (tianting, diting,
at the end of the rules) and two tai items (at the end). Version 3 adds 報聽: a feature of a seat (declared 報聽, after
the other two), a rule (baoting, at the end of the rules) and a tai item (at the end).

The new inputs get weights of zero, so every old position gives the same outputs as before; a new action starts from
the embedding of `tsumo` (an action taken nearly always when it is legal); the new items of the tai head start at
"does not happen". The optimizer's statistics are kept, with zeros for the new weights.
"""

import argparse
import os
import sys

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.model import FEATURES_VERSION, ME_OUT, MahjongNet, ModelConfig, export_onnx  # noqa: E402

# Each step from version v to v + 1: the special actions before it and how many it adds, the seat features, the rules
# and the tai items before it and how many it adds (all at the ends of their parts).
STEPS = {
    1: dict(special=8, new_special=1, new_seat=2, new_rules=2, tai=38, new_tai=2),
    2: dict(special=9, new_special=0, new_seat=1, new_rules=1, tai=40, new_tai=1),
}

def insert(t, dim, at, n, fill=0.0):
    shape = list(t.shape)
    shape[dim] = n
    new = torch.full(shape, fill, dtype=t.dtype)
    a, b = t.narrow(dim, 0, at), t.narrow(dim, at, t.shape[dim] - at)
    return torch.cat([a, new, b], dim=dim)


def upgrade_tensor(name, t, old, step, weights):
    """One parameter (weights=True) or a statistic of the optimizer for it (zeros where it grows)."""
    ga, rules_at = old["ga_size"], old["go_actions"] + step["special"] * old["ga_size"]
    if name == "seat_in.weight":
        return insert(t, 1, old["seat_obs"], step["new_seat"])
    if name == "global_in.0.weight":
        t = insert(t, 1, old["global_obs"], step["new_rules"])   # the later place first, so the earlier one stays valid
        return insert(t, 1, rules_at, ga * step["new_special"])
    if name.endswith("special_emb") and step["new_special"]:
        row = t[0:1].clone() if weights else torch.zeros_like(t[0:1])
        return torch.cat([t] + [row] * step["new_special"], dim=0)
    if name == "me_out.weight":
        return insert(t, 0, step["tai"], step["new_tai"])
    if name == "me_out.bias":
        return insert(t, 0, step["tai"], step["new_tai"], -6.0 if weights else 0.0)
    return t


def upgrade_state(sd, old, step, weights=True):
    return {k: upgrade_tensor(k, v, old, step, weights) for k, v in sd.items()}


def upgrade_step(state):
    old = dict(state["config"])
    version = old.get("features", 1)
    step = STEPS[version]
    config = dict(old)
    config["features"] = version + 1
    config["seat_obs"] = old["seat_obs"] + step["new_seat"]
    config["global_obs"] = old["global_obs"] + old["ga_size"] * step["new_special"] + step["new_rules"]
    # The optimizer numbers the parameters as train0.py and train1.py group them: those with weight decay first.
    named = [(n, p.ndim) for n, p in MahjongNet(ModelConfig(**config)).named_parameters()]

    def decays(n, ndim):
        return ndim >= 2 and "emb" not in n and "att_bias" not in n
    names = [n for n, d in named if decays(n, d)] + [n for n, d in named if not decays(n, d)]
    out = dict(state)
    out["config"] = config
    out["model"] = upgrade_state(state["model"], old, step)
    if "ema" in state:
        out["ema"] = upgrade_state(state["ema"], old, step)
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
                            st[key] = upgrade_tensor(name, value.cpu(), old, step, False).to(value.device)
        else:
            del out["optimizer"]
    return out


def upgrade(state):
    version = state["config"].get("features", 1)
    if version >= FEATURES_VERSION:
        raise SystemExit(f"This checkpoint has features version {version} already")
    while state["config"].get("features", 1) < FEATURES_VERSION:
        state = upgrade_step(state)
    assert state["model"]["me_out.bias"].shape[0] == ME_OUT
    return state


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
