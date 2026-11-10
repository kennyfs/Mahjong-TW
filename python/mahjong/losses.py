"""The losses of the heads (docs/plan.md 3.3), from a batch of the data library and a network's outputs.

Every loss is a mean over the batch; per-kind losses are summed over the 34 kinds. `loss_terms` returns them
unweighted, by name; WEIGHTS gives each group's weight (the training-only heads are set so that each group's
gradient at the trunk is some 10 to 40% of the main heads': python/train0.py --grad-report measures it).
"""

import torch
import torch.nn.functional as F

from .model import (EVENT_BITS, EVENT_WINNERS, NUM_EVENTS, NUM_OUTCOMES, NUM_TAI_ITEMS, OTHER_BITS, TAI_CLASSES,
                    TAI_SCALE, DRAWS_SCALE, compose_q)

# The heads that ship.
MAIN = ("events", "tai", "adv", "policy")
OPPONENTS = ("ready", "danger", "danger_tai")
# Training only, by the groups of the plan's table.
AUX_GROUPS = {
    "unseen": ("opp_hand",),
    "win_tile": ("win_tile",),
    "tai_items": ("tai_items", "my_tai"),
    "road": ("ready_within", "draws_left", "end_shanten"),
    "final_hand": ("final_hand",),
    "others_next": ("next_discard", "chow"),
    "others_result": ("others",),
}
WEIGHTS = {
    "events": 1.0, "tai": 1.0, "adv": 0.5, "policy": 1.0,
    "ready": 0.5, "danger": 1.0, "danger_tai": 0.5,
    "opp_hand": 0.1, "win_tile": 0.2, "tai_items": 0.2, "my_tai": 0.1, "ready_within": 0.2, "draws_left": 0.2,
    "end_shanten": 0.1, "final_hand": 0.05, "next_discard": 0.1, "chow": 0.03, "others": 0.5,
}
# What the critic is given as input, so it has nothing to learn from.
IN_FULL_STATE = ("ready", "danger", "danger_tai", "opp_hand", "chow")


def weights_for(full=False, aux=1.0, overrides=None):
    """The loss weights; `aux` scales the training-only heads' (0 or False: without them)."""
    w = dict(WEIGHTS)
    for names in AUX_GROUPS.values():
        for n in names:
            w[n] *= float(aux)
    if full:
        for n in IN_FULL_STATE:
            w[n] = 0.0
    if overrides:
        w.update(overrides)
    return w


def outcome_labels(batch):
    """-> (the 17 outcome events of every action [B, A, 17], the tai of my ten events' winners [B, A, 10])."""
    events = batch["events"].long()
    bits = torch.tensor(EVENT_BITS + OTHER_BITS, device=events.device)
    y = ((events.unsqueeze(-1) >> bits) & 1).float()
    draw = (events == 0).float().unsqueeze(-1)
    winners = torch.tensor(EVENT_WINNERS, device=events.device)
    return torch.cat([y, draw], dim=-1), batch["tai"].float()[:, :, winners]


def payment_unit(batch):
    """A typical payment under the sample's rules, to put results of different rules on one scale: [B]."""
    return batch["coef"][:, 0, 1:4].mean(dim=-1) + 2.0


def masked_mean(x, mask):
    """The mean of x over the actions where mask is 1, per sample."""
    return (x * mask).sum(dim=-1) / mask.sum(dim=-1).clamp(min=1.0)


