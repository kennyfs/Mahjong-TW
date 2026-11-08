# Plan

How to build a strong, light Taiwanese mahjong player, what exists now, and what comes next. The reference material
(papers, other projects, rule pages) is described in `reference/README.md` (not committed).

## 1. What makes this game different

- **A long sequence of decisions with hidden information.** About 50 to 80 decisions per hand among four players;
  the wall's order and the others' hands are hidden. Unlike Thirteen there is no small set of candidates to score
  exactly against the opponents.
- **Each hand is settled on its own** (base + tai), with no placement bonus, so the objective is the expected result
  of each hand (in tai). Japanese mahjong AIs need a model of the final placement (Suphx's global reward prediction,
  Mortal's GRP); this game does not. The dealer's streak links hands only weakly.
- **No furiten and no riichi.** A player may win on a kind they discarded, so "safe" tiles are only less likely, and
  nobody declares being ready: defense rests on reading the others.
- **No public game records** of Taiwanese mahjong, so learning is by self-play, started from a hand-written player.
- The published Taiwanese programs (Computer Olympiad: SIMCAT, VERYLONGCAT, MeowCaTS) search for seconds per move
  (flat Monte Carlo, expectimax, MCTS); none is a network small enough for a phone.

## 2. What exists (C++)

- Rules, scoring and the play of a hand (docs/rules.md), with the flower settings and the tai values as parameters.
- `shape`: winning test, waits, all readings of a hand, and shanten for any number of melds (per-suit tables of
  partial sets, memoized per thread). Tests check shanten against its inductive definition on random hands.
- Players `random` and `greedy` (fewest tiles from ready, then the most improving tiles; calls only when they lower
  shanten; no defense, no tai awareness).
- `match` (duplicate walls, 4 rotations or 24 orders, multithreaded), `show`, `tai`, `benchmark`.
- `Position` (what one seat knows) and loading it back with the hidden tiles filled at random: for the analysis of a
  position set up by hand, and later for sampling in a search.
- Tile-efficiency analysis of every legal action, and the JSON engine of the interfaces (docs/gui-engine.md):
  stateless, a hand travels as its record (rules, setup, wall, decisions) and is replayed per request.

Speed on one thread (`mahjong benchmark`): shanten of a 16-tile hand 0.38 µs; a hand between four `random` players
0.09 ms, between four `greedy` players 1.8 ms (most of it the improving-tile counts). `match` with 32 threads plays
about 14,000 greedy hands per second.

First results (5,000 duplicate walls × 4 rotations, `simple,none`):

| Players | Result per hand | Win | Self-draw | Deal-in | Exhaustive draw |
|---|---|---|---|---|---|
| `greedy` (×2) vs `random` (×2) | greedy +1.34 ± 0.03 tai | 46% | 10% | 11% | 8% |
| `greedy` ×4 | 0 by symmetry | 24.5% each | 5.6% | 18.9% | 2.1% |

Four greedy players almost always end the hand with a win: nobody folds.

## 3. Next steps

### 3.1 Rules

The family's exact rules still have to be confirmed (docs/rules.md lists the choices). Changes there are parameter
changes, so the engine and the network's rules input can follow.

### 3.2 Engine for learning

1. **Observation encoding** of what a seat sees: its hand, every meld (others' concealed kongs only as kongs), the
   discards in order with their drawn/called flags, flowers, winds, dealer, streak, tiles left, and the rules.
2. **Action space**: discard (34 kinds), win, chow (3 ways), pung, kong (exposed, concealed or added, by kind), pass.
3. **Compact game records** (wall seed + actions), from which the trainer rebuilds observations through the C++
   library, as in Thirteen (`trainingrows` and `libthirteen_data.so`).
4. **Better baselines**: greedy with tai awareness (keeping concealed and pure-suit hands when they pay) and with
   defense (folding when an opponent is likely ready); a flat Monte Carlo player in the style of SIMCAT for
   evaluation.

### 3.3 The network

- **Attention**, as in Thirteen: tokens for the tiles a seat knows (own hand, melds, discards with their order and
  flags, flowers) plus a global token (rules, winds, dealer, streak, tiles left), a small Transformer, and heads read
  from it. Budget: about 1M parameters, a 4 MB FP32 ONNX file, a few ms per decision on a phone's CPU without search.
- **Outputs**: the policy over legal actions; the expected result of the hand; auxiliary predictions that are cheap
  to learn and useful in the app: the chance to win, to deal in, the expected tai when winning, and for each opponent
  the chance they are ready and the kinds they wait on (Mizukami & Tsuruoka's opponent model, learned).

### 3.4 Training

1. **Behavior cloning** from the greedy player, so self-play starts from sensible play.
2. **Self-play reinforcement learning** (PPO or a similar actor-critic) with:
   - an **oracle critic** that sees every hand and the wall (training only), which cuts the variance of the returns
     (Suphx's oracle guiding; Li et al., CoG 2022);
   - the reward: the hand's result in tai, zero-sum;
   - a **population** of the newest and earlier networks and the baselines, so it does not chase itself;
   - **rules drawn per hand**, the main settings most often: flowers none, see, seat in that order of weight (about
     45%, 35%, 20%); tai tables with few items (the `simple` preset and close variants) weighted up, `standard` and
     `masters` and random values for the rest; base from 1 to 5 tai.
3. **Evaluation** by duplicate matches against the baselines and earlier networks: result per hand with its
   standard error, win, self-draw and deal-in rates, average tai.

Self-play runs on the workstation (32 cores); training on the home GPU or Colab (L4), with Thirteen's
`remote_loop` pattern (records over rsync, networks back).

### 3.5 Later

A light search at decision time on a computer (sampling the hidden tiles and playing out with the network).

## 4. Order of work

Sessions do not overlap, so they share the checkout and the machine's quota.

1. Engine and rules (done, on `master`).
2. The app and the web page, a first version, in a separate session on branch `ui` (docs/ui-handoff.md). It uses
   the engine through docs/gui-engine.md, with the `greedy` bot and the efficiency analysis.
3. The family's rules confirmed; `ui` merged into `master` (fast-forward); 3.2 to 3.4 above. **No training before
   the rules are confirmed**: the flow of play may differ in details beyond the tai.
4. Training.
5. The network in the app and the web page (bots, analysis, the puzzle contest's scoring).
