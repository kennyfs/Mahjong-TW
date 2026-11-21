#!/usr/bin/env python3
"""Making the analysis' outputs more accurate (docs/training.md, "Making the analysis' outputs more accurate"):
several actors ("arms") trained from one network at once on the same stream of self-play samples, read several
times, with one critic that keeps learning beside them.

    python train_acc.py --data DIR... --held DIR --actor-init actor_221.pt --critic-init critic_221.pt \\
        --out OUT --passes 2 --arm base --arm exact:exact_wins=1 --arm skip:tai_skip=1,exact_wins=1

Every arm's actor starts from the averaged weights of --actor-init. Per batch:

- the critic (unless --critic-lr 0) learns the outcomes of the actions played out (the action played and a fork's),
  and with --critic-exact the known result of a self-draw that is on offer (it ends the hand there: the event and
  the tai are certain) and the tai of a win on a discard (certain, given that it is mine);
- each arm's actor learns its heads' labels (the action played as the known outcome), the critic's events and tai of
  every legal action (distillation), and stays near the starting network's policy: KL(pi_0 || pi), weight
  `pi_kl`. With `exact_wins` the distillation's targets at the win actions are the certain ones above.

An arm is NAME or NAME:KEY=VALUE,...: lr, pi_kl, distill, tai_skip (0/1), exact_wins (0/1), freeze (none, trunk:
only the heads learn), init (a checkpoint to start this arm from instead of --actor-init, such as a larger network
from grow_actor.py; the policy's KL is still to --actor-init's), and any loss weight by its name (events, tai,
ready, danger, ...).
"""
import argparse
import copy
import json
import math
import os
import sys
import time

import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.data import DEFAULT_LIB, Library, Stream, batches, count_marks, list_files, to_torch  # noqa: E402
from mahjong.losses import distill_terms, loss_terms, total_loss, weights_for  # noqa: E402
from mahjong.model import (A_SPECIAL, GA_READY, NUM_ACTIONS, NUM_EVENTS, NUM_SPECIAL, MahjongNet,  # noqa: E402
                           ModelConfig, TAI_SCALE, compose_q, export_onnx, load_checkpoint, save_checkpoint)

A_TSUMO, A_RON = A_SPECIAL, A_SPECIAL + 1
SO_TURN = 24  # a seat token's "discarded the tile on offer"


def exact_win_labels(b, go_actions, ga_size):
    """The certain results of the win actions on offer.

    -> (tsumo legal [B], its tai [B], ron legal [B], its tai [B], the event of my ron (1 to 3) [B]).
    A self-draw ends the hand at once; a win on a discard is mine only if no seat before me wins on it too, so only
    its tai is certain (given that I win).
    """
    g = b["global"].float()
    blocks = g[:, go_actions:go_actions + NUM_SPECIAL * ga_size].reshape(-1, NUM_SPECIAL, ga_size)
    tsumo = b["legal"][:, A_TSUMO] > 0
    ron = b["legal"][:, A_RON] > 0
    tsumo_tai = (blocks[:, 0, GA_READY + 2] * TAI_SCALE).round()
    ron_tai = (blocks[:, 1, GA_READY + 1] * TAI_SCALE).round()
    discarder = b["seat"][:, :, SO_TURN].float().argmax(-1).clamp(min=1)
    return tsumo, tsumo_tai, ron, ron_tai, discarder


def with_exact_known(b, ex):
    """The batch with the self-draws on offer as known actions (for the critic's own labels)."""
    tsumo, tsumo_tai, _, _, _ = ex
    out = dict(b)
    known = b["known"].clone()
    events = b["events"].long().clone()
    tai = b["tai"].clone()
    idx = torch.nonzero(tsumo & (known[:, A_TSUMO] == 0)).squeeze(-1)
    if len(idx):
        known[idx, A_TSUMO] = 1
        events[idx, A_TSUMO] = 1  # bit 0: I win by self-draw
        t = tai[idx, A_TSUMO]
        t[:, 0] = tsumo_tai[idx].to(t.dtype)
        tai[idx, A_TSUMO] = t
    out["known"], out["events"], out["tai"] = known, events, tai
    return out


