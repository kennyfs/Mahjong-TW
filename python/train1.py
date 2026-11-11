#!/usr/bin/env python3
"""Stage 1 (docs/plan.md 3.4): one iteration's training on self-play hands written by `mahjong selfplay`.

    python train1.py --data run/selfplay/iter_12 --actor-init actor_11.pt --critic-init critic_11.pt \\
        --actor-out actor_12.pt --critic-out critic_12.pt --export actor_12.onnx

Every sample is read once. Per batch:

- The critic (the full state as input) learns the outcome events of the actions whose hand was played out: the
  action played, and the other action of a fork. Its composed results Q(s, a) of all legal actions, as they are
  before this step, give the advantages.
- The policy moves along sum_a grad pi(a|o) A(s, a), corrected by the actual result of the action played, held near
  the policy that played by a KL term, with a small entropy bonus.
- The actor's per-action event heads learn the critic's (all legal actions) and the actual outcome (the action
  played); its other heads their labels, as in stage 0.

One hand in a hundred is held out and reported at the end, with the diagnostics of 3.5: how much of the results the
critic explains, and on forks the predicted against the realized difference between the two actions.
"""

import argparse
import copy
import json
import os
import sys
import time

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.data import DEFAULT_LIB, Library, Stream, batches, count_marks, list_files, to_torch  # noqa: E402
from mahjong.losses import (distill_terms, loss_terms, outcome_labels, payment_unit, policy_terms,  # noqa: E402
                            total_loss, weights_for)
from mahjong.model import (NUM_ACTIONS, NUM_EVENTS, MahjongNet, ModelConfig, check_onnx, compose_q,  # noqa: E402
                           export_onnx, load_checkpoint, save_checkpoint)


def make_optimizer(model, lr, weight_decay, state):
    def decays(n, p):
        return p.ndim >= 2 and "emb" not in n and "att_bias" not in n
    opt = torch.optim.AdamW(
        [{"params": [p for n, p in model.named_parameters() if decays(n, p)], "weight_decay": weight_decay},
         {"params": [p for n, p in model.named_parameters() if not decays(n, p)], "weight_decay": 0.0}], lr=lr)
    if state and "optimizer" in state:
        try:
            opt.load_state_dict(state["optimizer"])
        except ValueError:
            print("An optimizer's state does not match; starting it fresh", flush=True)
    for group in opt.param_groups:
        group["lr"] = lr
    return opt


def step_losses(actor, critic, b, args, actor_weights, critic_weights, use_amp, device):
    """-> (the actor's loss, the critic's loss, everything to report)."""
    report = {}
    with torch.autocast(device_type=device.type, dtype=torch.bfloat16, enabled=use_amp):
        c_out = critic(*critic.inputs(b))
        a_out = actor(*actor.inputs(b))
    # The critic: the outcomes of the actions played out (the action played, and a fork's).
    c_terms, c_metrics = loss_terms(c_out, b)
    critic_loss = total_loss(c_terms, critic_weights)
    report.update({"c_" + k: v for k, v in c_terms.items() if critic_weights.get(k, 0.0) != 0.0})
    report["c_q_mse"] = c_metrics["q_mse"]
    q_critic = compose_q(torch.sigmoid(c_out["outcomes"][..., :NUM_EVENTS].float()), c_out["tai"].float(),
                         b["coef"]).detach()

    # The actor: its heads' labels with the action played alone as the known outcome (forks teach only the critic).
    own = dict(b)
    own["known"] = torch.nn.functional.one_hot(b["played"].long(), NUM_ACTIONS).to(b["known"].dtype)
    a_terms, a_metrics = loss_terms(a_out, own)
    a_terms.pop("policy")
    a_terms.pop("adv")
    a_terms.update(distill_terms(a_out, c_out, b))
    p_terms, p_metrics = policy_terms(a_out["policy"], b, q_critic)
    a_terms.update(p_terms)
    actor_loss = total_loss(a_terms, actor_weights)
    report.update({k: v for k, v in a_terms.items() if actor_weights.get(k, 0.0) != 0.0})
    report.update(p_metrics)
    report["q_mse"] = a_metrics["q_mse"]
    report["actor_loss"] = actor_loss
    report["critic_loss"] = critic_loss
    return actor_loss, critic_loss, report, q_critic


