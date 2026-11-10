# Plan

How to build a strong, light Taiwanese mahjong player: what exists, the design of the network and of its training,
and the order of work. The reference material (papers, other projects, rule pages) is described in
`reference/README.md` (not committed).

**The goal.** One network of about 1M parameters (4 MB as FP32 ONNX, 2 MB with FP16 weights) that plays without
search on a phone's or a browser's CPU, under any rules of docs/rules.md, for the best expected result of each hand.
It ships inside the app and the web page and then stays as it is. So what counts is the final strength (clearly
above strong human play at least, as near to perfect play as it gets) reached with the least time and compute;
matching published programs or their budgets is not a goal.

Sections 3 and 4 were rewritten on 2026-11-10 in two reviews: of the first plan's design, then against KataGo's
methods for faster training. Section 5 lists what each changed and why.

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

Those were the estimates. Measured (5.3): with the records, the forks and 64 hands at once per thread, one thread
evaluates 1,000 decisions a second and 32 threads 16,000, which is 183 hands a second with the network in all four
seats (beyond 8 threads the gain is small; not yet understood). Training belongs on the GPU: a desktop GPU trains the
actor at 15,000 samples a second, and actor and critic together at about 4,000. **So the GPU outruns self-play, as
in Thirteen**: hands are the scarce resource, and what is recorded of each hand decides how far they go (3.4).

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
5. **Predict the parts of the result, not only the result.** A hand's result is one noisy number. It is made of
   parts that can each be predicted: who wins on which tile, the items of the winning hand's tai, whether and when a
   hand gets ready, where the unseen tiles are. A wrong part says where the judgment went wrong; the result alone
   leaves the network to guess. Heads for these parts exist only in training and are cut from the shipped file (3.3).
6. **Many hands, few decisions from each, each read once.** The decisions of one hand share its luck, so a decision
   from another hand teaches more than one more from the same hand; and a network that reads the same results again
   learns them by heart, the value heads first. Hands are cheap here and training steps are not (section 2), so
   there is always fresh data to read instead.
7. **Measure, then choose.** The points marked "measured" below are decided by small experiments on fixed data, not
   by taste; 3.5 says when to stop.

Principles 1, 5 and 6 are KataGo's lessons (game-specific inputs, auxiliary targets such as ownership, playout cap
randomization); 5.2 goes through its methods one by one.

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
   next draws from the wall (if nobody calls); who plays each seat (the newest network, an earlier one, `greedy`);
   and, as an option, the result of the hand if everybody played `greedy` from here (one playout, about a
   millisecond), a strong hint of where the wall leads.
3. **Actions**: per kind, discard and kong (concealed or added: at most one applies to a kind); and win by
   self-draw, win on a discard (or a robbed kong), pass, pung, exposed kong and chow in its three positions. 76
   outputs, masked by legality. A decision with one legal action never reaches the network.
4. **Outcome events** of a hand for one seat: it wins by self-draw; it wins on seat j's discard; seat j wins on its
   discard; seat j wins by self-draw (ten events, not exclusive when several players win on a discard), with the tai
   of each winning hand. The seat's result follows exactly: every payment is the base, plus the winner's tai, plus
   the dealer's tai when the dealer is on either side (rules 7).
5. **Labels of the training-only heads** (3.3), all read off the hand's record: the opponents' hands, the winning
   tile, the items of the winner's tai, each seat's shanten at every turn and at the end, the hand's length, the
   final hands, the next discards.
6. **Records.** A hand is its rules, setup, wall seed and decisions (the GUI record), about 0.5 KB; self-play marks
   the decisions to train on and keeps the playing policy's probabilities there. The trainer keeps hands in this
   form and expands batches (features, legal actions, targets, labels) through the C++ library, as Thirteen's
   `trainingrows` and `libthirteen_data.so` do.
7. **Branches.** From any decision of a record: play another action and finish the hand with given players, on the
   same wall and with the same random numbers for the players' choices, so that two lines differ only where the
   action mattered. Stage 0's targets, stage 1's forks and the diagnostics of 3.5 are all branches.