def loss_terms(out, batch, policy_temp=0.03, full=False):
    """-> (losses by name, metrics by name), all scalars."""
    f = {k: v.float() for k, v in out.items()}
    known = batch["known"].float()
    legal = batch["legal"] > 0
    y, tai_label = outcome_labels(batch)
    coef = batch["coef"]
    unit = payment_unit(batch)
    terms, metrics = {}, {}

    # ----- Per action: the events, the winners' tai, and the composed result -----
    bce = F.binary_cross_entropy_with_logits(f["outcomes"], y, reduction="none")
    terms["events"] = masked_mean(bce[..., :NUM_EVENTS].sum(-1), known).mean()
    terms["others"] = masked_mean(bce[..., NUM_EVENTS:].sum(-1), known).mean()
    happened = y[..., :NUM_EVENTS]
    tai_err = ((f["tai"] - tai_label) / TAI_SCALE) ** 2 * happened
    terms["tai"] = masked_mean(tai_err.sum(-1), known).mean()
    q_label = compose_q(happened, tai_label, coef)
    q = compose_q(torch.sigmoid(f["outcomes"][..., :NUM_EVENTS]), f["tai"], coef)
    # What an action changes: results against the decision's mean over the actions known. Within a decision the
    # actions were finished on the same wall, so these differences carry far less luck than the results themselves.
    centered = (q - masked_mean(q, known).unsqueeze(-1)) - (q_label - masked_mean(q_label, known).unsqueeze(-1))
    adv = F.huber_loss(centered / unit.unsqueeze(-1), torch.zeros_like(centered), reduction="none", delta=4.0)
    terms["adv"] = masked_mean(adv, known).mean()

    # ----- The policy: a softmax of the composed results (stage 0) -----
    neg = torch.finfo(torch.float32).min
    logp = F.log_softmax(f["policy"].masked_fill(~legal, neg), dim=-1)
    with torch.no_grad():
        target = F.softmax((q / (unit.unsqueeze(-1) * policy_temp)).masked_fill(~legal, neg), dim=-1)
    terms["policy"] = -(target * logp).sum(-1).mean()

    # ----- Per opponent -----
    pair, seats, me = f["pair"], f["seats"], f["me"]
    danger = batch["danger"].float()
    terms["ready"] = F.cross_entropy(seats[:, 1:, :4].reshape(-1, 4), batch["opp_shanten"].long().reshape(-1))
    terms["danger"] = F.binary_cross_entropy_with_logits(pair[:, 1:, :, 0], danger, reduction="none").sum(-1).mean()
    dt = (pair[:, 1:, :, 1] - batch["danger_tai"].float() / TAI_SCALE) ** 2 * danger
    terms["danger_tai"] = dt.sum(-1).mean()

    # ----- Training only -----
    hand = batch["opp_hand"].long().clamp(max=4)
    terms["opp_hand"] = F.cross_entropy(pair[:, 1:, :, 2:7].reshape(-1, 5), hand.reshape(-1)) * 34
    final = batch["final_hand"].long().clamp(max=4)
    terms["final_hand"] = F.cross_entropy(pair[:, 0, :, 2:7].reshape(-1, 5), final.reshape(-1)) * 34
    nd = batch["next_discard"].long()
    terms["next_discard"] = F.cross_entropy(pair[:, 1:, :, 7].reshape(-1, 34), nd.reshape(-1), ignore_index=-1)
    wt = batch["win_tile"].long()
    wt = torch.where(wt < 0, torch.full_like(wt, 34), wt)
    wt_logits = torch.cat([pair[:, :, :, 8], seats[:, :, 9:10]], dim=-1)
    terms["win_tile"] = F.cross_entropy(wt_logits.reshape(-1, 35), wt.reshape(-1))
    future = batch["future"]
    terms["end_shanten"] = F.cross_entropy(seats[:, :, 4:9].reshape(-1, 5), future[:, 7:11].long().reshape(-1))
    terms["chow"] = F.binary_cross_entropy_with_logits(f["chow"], batch["next_chow"].float(), reduction="none").sum(-1).mean()
    terms["tai_items"] = F.binary_cross_entropy_with_logits(
        me[:, :NUM_TAI_ITEMS], batch["tai_items"].float(), reduction="none").sum(-1).mean()
    won = future[:, 0].float()
    my_tai = F.cross_entropy(me[:, NUM_TAI_ITEMS:NUM_TAI_ITEMS + TAI_CLASSES],
                             future[:, 1].long().clamp(max=TAI_CLASSES - 1), reduction="none")
    terms["my_tai"] = (my_tai * won).mean()
    o = NUM_TAI_ITEMS + TAI_CLASSES
    terms["ready_within"] = F.binary_cross_entropy_with_logits(
        me[:, o:o + 4], future[:, 2:6].float(), reduction="none").sum(-1).mean()
    terms["draws_left"] = F.huber_loss(me[:, o + 4], future[:, 6].float() / DRAWS_SCALE)

    # ----- What the composed results are worth -----
    with torch.no_grad():
        metrics["q_mse"] = masked_mean(((q - q_label) / unit.unsqueeze(-1)) ** 2, known).mean()
        played = batch["played"].long().unsqueeze(-1)
        ql_played = q_label.gather(1, played).squeeze(-1)
        all_known = (known >= batch["legal"].float()).all(dim=-1) & (legal.sum(-1) > 1)
        if all_known.any():
            # Every action's hand was finished on the same wall (stage 0): the result of the network's choice,
            # against the action played, in points. Averaged over hands this is what its choices gain per decision.
            best_q = q.masked_fill(~legal, neg).argmax(-1, keepdim=True)
            best_pi = f["policy"].masked_fill(~legal, neg).argmax(-1, keepdim=True)
            w = all_known.float()
            n = w.sum()
            metrics["gain_q"] = ((q_label.gather(1, best_q).squeeze(-1) - ql_played) * w).sum() / n
            metrics["gain_pi"] = ((q_label.gather(1, best_pi).squeeze(-1) - ql_played) * w).sum() / n
            metrics["gain_best"] = ((q_label.masked_fill(~legal, neg).max(-1).values - ql_played) * w).sum() / n
            metrics["agree"] = ((best_q == best_pi).squeeze(-1).float() * w).sum() / n
            metrics["same_as_played"] = ((best_q == played).squeeze(-1).float() * w).sum() / n
            # The same against what the plain greedy player would play here.
            greedy = batch["greedy"].long().unsqueeze(-1)
            ql_greedy = q_label.gather(1, greedy).squeeze(-1)
            metrics["q_vs_greedy"] = ((q_label.gather(1, best_q).squeeze(-1) - ql_greedy) * w).sum() / n
            metrics["pi_vs_greedy"] = ((q_label.gather(1, best_pi).squeeze(-1) - ql_greedy) * w).sum() / n
            metrics["q_is_greedy"] = ((best_q == greedy).squeeze(-1).float() * w).sum() / n
            metrics["pi_is_greedy"] = ((best_pi == greedy).squeeze(-1).float() * w).sum() / n
        metrics["entropy"] = -(logp.exp() * logp.masked_fill(~legal, 0.0)).sum(-1).mean()
        metrics["target_entropy"] = -(target * torch.log(target.clamp(min=1e-12))).sum(-1).mean()
    return terms, metrics