def exact_ron_tai_loss(out, b, ex):
    """The tai head at a win on a discard, given the win: its tai is certain."""
    _, _, ron, ron_tai, discarder = ex
    if not bool(ron.any()):
        return out["tai"].sum() * 0.0
    t = out["tai"].float()[:, A_RON, :]                     # [B, 10]
    pred = t.gather(1, discarder.long()[:, None]).squeeze(1)  # ron_k is event k
    err = ((pred - ron_tai) / TAI_SCALE) ** 2
    return (err * ron.float()).sum() / ron.float().sum().clamp(min=1.0)


def distill_with_exact(a_out, c_out, b, ex):
    """distill_terms with the certain results at the win actions in place of the critic's."""
    tsumo, tsumo_tai, ron, ron_tai, discarder = ex
    teacher = {"outcomes": c_out["outcomes"].float().clone(), "tai": c_out["tai"].float().clone()}
    big = 30.0
    if bool(tsumo.any()):
        o = teacher["outcomes"][:, A_TSUMO]
        o[tsumo] = -big
        o[tsumo, 0] = big
        teacher["outcomes"][:, A_TSUMO] = o
        tt = teacher["tai"][:, A_TSUMO]
        tt[tsumo, 0] = tsumo_tai[tsumo]
        teacher["tai"][:, A_TSUMO] = tt
    if bool(ron.any()):
        tt = teacher["tai"][:, A_RON]
        rows = torch.nonzero(ron).squeeze(-1)
        tt[rows, discarder[rows].long()] = ron_tai[rows]
        teacher["tai"][:, A_RON] = tt
    return distill_terms(a_out, teacher, b)


def fork_mse(out, b):
    """The squared error of the predicted difference between a fork's two actions, in typical payments (summed over
    the samples with a fork, divided by all samples: the held report weighs it by batch size)."""
    known = b["known"] > 0
    other = known & (F.one_hot(b["played"].long(), NUM_ACTIONS) == 0)
    has = other.any(-1)
    if not bool(has.any()):
        return 0.0
    from mahjong.losses import outcome_labels, payment_unit
    y, tai_label = outcome_labels(b)
    coef = b["coef"].float()
    q = compose_q(torch.sigmoid(out["outcomes"][..., :NUM_EVENTS].float()), out["tai"].float(), coef)
    ql = compose_q(y[..., :NUM_EVENTS], tai_label, coef)
    unit = payment_unit(b).float()
    played = b["played"].long()[:, None]
    alt = other.float().argmax(-1, keepdim=True)
    dp = (q.gather(1, played) - q.gather(1, alt)).squeeze(1) / unit
    dr = (ql.gather(1, played) - ql.gather(1, alt)).squeeze(1) / unit
    return float((((dp - dr) ** 2) * has.float()).sum() / has.float().sum() )


def rank_loss(out, q_critic, t_policy, b):
    """Q's differences among the actions a player would consider: sum_a pi_0(a) (d(a) - d_c(a))^2, with d the actor's
    composed result less its pi_0-weighted mean and d_c the critic's, in typical payments. A squared error, so the
    actor still learns the mean over what it cannot see; weighted by the starting policy, so the actions that decide
    which one is best count, not the hopeless ones."""
    from mahjong.losses import payment_unit
    legal = b["legal"] > 0
    unit = payment_unit(b).float()[:, None]
    qa = compose_q(torch.sigmoid(out["outcomes"][..., :NUM_EVENTS].float()), out["tai"].float(), b["coef"].float()) / unit
    qc = q_critic.float() / unit
    neg = torch.finfo(torch.float32).min
    pi = torch.softmax(t_policy.float().masked_fill(~legal, neg), dim=-1).detach()
    da = qa - (pi * qa).sum(-1, keepdim=True)
    dc = qc - (pi * qc).sum(-1, keepdim=True)
    return (pi * (da - dc) ** 2).masked_fill(~legal, 0.0).sum(-1).mean()