8. **Players**: `net:model=FILE` (ONNX Runtime as in Thirteen; self-play evaluates many concurrent hands in one batch
   per thread) and `mc` (flat Monte Carlo: hidden tiles filled at random, a greedy playout per action; the style of
   the Olympiad programs, as a yardstick only). The hand-written "better greedy" of the first plan is dropped: the
   stage 0 network is a better baseline for less work.

### 3.3 The networks

**The actor** (the one that ships, about 1M parameters). The 38 tokens go through a small Transformer (default:
width 128, 5 layers, attention biased by whether two kinds share a suit and by their distance in rank; layer
normalization, so the network computes the same in training and in the app).

Heads that ship:

- **Policy**: one logit per legal action (discard and kong from the kind's token; the others from the token of the
  tile on offer and the seat's own token).
- **Per action, the outcome events**: the ten probabilities and the expected tai of the winner in each. With the
  hand's base and dealer's tai they give Q(o, a), the expected result of the action. These are also what the app's
  analysis shows: expected points, the chance to win, to deal in.
- **Per opponent**: how far from ready, and per kind the chance that the opponent wins on it now and with how many
  tai. Exact, dense labels (3 × 34 per decision) that teach the trunk to read hands, and the app's danger display.

Heads for training only (principle 5). Each is a linear layer on the trunk, cut at export, so the app pays nothing
for them. Mahjong has no board to own, but the 34 kinds play that part: most of these heads give one answer per
kind, as KataGo's ownership head gives one per point.

| Head | Predicts | Why |
|---|---|---|
| Where the unseen tiles are | Per kind, how many copies each opponent holds in hand | The nearest thing to ownership: every unseen tile is in some opponent's hand or in the wall. Exact labels at every decision; readiness and danger are functions of these hands. |
| The winning tile | Per kind, the chance that the hand ends with me winning on it, and with each opponent winning on it | Summed over the kinds these are the win and deal-in events, so a surprise is charged to the kind it happened on. |
| The items of the tai | For my win: which items score (concealed, single wait, pure suit, pungs of dragons, flowers, ...), and the tai as a distribution | The tai is the sum of its items (as KataGo predicts the score next to the winner). Matters most under `standard` and `masters`. |
| The road to a win | Whether I am ready within my next 1, 2, 4, 8 draws; how many draws the hand still lasts; each seat's shanten when it ends | A win is "ready, and the hand lasts, and a wait comes". These settle within a few turns, with far less luck in them than the result (KataGo's short-term targets). |
| My hand at the end | Per kind, how many copies I hold (hand and melds) when the hand ends | Which of my tiles the hand gets built on. |
| What the others do next | Each opponent's next discard; per kind, whether the next player could chow it | KataGo's auxiliary policy target (the opponent's reply), and the tiles I would be feeding. |
| The others' results | Who wins from whom among the other three seats; the exhaustive draw | The whole outcome of the hand, not only my share of it. |

Their loss weights are set once, so that each group's gradient is some 10 to 40% of the main heads' (KataGo's rule
of thumb; it did not tune them either).

**The critic** (training only, on the GPU, so it may be larger): the same shape over the full state. Per action the
same events, hence Q(s, a) in the true state; the events of all four seats; and those training-only heads whose
answer is not already in its input.

**Measured on stage 0's data** (the same hands and training budget for each candidate; compared by the held-out
losses of the heads that ship, and by CPU cost):

- Transformer or 1D residual network over the kinds (0.45 against 0.78 ms). The residual network gets KataGo's
  global pooling layers; without them it sees only neighbouring kinds.
- Whether tokens for the last discards in order add anything to the per-kind discard features (0.9 ms). A full
  event sequence (2.5 ms) only if that shows a large gain.
- Width and depth within the budget, and whether 0.5M or 2M parameters (2M still fits 4 MB with FP16 weights) change
  the losses enough to matter.
- The training-only heads: with and without them, then group by group if the gain is unclear.

