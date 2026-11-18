# Explaining a decision: what the network does that tile efficiency does not

A study for the per-decision explanation (逐手解說, docs/coach.md "One decision explained", 2026-11-19). The user's
aim: an explanation read in a review should teach what to do at the table next time, where no analysis is shown. So
it should name the lesson, the signs on the table that call for it, and a rule of thumb, rather than list the
network's figures. This note is what self-play shows about such lessons; a rule-based explanation built on it needs
no language model.

## 1. Data

`mahjong study` (cpp/command/study.cpp): four copies of the shipped network (s3 221) play, drawing from the policy;
at every decision with a choice it writes the network's figures for every action, tile efficiency's choice
(`greedy`), what the seat could see of each opponent (discards, melds, 摸切 at the end of the discards, the last
discard from the hand, the last call) and what was hidden (whether the opponent was ready, its waits). With
`--shape`, three more numbers per discard: the shape beyond the improving tiles (waits once ready, the draws that
improve a ready hand, the two-step improving tiles).

Runs of 2026-11-19: `simple2,see` 200,000 hands (10.7 million decisions), `simple1,none` 100,000 hands, and 19,000
hands of `simple2,see` with `--shape`. 300 hands take 2 s on 30 threads; a pass over 10 million decisions in Python
with 30 processes takes seconds. Most tables below are stable from about 20,000 hands.

`greedy` is first-order tile efficiency: fewest steps from ready, then most unseen improving tiles, honors then
terminals first on ties. It ignores value, danger, calls (it calls only when that lowers shanten) and kongs (it adds
a kong whenever it may). It already picks the best discard by the two-step measures in 94–95% of decisions, and
those measures do not explain the network's other choices at the same distance: the differences lie elsewhere.

## 2. Where the network differs from tile efficiency

