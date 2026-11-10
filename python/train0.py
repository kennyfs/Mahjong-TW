#!/usr/bin/env python3
"""Stage 0 (docs/plan.md 3.4): trains a network on hands labelled by `mahjong gen0`, where every legal action of a
marked decision was finished by greedy players on the same wall.

    python train0.py --data DIR --out run/stage0/actor.pt --export run/stage0/actor.onnx

The per-action event heads learn those outcomes (their mean over the hidden states is what the action is worth if
everybody plays greedy afterwards); the opponent heads and the training-only heads learn their exact labels; the
policy head a softmax of the composed results. The data is read once (--passes), with a random symmetry per sample;
one hand in a hundred is held out and reported. --full trains the critic's shape on the same data.
"""

import argparse
import copy
import json
import math
import os
import sys
import time

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.data import DEFAULT_LIB, Library, Stream, batches, count_marks, list_files, to_torch  # noqa: E402
from mahjong.losses import AUX_GROUPS, MAIN, OPPONENTS, loss_terms, total_loss, weights_for  # noqa: E402
from mahjong.model import (MahjongNet, ModelConfig, check_onnx, export_onnx, load_checkpoint,  # noqa: E402
                           save_checkpoint)

TRAIN_ONLY_CONFIG = ("features", "kind_obs", "seat_obs", "global_obs", "kind_full", "seat_full", "global_full",
                     "go_actions", "ga_size")


def evaluate(model, held, weights, args, use_amp, device):
    model.eval()
    sums, n = {}, 0
    with torch.no_grad():
        for batch in held:
            b = to_torch(batch, device)
            with torch.autocast(device_type=device.type, dtype=torch.bfloat16, enabled=use_amp):
                out = model(*model.inputs(b))
            terms, metrics = loss_terms(out, b, args.policy_temp)
            size = len(batch["played"])
            for k, v in {**terms, **metrics, "loss": total_loss(terms, weights)}.items():
                sums[k] = sums.get(k, 0.0) + float(v.detach()) * size
            n += size
    model.train()
    return {k: v / max(1, n) for k, v in sums.items()}