Results (docs/training.md; 5.3): the Transformer stays (a residual network of the same width was no better and, at
this size, slower); the training-only heads are the largest gain of all (−0.105 without them against −0.017 points
per decision) and their weights stay as planned; kind features fed straight to the action heads, three passes and
smaller batches change nothing clearly. The sizes (96, 128 and 160 wide) and the tokens for the last discards are
still to be compared.

### 3.4 Training

**Stage 0: exact targets from the greedy player (supervised; hours).**

Hands between `greedy` players (with variations for diversity: seats that call more or less, a random discard now
and then) are branched at a few of their decisions, about 5 of the 51 (kongs, wins and calls more often than their
share): every legal action is tried and the hand finished by greedy players on the same wall. The players are
deterministic, so each action's outcome is exact; the only noise left is which hidden state it was. A hand with
five labelled decisions costs about 45 ms of one core, so 32 threads make some 700 hands and 3,600 labelled
decisions a second: 40M decisions in three hours. Labelling every decision of a hand would give as many labels from
a tenth of the hands (principle 6).

- The actor's per-action event heads learn these outcomes; their average over the hidden states is what each action
  is worth if everybody plays greedy afterwards. The opponent heads learn the true hands; the policy head a softmax
  of the composed Q.
- Playing the best Q is one step of improvement over greedy at every decision. It already avoids the discards that
  deal in on average, weighs calls, kongs and declined wins by their results, and sees the rules' payments. Cloning
  greedy's own choices, as first planned, could only equal greedy.
