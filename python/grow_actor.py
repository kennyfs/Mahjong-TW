#!/usr/bin/env python3
"""A larger actor that starts out computing exactly what a trained one does (docs/training.md, "Making the analysis'
outputs more accurate"), so that training it measures what the size adds, not how far a network from scratch is
behind one trained for months.

    python grow_actor.py FROM.pt OUT.pt --layers 10 --ff 1024 --head 256

- Deeper: new blocks between the old ones, whose two residual branches end in zero weights (the attention's output
  projection and the feed-forward's second layer), so each passes its input through unchanged.
- A wider feed-forward layer in every block, and wider hidden layers in the action heads: the new units' incoming
  weights are random, their outgoing weights zero.

The width of the tokens (d_model) stays: a wider token would change every LayerNorm's statistics. The grown network's
outputs are checked against the original's on random inputs before it is saved (the EMA weights of FROM if it has
them; OUT holds them as its weights).
"""
import argparse
import os
import sys

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.model import MahjongNet, ModelConfig, load_checkpoint, save_checkpoint  # noqa: E402


def copy_into(dst, src):
    """src into the leading slice of dst (every dimension)."""
    with torch.no_grad():
        dst[tuple(slice(0, s) for s in src.shape)].copy_(src)


def grow(old, layers, ff, head, seed=0):
    torch.manual_seed(seed)
    oc = old.config
    assert layers >= oc.num_layers and ff >= oc.ff and head >= oc.head and oc.arch == "transformer"
    cfg = dict(oc.to_dict())
    cfg.update(num_layers=layers, ff=ff, head=head)
    new = MahjongNet(ModelConfig(**cfg))
    old_sd = old.state_dict()
    # Old block i goes to new block place[i]; the rest are new.
    place = [round(i * layers / oc.num_layers) for i in range(oc.num_layers)]
    new_sd = new.state_dict()
    with torch.no_grad():
        for name, p in new_sd.items():
            if name.startswith("blocks."):
                j = int(name.split(".")[1])
                rest = name.split(".", 2)[2]
                if j in place:
                    copy_into(p, old_sd[f"blocks.{place.index(j)}.{rest}"])
                    if rest.startswith("ff2.weight"):
                        p[:, oc.ff:] = 0.0           # the new feed-forward units' outgoing weights
                elif rest.startswith(("proj.", "ff2.")):
                    p.zero_()                         # a new block: both branches end in zeros
            elif name == "att_bias":
                p.zero_()
                for i, j in enumerate(place):
                    p[j] = old_sd[name][i]
            else:
                copy_into(p, old_sd[name])
        for h in ("outcome_head", "policy_head"):
            w_old = old_sd[f"{h}.kind_out.weight"].shape[1]
            new_sd[f"{h}.kind_out.weight"][:, w_old:] = 0.0
            new_sd[f"{h}.special_out.weight"][:, w_old:] = 0.0
        new_sd["me_out.weight"][:, oc.head:] = 0.0
    new.load_state_dict(new_sd)
    return new


def check(old, new, n=256, seed=1):
    g = torch.Generator().manual_seed(seed)
    c = old.config
    kind = torch.rand(n, 34, c.kind_obs, generator=g)
    seat = torch.rand(n, 4, c.seat_obs, generator=g)
    glob = torch.rand(n, c.global_obs, generator=g)
    old.eval(), new.eval()
    with torch.no_grad():
        a, b = old(kind, seat, glob), new(kind, seat, glob)
    worst = 0.0
    for k, v in a.items():
        if not torch.is_tensor(v):
            continue
        worst = max(worst, float((v - b[k]).abs().max()))
    return worst


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src")
    ap.add_argument("out")
    ap.add_argument("--layers", type=int, required=True)
    ap.add_argument("--ff", type=int, required=True)
    ap.add_argument("--head", type=int, required=True)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()
    old, state = load_checkpoint(args.src)
    if "ema" in state:
        old.load_state_dict(state["ema"])
    new = grow(old, args.layers, args.ff, args.head, args.seed)
    worst = check(old, new)
    print(f"{old.num_parameters()} -> {new.num_parameters()} parameters; largest output difference {worst:.2e}")
    if worst > 1e-4:
        raise SystemExit("The grown network does not compute what the original does")
    save_checkpoint(args.out, new, None, {"grown_from": os.path.basename(args.src), "stage": 1})


if __name__ == "__main__":
    main()