def critic_wall_mean(c_fwd, b, k, use_amp):
    """The critic's events and tai of every action, averaged over the k reshuffled orders of the undrawn tiles the
    batch holds (data.full_reshuffled): the probabilities' mean, and the tai's mean weighted by each order's
    probability of that win. Given the hands the order is uniform, so this is the same target as the critic's with
    the order as recorded, less the order's noise."""
    n = b["kind"].shape[0]

    def joined(obs, full):
        return torch.cat([obs[:, None].expand(n, k, *obs.shape[1:]), full], dim=-1).reshape(n * k, *full.shape[2:-1], -1)

    with torch.no_grad(), torch.autocast(device_type="cuda", dtype=torch.bfloat16, enabled=use_amp):
        o = c_fwd(joined(b["kind"], b["kind_full_k"]), joined(b["seat"], b["seat_full_k"]),
                  joined(b["global"], b["global_full_k"]))
    p = torch.sigmoid(o["outcomes"].float()).reshape(n, k, NUM_ACTIONS, -1)
    tai = o["tai"].float().reshape(n, k, NUM_ACTIONS, -1)
    pw = p[..., :NUM_EVENTS]
    return {"outcomes": torch.logit(p.mean(1).clamp(1e-6, 1 - 1e-6)),
            "tai": (pw * tai).sum(1) / pw.sum(1).clamp(min=1e-6)}


def parse_arm(spec):
    name, _, rest = spec.partition(":")
    opts = {"lr": None, "pi_kl": 1.0, "distill": 1.0, "tai_skip": 0, "exact_wins": 0, "forks": 0, "big": 0.0, "big_min": 6.0, "rank": 0.0,
            "wall": 0,
            "freeze": "none", "init": None,
            "weights": {}}
    for kv in filter(None, rest.split(",")):
        k, _, v = kv.partition("=")
        if k in ("lr", "pi_kl", "distill", "big", "big_min", "rank"):
            opts[k] = float(v)
        elif k in ("tai_skip", "exact_wins", "forks", "wall"):
            opts[k] = int(v)
        elif k in ("freeze", "init"):
            opts[k] = v
        else:
            opts["weights"][k] = float(v)
    opts["name"] = name
    return opts


def make_optimizer(params, lr, weight_decay):
    params = list(params)
    decay = [p for n, p in params if p.ndim >= 2 and "emb" not in n and "att_bias" not in n]
    other = [p for n, p in params if not (p.ndim >= 2 and "emb" not in n and "att_bias" not in n)]
    return torch.optim.AdamW([{"params": decay, "weight_decay": weight_decay},
                              {"params": other, "weight_decay": 0.0}], lr=lr, fused=True)


def build_actor(path, tai_skip, device):
    base, state = load_checkpoint(path, device)
    if "ema" in state:
        base.load_state_dict(state["ema"])
    cfg = dict(base.config.to_dict())
    cfg["tai_skip"] = bool(tai_skip)
    model = MahjongNet(ModelConfig(**cfg)).to(device)
    missing, unexpected = model.load_state_dict(base.state_dict(), strict=False)
    assert not unexpected and all("tai_gate" in m for m in missing), (missing, unexpected)
    return model