- A second round takes its positions from the new network's own play (targets still by greedy playouts), so that it
  is trained on the positions it reaches. Half of its labelled decisions are drawn where the first network's policy
  and action values disagree (stage 1's weights).
- The critic is pretrained on the same data, and the choices of 3.3 are made here.
- It exercises the whole pipeline (features, records, the data library, ONNX export, the `net` player, matches) with
  a plain supervised loss before any reinforcement learning.
- The data is made to be read once or twice. One hand in a hundred is held out; if the held-out losses stop falling
  while the training losses go on, the answer is more data (hours of this machine), not more passes.

Gate: the stage 0 network against `greedy` (one among three, and three against one). It has to beat `greedy`
clearly in both forms; if it does not, something in the pipeline is wrong, and it is found here, not inside the
self-play loop. (`mc` was to be a second opponent; it is far below `greedy` at any affordable number of playouts.)

Passed (5.3): +0.136 ± 0.032 points a hand as one among three greedy players, and the greedy player among three
networks loses 0.088 ± 0.035. Two things differ from the text above. The policy head has a head of its own and, in
stage 0, learns from the trunk without changing it: its nearly one-hot target is noisy, and through shared layers
it made the outcome heads worse the more data there was. And there was no second round on the network's own
positions: stage 1 starts from the first network, whose self-play gives exactly those positions.

**Stage 1: self-play with a full-information critic (days).**

Each iteration:

1. **Self-play** (this machine). Hands between the newest network, sampling from its policy; in a fifth of them one
   to three seats are earlier networks or `greedy`. About 8 of the 50 decisions of a hand are **recorded** for
   training, half of them drawn evenly and half in proportion to the points the policy gives up at the decision by
   the network's own action values (max Q − Σ π Q): where the choice matters and the policy is unsure, there is
   something to learn (KataGo's policy surprise weighting). The weight uses only what the seat sees, so it cannot
   tilt the policy by hidden information.
2. **Forks.** At one or two of the recorded decisions another action is played and the hand finished again by the
   same players. The other action is drawn from the policy (70%), from the policy at temperature 2 (25%) or evenly
   (5%), KataGo's proportions for its forks. Only the critic learns from forks.
3. **The critic** learns Q(s, a) from the outcomes of the actions played, main lines and forks alike. It sees the
   hands and the wall, so little of an outcome is left unexplained; and a fork gives it two actions from the same
   state on the same wall: a direct measurement of what the action changed.
4. **The policy** moves a small step along the advantages of all legal actions in the true state,
   A(s, a) = Q(s, a) − Σ π(b|o) Q(s, b):

   `gradient = Σ_a ∇π(a|o) A(s, a)  +  ρ ∇log π(a_t|o) (G − Q(s, a_t))`

   The first term is summed over the actions, not sampled. The second corrects the critic with what really happened
   after the action played (G: the actual result, or a mix with the critic's values at the seat's later decisions;
   ρ: the clipped ratio to the playing policy), so the critic's errors add noise, not bias. A KL limit to the playing
   policy bounds the step, as in PPO, and a small entropy bonus keeps the policy from closing on one action early.
5. **The actor's other heads**: the per-action events learn the critic's (all actions) and the actual outcome (the
   action played); the opponent heads and the training-only heads their exact labels.
6. Export (EMA weights) and the next iteration. Self-play does not wait for training: it runs one network behind,
   which the ratios ρ account for.

Why this shape:

- Averaged over the hidden states behind one observation, the first term is each action's true advantage for the
  player. Full information makes the signal sharp, not the player clairvoyant: after the action everybody, the player
  too, goes on with the real policy.
- Each decision teaches about all its actions. The critic knows of every tile whether it deals in right now; a
  learner that samples finds out only when it happens (once in five hands for greedy).
- Finishing the hand with the network for every action would cost about 300 evaluations per decision. The critic
  amortizes that; the forks are the playouts that keep it honest about actions the policy seldom takes.
- Forks try what the policy avoids while the policy's own data stays its own play: exploring and teaching the
  policy are kept apart, as KataGo prunes its forced playouts from the policy target. The critic may learn from any
  line: it sees every hand, so what it learns does not depend on how a state was reached.
- Small steps accumulate many noisy estimates in the policy; replacing the policy each iteration by a softmax of the
  newest Q would act on one estimate, and with four learners at once it tends to swing.

**Fresh data, read once.** In stage 1 nothing is read twice: an iteration's samples are trained on once, and the advantages come from the critic as it was before
it saw them. That closes the usual way value heads go wrong late in training, learning the results in the window by
heart; a critic that sees the wall would be the quickest to do it. (KataGo reads a sample about four times, and its
ablation with 600 playouts on every move overfitted for lack of games.) One hand in a hundred is held out, and the
losses of the critic and of the event heads on those hands are reported beside the training losses.

Starting values, to be tuned by the diagnostics of 3.5: 100,000 hands an iteration (about 9 minutes here), with 8
recorded decisions and one or two forks each: 0.8M samples, which the GPU trains on in 4 minutes. The GPU is faster
than self-play can feed it, so, in this order: more decisions per hand are recorded (up to about 15); the critic
grows (it costs nothing in the app); a second reading, for the critic only, comes after that and is watched on the
held-out hands. Advantages are divided by the typical payment
under the hand's rules, so that rules with large tai do not dominate the steps; every sample gets a random symmetry
(section 1).

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

**The loop** is Thirteen's: self-play here (32 threads, `loop1.py --role selfplay`), training on the home GPU
(`train_loop.py`), hands by rsync and networks back, through a key that allows rsync into one directory and nothing
else. Only data crosses between the machines: hands one way; networks, logs and a file of numeric training options
the other. The measured speeds give some 15M hands (about 4.5 GB of records) and 120M recorded decisions a day.

### 3.5 Evaluation, and when to stop

- **Duplicate matches** (the same walls, seats rotated), in both forms: one new network among three opponents, and
  three new ones against one opponent (playing against copies of oneself and against others can rank differently).
  Opponents: `greedy`, the stage 0 network, and the networks of 1, 5 and 20 iterations before. Result per hand
  with its standard error; win, self-draw, deal-in and call rates, average tai, how often a win is declined. Under
  `simple1,none`, `simple2,none`, `simple2,see` and `standard,seat`.
- **Training diagnostics**: how much of the results the critic explains, by stage of the hand; on held-out forks,
  the predicted against the realized difference between two actions; the calibration of the event heads; every loss
  on the held-out hands beside the training loss (a growing gap is overfitting).
- **Distance from a fixed point.** On a sample of decisions, finish the hand with the network for every action (as
  stage 0 does with greedy) and measure what the policy's choice loses against the best action. This is what a
  search on top of the network could still gain; when it is near zero the network is a best response to itself, the
  practical meaning of "self-play has nothing more to teach".
- **Stop.** When the newest network no longer gains on the one of two days before (under about 0.02 points a hand,
  1% of a small win, in both forms of match) and the loss above has stopped falling, the learning rate is lowered to
  a third and training goes on (KataGo dropped its rate tenfold for the last days of its run, for final strength).
  Training ends when a lowering brings no further gain.
- **Against people** there is no rating to compare with. The app keeps every hand; reviewing a person's decisions
  with the network's Q gives the points lost per decision (Thirteen's contest scoring), which needs far fewer hands
  than raw results; plain results in practice mode back it up.

### 3.6 Risks and what to do then

- **The critic's differences between actions are not accurate enough** (the fork test): more forks per hand, at
  the price of fewer hands; each action's greedy playout as a critic input; more weight on actual results in the
  correction.
- **Self-play circles or forgets** (it beats its predecessor but loses ground against the fixed opponents): a larger
  share of earlier networks in the seats, smaller steps.
- **A rule set lags behind**: shift the sampling; the simple presets come first.
- **Throughput far from the estimates**: the iteration size follows; a 0.5M network (half the cost) for the first
  days if evaluations are the limit.
- **The value heads or the critic overfit** (held-out losses rise while training losses fall): fewer decisions per
  hand, no second readings, stronger weight decay; for stage 0, new data.
- **Rare, high-paying hands are learned too slowly** (`standard`, `masters`): deals tilted toward them in a share of
  the hands, weighted back in the losses so that the reading of opponents stays calibrated.
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
   and of training on the GPU, which settle the sizes above (done).
5. Stage 0: data, the comparisons of 3.3, the first network and its matches (done; the gate passed).
6. Stage 1: the loop on the two machines, until the stop rule (the loop is built and tested; running it is next).
7. With the final network: the app and the web page again (the network as bots and analysis, the contest's scoring,
   the bugs and requests noted in docs/app.md).

## 5. What the reviews changed (2026-11-10)

### 5.1 The first review: the design

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
| Bottleneck | Assumed self-play, as in Thirteen | Measured: training on the GPU | Spare CPU goes to forks; the GPU runs all the time. (Measured since: the reverse, 5.3.) |

### 5.2 The second review: KataGo's methods

Sources: Wu, *Accelerating Self-Play Learning in Go* (`reference/KataGo_paper/`) and KataGo's
`docs/KataGoMethods.md`. A factor in brackets is the paper's ablation: how much longer training took without the
method. KataGo learns from a search and this design has none, so some methods carry over as they are, some change
shape, and the methods of the search itself have nothing to attach to.

| KataGo | Here |
|---|---|
| **Playout cap randomization** (1.37×). The game's result needs many games, so most moves get a cheap search and only the quarter with a full search is recorded. | Principle 6. Stage 0 labels 5 decisions of a hand, not all 51: ten times the hands for the same labels. Stage 1 records about 8 of 50. |
| **Window and reuse.** Samples are drawn from a growing window, each read about four times; with too few games (600 playouts on every move) the net overfitted. | Nothing is read twice in stage 1, since fresh data exceeds what the GPU trains on; stage 0's data is read once or twice. Held-out hands show the gap. No window is needed. |
| **Auxiliary ownership and score targets** (1.65×): "whenever a desired target can be expressed as a sum, conjunction, or disjunction of separate subevents, [...] predicting those subevents is likely to help". | Principle 5 and the training-only heads of 3.3. The result was already split into events and tai; now also by kind (unseen tiles, winning tile, final hand), by item of the tai and by step toward the win. |
| **Auxiliary policy target** (1.30×): the opponent's reply. | Training-only head: each opponent's next discard, and whether the next player could chow each kind. |
| **Short-term value targets**: averages of the search's values over the next 6, 16 and 50 turns, with less noise than the result. | Training-only head "the road to a win" (ready within 1, 2, 4, 8 draws; the hand's remaining length). For the event heads, the critic's values of all actions already are low-noise targets. |
| **Policy surprise weighting**: positions whose search result surprised the policy are trained on more often. | The recorded decisions are drawn half evenly, half by what the policy gives up by the network's own values. Unlike KataGo's, the weight may only use what the seat sees. |
| **Forced playouts and policy target pruning** (1.25×): exploring moves without teaching the policy to play them. | Forks feed the critic only. |
| **Forks**: 2.5% of positions branch to another move, drawn from the policy at temperature 1, 2 or ∞ (70, 25, 5%). | Forks at one or two decisions a hand, with the same proportions. |
| **Game-specific input features** (1.55×). | Principle 1 (first review). |
| **Global pooling** (1.60×). | Attention is global already; the residual candidate gets pooling layers. |
| **One net for all rules and board sizes**, with rare extreme settings. | One network for all rules; 15% of hands with random values around a preset. |
| **Auxiliary soft policy target**: a second policy head on a flattened target, to tell apart the moves below the best two. | No head needed: the per-action values are learned for every legal action alike. |
| **Learning rate dropped tenfold at the end; averaged weights.** | The stop rule lowers the rate before ending; EMA weights are exported. |
| **Gating**: a new net must win 100 of 200 games. | Not adopted. Steps are bounded by the KL limit, matches watch for a regression, and checkpoints allow going back. |
| **Net size grown during the run.** | Not adopted: the shipped size is small and fixed. A 0.5M start remains a fallback (3.6). |
| **Fixup, then fixed-variance initialization**: no batch normalization at inference. | Layer normalization: the same computation in training and in the app. |
| **Methods of the search**: subtree value bias correction, variance-scaled cPUCT, uncertainty-weighted playouts, optimistic policy, shaped Dirichlet noise, root policy temperature. | Nothing to attach them to. The entropy bonus does the root temperature's work (it keeps equally good actions alive). They come back if 3.7's search is built. |

