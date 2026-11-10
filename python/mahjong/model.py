"""The networks (docs/plan.md 3.3): the actor that ships, and the critic that sees the full state.

Inputs, as the C++ engine writes them (cpp/learn/features.h):

    kind    float32 [B, 34, KIND_OBS]   one token per tile kind
    seat    float32 [B, 4, SEAT_OBS]    one token per seat: the deciding seat, then the seats after it
    global  float32 [B, GLOBAL_OBS]     the rules, the hand's setup, the special actions on offer

The critic (config.full) gets the full state's features appended to each of the three.

The 38 tokens go through a small Transformer. Nothing in the inputs names a suit or a wind, and the network adds
only a kind's type (number tile of some rank, wind, dragon) and an attention bias by how two tokens relate (same
suit and the distance in rank, and so on), so exchanging suits changes nothing.

Heads. Per action (76: discard and kong per kind, 8 special ones), from the kind's token or from the deciding seat's
context: a policy logit; the logits of 17 outcome events (the ten of my own result in the order of
cpp/learn/outcome.h, the six among the other seats, the exhaustive draw); and for each of my ten events the tai of
its winner, given that it happens. The expected result of an action is composed from these exactly by the rules'
coefficients (compose_q, the same as Learn::eventCoefficients). Per opponent: how far from ready, and per kind the
chance that it wins on a discard of it now, and with how many tai. Everything else is for training only and is cut
at export.
"""

import math
import os
from dataclasses import asdict, dataclass

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

FEATURES_VERSION = 1
NUM_KINDS = 34
NUM_SEATS = 4
NUM_TOKENS = NUM_KINDS + NUM_SEATS
NUM_ACTIONS = 76
A_KONG = 34
A_SPECIAL = 68
NUM_SPECIAL = 8
SPECIAL_NAMES = ("tsumo", "ron", "pass", "pon", "minkan", "chi_low", "chi_mid", "chi_high")
# Outcome events (cpp/learn/outcome.h): bit 4 w + p of a sample's events is "the seat w places after me wins from
# the seat p places after me" (p == w: by self-draw).
NUM_EVENTS = 10
EVENT_BITS = (0, 1, 2, 3, 4, 8, 12, 5, 10, 15)
EVENT_WINNERS = (0, 0, 0, 0, 1, 2, 3, 1, 2, 3)
EVENT_NAMES = ("tsumo", "ron_1", "ron_2", "ron_3", "deal_in_1", "deal_in_2", "deal_in_3", "opp_tsumo_1",
               "opp_tsumo_2", "opp_tsumo_3")
OTHER_BITS = (6, 7, 9, 11, 13, 14)
NUM_OUTCOMES = NUM_EVENTS + len(OTHER_BITS) + 1  # and the exhaustive draw
ACTION_OUT = 1 + NUM_OUTCOMES + NUM_EVENTS
TAI_SCALE = 8.0
# Where the kind features say "the tile just drawn" and "the tile on offer" (Features::KO_DRAWN, KO_CLAIM).
KO_DRAWN = 4
KO_CLAIM = 5
# Training-only heads.
PAIR_OUT = 9          # per (seat, kind): danger, its tai, copies held (5 classes), next discard, winning tile
SEAT_OUT = 10         # per seat: from ready (4 classes), shanten at the end (5 classes), no winning tile
NUM_TAI_ITEMS = 38
TAI_CLASSES = 33      # my win's tai, 0 to 32 or more
ME_OUT = NUM_TAI_ITEMS + TAI_CLASSES + 4 + 1  # items, tai, ready within 1, 2, 4, 8 draws, draws left
DRAWS_SCALE = 40.0
NUM_RELATIONS = 48


@dataclass
class ModelConfig:
    full: bool = False        # the critic: the full state is part of the inputs
    arch: str = "transformer"
    d_model: int = 128
    num_layers: int = 5
    num_heads: int = 4
    ff: int = 384
    head: int = 128           # width of the heads' hidden layers
    pair: int = 64
    skip: bool = False        # the kinds' input features go to the action heads directly as well
    # The sizes of the inputs, from the data library (Features::VERSION and its sizes).
    features: int = FEATURES_VERSION
    kind_obs: int = 93
    seat_obs: int = 26
    global_obs: int = 158
    kind_full: int = 34
    seat_full: int = 15
    global_full: int = 1
    go_actions: int = 21
    ga_size: int = 11

    def to_dict(self):
        return asdict(self)

    @staticmethod
    def for_library(sizes, **kwargs):
        if sizes["version"] != FEATURES_VERSION:
            raise ValueError(f"The data library has features version {sizes['version']}, the model {FEATURES_VERSION}")
        keys = ("kind_obs", "seat_obs", "global_obs", "kind_full", "seat_full", "global_full", "go_actions", "ga_size")
        return ModelConfig(**{k: sizes[k] for k in keys}, **kwargs)