def kl_to(teacher_logits, logits, legal):
    neg = torch.finfo(torch.float32).min
    lt = F.log_softmax(teacher_logits.float().masked_fill(~legal, neg), dim=-1)
    lp = F.log_softmax(logits.float().masked_fill(~legal, neg), dim=-1)
    pt = lt.exp()
    return (pt * (lt - lp)).masked_fill(~legal, 0.0).sum(-1).mean()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", nargs="+", required=True)
    ap.add_argument("--held", nargs="+", required=True, help="Hands no network trained on, for the reports")
    ap.add_argument("--held-samples", type=int, default=60000)
    ap.add_argument("--lib", default=DEFAULT_LIB)
    ap.add_argument("--actor-init", required=True)
    ap.add_argument("--critic-init", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--arm", action="append", required=True)
    ap.add_argument("--passes", type=int, default=1)
    ap.add_argument("--max-samples", type=int, help="Stop after this many samples (all passes)")
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--critic-lr", type=float, default=1e-4)
    ap.add_argument("--critic-exact", action="store_true")
    ap.add_argument("--warmup", type=int, default=300)
    ap.add_argument("--wall-k", type=int, default=0,
                    help="Reshuffle the undrawn tiles this many times per sample; arms with wall=1 learn the critic's "
                         "mean over those orders (needs a library with mj_full_reshuffled)")
    ap.add_argument("--start-step", type=int, default=0,
                    help="Go on from this step of the schedule (an interrupted run: every arm's init= its saved EMA "
                         "weights, a new --seed for the order of the hands; the learning rate warms up again)")
    ap.add_argument("--weight-decay", type=float, default=0.01)
    ap.add_argument("--ema", type=float, default=0.999)
    ap.add_argument("--expand-threads", type=int, default=8)
    ap.add_argument("--group", type=int, default=64)
    ap.add_argument("--report-every", type=int, default=2000)
    ap.add_argument("--save-every", type=int, default=0, help="Also save every arm's EMA weights every this many steps")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--amp", action="store_true")
    ap.add_argument("--compile", action="store_true", help="torch.compile the networks' training forward passes "
                    "(fuses the small element-wise steps: faster where the GPU's power or memory is the limit)")
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    device = torch.device("cuda")
    use_amp = args.amp
    os.makedirs(args.out, exist_ok=True)
    log = open(os.path.join(args.out, "train.jsonl"), "a")

    def record(rec):
        print(" ".join(f"{k} {v:.5g}" if isinstance(v, float) else f"{k} {v}" for k, v in rec.items()), flush=True)
        log.write(json.dumps(rec) + "\n")
        log.flush()

    library = Library(args.lib, threads=args.expand_threads)
    files = list_files(args.data)
    total = int(count_marks(files) * 0.99) * args.passes
    if args.max_samples:
        total = min(total, args.max_samples)
    steps = total // args.batch

    critic, c_state = load_checkpoint(args.critic_init, device)
    c_opt = make_optimizer(critic.named_parameters(), args.critic_lr, args.weight_decay) if args.critic_lr > 0 else None
    critic_weights = weights_for(full=True)
    critic_weights["policy"] = 0.0
    teacher = build_actor(args.actor_init, False, device).eval()
    for p in teacher.parameters():
        p.requires_grad_(False)
    cfg = teacher.config
    maybe_compile = torch.compile if args.compile else (lambda m: m)
    c_fwd, t_fwd = maybe_compile(critic), maybe_compile(teacher)

    arms = []
    for spec in args.arm:
        o = parse_arm(spec)
        model = build_actor(o["init"] or args.actor_init, o["tai_skip"], device)
        if o["freeze"] == "trunk":
            for n, p in model.named_parameters():
                if not any(n.startswith(h) for h in ("outcome_head", "pair_", "seat_out", "me_out", "chow_out",
                                                     "tai_gate")):
                    p.requires_grad_(False)
        lr = o["lr"] or args.lr
        opt = make_optimizer([(n, p) for n, p in model.named_parameters() if p.requires_grad], lr, args.weight_decay)
        ema = copy.deepcopy(model).eval()
        for p in ema.parameters():
            p.requires_grad_(False)
        w = weights_for(full=False)
        w.update({"distill_events": o["distill"], "distill_others": 0.5 * o["distill"], "distill_tai": o["distill"],
                  "pi_kl": o["pi_kl"], "exact_ron_tai": 1.0 if o["exact_wins"] else 0.0})
        w.update(o["weights"])
        arms.append({"o": o, "model": model, "fwd": maybe_compile(model), "opt": opt, "ema": ema, "w": w, "lr": lr,
                     "sums": {}, "n": 0, "ema_p": list(ema.parameters()), "model_p": list(model.parameters())})
        print(f"arm {o['name']}: {o} lr {lr}", flush=True)
    print(f"{len(files)} files, {steps} steps of {args.batch} ({total} samples)", flush=True)

    held = []
    for batch in batches(library, list_files(args.held), 1024, part="all", symmetries=False, drop_last=False,
                         shuffle=False):
        held.append(batch)
        if sum(len(b["played"]) for b in held) >= args.held_samples:
            break

    def held_report(step):
        rec = {"step": step, "held": 1}
        with torch.no_grad():
            sums = {}
            n = 0
            for batch in held:
                b = to_torch(batch, device)
                own = dict(b)
                own["known"] = F.one_hot(b["played"].long(), NUM_ACTIONS).to(b["known"].dtype)
                size = len(batch["played"])
                models = [("teacher", teacher)] + [(a["o"]["name"], a["ema"]) for a in arms]
                tout = None
                for name, m in models:
                    with torch.autocast(device_type="cuda", dtype=torch.bfloat16, enabled=use_amp):
                        out = m(*m.inputs(b))
                    terms, metrics = loss_terms(out, own)
                    vals = {k: float(terms[k]) for k in ("events", "tai", "ready", "danger", "danger_tai",
                                                         "ready_within", "tai_items")}
                    vals["q_mse"] = float(metrics["q_mse"])
                    vals["fork_mse"] = fork_mse(out, b)
                    if name == "teacher":
                        tout = out
                    else:
                        vals["pi_kl"] = float(kl_to(tout["policy"], out["policy"], b["legal"] > 0))
                    for k, v in vals.items():
                        sums[(name, k)] = sums.get((name, k), 0.0) + v * size
                with torch.autocast(device_type="cuda", dtype=torch.bfloat16, enabled=use_amp):
                    cout = critic(*critic.inputs(b))
                cterms, cmet = loss_terms(cout, own)
                sums[("critic", "events")] = sums.get(("critic", "events"), 0.0) + float(cterms["events"]) * size
                sums[("critic", "q_mse")] = sums.get(("critic", "q_mse"), 0.0) + float(cmet["q_mse"]) * size
                n += size
        for (name, k), v in sums.items():
            rec[f"{name}.{k}"] = v / n
        critic.train()
        record(rec)

    held_report(args.start_step)
    stream = Stream(library, files, args.batch, seed=args.seed, part="train", group=args.group, passes=args.passes,
                    wall_k=args.wall_k)
    critic.train()
    for a in arms:
        a["model"].train()
    step = args.start_step
    start = time.time()
    csums, cn = {}, 0
    for batch in stream:
        if step >= steps:
            break
        step += 1
        scale = min(1.0, (step - args.start_step) / args.warmup) * 0.5 * (1 + math.cos(math.pi * min(1.0, step / steps)))
        b = to_torch(batch, device)
        b["events"] = b["events"].to(torch.int32)  # uint16 cannot be indexed on the GPU
        ex = exact_win_labels(b, cfg.go_actions, cfg.ga_size)
        # The critic.
        with torch.autocast(device_type="cuda", dtype=torch.bfloat16, enabled=use_amp), \
                torch.set_grad_enabled(c_opt is not None):
            c_out = c_fwd(*critic.inputs(b))
        if c_opt is not None:
            cb = with_exact_known(b, ex) if args.critic_exact else b
            c_terms, c_metrics = loss_terms(c_out, cb)
            c_loss = total_loss(c_terms, critic_weights)
            if args.critic_exact:
                c_loss = c_loss + exact_ron_tai_loss(c_out, b, ex)
            if torch.isfinite(c_loss):
                for g in c_opt.param_groups:
                    g["lr"] = args.critic_lr * scale
                c_opt.zero_grad(set_to_none=True)
                c_loss.backward()
                torch.nn.utils.clip_grad_norm_(critic.parameters(), 1.0)
                c_opt.step()
            csums["c_events"] = csums.get("c_events", 0.0) + c_terms["events"].detach().float()
            csums["c_q_mse"] = csums.get("c_q_mse", 0.0) + torch.as_tensor(c_metrics["q_mse"]).detach().float()
            cn += 1
        c_out = {k: v.detach() for k, v in c_out.items()}
        c_wall = critic_wall_mean(c_fwd, b, args.wall_k, use_amp) if args.wall_k else None
        with torch.no_grad(), torch.autocast(device_type="cuda", dtype=torch.bfloat16, enabled=use_amp):
            t_policy = t_fwd(*teacher.inputs(b))["policy"]
        own = dict(b)
        own["known"] = F.one_hot(b["played"].long(), NUM_ACTIONS).to(b["known"].dtype)
        legal = b["legal"] > 0
        for a in arms:
            m, o = a["model"], a["o"]
            c_t = c_wall if o["wall"] else c_out
            with torch.autocast(device_type="cuda", dtype=torch.bfloat16, enabled=use_amp):
                out = a["fwd"](*m.inputs(b))
            # With `forks` the actor learns a fork's second action too, and the difference between the two (adv).
            terms, _ = loss_terms(out, b if o["forks"] else own)
            terms.pop("policy")
            if not o["forks"]:
                terms.pop("adv")
            if o["exact_wins"]:
                terms.update(distill_with_exact(out, c_t, b, ex))
                terms["exact_ron_tai"] = exact_ron_tai_loss(out, b, ex)
            else:
                terms.update(distill_terms(out, c_t, b))
            terms["pi_kl"] = kl_to(t_policy, out["policy"], legal)
            if o["rank"] > 0:
                q_critic = compose_q(torch.sigmoid(c_t["outcomes"][..., :NUM_EVENTS].float()), c_t["tai"].float(),
                                     b["coef"].float())
                terms["rank"] = rank_loss(out, q_critic, t_policy, b)
                a["w"]["rank"] = o["rank"]
            loss = total_loss(terms, a["w"])
            if o["big"] > 0:
                # The samples whose own hand can be won for many tai, as the inputs state it (a weight by the inputs
                # alone leaves the best prediction of every input where it is), count `big` times more in the outcome
                # heads' losses.
                st = teacher.stated_tai(b["kind"].float(), b["global"].float()).amax(dim=(1, 2))
                m_big = st >= o["big_min"]
                if bool(m_big.any()):
                    sub = lambda d: {k: v[m_big] for k, v in d.items() if torch.is_tensor(v) and v.shape[:1] == m_big.shape}
                    so, sb, sc = sub(out), sub(b if o["forks"] else own), sub(c_t)
                    bt, _ = loss_terms(so, sb)
                    bd = distill_terms(so, sc, sub(b))
                    frac = float(m_big.float().mean())
                    extra = (bt["events"] + bt["tai"] + bd["distill_events"] + bd["distill_tai"]) * o["big"] * frac
                    loss = loss + extra
                    terms["big_n"] = torch.tensor(float(m_big.sum()))
            if not torch.isfinite(loss):
                continue
            for g in a["opt"].param_groups:
                g["lr"] = a["lr"] * scale
            a["opt"].zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(m.parameters(), 1.0)
            a["opt"].step()
            with torch.no_grad():
                torch._foreach_lerp_(a["ema_p"], a["model_p"], 1 - args.ema)
            for k in ("events", "tai", "distill_events", "distill_tai", "pi_kl", "danger", "ready", "big_n", "rank"):
                if k in terms:  # summed on the GPU: a float() each step would wait for it
                    a["sums"][k] = a["sums"].get(k, 0.0) + torch.as_tensor(terms[k]).detach().float()
            a["n"] += 1
        if step % 500 == 0:
            rec = {"step": step, "samples_per_s": (step - args.start_step) * args.batch / (time.time() - start),
                   "lr_scale": scale}
            if cn:
                rec.update({k: float(v) / cn for k, v in csums.items()})
            for a in arms:
                rec.update({f"{a['o']['name']}.{k}": float(v) / max(1, a["n"]) for k, v in a["sums"].items()})
                a["sums"], a["n"] = {}, 0
            csums, cn = {}, 0
            record(rec)
        if step % args.report_every == 0:
            held_report(step)
        if args.save_every and step % args.save_every == 0:
            for a in arms:
                save_checkpoint(os.path.join(args.out, f"actor_{a['o']['name']}_{step}.pt"), a["ema"], None,
                                {"stage": 1, "arm": a["o"], "step": step})
    stream.close()
    held_report(step)
    for a in arms:
        name = a["o"]["name"]
        save_checkpoint(os.path.join(args.out, f"actor_{name}.pt"), a["model"], a["opt"],
                        {"ema": a["ema"].state_dict(), "stage": 1, "arm": a["o"]})
        export_onnx(a["ema"], os.path.join(args.out, f"actor_{name}.onnx"))
    if c_opt is not None:
        save_checkpoint(os.path.join(args.out, "critic.pt"), critic, c_opt, {"stage": 1})
    print(f"Saved to {args.out} after {step} steps, {time.time() - start:.0f} s", flush=True)


if __name__ == "__main__":
    main()