### 5.3 What building it changed (2026-11-11)

Measurements are in docs/training.md.

| Topic | Planned | Now | Why |
|---|---|---|---|
| Bottleneck | Training on the GPU | Self-play: 183 hands a second on 32 threads feed 1,500 samples a second; the GPU trains 4,000 | Records, forks and 88 evaluations a hand cost more than estimated, and the threads scale poorly beyond 8. Iterations are 100,000 hands; the GPU's slack goes to more recorded decisions per hand and a larger critic. |
| Policy head | An output of the shared action head | A head of its own; in stage 0 its gradient stops at the trunk | With 12.8M decisions the network was worse than with 2.2M (−0.047 against −0.017): the nearly one-hot target is noisy and pulled the shared layers away from the outcome heads. Separated: +0.0165 on 9.2M. |
| Stage 0's data | Read once or twice, a second round on the network's positions | 22M labelled decisions, one round | The first round passed the gate; stage 1's self-play gives the network's own positions. |
| Residual network | A candidate trunk | Dropped | No better at equal width (−0.028 against −0.017), and slower than the Transformer at 1M parameters. |
| Training-only heads | To be measured | Kept, weights as planned | −0.105 without them; halving their weights −0.036; doubling or quadrupling them changes nothing. |
| `mc` | A yardstick and part of the gate | Neither | With 400 playouts a decision it loses 0.6 points a hand to `greedy`: one hand's result varies by several points whatever the discard, which is the reason for principle 6 in the first place. |
| The two machines | The self-play machine drives the trainer over ssh (Thirteen's `remote_loop.py`) | Each side runs its own loop over a shared directory; the trainer holds a key for rsync into it only | The training machine is somebody's desktop: nothing that runs there should come from elsewhere without its owner. |