def held_out_report(actor, critic, held, args, actor_weights, critic_weights, use_amp, device):
    actor.eval()
    critic.eval()
    sums, n = {}, 0
    # Forks: the critic's difference between the two actions against the realized one, in typical payments.
    pred, real = [], []
    # How much of the results the critic explains.
    resid, results = [], []
    with torch.no_grad():
        for batch in held:
            b = to_torch(batch, device)
            _, _, report, q_critic = step_losses(actor, critic, b, args, actor_weights, critic_weights, use_amp, device)
            size = len(batch["played"])
            for k, v in report.items():
                sums[k] = sums.get(k, 0.0) + float(v) * size
            n += size
            unit = payment_unit(b)
            y, tai_label = outcome_labels(b)
            q_label = compose_q(y[..., :NUM_EVENTS], tai_label, b["coef"])
            played = b["played"].long().unsqueeze(-1)
            g = q_label.gather(1, played).squeeze(-1)
            results.append((g / unit).cpu())
            resid.append(((g - q_critic.gather(1, played).squeeze(-1)) / unit).cpu())
            other = (b["known"] > 0) & (torch.nn.functional.one_hot(b["played"].long(), NUM_ACTIONS) == 0)
            has = other.any(dim=-1)
            if has.any():
                alt = other.float().argmax(-1, keepdim=True)
                d_pred = (q_critic.gather(1, played) - q_critic.gather(1, alt)).squeeze(-1) / unit
                d_real = (q_label.gather(1, played) - q_label.gather(1, alt)).squeeze(-1) / unit
                pred.append(d_pred[has].cpu())
                real.append(d_real[has].cpu())
    actor.train()
    critic.train()
    out = {k: v / max(1, n) for k, v in sums.items()}
    if results:
        results, resid = torch.cat(results), torch.cat(resid)
        out["critic_explains"] = float(1.0 - (resid ** 2).mean() / results.var().clamp(min=1e-9))
    if pred:
        pred, real = torch.cat(pred), torch.cat(real)
        out["forks"] = len(pred)
        # 1 when the critic predicts the realized differences exactly, 0 when it says nothing about them.
        out["fork_explains"] = float(1.0 - ((real - pred) ** 2).mean() / (real ** 2).mean().clamp(min=1e-9))
        out["fork_slope"] = float((pred * real).sum() / (pred ** 2).sum().clamp(min=1e-9))
        out["fork_pred_rms"] = float((pred ** 2).mean().sqrt())
        out["fork_real_rms"] = float((real ** 2).mean().sqrt())
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", nargs="+", required=True, help="Directories with selfplay .npz files")
    ap.add_argument("--lib", default=DEFAULT_LIB, help="libmahjong_data.so")
    ap.add_argument("--actor-init", required=True)
    ap.add_argument("--critic-init", required=True)
    ap.add_argument("--actor-out", required=True)
    ap.add_argument("--critic-out", required=True)
    ap.add_argument("--export", help="Export the actor's EMA weights to this .onnx file")
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--critic-lr", type=float, default=3e-4)
    ap.add_argument("--weight-decay", type=float, default=0.01)
    ap.add_argument("--ema", type=float, default=0.999)
    ap.add_argument("--pg", type=float, default=1.0, help="Weight of the policy gradient over all actions")
    ap.add_argument("--corr", type=float, default=0.25, help="Weight of the correction by the actual result")
    ap.add_argument("--kl", type=float, default=0.25, help="Weight of the KL term to the policy that played")
    ap.add_argument("--entropy", type=float, default=0.005, help="Entropy bonus")
    ap.add_argument("--distill", type=float, default=1.0, help="Weight of the critic's events in the actor's heads")
    ap.add_argument("--aux-scale", type=float, default=1.0,
                    help="Scales the weights of the training-only heads (0: without them)")
    ap.add_argument("--max-samples", type=int)
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    ap.add_argument("--amp", action="store_true", help="bf16 autocast on CUDA")
    ap.add_argument("--threads", type=int, help="CPU threads of PyTorch")
    ap.add_argument("--expand-threads", type=int, default=8)
    ap.add_argument("--group", type=int, default=64, help="Data files held in memory and shuffled together")
    ap.add_argument("--held-samples", type=int, default=30000)
    ap.add_argument("--log-every", type=int, default=500)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--log-file", help="Append JSON lines here")
    ap.add_argument("--progress", help="A file rewritten as the run goes: the steps done, the steps to do, the seconds")
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    if args.threads:
        torch.set_num_threads(args.threads)
    device = torch.device(args.device)
    library = Library(args.lib, threads=args.expand_threads)
    use_amp = args.amp and device.type == "cuda"

    actor, a_state = load_checkpoint(args.actor_init, device)
    critic, c_state = load_checkpoint(args.critic_init, device)
    if actor.config.full or not critic.config.full:
        raise SystemExit("--actor-init must be an actor and --critic-init a critic (trained with --full)")
    total_steps = a_state.get("total_steps", 0)
    ema = copy.deepcopy(actor).eval()
    if "ema" in a_state:
        ema.load_state_dict(a_state["ema"])
    for p in ema.parameters():
        p.requires_grad_(False)
    # A checkpoint of stage 0 comes with the optimizer of a different loss: start fresh then, and from the averaged
    # weights, which are the ones that played (the KL term is measured from them).
    fresh = a_state.get("stage", 0) != 1
    if fresh and "ema" in a_state:
        actor.load_state_dict(a_state["ema"])
    a_opt = make_optimizer(actor, args.lr, args.weight_decay, None if fresh else a_state)
    c_opt = make_optimizer(critic, args.critic_lr, args.weight_decay, None if c_state.get("stage", 0) != 1 else c_state)

    actor_weights = weights_for(full=False, aux=args.aux_scale)
    actor_weights.update({"pg": args.pg, "corr": args.corr, "kl": args.kl, "entropy": -args.entropy,
                          "distill_events": args.distill, "distill_others": 0.5 * args.distill,
                          "distill_tai": args.distill})
    critic_weights = weights_for(full=True, aux=args.aux_scale)
    critic_weights["policy"] = 0.0

    files = list_files(args.data)
    if not files:
        raise SystemExit("No data files")
    samples = int(count_marks(files) * 0.99)
    if args.max_samples:
        samples = min(samples, args.max_samples)
    steps = samples // args.batch
    print(f"Actor {actor.num_parameters()} parameters, critic {critic.num_parameters()}; {len(files)} files, "
          f"{steps} steps of {args.batch}", flush=True)
    held = []
    for batch in batches(library, files, 1024, part="held", symmetries=False, drop_last=False, shuffle=False):
        held.append(batch)
        if sum(len(b["played"]) for b in held) >= args.held_samples:
            break

    log = open(args.log_file, "a") if args.log_file else None

    def record(rec):
        print(" ".join(f"{k} {v:.4g}" if isinstance(v, float) else f"{k} {v}" for k, v in rec.items()), flush=True)
        if log:
            log.write(json.dumps(rec) + "\n")
            log.flush()

    def progress(done):
        if args.progress:
            with open(args.progress + ".tmp", "w") as f:
                f.write(f"{done} {steps} {int(time.time() - start)}\n")
            os.replace(args.progress + ".tmp", args.progress)

    start = time.time()
    progress(0)
    stream = Stream(library, files, args.batch, seed=args.seed + total_steps, part="train", group=args.group)
    actor.train()
    critic.train()
    sums, count, step, bad = {}, 0, 0, 0
    start = time.time()
    for batch in stream:
        if step >= steps:
            break
        step += 1
        if step % 20 == 0:
            progress(step)
        b = to_torch(batch, device)
        actor_loss, critic_loss, report, _ = step_losses(actor, critic, b, args, actor_weights, critic_weights,
                                                         use_amp, device)
        if not bool(torch.isfinite(actor_loss + critic_loss)):
            # Nobody watches this run: a batch that gives no finite loss is left out, and a run of them ends it
            # with an error before the networks are saved.
            bad += 1
            if bad > 20:
                raise SystemExit("The losses are not finite")
            continue
        a_opt.zero_grad(set_to_none=True)
        c_opt.zero_grad(set_to_none=True)
        (actor_loss + critic_loss).backward()
        torch.nn.utils.clip_grad_norm_(actor.parameters(), 1.0)
        torch.nn.utils.clip_grad_norm_(critic.parameters(), 1.0)
        a_opt.step()
        c_opt.step()
        total_steps += 1
        if args.ema > 0:
            with torch.no_grad():
                for pe, pm in zip(ema.parameters(), actor.parameters()):
                    pe.mul_(args.ema).add_(pm.detach(), alpha=1 - args.ema)
        for k, v in report.items():
            sums[k] = sums.get(k, 0.0) + float(v.detach())
        count += 1
        if step % args.log_every == 0:
            rec = {"step": total_steps, "samples_per_s": step * args.batch / (time.time() - start)}
            rec.update({k: v / count for k, v in sums.items()})
            record(rec)
            sums, count = {}, 0
    stream.close()
    progress(step)
    if count:
        rec = {"step": total_steps, "samples_per_s": step * args.batch / (time.time() - start)}
        rec.update({k: v / count for k, v in sums.items()})
        record(rec)

    exported = ema if args.ema > 0 else actor
    if held:
        rec = {"step": total_steps, "held": 1}
        rec.update(held_out_report(exported, critic, held, args, actor_weights, critic_weights, use_amp, device))
        record(rec)
    for path in (args.actor_out, args.critic_out):
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    save_checkpoint(args.actor_out, actor, a_opt, {"total_steps": total_steps, "ema": ema.state_dict(), "stage": 1})
    save_checkpoint(args.critic_out, critic, c_opt, {"total_steps": total_steps, "stage": 1})
    print(f"Saved {args.actor_out} and {args.critic_out}", flush=True)
    if args.export:
        os.makedirs(os.path.dirname(os.path.abspath(args.export)), exist_ok=True)
        export_onnx(exported, args.export)
        diff = check_onnx(exported, args.export, to_torch(held[0], "cpu")) if held else None
        note = "not checked: no onnxruntime here" if diff is None else f"largest difference from PyTorch {diff:.2g}"
        print(f"Exported {args.export} ({note})", flush=True)


if __name__ == "__main__":
    main()
