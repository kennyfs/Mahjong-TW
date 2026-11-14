# The analysis in the app: what the network can show, measured

Notes for the design of the review of a finished hand and of the analysis screen, once the network is in the app
(docs/app.md, "Later: the network"). Section 1 is what exists, section 2 what was measured with an early network,
section 3 a proposal that is still under discussion with the user (2026-11-11), section 4 the tools. Work on this
is on branch `analysis`.

## 1. What exists (the prototype of docs/app.md)

- **After a hand**: the result sheet (winner, hand, tai items, payments, next dealer), and from it or from the
  records the **review**: the table step by step through the record's decisions (others' passes skipped; buttons for
  the start, the previous and next step, the player's previous and next decision, the end), everyone's tiles at a
  tap, and at each decision a button to a separate screen with that decision's analysis.
- **A decision's analysis**: every legal action with the engine's tile efficiency (tiles from ready, improving tiles
  left, when ready the waits with copies left and their tai), ranked win > fewer tiles from ready > more improving
  tiles, the best and the action taken marked.
- **The analysis screen**: a position entered by hand (the hand, the tile drawn or the discard to answer, every
  seat's melds, flowers and discards, dealer, round wind, tiles left), then the same list.
- Nothing uses the network, nothing sums up a hand, and nothing speaks of danger.

## 2. Measurements (network 50 of stage 1, 2026-11-11)

Network 50 has seen 5 million self-play hands; it beats `greedy` by 0.15 points a hand as one among three and is
far from final. Hands were played with `mahjong review` (section 4) and read with `python/review.py`; logs in
`mahjong-files/logs/analysis_*.log`. Points are the rules' payments (base and tai). Q is an action's expected result
composed from the outcome events; V is the policy's mean of Q; a decision's **loss** is max Q − Q(played).

**The losses add up to the real difference in results.**

| Reviewed player (among three copies of the network that sample from the policy) | Rules | Result a hand | Sum of V − Q(played) a hand |
|---|---|---:|---:|
| `greedy`, 20,000 hands | `simple1,none` | −0.146 ± 0.028 | 0.123 lost |
| `greedy`, 20,000 hands | `simple2,see` | −0.148 ± 0.038 | 0.212 lost |
| The network, best Q (duplicate match, 4,000 deals) | `simple1,none` | +0.120 ± 0.031 | 0.190 gained |
| The network, the policy's first choice (the same) | `simple1,none` | +0.090 ± 0.031 | 0.114 gained |
| The network, sampling (16,000 hands) | both | 0.000 ± 0.04 | 0.001 |

The value at a seat's first decision predicts its result with slope 0.98 (`simple1,none`) and 0.91
(`simple2,see`), and explains 4% of its variance: the rest is luck. A hand's loss and its result correlate at −0.06
to −0.10, so the result says almost nothing about how the hand was played.

**Small losses are noise; large ones tell players apart.** By max Q − Q(played), a hand (`simple1,none`):

| | The network sampling | `greedy` |
|---|---:|---:|
| Sum | 0.19 | 0.31 |
| Decisions losing more than 0.05 | 1.21 | 1.28 |
| more than 0.2 | 0.08 | 0.37 |
| more than 0.5 | 0.01 | 0.10 |
| more than 1 | 0.00 | 0.02 |

Five percent of `greedy`'s decisions carry two thirds of its loss. Nearly all of it is the choice of discard
(0.28 of 0.31 a hand). Among the larger losses (600 hands under `simple2,see`): another tile at the same distance
from ready (for example keeping the pairs that can be punged rather than an edge wait), a safer tile at the same
distance, and, at half their size, giving up a step to fold; then kongs that break a ready hand, wins on a discard
that are better declined early (a self-draw pays three), and calls.

**Best Q and the policy's first choice play about alike.** One against three of the other (4,000 deals each): the
one playing best Q got −0.017 ± 0.029 (`simple1,none`) and +0.004 ± 0.037 (`simple2,see`); the one playing the
policy's first choice −0.077 ± 0.029 and +0.065 ± 0.038. No consistent difference. The two choose the same action
in 75% of decisions.

**The opponent heads are calibrated** (the network's own games, 630,000 pairs of a decision and an opponent):

- `ready`: AUC 0.925 (0.83 from the number of tiles left in the wall alone). Predicted and real shares agree in
  every bin (0.05 to 0.10: 0.06; 0.5 to 0.6: 0.53; above 0.9: 0.97).
- `danger`: predicted and real shares agree from 0.0003 to 0.6. Of a seat's own possible discards, 44% are
  predicted under 0.2% (real: 0.03%) and 3% above 20% (real: 24 to 59%); AUC 0.91. When an opponent is ready, its
  most dangerous kind is a real wait in 23% of cases and one of the three most dangerous in 49%: the head knows
  that an opponent is dangerous and which tiles are safe far better than which tile it waits on.

**The training-only heads against counting** (held-out losses of network 50, as the trainer reports them; "counting"
knows only how many tiles of each kind the seat has not seen):

| Head | The network | Counting | Its rate alone |
|---|---:|---:|---:|
| `opp_hand` | 23.50 | 25.70 | 27.73 |
| `chow` | 14.89 | 15.96 | 16.92 |
| `next_discard` | 3.28 | 3.50 | 3.53 |
| `ready_within` | 1.55 | | 2.55 |
| `draws_left` (root mean square error, draws) | about 9.7 | 10.3 (by the tiles left) | 12.1 |

Reading the discards adds about as much again as counting does for the opponents' hands and for the next player's
chows; an opponent's next discard and the hand's remaining length are close to unpredictable.

## 3. Proposal (under discussion)

1. **Judge by what the seat could see, then show what was hidden.** A decision is graded by the actor's Q; the
   opponents' true hands and waits (exact, from the engine) are shown beside the estimates on request. No hints
   during play unless asked for (2026-11-14: 練習時顯示分析, a hand then counting for nothing); a review's branch and
   the analysis' 從這裡打 show the analysis at every decision before it is made.
2. **A hand's summary** on the result sheet and at the top of the review: the expected result at the first
   decision, the points lost by decisions (the sum of the losses), and the rest as luck (result − first value +
   losses), with the number of mistakes. A graph of max Q over the player's decisions, to jump from.
3. **Grades from 0.2 points up** (questionable from 0.2, a mistake from 0.5, a blunder from 1, scaled by the rules'
   typical payment); differences under 0.05 are shown as equal. The review steps between these decisions.
4. **Figures on the tiles.** At the player's discard: on each tile of the hand its danger now (four levels) and
   what discarding it costs against the best; the best and the tile played marked; the opponents' chance of being
   ready on their name plates. A tap gives the tile's line: expected result, chance to win (by self-draw), to deal
   in, the danger per opponent with its tai, tiles from ready and improving tiles. Calls and wins as buttons with
   the same figures. The whole list stays one tap away.
5. **A reason in words** from the figures: which part of the difference between the best action and the one played
   is winning less, dealing in more, or being drawn on more.
6. **The analysis screen** starts from a step of a review (the position copied into the editor) as well as from
   nothing; the tile drawn is changed with a tap and the figures follow at once; a table of what to discard after
   each of the 34 possible draws, and of what to call on each discard of each opponent (some 140 evaluations).
7. **Statistics**: points lost a hand and mistakes a hand over time, by kind (danger, tile choice, calls, wins),
   beside the network's own 0.19.
8. **The outputs of the model.** The six shipped outputs cover all of the above. The training-only heads are linear
   layers on the trunk and stay in every checkpoint, so exporting more of them later needs no training and costs
   under 1% of an evaluation: no decision is needed now, and no second path through the network. Candidates if a
   use is found: `ready_within` (the speed of the own hand), `opp_hand` summed per suit, `chow` as one more figure
   of a discard. Not `next_discard` or `draws_left` (see above).

## 4. What is built (branch `analysis`, 2026-11-11)

The web page first, then the app (version 0.1.0, a beta with the network of iteration 50). The engine's requests
are in docs/gui-engine.md (`analyze` with a model, `review`, `whatif`, `steps`); the page's logic is
`web/src/lib/review.ts`, the app's `Review.kt`.

- **The network in the page**: `actor.onnx` ships with the engine (ONNX Runtime as WebAssembly); the practice bots
  are the network by default (`net:model=...`, the action it values most), tile efficiency by choice.
- **A hand's summary** on the result sheet, in the review and in the records' lines: first value, decisions, luck,
  and the number of mistakes at the settings' level.
- **The review** (`Replay.svelte`): the graph of the value over the player's decisions with the decisions worth a
  look marked, steps to the previous and next of them, the verdict on each decision (grade, loss, the best action,
  and the reason from the parts of the two values), the opponents' chance of being ready on their name plates, and
  with "show everyone's tiles" what they really held (tiles from ready, waits), their hands on a rack beside their
  melds.
- **The steps shown** are the actions that took effect and the player's own decisions: others' passes, and others'
  claims that another seat's claim came before (a chow when someone won on the tile), are skipped (`steps`); a claim
  of the player's own that was not made stays, with whose came first. The button to the end goes to the last move
  (the win, with who dealt in; without a win the last discard), then to the table at the end with everyone's tiles;
  the result's sheet opens from its own button, never by itself.
- **A decision is shown** on the hand's tiles (each discard's cost over it, its danger as a band along its foot,
  calls and wins as chips, a tap for a tile's line) and in a list of every action, one tap away (beside the table on
  a large window), whose rows are numbers, bars of where the action differs from the best (winning, dealing in,
  others' self-draws), or both (the settings). Three candidates were tried first (tiles, numbers, bars), and merged:
  the tiles' marks and the list do not exclude each other. The list keeps its distance from its end over a step when
  it is scrolled near it: the opponents' figures are there, to watch them change.
- **Levels** 入門, 中級, 高手: a mistake is a loss of 1, 0.5, 0.2 points or more, scaled by `unit / 4`; losses under
  0.05 show as equal.
- **Best actions that are not the obvious ones** are marked whatever the level, when they are worth at least a
  questionable move over the obvious one: a win passed up, a discard that gives up a step towards ready (to fold, or
  for a slower hand worth more), a call left alone; with a star on the graph when the player found it.
- **The analysis screen**: the figures follow every change at once; a step of a review is taken there with its
  rules, and while only the hand's own tiles change, the figures are asked of the hand's game with that hand (the
  network reads the game's order of play; a position of its own has only a plain history, docs/gui-engine.md), so
  they match the review's; the tables of draws (what to do after each tile, which ones are kept) and of claims
  (which discards are worth calling) on request.
- **Statistics** over the reviewed hands of a records tab: points lost a hand, mistakes a hand, and the loss by kind
  of decision (tile choice, danger, calls, wins); hands not reviewed by this network (another network, or a review
  cut short) are reviewed on request.
- **The app** (0.1.0) has all of the above, the same screens as the page; the review is full screen (no navigation
  bar or rail), with its controls beside the table on a phone sideways and the list of actions beside the table too
  on a large window.

Not done: the Docker image of the site with the network (when a stable version is released); a review of another
seat's decisions (they are analyzed one at a time when their list is opened).

## 5. Tools

```bash
M=$HOME/mahjong_remote/share/s1/models/actor_50.onnx
# A greedy seat (role 0) among three copies of the network that sample, reviewed by the network.
cpp/build/mahjong review -m $M -p greedy --rules simple1,none -n 20000 -t 16 -o greedy.jsonl
python/review.py losses greedy.jsonl
# The network reviewing its own games: the noise of the losses, and the opponent heads against the truth.
cpp/build/mahjong review -m $M -r 0 -r 1 -r 2 -r 3 --rules simple1,none -n 4000 -t 16 -o self.jsonl
python/review.py losses self.jsonl; python/review.py heads self.jsonl
# Examples: every figure of every action, the position and the hidden hands.
cpp/build/mahjong review -m $M -p greedy --rules simple2,see -n 600 -t 6 --full -o examples.jsonl
python/review.py list examples.jsonl; python/review.py show examples.jsonl 200 72; python/review.py hand examples.jsonl 200
# The training-only heads' baselines, to set beside train.jsonl's held-out losses.
python/review.py aux $HOME/mahjong_remote/share/s1/selfplay/iter_50
```

`mahjong review` writes one JSON line per decision (cpp/command/review.cpp lists the fields): it is the data a
network-backed `analyze` of the GUI engine would return.

## 6. The colors and the grades (network 211 of s2, 2026-11-12)

`python/thresholds.py` on `mahjong review` lines (3,000 hands of the network sampling, reviewed by itself with
`--full`; 20,000 hands of `greedy` among three copies of the network; 4,000 hands of four `greedy` players reviewed by
networks 201 and 211 with the same seed), under `simple1,none` and `simple2,see`. The `.jsonl` files (2 GB) were
deleted after; the output is `mahjong-files/exp1112b/thresholds.log`.

**The colors of a list's figures** (as Thirteen's NOTABLE_GAP): the 75th percentile of |best − k-th| for the 2nd to
6th action by Q. Both rules alike:

| Figure | p50 | p75 (the gap that is colored) |
|---|---:|---:|
| 胡 (win) | 0.024 | 0.080 |
| 自摸 (self-draw) | 0.009 | 0.032 |
| 放槍 (deal in) | 0.010 | 0.027–0.029 |
| 被自摸 (another's self-draw) | 0.012 | 0.030–0.031 |
| 當下危險 (danger now) | 0.005 | 0.027 |

An action more than that better than the best one is green, worse red (8% for 胡, 3% for the others).

**The grades**: the networks' own noise, |loss by 201 − loss by 211| of the same action in the same position: for
the action played p90 0.03–0.05, p95 0.05–0.07; over every action p75 0.07–0.09. `greedy`'s losses (simple1; simple2
in brackets), decisions a hand above each step: 0.1: 0.77 (1.04), 0.25: 0.33 (0.48), 0.5: 0.12 (0.21), 1: 0.02
(0.06). The network sampling, by itself: 0.1: 0.96, 0.25: 0.11, 0.5: 0.015. The steps were 0.2, 0.5, 1 (questionable,
mistake, blunder, for a payment of 4); the user found them too large, and they are halved to **0.1, 0.25, 0.5**: the
lowest is still above the noise of the action played (its p95), and `greedy` then has about one questionable decision
a hand and one mistake in three hands.