def grad_report(model, batch, weights, args):
    """The size of each group's gradient at the trunk's output, relative to the main heads'."""
    out = model(*model.inputs(batch), detach_policy=not args.policy_through)
    terms, _ = loss_terms(out, batch, args.policy_temp)
    groups = {"main": MAIN, "opponents": OPPONENTS, **AUX_GROUPS}
    norms = {}
    for name, members in groups.items():
        loss = sum(weights[m] * terms[m] for m in members if weights[m] != 0.0)
        if not torch.is_tensor(loss):
            continue
        (g,) = torch.autograd.grad(loss, out["trunk"], retain_graph=True)
        norms[name] = float(g.norm())
    return {k: v / norms["main"] for k, v in norms.items()}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", nargs="+", required=True, help="Directories with gen0 .npz files (searched recursively)")
    ap.add_argument("--lib", default=DEFAULT_LIB, help="libmahjong_data.so")
    ap.add_argument("--init", help="Checkpoint to continue from; without it, a new model from the --model-* options")
    ap.add_argument("--out", required=True, help="Output checkpoint (.pt)")
    ap.add_argument("--export", help="Also export the EMA weights to this .onnx file (the actor only)")
    ap.add_argument("--full", action="store_true", help="Train the critic's shape (the full state as input)")
    ap.add_argument("--aux-scale", type=float, default=1.0,
                    help="Scales the weights of the training-only heads (0: without them)")
    ap.add_argument("--weight", action="append", default=[], metavar="NAME=VALUE",
                    help="A loss weight to change (mahjong/losses.py WEIGHTS)")
    ap.add_argument("--passes", type=float, default=1.0, help="How many times the data is read")
    ap.add_argument("--max-samples", type=int, help="Stop after this many samples")
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--final-lr", type=float, default=0.1, help="The last learning rate, as a fraction (cosine)")
    ap.add_argument("--warmup", type=int, default=1000)
    ap.add_argument("--weight-decay", type=float, default=0.01)
    ap.add_argument("--ema", type=float, default=0.999, help="EMA decay of the exported weights (0 disables)")
    ap.add_argument("--policy-temp", type=float, default=0.01,
                    help="Temperature of the policy target, in typical payments")
    ap.add_argument("--policy-through", action="store_true",
                    help="Let the policy loss's gradient into the trunk (it hurt the event heads when measured)")
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    ap.add_argument("--amp", action="store_true", help="bf16 autocast on CUDA")
    ap.add_argument("--threads", type=int, help="CPU threads of PyTorch")
    ap.add_argument("--expand-threads", type=int, default=8, help="Threads that expand batches")
    ap.add_argument("--group", type=int, default=16, help="Data files held in memory and shuffled together")
    ap.add_argument("--eval-data", nargs="+", help="Evaluate on these files instead of the held-out hands")
    ap.add_argument("--held-samples", type=int, default=20000, help="Decisions to evaluate on, at most")
    ap.add_argument("--eval-every", type=int, default=2000)
    ap.add_argument("--log-every", type=int, default=200)
    ap.add_argument("--grad-report", action="store_true", help="Report each group's gradient at the trunk")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--log-file", help="Append JSON lines of losses here")
    for field, default in ModelConfig().to_dict().items():
        if field not in TRAIN_ONLY_CONFIG and field != "full":
            if isinstance(default, bool):
                ap.add_argument(f"--model-{field.replace('_', '-')}", action="store_true")
            else:
                ap.add_argument(f"--model-{field.replace('_', '-')}", type=type(default), default=default)
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    if args.threads:
        torch.set_num_threads(args.threads)
    device = torch.device(args.device)
    library = Library(args.lib, threads=args.expand_threads)

    if args.init:
        model, state = load_checkpoint(args.init, device)
        total_steps = state.get("total_steps", 0)
        ema_state = state.get("ema")
        if model.config.features != library.sizes["version"]:
            raise SystemExit("The checkpoint and the data library disagree on the features' version")
    else:
        options = {f: getattr(args, "model_" + f) for f in ModelConfig().to_dict()
                   if f not in TRAIN_ONLY_CONFIG and f != "full"}
        model = MahjongNet(ModelConfig.for_library(library.sizes, full=args.full, **options)).to(device)
        state, total_steps, ema_state = {}, 0, None
    full = model.config.full
    weights = weights_for(full=full, aux=args.aux_scale,
                          overrides={k: float(v) for k, v in (w.split("=") for w in args.weight)})
    if full:
        weights["policy"] = 0.0  # The critic does not play.
    print(f"Model: {model.config.to_dict()}, {model.num_parameters()} parameters", flush=True)

    ema = copy.deepcopy(model).eval()
    if ema_state is not None:
        ema.load_state_dict(ema_state)
    for p in ema.parameters():
        p.requires_grad_(False)

    decay = [p for n, p in model.named_parameters() if p.ndim >= 2 and "emb" not in n and "att_bias" not in n]
    no_decay = [p for n, p in model.named_parameters() if not (p.ndim >= 2 and "emb" not in n and "att_bias" not in n)]
    opt = torch.optim.AdamW([{"params": decay, "weight_decay": args.weight_decay},
                             {"params": no_decay, "weight_decay": 0.0}], lr=args.lr)
    if args.init and "optimizer" in state:
        try:
            opt.load_state_dict(state["optimizer"])
        except ValueError:
            print("Optimizer state does not match; starting fresh", flush=True)

    files = list_files(args.data)
    if not files:
        raise SystemExit("No data files")
    num_marks = count_marks(files)
    samples = int(num_marks * 0.99 * args.passes)
    if args.max_samples:
        samples = min(samples, args.max_samples)
    steps = samples // args.batch
    print(f"{len(files)} files, {num_marks} labelled decisions; {steps} steps of {args.batch}", flush=True)

    held = []
    held_files, held_part = (list_files(args.eval_data), "all") if args.eval_data else (files, "held")
    for batch in batches(library, held_files, 1024, part=held_part, symmetries=False, drop_last=False, shuffle=False):
        held.append(batch)
        if sum(len(b["played"]) for b in held) >= args.held_samples:
            break
    print(f"Evaluation: {sum(len(b['played']) for b in held)} decisions", flush=True)

    stream = Stream(library, files, args.batch, seed=args.seed + total_steps, part="train", group=args.group,
                    passes=math.ceil(args.passes))
    log = open(args.log_file, "a") if args.log_file else None
    use_amp = args.amp and device.type == "cuda"

    def record(rec):
        print(" ".join(f"{k} {v:.4g}" if isinstance(v, float) else f"{k} {v}" for k, v in rec.items()), flush=True)
        if log:
            log.write(json.dumps(rec) + "\n")
            log.flush()

    model.train()
    sums, count = {}, 0
    start = time.time()
    step = 0
    for batch in stream:
        if step >= steps:
            break
        step += 1
        progress = step / max(1, steps)
        lr = args.lr * (args.final_lr + (1 - args.final_lr) * 0.5 * (1 + math.cos(math.pi * progress)))
        if not args.init:
            lr *= min(1.0, step / max(1, args.warmup))
        for group in opt.param_groups:
            group["lr"] = lr
        b = to_torch(batch, device)
        # The policy's target is a softmax of the composed results, which are noisy: its gradient stays out of the
        # trunk here (--policy-through lets it in).
        with torch.autocast(device_type=device.type, dtype=torch.bfloat16, enabled=use_amp):
            out = model(*model.inputs(b), detach_policy=not args.policy_through)
        terms, metrics = loss_terms(out, b, args.policy_temp)
        loss = total_loss(terms, weights)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        total_steps += 1
        if args.ema > 0:
            # A young average follows the weights closely, so that early exports are not mostly the initial weights.
            d = min(args.ema, (1 + total_steps) / (10 + total_steps))
            with torch.no_grad():
                for pe, pm in zip(ema.parameters(), model.parameters()):
                    pe.mul_(d).add_(pm.detach(), alpha=1 - d)

        for k, v in {**terms, **metrics, "loss": loss}.items():
            sums[k] = sums.get(k, 0.0) + float(v.detach())
        count += 1
        if step % args.log_every == 0:
            rec = {"step": total_steps, "lr": lr, "samples_per_s": step * args.batch / (time.time() - start)}
            rec.update({k: v / count for k, v in sums.items()})
            if args.grad_report:
                rec.update({"grad_" + k: v for k, v in grad_report(model, b, weights, args).items()})
            record(rec)
            sums, count = {}, 0
        if step % args.eval_every == 0:
            rec = {"step": total_steps, "held": 1}
            rec.update(evaluate(ema if args.ema > 0 else model, held, weights, args, use_amp, device))
            record(rec)
    stream.close()
    if count:
        rec = {"step": total_steps, "lr": lr, "samples_per_s": step * args.batch / (time.time() - start)}
        rec.update({k: v / count for k, v in sums.items()})
        record(rec)
    rec = {"step": total_steps, "held": 1}
    rec.update(evaluate(ema if args.ema > 0 else model, held, weights, args, use_amp, device))
    record(rec)

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    save_checkpoint(args.out, model, opt, {"total_steps": total_steps, "ema": ema.state_dict()})
    print(f"Saved {args.out}", flush=True)
    if args.export:
        if full:
            print("The critic is not exported", flush=True)
        else:
            os.makedirs(os.path.dirname(os.path.abspath(args.export)), exist_ok=True)
            exported = ema if args.ema > 0 else model
            export_onnx(exported, args.export)
            diff = check_onnx(exported, args.export, to_torch(held[0], "cpu"))
            note = "not checked: no onnxruntime here" if diff is None else f"largest difference from PyTorch {diff:.2g}"
            print(f"Exported {args.export} ({note})", flush=True)


if __name__ == "__main__":
    main()
