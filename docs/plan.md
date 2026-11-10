# Plan

How to build a strong, light Taiwanese mahjong player: what exists, the design of the network and of its training,
and the order of work. The reference material (papers, other projects, rule pages) is described in
`reference/README.md` (not committed).

**The goal.** One network of about 1M parameters (4 MB as FP32 ONNX, 2 MB with FP16 weights) that plays without
search on a phone's or a browser's CPU, under any rules of docs/rules.md, for the best expected result of each hand.
It ships inside the app and the web page and then stays as it is. So what counts is the final strength (clearly
above strong human play at least, as near to perfect play as it gets) reached with the least time and compute;
matching published programs or their budgets is not a goal.

Sections 3 and 4 were rewritten on 2026-11-10 (the review of the first plan); section 5 lists what changed and why.

## 1. What makes this game different

- **A long sequence of decisions with hidden information.** A hand between greedy players has 51 decisions (38
  discards, 12 answers to a discard, a kong or a self-draw now and then), with 11.7 legal actions on one's own turn
  and 2.2 on a discard; careful players make hands longer. The wall's order and the others' hands are hidden. Unlike
  Thirteen there is no small set of candidates to score exactly against the opponents.
- **Each hand is settled on its own** (base + tai), with no placement bonus, so the objective is the expected result
  of each hand (in tai). Japanese mahjong AIs need a model of the final placement (Suphx's global reward prediction,
  Mortal's GRP); this game does not. The dealer's streak links hands only weakly.
- **No furiten and no riichi.** A player may win on a kind they discarded, so "safe" tiles are only less likely, and
  nobody declares being ready: defense rests on reading the others. The one certainty is short-lived: a kind
  discarded since a player's last discard cannot be won by that player now (missed win, rules 5.4).
- **Luck is most of a hand's result.** Between greedy players the result of a hand has a standard deviation of 2.9 to
  4.6 points (3.3 under `simple1,none`, 4.6 under `simple2,see`), nearly all of it the wall, while the choices worth
  learning differ by hundredths to tenths of a point. Learning from the bare results of the actions played takes an
  enormous number of hands; the design below is about better signals.
- **The rules change the play.** Under `simple1` a self-draw brings 9 points (three players pay base 2 + 1) and a
  discard win 2, so even declining a win can be right; under `simple2` a concealed hand and a single wait pay. The
  rules are an input of the network, and nothing ("always win") is hard-coded.
- **No public game records** of Taiwanese mahjong, so learning is by self-play, started from a hand-written player.
- The published Taiwanese programs (Computer Olympiad: SIMCAT, VERYLONGCAT, MeowCaTS) search for seconds per move
  (flat Monte Carlo, expectimax, MCTS); none is a network small enough for a phone.
- **In self-play everything is known, and the simulator is fast.** The others' hands, their waits and the wall are
  there as exact labels and as the input of a critic used only in training; finishing a hand again after a different
  action takes about a millisecond with the greedy player. This is where cheap, exact learning signals come from.
- **Symmetries.** The three suits can be exchanged, each suit reversed (1 ↔ 9) and the dragons exchanged without
  changing the game (the winds too, together with their seat and round roles): thousands of equivalent forms of
  every position, free as augmentation.

## 2. What exists

- Rules, scoring and the play of a hand (docs/rules.md), with the flower settings and the tai values as parameters.
  The presets are confirmed: `simple1` (southern Taiwan: dealer, streak and self-draw only, base 2), `simple2` (the
  family's: also concealed, single wait, pure suit; several winners on a discard), `standard`, `masters`.
- `shape`: winning test, waits, all readings of a hand, and shanten for any number of melds (per-suit tables of
  partial sets, memoized per thread). Tests check shanten against its inductive definition on random hands.
- Players `random` and `greedy` (fewest tiles from ready, then the most improving tiles; calls only when they lower
  shanten; no defense, no tai awareness, the same play under any rules).
- `match` (duplicate walls, 4 rotations or 24 orders, multithreaded), `show`, `tai`, `benchmark`.
- `Position` (what one seat knows) and loading it back with the hidden tiles filled at random.
- Tile-efficiency analysis of every legal action, and the JSON engine of the interfaces (docs/gui-engine.md):
  stateless, a hand travels as its record (rules, setup, wall, decisions) and is replayed per request.
- A first version of the app, the web page and the battle server (docs/app.md, docs/battle.md), with `greedy` bots
  and the efficiency analysis. They wait for the network.

Speed on one thread (`mahjong benchmark`): shanten of a 16-tile hand 0.38 µs; a hand between four `random` players
0.09 ms, between four `greedy` players 1.8 ms (most of it the improving-tile counts). `match` with 32 threads plays
about 14,000 greedy hands per second.

Results between the baselines (duplicate walls × 4 rotations):

| Players | Rules | Result per hand | Win | Self-draw | Deal-in | Exhaustive draw |
|---|---|---|---|---|---|---|
| `greedy` (×2) vs `random` (×2) | `simple1,none` | greedy +1.47 ± 0.05 | 46% | 10% | 11% | 8% |
| `greedy` (×2) vs `random` (×2) | `simple2,see` | greedy +1.94 ± 0.06 | 47% | 11% | 11% | 7% |
| `greedy` ×4 | `simple1,none` | 0 by symmetry | 24.5% each | 5.7% | 18.8% | 2.0% |

Four greedy players almost always end the hand with a win: nobody folds.

**Measurements for the design** (2026-11-10; one thread of this machine, a Xeon 8352V; PyTorch standing in for
ONNX Runtime; logs in `mahjong-files/logs/bench_*.log`):

| Network | Tokens | Parameters | One evaluation (in batches of 64) |
|---|---:|---:|---:|
| Transformer, width 128, 5 layers | 38 | 1.02M | 0.78 ms |
| The same over an event sequence (every discard a token) | 110 | 1.02M | 2.5 ms |
| The same with tokens for the last 12 discards | 50 | 1.02M | 0.9 ms |
| Transformer, width 96, 4 layers | 38 | 0.47M | 0.36 ms |
| 1D residual network, 128 channels, 8 blocks | 34 | 0.85M | 0.45 ms |

So 32 threads evaluate some 25,000 to 40,000 decisions a second: a few hundred hands a second with the network in
all four seats. Training the same network on this machine's CPUs is slow (300 samples/s on one thread, 1,200 with
16), so training belongs on the GPU, where about 10,000 samples a second are expected for the actor alone (scaled
from Thirteen's 39,000 rows/s; to be measured). **Self-play here outruns training on one GPU several times over**,
the reverse of Thirteen: training steps are the scarce resource, and spare CPU time is best spent on better targets
(3.4).

## 3. The design

### 3.1 Principles

1. **Do not make the network learn what the engine computes exactly.** Shanten, improving tiles, waits and the tai of
   a hand under the hand's rules are combinatorics; they go in as input features, and a 1M network spends itself on
   judgment.
2. **Use every exact signal self-play offers.** The others' true hands and waits are labels for the heads that read
   opponents, and inputs of the critic. What happened is known for more than the action played (principle 3).
3. **Value every legal action at a decision, not only the one played.** A decision has about 12 actions; a learner
   that only sees the result of the one it tried needs 12 times the decisions and never looks at what it believes
   bad. Stage 0 gets all actions' outcomes exactly; stage 1 from a critic.
4. **Rules enter exactly where they can.** The network predicts events (who wins, on whose tile, with how many tai);
   the base and the dealer's tai turn them into points by the rules' own arithmetic, as Thirteen's `compose_q` does.
5. **Measure, then choose.** The points marked "measured" below are decided by small experiments on fixed data, not
   by taste; 3.5 says when to stop.

### 3.2 The engine for learning (C++)

1. **Observation** of the deciding seat, as 34 kind tokens and 4 seat tokens.
   - Per kind (about 120 numbers): copies in the own hand, the tile just drawn, the tile on offer; each seat's melds
     of it (a chow starting here, pung, kong by type) and discards of it (how many, from the hand or the draw, how
     early the first and how recent the last, taken by a call); copies not yet seen; safe now against each opponent
     (discarded since that opponent's last discard); its roles (round wind, each seat's wind).
   - Per kind, **computed by the engine**: as a discard, the shanten after it, the improving tiles left after it and,
     when it leaves the hand ready, the waits' tiles left and the hand's tai by discard and by self-draw under the
     hand's rules; as a draw, whether it improves or wins the hand. The same after each call on offer.
   - Per seat: melds and concealed kongs, tiles held, flowers and their tai, discards made, how many discards in a
     row came straight from the draw (a ready hand's tell), turns since the last call, seat wind, dealer. For the
     deciding seat also its shanten, whether it is barred from winning (missed win), and the kind of decision.
   - Global: every rule parameter (scaled), the dealer's tai this hand, round wind, streak, tiles left to draw.
2. **The full state** (training only): the same, plus what the seat cannot see: each opponent's hand per kind,
   shanten, exact waits with the tai of a win on each, missed-win states, the kinds of concealed kongs; each seat's
   next draws from the wall (if nobody calls); and, as an option, the result of the hand if everybody played `greedy`
   from here (one playout, about a millisecond), a strong hint of where the wall leads.
3. **Actions**: per kind, discard and kong (concealed or added: at most one applies to a kind); and win by
   self-draw, win on a discard (or a robbed kong), pass, pung, exposed kong and chow in its three positions. 76
   outputs, masked by legality. A decision with one legal action never reaches the network.
4. **Outcome events** of a hand for one seat: it wins by self-draw; it wins on seat j's discard; seat j wins on its
   discard; seat j wins by self-draw (ten events, not exclusive when several players win on a discard), with the tai
   of each winning hand. The seat's result follows exactly: every payment is the base, plus the winner's tai, plus
   the dealer's tai when the dealer is on either side (rules 7).
5. **Records.** A hand is its rules, setup, wall seed and decisions (the GUI record) with the probability each
   decision had under the playing policy: about 0.5 KB. The trainer keeps hands in this form and expands batches
   (features, legal actions, targets) through the C++ library, as Thirteen's `trainingrows` and
   `libthirteen_data.so` do.
6. **Branches.** From any decision of a record: play another action and finish the hand with given players, on the
   same wall and with the same random numbers for the players' choices, so that two lines differ only where the
   action mattered. Stage 0's targets, stage 1's forks and the diagnostics of 3.5 are all branches.
7. **Players**: `net:model=FILE` (ONNX Runtime as in Thirteen; self-play evaluates many concurrent hands in one batch
   per thread) and `mc` (flat Monte Carlo: hidden tiles filled at random, a greedy playout per action; the style of
   the Olympiad programs, as a yardstick only). The hand-written "better greedy" of the first plan is dropped: the
   stage 0 network is a better baseline for less work.

### 3.3 The networks

**The actor** (the one that ships, about 1M parameters). The 38 tokens go through a small Transformer (default:
width 128, 5 layers, attention biased by whether two kinds share a suit and by their distance in rank). Heads:

- **Policy**: one logit per legal action (discard and kong from the kind's token; the others from the token of the
  tile on offer and the seat's own token).
- **Per action, the outcome events**: the ten probabilities and the expected tai of the winner in each. With the
  hand's base and dealer's tai they give Q(o, a), the expected result of the action. These are also what the app's
  analysis shows: expected points, the chance to win, to deal in.
- **Per opponent**: how far from ready, and per kind the chance that the opponent wins on it now and with how many
  tai. Exact, dense labels (3 × 34 per decision) that teach the trunk to read hands, and the app's danger display.

**The critic** (training only, on the GPU, so it may be larger): the same shape over the full state. Per action the
same events, hence Q(s, a) in the true state, and the events of all four seats.

**Measured on stage 0's data** (the same hands and training budget for each candidate; compared by the held-out
losses of the event, wait and readiness heads, and by CPU cost):

- Transformer or 1D residual network over the kinds (0.45 against 0.78 ms).
- Whether tokens for the last discards in order add anything to the per-kind discard features (0.9 ms). A full
  event sequence (2.5 ms) only if that shows a large gain.
- Width and depth within the budget, and whether 0.5M or 2M parameters (2M still fits 4 MB with FP16 weights) change
  the losses enough to matter.

### 3.4 Training

**Stage 0: exact targets from the greedy player (supervised; hours).**

Hands between `greedy` players (with variations for diversity: seats that call more or less, a random discard now
and then) are branched at their decisions: every legal action is tried and the hand finished by greedy players on
the same wall. The players are deterministic, so each action's outcome is exact; the only noise left is which hidden
state it was. Expanding every decision of a hand costs about 0.4 s of one core, so 32 threads label some 4,000
decisions a second with all their actions: tens of millions in a few hours.

- The actor's per-action event heads learn these outcomes; their average over the hidden states is what each action
  is worth if everybody plays greedy afterwards. The opponent heads learn the true hands; the policy head a softmax
  of the composed Q.
- Playing the best Q is one step of improvement over greedy at every decision. It already avoids the discards that
  deal in on average, weighs calls, kongs and declined wins by their results, and sees the rules' payments. Cloning
  greedy's own choices, as first planned, could only equal greedy.
- A second round takes its positions from the new network's own play (targets still by greedy playouts), so that it
  is trained on the positions it reaches.
- The critic is pretrained on the same data, and the choices of 3.3 are made here.
- It exercises the whole pipeline (features, records, the data library, ONNX export, the `net` player, matches) with
  a plain supervised loss before any reinforcement learning.

Gate: the stage 0 network against `greedy` (one among three, and three against one) and against `mc`. It has to
beat `greedy` clearly in both forms; if it does not, something in the pipeline is wrong, and it is found here, not
inside the self-play loop.

**Stage 1: self-play with a full-information critic (days).**

Each iteration:

1. **Self-play** (this machine). Hands between the newest network, sampling from its policy; in a share of them one
   to three seats are earlier networks or `greedy`. After each hand a few **forks**: at some of its decisions (rare
   kinds of decision, such as wins, kongs and calls, drawn more often) another action is played and the hand
   finished again by the same players.
2. **The critic** learns Q(s, a) from the outcomes of the actions played, main lines and forks alike. It sees the
   hands and the wall, so little of an outcome is left unexplained; and a fork gives it two actions from the same
   state on the same wall: a direct measurement of what the action changed.
3. **The policy** moves a small step along the advantages of all legal actions in the true state,
   A(s, a) = Q(s, a) − Σ π(b|o) Q(s, b):

   `gradient = Σ_a ∇π(a|o) A(s, a)  +  ρ ∇log π(a_t|o) (G − Q(s, a_t))`

   The first term is summed over the actions, not sampled. The second corrects the critic with what really happened
   after the action played (G: the actual result, or a mix with the critic's values at the seat's later decisions;
   ρ: the clipped ratio to the playing policy), so the critic's errors add noise, not bias. A KL limit to the playing
   policy bounds the step, as in PPO, and a small entropy bonus keeps the policy from closing on one action early.
4. **The actor's other heads**: the per-action events learn the critic's (all actions) and the actual outcome (the
   action played); the opponent heads the true hands.
5. Export (EMA weights) and the next iteration. Self-play does not wait for training: it runs one network behind,
   which the ratios ρ account for.

Why this shape:

- Averaged over the hidden states behind one observation, the first term is each action's true advantage for the
  player. Full information makes the signal sharp, not the player clairvoyant: after the action everybody, the player
  too, goes on with the real policy.
- Each decision teaches about all its actions. The critic knows of every tile whether it deals in right now; a
  learner that samples finds out only when it happens (once in five hands for greedy).
- Finishing the hand with the network for every action would cost about 300 evaluations per decision. The critic
  amortizes that; the forks are the playouts that keep it honest about actions the policy seldom takes.
- Small steps accumulate many noisy estimates in the policy; replacing the policy each iteration by a softmax of the
  newest Q would act on one estimate, and with four learners at once it tends to swing.

Starting values, to be tuned by the diagnostics of 3.5: 200,000 hands an iteration (about 20 minutes here with two or
three forks a hand); the trainer draws about 15 decisions per hand (decisions of one hand say much the same, and the
GPU is the limit); the critic and the other heads train on a window of recent iterations (Thirteen's power-law
window), the policy on the newest; advantages are divided by the typical payment under the hand's rules, so that
rules with large tai do not dominate the steps; every sample gets a random symmetry (section 1).

**Rules drawn per hand.** Simple rules most often, flowers in the order none, see, seat:

| Tai | Share | Flowers: none / see / seat |
|---|---:|---|
| `simple1` | 30% | 70 / 25 / 5 |
| `simple2` | 30% | 45 / 50 / 5 |
| `standard` | 15% | 25 / 25 / 50 |
| `masters` | 10% | 25 / 25 / 50 |
| Random values around a preset (items switched on, off or changed) | 15% | evenly |

Overall the flowers come to about 46 / 34 / 20%. In a quarter of the hands of every row the settings the app offers
are varied as well: the base (1 to 5), several winners on a discard, the streak's tai, the streak after a draw, the
loose single wait. Dealer and round wind are uniform, the streak 0 to 3 (60, 25, 10, 5%).

**The loop** is Thirteen's: self-play here (32 threads), training on the home GPU or Colab, records by rsync and
networks back (`remote_loop.py`, the restricted ssh key, `check_onnx.py`, `fp16_weights.py`). Unlike Thirteen the GPU
works the whole time; the estimates above give some 14M hands and 200M trained decisions a day.

### 3.5 Evaluation, and when to stop

- **Duplicate matches** (the same walls, seats rotated), in both forms: one new network among three opponents, and
  three new ones against one opponent (playing against copies of oneself and against others can rank differently).
  Opponents: `greedy`, `mc`, the stage 0 network, and the networks of 1, 5 and 20 iterations before. Result per hand
  with its standard error; win, self-draw, deal-in and call rates, average tai, how often a win is declined. Under
  `simple1,none`, `simple2,none`, `simple2,see` and `standard,seat`.
- **Training diagnostics**: how much of the results the critic explains, by stage of the hand; on held-out forks,
  the predicted against the realized difference between two actions; the calibration of the event heads.
- **Distance from a fixed point.** On a sample of decisions, finish the hand with the network for every action (as
  stage 0 does with greedy) and measure what the policy's choice loses against the best action. This is what a
  search on top of the network could still gain; when it is near zero the network is a best response to itself, the
  practical meaning of "self-play has nothing more to teach".
- **Stop** when the newest network no longer gains on the one of two days before (under about 0.02 points a hand,
  1% of a small win, in both forms of match) and the loss above has stopped falling.
- **Against people** there is no rating to compare with. The app keeps every hand; reviewing a person's decisions
  with the network's Q gives the points lost per decision (Thirteen's contest scoring), which needs far fewer hands
  than raw results; plain results in practice mode back it up.

### 3.6 Risks and what to do then

- **The critic's differences between actions are not accurate enough** (the fork test): more forks per hand (CPU
  time is spare); each action's greedy playout as a critic input; more weight on actual results in the correction.
- **Self-play circles or forgets** (it beats its predecessor but loses ground against the fixed opponents): a larger
  share of earlier networks in the seats, smaller steps.
- **A rule set lags behind**: shift the sampling; the simple presets come first.
- **Throughput far from the estimates**: the iteration size follows; a 0.5M network (half the cost) for the first
  days if evaluations are the limit.
- **An action the policy has almost dropped comes back too slowly** (a softmax scales each action's gradient by its
  probability): the same update without that factor (NeuRD).
- **1M parameters prove too small** (larger candidates clearly better in 3.3's comparison): 2M with FP16 weights is
  still 4 MB, at twice the self-play and training cost.

### 3.7 Later

- A light search at decision time on a computer (sampling the hidden tiles and playing out with the network), if
  the fixed-point test shows something left to gain.
- Bot levels in the app: earlier networks, or sampling at a higher temperature.
- A model of human opponents and a network that answers it, as in Thirteen, if the bots should punish typical
  mistakes rather than play the self-play game.

## 4. Order of work

Sessions do not overlap, so they share the checkout and the machine's quota.

1. Engine and rules (done).
2. The app, the web page and the battle server, a first version (done in a one-off session; notes in docs/app.md).
3. The family's rules confirmed (done: `simple1`, `simple2`).
4. The engine for learning (3.2): features, actions, events, records, the data library, branches, the `net` and `mc`
   players, with tests; the model and trainer in Python; ONNX Runtime on this machine; the real speeds of self-play
   and of training on the GPU, which settle the sizes above.
5. Stage 0: data, the comparisons of 3.3, the first network and its matches.
6. Stage 1: the loop on the two machines, until the stop rule.
7. With the final network: the app and the web page again (the network as bots and analysis, the contest's scoring,
   the bugs and requests noted in docs/app.md).

## 5. What the review changed (2026-11-10)

Kept from the first plan: a network of about 1M parameters built on attention, the rules as its input, self-play on
this machine with training on a GPU, rules drawn per hand with the simple ones most often, a critic that sees hidden
information, duplicate matches, and the expected result of a hand as the objective.

| | First plan | Now | Why |
|---|---|---|---|
| Criterion | Unstated | Final strength at the least time and compute; a rule for stopping | The network ships once. |
| Tokens | One per tile and per discard, in order | 34 kinds and 4 seats, with the order of discards as per-kind features | 3.3 times cheaper per evaluation (measured); actions and dangers are per kind, so inputs and outputs line up. Order tokens stay as a measured option. |
| Features | What the seat sees | Plus what the engine computes: shanten, improving tiles, waits, tai, tiles safe now | A small network should not relearn combinatorics from noisy results. |
| Value output | One expected result | Per action, the events that make up the result, composed by the rules | The app wants them per action; base and dealer's tai enter exactly; rare big hands do not dominate a squared error. |
| Start | Cloning the greedy player | Exact outcomes of every action under greedy play | Cloning can only equal greedy. The new start is one improvement step beyond it for a few hours of CPU, and tests the pipeline. |
| Self-play learning | PPO on the result of the action played, with a critic as baseline | The critic values every legal action (advantages summed over actions), corrected by the actual result; forks | About 12 actions per decision instead of one; danger is learned from every tile at every turn, not from the deal-ins that happen. |
| Critic's input | "Every hand and the wall" | Spelled out: exact waits and tai, each seat's next draws, optionally a greedy playout | Most of a hand's luck is the wall; the less the critic leaves unexplained, the less noise in every step. |
| Baselines | Tai-aware and defensive greedy, flat Monte Carlo | Flat Monte Carlo only | Hand-tuned heuristics cost more than they tell; the stage 0 network takes their place. |
| Rules sampling | By the old presets | By `simple1`, `simple2`, `standard`, `masters` and random values, with the app's settings varied | The presets changed; whatever the app can set should have been seen. |
| Evaluation | Duplicate matches | Both seatings, the critic's diagnostics, the fixed-point test, the stop rule, people by review | To know when it is done, and how far from "perfect". |
| Bottleneck | Assumed self-play, as in Thirteen | Measured: training on the GPU | Spare CPU goes to forks; the GPU runs all the time. |