def kind_types():
    """Per kind: 0 to 8 a number tile's rank, 9 a wind, 10 a dragon."""
    return [k % 9 if k < 27 else (9 if k < 31 else 10) for k in range(NUM_KINDS)]


def relations():
    """How token j relates to token i, as an index [38, 38] into the attention biases."""
    rel = np.zeros((NUM_TOKENS, NUM_TOKENS), np.int64)
    types = kind_types()
    for i in range(NUM_KINDS):
        for j in range(NUM_KINDS):
            ni, nj = types[i] < 9, types[j] < 9
            if ni and nj:
                if i // 9 == j // 9:
                    rel[i, j] = types[j] - types[i] + 8                 # 0..16: same suit, by the distance in rank
                else:
                    rel[i, j] = 17 if types[i] == types[j] else 18      # other suits: the same rank, or not
            elif ni or nj:
                rel[i, j] = 19
            elif i == j:
                rel[i, j] = 20
            elif types[i] == types[j]:
                rel[i, j] = 21 if types[i] == 9 else 22
            else:
                rel[i, j] = 23
        for r in range(NUM_SEATS):
            rel[i, NUM_KINDS + r] = 24 + r
            rel[NUM_KINDS + r, i] = 28 + r
    for a in range(NUM_SEATS):
        for b in range(NUM_SEATS):
            rel[NUM_KINDS + a, NUM_KINDS + b] = 32 + 4 * a + b
    return rel


class Block(nn.Module):
    """Pre-norm Transformer block with an additive attention bias, in basic ops so that it exports cleanly."""

    def __init__(self, d, heads, ff):
        super().__init__()
        self.heads = heads
        self.norm1 = nn.LayerNorm(d)
        self.qkv = nn.Linear(d, 3 * d)
        self.proj = nn.Linear(d, d)
        self.norm2 = nn.LayerNorm(d)
        self.ff1 = nn.Linear(d, ff)
        self.ff2 = nn.Linear(ff, d)

    def forward(self, x, bias):
        b, t, d = x.shape
        dh = d // self.heads
        qkv = self.qkv(self.norm1(x)).reshape(b, t, 3, self.heads, dh).permute(2, 0, 3, 1, 4)
        q, k, v = qkv[0], qkv[1], qkv[2]
        att = torch.softmax(torch.matmul(q, k.transpose(-1, -2)) * (1.0 / math.sqrt(dh)) + bias, dim=-1)
        out = torch.matmul(att, v).permute(0, 2, 1, 3).reshape(b, t, d)
        x = x + self.proj(out)
        x = x + self.ff2(F.gelu(self.ff1(self.norm2(x))))
        return x