Decisions where the network's best action is worth at least 0.1 more than `greedy`'s (`simple2,see`; a seat-hand is
one player's hand; `greedy` loses 0.56 a seat-hand in all):

| Lesson | A seat-hand | Mean gap | Share of the loss |
|---|---:|---:|---:|
| A safer tile at the same distance | 0.22 | 0.37 | 16% |
| A step back, to defend | 0.13 | 0.54 | 13% |
| A safer discard when ready | 0.07 | 0.62 | 7% |
| The same distance, another shape (mostly: keep pairs) | 0.32 | 0.31 | 34% |
| A kong that breaks a set the hand uses | 0.06 | 1.07 | 11% |
| No call where `greedy` calls | 0.14 | 0.20 | 6% |
| A call `greedy` does not make (mostly honor pungs) | 0.06 | 0.31 | 3% |
| Letting a winning discard go, for a self-draw | 0.01 | 0.85 | 2% |

`simple1,none` has nearly the same shares.

## 3. Attack and defense

**Is an opponent ready?** From the signs alone (discards, melds, 摸切 at the end, the last discard from the hand,
the last call) boosted trees reach AUC 0.905 on held-out data; the network's head 0.936. The share truly ready
(`simple2,see`):

| Opponent's discards | 0 melds | 1 | 2 | 3 | 4 |
|---|---:|---:|---:|---:|---:|
| 3–5 | 1% | 4% | 11% | 29% | |
| 6–8 | 9% | 16% | 29% | 50% | 82% |
| 9–11 | 22% | 33% | 47% | 66% | 90% |
| 12–14 | 29% | 42% | 57% | 74% | 91% |

Two or more 摸切 at the end raise it about 1.5 times (9–11 discards, 2 melds: 47% → 68%); a fresh call raises it;
late discards from the hand of middle tiles lower it.

**When the network gives up speed**, by the most threatening opponent's chance from those signs and the tiles left
(a step back / a step back or a safer tile at the same distance):

| Own hand | Opponent looks | 30+ left | 20–29 | 10–19 | under 10 |
|---|---|---:|---:|---:|---:|
| Ready | 80%+ ready | 1%/3% | 3%/6% | 5%/9% | 20%/23% |
| One away | 40–60% | 1%/9% | 3%/15% | 11%/24% | 48%/57% |
| One away | 80%+ | 3%/14% | 7%/22% | 19%/34% | 54%/64% |
| Two away | 40–60% | 3%/12% | 16%/29% | 41%/55% | 43%/55% |
| Two away | 80%+ | 5%/18% | 26%/41% | 43%/57% | 42%/55% |

The tiles left matter more than how sure the threat is; dealer or not makes no difference. The network folds far
less than a cautious player: a deal-in pays one player, and folding does not stop the others' self-draws.

**A tile's chance of dealing in to a ready opponent**, by what it is to that opponent:

| | Honor | 1/9 | 2/8 | 3/7 | 4–6 |
|---|---:|---:|---:|---:|---:|
| It discarded the kind (現物) | 0.1% | 0.7% | 0.7% | 0.5% | 0.8% |
| Suji (筋) | | 2.5% | 4.0% | 5.6% | 4.2% |
| Half suji | | | | | 8.2% |
| Other, none on the table | 3.4% | 9.1% | 11.4% | 12.2% | 13.3% |
| Other, one on the table | 0.5% | 5.3% | 6.8% | 7.4% | 9.0% |
| Other, two on the table | 0% | 2.9% | 3.4% | 4.0% | 5.3% |

Without furiten a kind the opponent discarded is still nearly safe; when the network defends, 40% of its discards
are such tiles (the fastest discard: 18%).

## 4. Pairs, honors, calls, wins, kongs

- **Pairs.** The network almost never breaks a pair that stands alone and can still be punged: honor pairs 1.4% of
  the times a pair is broken (`greedy` 25%), terminal pairs 0.7% (12%). It breaks pairs joined to a run (the 4 of
  4456, about 40%) and dead pairs (the other copies seen, about 70%). Tile efficiency, on a tie of improving tiles,
  breaks pairs to keep closed and edge waits; a pair can be punged from any of three players and is the eye. Under
  `simple1,none`, where honors score nothing, the same: the reason is the pung, not the tai.
- **Early honors.** Among the 3rd and 4th discards, the network throws a lone honor about 40% of the time,
  `greedy` about 70%: lone number tiles go first (less so under `simple1,none`).
- **Calls late.** A chow or pung that lowers shanten: the network calls 85–93% with 40+ tiles left, about half with
  11–25, about 20% with 10 or fewer (a shorter hand cannot defend). Closed and three away: chows about 60%.
- **Honor pungs** that do not lower shanten: 35–55% from one to three away (a tai under `see`, one danger tile less).
- **Wins.** With 40+ tiles left and three or more waits, the network lets a winning discard go for a self-draw
  (all three pay): 40–70% of the time closed, 20–60% with melds; with 26–40 left only with five or more waits;
  later almost never.
- **Kongs.** Adding the fourth tile of a pung that a run also uses breaks the run (a gap of about 1).

## 5. A rule-based explanation

A prototype (Python, outside the repository: `$WORK/study/py/explain_proto.py`) classifies a decision into one of
about fifteen lessons from what a review already has (the position with each discard's 摸切, the network's figures
for every action, the opponents' readiness and danger, tile efficiency), and writes three parts: the lesson in one
sentence; the signs in this position with their chance from the tables above; a rule of thumb with its figure. No
expected values are quoted. Two lookup tables come with it: the signs to a chance of being ready (43 cells), a
tile's kind to its deal-in rate (55 cells).

Coverage, by loss, of decisions losing at least 0.1: where `greedy` is the player, 92% fall in a lesson with a rule;
where the self-play players' own sampled mistakes are explained, 69% (random mistakes have no pattern: "another
discard at the same distance" is the rest, said plainly or left out).

A language model then is not needed for the explanation itself; if kept for questions after it, it should be given
the lesson, the signs and the tables' figures, and asked to explain in table terms, not the list of actions with
their expected values (which is what a cheap model echoes back).

## 6. In the app and on the page (解說)

Explain.kt and web/src/lib/explain.ts (the same rules, and the two lookup tables embedded) explain a reviewed
decision from what the review has: the analysis (every action's figures, the opponents' chance of being ready), the
seat's view at the decision (its hand, the discards with 摸切 and `after_call`, the melds, the events from the start
for the tiles each opponent let go since its last discard: 過水, safe against it now) and the rules (whether an honor
pung scores, 門清's tai). What is compared: the action played and the best when the played one lost at least a
questionable decision's loss; else, when tile efficiency's choice (`greedyOf`, as `greedy`) would have lost that much
and the best was played, that choice and the best ("well played"; only for a lesson with a rule).

The lessons, in the order they are tried: a win let go for a self-draw / a win to take; a kong that breaks a set;
calls (late in the hand, closed and far, not worth it, an honor pung, a call that is a step closer, a pung that
improves the shape); for discards against the opponent the network thinks most likely ready: a step back to defend,
a safer tile at the same distance, no need to fall back; a pair to keep, which pair to break, honors later; the
choice of a wait (more tiles, more tai, other); tile efficiency (a step further, far fewer improving tiles); a step
back for a better hand; a fine difference. Each gives a title, one or two sentences of this hand, and when there is
one a rule of thumb with its figure from sections 3 and 4; the signs line (discards, melds, 摸切 at the end, a fresh
call, declared) when the lesson is about defense.

The table tips (打牌要訣) show the tables behind them: the share ready by discards and melds (without 摸切 at the
end), how often the network turns to safety against an opponent who looks 60–80% ready by its own distance and the
tiles left, and a tile's chance of dealing in by what it is to a ready opponent.