def distill_terms(out, teacher, batch):
    """The actor's per-action heads learn the critic's, over every legal action (stage 1). -> losses by name."""
    legal = batch["legal"].float()
    target = torch.sigmoid(teacher["outcomes"].float()).detach()
    bce = F.binary_cross_entropy_with_logits(out["outcomes"].float(), target, reduction="none")
    # Less the targets' own entropy, so that the loss is zero where the two agree.
    floor = F.binary_cross_entropy(target, target, reduction="none")
    diff = bce - floor
    tai = ((out["tai"].float() - teacher["tai"].float().detach()) / TAI_SCALE) ** 2 * target[..., :NUM_EVENTS]
    return {
        "distill_events": masked_mean(diff[..., :NUM_EVENTS].sum(-1), legal).mean(),
        "distill_others": masked_mean(diff[..., NUM_EVENTS:].sum(-1), legal).mean(),
        "distill_tai": masked_mean(tai.sum(-1), legal).mean(),
    }


def policy_terms(policy_logits, batch, q_critic):
    """The policy's step (docs/plan.md 3.4, stage 1) from the critic's results of every action in the true state.

    pg: minus sum_a pi(a|o) A(s, a), with A(s, a) = Q(s, a) - sum_b pi(b|o) Q(s, b) held fixed: its gradient is the
    policy gradient summed over the actions. corr: the correction by what really happened after the action played,
    rho log pi(a_t|o) (G - Q(s, a_t)). kl: KL(mu || pi) to the policy that played. entropy: of pi. Results are in
    typical payments of the hand's rules.
    """
    legal = batch["legal"] > 0
    neg = torch.finfo(torch.float32).min
    logp = F.log_softmax(policy_logits.float().masked_fill(~legal, neg), dim=-1)
    logp0 = logp.masked_fill(~legal, 0.0)
    p = logp.exp()
    unit = payment_unit(batch).unsqueeze(-1)
    q = (q_critic.float().detach() / unit).masked_fill(~legal, 0.0)
    value = (p.detach() * q).sum(-1, keepdim=True)
    adv = (q - value).masked_fill(~legal, 0.0)
    played = batch["played"].long().unsqueeze(-1)
    mu = batch["mu"].float()
    mu_t = mu.gather(1, played).squeeze(-1).clamp(min=1e-4)
    rho = (p.detach().gather(1, played).squeeze(-1) / mu_t).clamp(max=1.0)
    resid = batch["info"][:, 2] / unit.squeeze(-1) - q.gather(1, played).squeeze(-1)
    terms = {
        "pg": -(p * adv).sum(-1).mean(),
        "corr": -(rho * logp.gather(1, played).squeeze(-1) * resid).mean(),
        "kl": (mu * (torch.log(mu.clamp(min=1e-6)) - logp0)).sum(-1).mean(),
        "entropy": -(p * logp0).sum(-1).mean(),
    }
    with torch.no_grad():
        metrics = {
            # What the critic says the new policy gains over the one that played, and what is still left to the
            # best action, per decision.
            "improve": ((p - mu) * q).sum(-1).mean(),
            "left": (q.masked_fill(~legal, neg).max(-1).values - value.squeeze(-1)).mean(),
            "resid_sq": (resid ** 2).mean(),
            "rho": rho.mean(),
        }
    return terms, metrics


def total_loss(terms, weights):
    return sum(weights[name] * value for name, value in terms.items() if weights.get(name, 0.0) != 0.0)