class MahjongNet(nn.Module):
    def __init__(self, config: ModelConfig):
        super().__init__()
        if config.arch != "transformer":
            raise ValueError(f"Unknown architecture {config.arch}")
        self.config = config
        c = config
        d, hd = c.d_model, c.head
        full = c.full
        self.kind_in = nn.Linear(c.kind_obs + (c.kind_full if full else 0), d)
        self.seat_in = nn.Linear(c.seat_obs + (c.seat_full if full else 0), d)
        self.global_in = nn.Sequential(
            nn.Linear(c.global_obs + (c.global_full if full else 0), d), nn.GELU(), nn.Linear(d, 2 * d))
        self.type_emb = nn.Parameter(torch.randn(11, d) * 0.02)
        self.register_buffer("types", torch.tensor(kind_types()), persistent=False)
        self.register_buffer("rel", torch.from_numpy(relations()), persistent=False)
        self.att_bias = nn.Parameter(torch.zeros(c.num_layers, c.num_heads, NUM_RELATIONS))
        self.blocks = nn.ModuleList([Block(d, c.num_heads, c.ff) for _ in range(c.num_layers)])
        self.norm = nn.LayerNorm(d)
        # Actions.
        self.context = nn.Linear(4 * d, hd)
        self.kind_hidden = nn.Linear(d, hd)
        self.kind_skip = nn.Linear(c.kind_obs + (c.kind_full if full else 0), hd, bias=False) if c.skip else None
        self.kind_out = nn.Linear(hd, 2 * ACTION_OUT)
        self.special_in = nn.Linear(c.ga_size, hd)
        self.special_emb = nn.Parameter(torch.randn(NUM_SPECIAL, hd) * 0.02)
        self.special_out = nn.Linear(hd, ACTION_OUT)
        # Per (seat, kind), per seat, per kind and for the deciding seat.
        self.pair_kind = nn.Linear(d, c.pair)
        self.pair_seat = nn.Linear(d, c.pair)
        self.pair_out = nn.Linear(c.pair, PAIR_OUT)
        self.seat_out = nn.Linear(d, SEAT_OUT)
        self.chow_out = nn.Linear(d, 1)
        self.me_out = nn.Linear(hd, ME_OUT)

    def num_parameters(self):
        return sum(p.numel() for p in self.parameters())

    def trunk(self, kind, seat, glob):
        """-> the tokens after the last block [B, 38, d]."""
        g = self.global_in(glob)
        d = self.config.d_model
        xk = self.kind_in(kind) + self.type_emb[self.types] + g[:, None, :d]
        xs = self.seat_in(seat) + g[:, None, d:]
        x = torch.cat([xk, xs], dim=1)
        for i, block in enumerate(self.blocks):
            x = block(x, self.att_bias[i][:, self.rel])
        return self.norm(x)

    def action_heads(self, h, kind, glob):
        """-> (per action [B, 76, ACTION_OUT], the deciding seat's context [B, head])."""
        c = self.config
        b = h.shape[0]
        hk, me = h[:, :NUM_KINDS], h[:, NUM_KINDS]
        claim = (kind[:, :, KO_CLAIM:KO_CLAIM + 1] * hk).sum(dim=1)
        drawn = (kind[:, :, KO_DRAWN:KO_DRAWN + 1] * hk).sum(dim=1)
        ctx = self.context(torch.cat([me, claim, drawn, hk.mean(dim=1)], dim=-1))
        zk = self.kind_hidden(hk) + ctx[:, None]
        if self.kind_skip is not None:
            zk = zk + self.kind_skip(kind)
        zk = F.gelu(zk)
        ok = self.kind_out(zk).reshape(b, NUM_KINDS, 2, ACTION_OUT)
        blocks = glob[:, c.go_actions:c.go_actions + NUM_SPECIAL * c.ga_size].reshape(b, NUM_SPECIAL, c.ga_size)
        zs = F.gelu(self.special_in(blocks) + self.special_emb + ctx[:, None])
        out = torch.cat([ok[:, :, 0], ok[:, :, 1], self.special_out(zs)], dim=1)
        return out, F.gelu(ctx)

    def pair_heads(self, hk, hs):
        """Kind tokens [B, 34, d] and some seats' tokens [B, S, d] -> [B, S, 34, PAIR_OUT]."""
        z = F.gelu(self.pair_kind(hk)[:, None] + self.pair_seat(hs)[:, :, None])
        return self.pair_out(z)

    def inputs(self, batch):
        """The three input tensors from a batch of the data library."""
        if not self.config.full:
            return batch["kind"], batch["seat"], batch["global"]
        return (torch.cat([batch["kind"], batch["kind_full"]], dim=-1),
                torch.cat([batch["seat"], batch["seat_full"]], dim=-1),
                torch.cat([batch["global"], batch["global_full"]], dim=-1))

    def forward(self, kind, seat, glob):
        """Everything, as a dict (the trainers' form).

        policy [B, 76] logits; outcomes [B, 76, 17] logits; tai [B, 76, 10]; pair [B, 4, 34, PAIR_OUT];
        seats [B, 4, SEAT_OUT]; chow [B, 34] logits; me [B, ME_OUT]; trunk [B, 38, d].
        """
        h = self.trunk(kind, seat, glob)
        hk, hs = h[:, :NUM_KINDS], h[:, NUM_KINDS:]
        act, ctx = self.action_heads(h, kind, glob)
        return {
            "policy": act[..., 0],
            "outcomes": act[..., 1:1 + NUM_OUTCOMES],
            "tai": act[..., 1 + NUM_OUTCOMES:] * TAI_SCALE,
            "pair": self.pair_heads(hk, hs),
            "seats": self.seat_out(hs),
            "chow": self.chow_out(hk).squeeze(-1),
            "me": self.me_out(ctx),
            "trunk": h,
        }


def compose_q(probs, tai, coef):
    """The expected result per action: probs, tai [B, A, 10] (tai given the event), coef [B, 2, 10] -> [B, A]."""
    return (probs * (coef[:, None, 0] + tai * coef[:, None, 1])).sum(dim=-1)


class Shipped(nn.Module):
    """What the engine runs: only the heads that ship, as probabilities."""

    def __init__(self, model):
        super().__init__()
        self.m = model

    def forward(self, kind, seat, glob):
        m = self.m
        h = m.trunk(kind, seat, glob)
        hk, hs = h[:, :NUM_KINDS], h[:, NUM_KINDS:]
        act, _ = m.action_heads(h, kind, glob)
        policy = act[..., 0]
        events = torch.sigmoid(act[..., 1:1 + NUM_EVENTS])
        tai = act[..., 1 + NUM_OUTCOMES:] * TAI_SCALE
        pair = m.pair_heads(hk, hs[:, 1:])
        ready = torch.softmax(m.seat_out(hs[:, 1:])[..., :4], dim=-1)
        return policy, events, tai, ready, torch.sigmoid(pair[..., 0]), pair[..., 1] * TAI_SCALE


OUTPUT_NAMES = ("policy", "events", "tai", "ready", "danger", "danger_tai")


def export_onnx(model, path, opset=17):
    """Exports the actor for cpp/players (inputs kind, seat, global; outputs OUTPUT_NAMES), with a dynamic batch."""
    import onnx

    if model.config.full:
        raise ValueError("The critic is not exported: only the actor plays")
    c = model.config
    wrapper = Shipped(model).eval().to("cpu")
    b = 2
    args = (torch.zeros(b, NUM_KINDS, c.kind_obs), torch.zeros(b, NUM_SEATS, c.seat_obs),
            torch.zeros(b, c.global_obs))
    names = ["kind", "seat", "global"]
    tmp = path + ".tmp"
    torch.onnx.export(
        wrapper, args, tmp, input_names=names, output_names=list(OUTPUT_NAMES),
        dynamic_axes={n: {0: "batch"} for n in names + list(OUTPUT_NAMES)}, opset_version=opset, dynamo=False)
    proto = onnx.load(tmp)
    for key, value in (("features", str(c.features)), ("d_model", str(c.d_model)), ("num_layers", str(c.num_layers))):
        entry = proto.metadata_props.add()
        entry.key, entry.value = key, value
    onnx.save(proto, tmp)
    os.replace(tmp, path)


def check_onnx(model, path, batch):
    """The largest difference between the exported file (ONNX Runtime, CPU) and the model, on a batch's inputs.

    None where ONNX Runtime is not installed (the training machine does not need it).
    """
    try:
        import onnxruntime as ort
    except ImportError:
        return None

    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    session = ort.InferenceSession(path, options, providers=["CPUExecutionProvider"])
    x = [batch[k].detach().to("cpu", torch.float32) for k in ("kind", "seat", "global")]
    with torch.no_grad():
        want = Shipped(model).eval().to("cpu")(*x)
    got = session.run(None, {"kind": x[0].numpy(), "seat": x[1].numpy(), "global": x[2].numpy()})
    return max(float(np.abs(g - w.numpy()).max()) for g, w in zip(got, want))


def save_checkpoint(path, model, optimizer=None, extra=None):
    state = {"config": model.config.to_dict(), "model": model.state_dict()}
    if optimizer is not None:
        state["optimizer"] = optimizer.state_dict()
    if extra:
        state.update(extra)
    tmp = path + ".tmp"
    torch.save(state, tmp)
    os.replace(tmp, path)


def load_checkpoint(path, device="cpu"):
    state = torch.load(path, map_location=device, weights_only=False)
    model = MahjongNet(ModelConfig(**state["config"])).to(device)
    model.load_state_dict(state["model"])
    return model, state
