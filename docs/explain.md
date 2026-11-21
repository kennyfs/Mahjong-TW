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
terminals first on ties. It ignores value and danger; it calls only when that lowers shanten (a call that keeps it is
passed, whatever its improving tiles), and kongs whenever the shanten is no worse than the best discard's. (Through
2.0.0 it took every added kong, even one that broke a run; the measurements below are of that one. The rules for calls
and kongs were chosen in matches, the simplest of those that played as well: cpp/players/players.cpp, GreedyPlayer.) It already picks the best discard by the two-step measures in 94–95% of decisions, and
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

Two or more 摸切 at the end raise it about 1.5 times (9–11 discards, 2 melds: 47% → 68%; section 7, 1: more where the table is low); a fresh call
hardly changes it once the melds are held fixed (section 7, 2: the meld count is the sign);
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
  11–25, about 20% with 10 or fewer (late, a call raises the chance of dealing in for little chance to win: section 7, 10). Closed and three away: chows about 60%.
- **Calls the network passes on** (a chow or pung, more than 25 tiles left), calling against passing in its own
  figures: one that leaves the hand as far: P(win) 5 points lower, P(deal in) the same (0.2 points higher); one a step
  faster: P(deal in) 3 points higher, P(win) the same. Closed and two or more away: as far, P(win) 3.6 lower; a step
  faster, P(deal in) 1 higher and the concealed hand's tai lost. Fewer tiles in hand cost defense only where the call
  makes the hand faster (it is then played on); otherwise the cost is the win. With an opponent truly ready, a safe
  discard was always there (0 of 400,000 decisions without one); the share of safe kinds falls 2 points from no melds
  to three.
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

## 7. The claims checked

The empirical sentences of the 解說 (`explain_*`), 打牌要訣 (`tips_*`) and 術語表 (`glossary_d_*`), each under the
condition Explain.kt says it in, against self-play (2026-11-20). Data: the first ~20,000 hands of the simple2 self-play
set (the one the tables come from; ~10,000 for the tile-safety checks) and, for "other rules", ~20,000 of the simple1 set; the network's choice is its best
action by q; "real" figures are what happened later in the hand (the seat won, dealt in; an opponent truly ready). The
lessons are re-derived as the code picks them, for two populations: a played (policy-sampled) action that lost at least
0.1 to the best ("mistake"), and tile efficiency's choice where the best was played ("found"). Intervals are binomial
95% intervals; decisions of one hand are correlated, so the true intervals are somewhat wider. Study scripts:
verify_signs.py, verify_safety.py, verify_discards.py, verify_calls.py, verify_slow.py (and the earlier ones named).
Pure rules and definitions are left out; so is 不值得吃碰 (section 4, checked 2026-11-20).

### Holds

| Claim (key) | Said when | Measurement | |
|---|---|---|---|
| The share ready by signs (`explain_signs_ready`, THREAT; tips table TIPS_THREAT) | defense lessons; tips | verify_signs: 2.4 M (opponent, decision) pairs; every cell with n ≥ 300 within 2.7 points of the code | holds |
| 連續摸切的人往往已經接近或正在聽牌 (`tips_river_text`, `glossary_d_tsumogiri`) | always | verify_signs: two or more 摸切 at the end, 6+ discards: one away or ready 84–98%, ready 39–84% (everyone: 62–79%, 21–53%); 3–5 discards: 46% / 11% | holds from the 6th discard |
| 現物 under 1% (`glossary_d_genbutsu`, `tips_safe_text` 現物幾乎安全) | always | verify_safety: pooled by rank 0.1–0.8%; the worst cells (one copy seen, 1/9, 2/8, 4–6) 1.0–1.1% | holds |
| 筋牌 a third to a half of an unseen like tile (`glossary_d_suji`); 筋牌約減半 (`tips_safe_text`) | always | verify_safety: suji (pooled) / non-suji unseen 0.29–0.48 by rank; / non-suji at the same copies seen 0.35–0.60. Both hold on their own comparison (the tips' table puts suji beside 生張, where it is about a third) | holds |
| 半筋 8.2% (`tips_half_note`); between suji and non-suji (`glossary_d_half_suji`) | always | 8.3%; suji 3.9%, non-suji 4–6 11.4% | holds |
| 字牌出現過一張就很安全 (`tips_safe_text`, `glossary_d_seen_tile`) | always | non-genbutsu honor, one copy seen: 0.5–0.7% (unseen 3.4%) | holds |
| 見過越多張越安全 (`glossary_d_seen_tile`) | always | non-suji numbers fall 9–13% → 5–9% → 3–5% from 0 to 2 seen; flat from 2 to 3 | holds |
| 無筋生張的中張最危險 (`tips_safe_text`, `explain_rule_defend`, `glossary_d_non_suji`, `glossary_d_middle`) | always | unseen non-suji 4–6 13.3%, 3/7 12.2%, 2/8 11.4%, 1/9 9.1%, honor 3.4%; to anyone late: 2–8 11–13%, 1/9 9%, honors 3% | holds |
| 後半局打生張特別容易放槍 (`glossary_d_live_tile`) | always | to anyone, fewer than 20 left: unseen number 19.1% vs seen 6.6%, unseen honor 8.6% vs 0.7%; 41+ left 0.6% both | holds |
| 么九早巡常先打掉 (`glossary_d_terminal_honor`) | always | order.py: the 1st–2nd discards are lone honors 78%, lone terminals 6%, lone 2–8 7% | holds |
| 字牌到後半局也常是安全牌 (`explain_honor_later`) | honor-later | to anyone, fewer than 20 left: honors 3.0%, number tiles 9–13% | holds |
| TIPS_FOLD (`tips_fold_text`: 主要看牌牆剩幾張和你自己差幾步) | tips | the second figures of fold_visible.py's 60–80% row, exactly | holds |
| 你自己胡牌的機會不大，放槍的代價比較高 (`explain_defend_step`) | defend-step | verify_discards: found n=4543: the seat wins 0.7% of these hands; the faster tile's P(win) 2.4%, P(deal in) 27% vs 3%. Mistakes n=1030: wins 6.1%, P(win) 6.6% | holds |
| The push rule's figures: 九成已聽牌時一向聽約 2 成退、聽牌不到 1 成；剩不到 10 張才常退 (`explain_rule_push`) | push | fold.py (full set): 90%+ threat: one away 20%, ready 8%; under 10 left: one away 53%, ready 18% | holds (but see its reason below) |
| 先拆嵌張、邊張，留住還碰得到的對子 (`explain_rule_pair`) | pair-keep | verify_discards: a live lone pair and a kanchan/penchan, the network breaks one: kanchan/penchan 97.7% (number pair, n=6070), 97.2% (honor pair, n=8393); simple1 97% / 94% | holds |
| 獨立的字牌、么九對子幾乎不拆 (`explain_rule_pair`) | pair-keep | pairs.py: both other copies unseen 1.4% / 0.7%; one unseen 13% / 11% | holds for an untouched pair; about 1 in 8 with one copy left |
| 拆連在順子的、或死對子 (`explain_rule_pair_which`) | pair-which | pairs.py: joined 40–57%, dead 67–78% (lone dead terminal 41%) | holds |
| 槓了會變慢就不要槓 (`explain_rule_kong`) | kong-breaks | verify_discards: a kong that adds a step: the network kongs 5.5% (n=5974) | holds |
| 剩 10 張以內約 2 成 (`explain_rule_call_late`) | call-late | verify_calls: 23% (closed 17%, melded 25%) | holds |
| 這時自己胡牌的機會也不多 (`explain_call_late`) | call-late | the network passing: P(win) 18% with 11–25 left, 7% with 10 or fewer (25% with 40+); in the call-late mistakes 1.5–17% | holds |
| 讓手牌少了三張，胡牌機會也沒有變大 (`explain_lose_tiles`, rules without 門清) | call-closed, simple1 | verify_calls (simple1): closed, 2+ away, the network passes: a step faster d P(win) −0.4 points (n=10820), no faster −3.6 (n=23364) | holds |
| 一到三向聽碰字牌對子約 3–5 成（不降向聽）；會快一步時幾乎一定碰 (`explain_rule_pon_honor`) | pon-honor | calls.py: 34% / 53–56% / 51–56% at 1 / 2 / 3 away; lowering 97–99.8% | holds (up to 5.5 at 2–3 away) |
| 剩不到 25 張就幾乎都胡 (`explain_rule_win_wait`) | win-wait | verify_calls: 3+ copies, 11–25 left: passes 0.7% | holds |
| 其他規則大致相同 (`tips_source`) | tips | simple1: the threat, safety, fold and call tables within a few points (threat-s1none, safety-s1none, fold-s1none, verify_calls) | holds for the tips' tables; not for honor order (below) |

### Overstated or wrong, with a suggested rewording

Applied (2026-11-20, the user's choices): all fourteen as suggested (the first wording), except 1 (no figures in the
words: the tips' table has them), 9 (「你離聽牌還遠，這時少餵給對手比較要緊」: deal-ins and feeding others' calls alike)
and 11 (without the closed figures: closed and melded are close). 2: the sign 剛吃碰過 is gone from the signs line. 5: a
best a step faster has its own sentence. 10: two sentences by whether the call is a step faster. 13: the lesson
門清先別吃碰 now only from three away (two away the network takes nearly every call that brings it closer, so the rule
and the lesson would disagree); a two-away pass falls to 不值得吃碰. The untestable 聽的牌還在牌牆裡 is reworded too.

1. **`tips_ready_text`** 最近連續摸切兩張以上時，大約再高一半. verify_signs: the share ready with two or more 摸切
   against the table's cell: ×2.4 where the cell is under 30%, ×1.6 at 30–60%, ×1.2 at 60%+ (×1.8 weighted). Understated
   where the table is low. → zh 「…；最近連續摸切兩張以上時更高：表上不到三成的大約翻倍，三到六成的約高一半。」 en "…; with
   the last two or more straight from the draw, higher: about double where the table is under 30%, half as much again at
   30–60%."
2. **`tips_river_text`** …或剛吃碰完的人，往往已經接近或正在聽牌 (and **`explain_sign_called`** as a sign). verify_signs: at
   the same discards and melds a fresh call changes the share ready ×0.9–1.05 up to the 8th discard, ×1.1–1.3 later; over
   3+ discards ready 22.5% vs 21.7% for anyone, one away or ready 56% vs 52%. The meld count is the sign; the call
   itself adds little (section 3's "a fresh call raises it" came from a tree that did not hold the melds fixed). The en
   "…or who just called are often ready" is wrong outright. → zh 「連續摸切的人，往往已經接近或正在聽牌；副露越多也越接近。」
   en "Players throwing draw-discards in a row are often close to ready or ready; so are players with more melds."
   Keep 剛吃碰過 in the signs line only if wanted as context; it does not change the figure shown.
3. **`explain_rule_defend`** …而你還差兩步以上，或牌牆剩不到 20 張時… fold.py / fold_visible.py: two away with 30+ left
   the network steps back 3–6% (safer at all 12–19%); ready with 10–19 left 4%; it turns to safety mostly when two or
   more away with fewer than 30 left (two away, 20–29: 20%, safer at all 29–41%) or one away with fewer than 10 (53%).
   The safety order holds. → zh 「對手看起來已聽牌（副露多、連續摸切），而你還差兩步以上、牌牆剩不到 30 張，或一向聽、剩不到
   10 張時：先打現物，再打見過的字牌、筋牌；無筋生張的中張最危險。」 en "…and you are two or more away with fewer than 30
   tiles left, or one away with fewer than 10: …"
4. **`explain_rule_push`** 自己快聽牌時，胡牌的機會比放槍的風險大. verify_discards, the most likely opponent 90%+ ready:
   ready: the seat then wins 19.5% of those hands, deals in 12.8% (holds); one away: wins 5.5%, deals in 8.2% (the
   network's own P(win) 6.3% vs P(deal in) 11.2%), in every wall bucket. The figures after the colon hold. → zh
   「自己快聽牌時不必急著退：就算對手九成已聽牌，一向聽時神經網路也只有約 2 成會退，聽牌時不到 1 成；牌牆剩不到 10 張才常退。」
   en "Close to ready, don't hurry to fall back: even against …" (the rest unchanged).
5. **`explain_safer_same`** 兩張進張速度差不多. The code says it whenever the safer best is not a step slower. Found
   (n=10358): the improving tiles within ±25% 69%, the safer one with under 0.8× 31%. Mistakes (n=5093): the best a step
   *faster* 7.8%, within ±25% 64%. → zh 「兩張離聽牌一樣遠，就算進張少一點，也優先挑安全的打。」 en "Both leave you as far
   from ready: throw the safe one even if it keeps a few fewer improving tiles." And when the best is faster (code:
   `ab.shanten < ap.shanten`), a sentence of its own, e.g. 「打%1$s又快又安全。」
6. **`explain_pair_even`** …拆掉它常常就缺眼. pair-keep against a two-sided wait: the pair is the hand's only pair in
   11% (found, n=221) / 15% (mistakes, n=61); the improving tiles are within 20% in 80–85% (that part holds). → zh
   「進張數差不多，但對子三家打出來都能碰，也能當眼。」 en "The improving tiles are about even, but a pair can be punged
   from any of three players and serves as the eye."
7. **`explain_rule_honor`** 之後孤張的數牌（尤其中張）比孤張字牌先打. verify_discards, a lone honor and a lone number tile in
   hand, nobody 20% ready, the network throws one of them: the honor 88% (1st–2nd discard), 61% (3rd–4th), 40% (5th–7th).
   By the number tile: against a lone 1/9 the honor goes first 66% / 38% (3rd–4th / 5th–7th), against a lone 3/7 83% / 61%:
   terminals go before honors, 3/7 last, the reverse of 尤其中張. Under simple1 (honors score nothing) the honor still goes
   first 79% / 59%. → zh 「第一、二巡先打用不到的字牌；之後字牌可以留晚一點（碰了有台時更是）：孤張的么九常比孤張字牌先打，孤張的
   三七最晚。」 en "In the first two turns throw useless honors; after that honors can wait (more so when a pung of them scores):
   a lone terminal often goes before a lone honor, a lone 3 or 7 last."
8. **`explain_rule_eff`** 進張一樣時先打字牌、么九. That is tile efficiency's tie-break, but as advice: on a tie with an honor
   among the tied, the network throws the honor 80% (1st–2nd discard), 51% (3rd–4th), 35% (5th–7th) (simple1 92 / 72 / 53%),
   which also contradicts `explain_rule_honor`. → zh 「先保持向聽數不變，再挑進張最多的打。」 en "Keep your distance from
   ready first, then keep the most improving tiles." (or 「進張一樣時，前兩巡先打字牌」).
9. **`explain_slow`** …留下的牌胡了比較大，或之後比較好湊. verify_slow, the decisions the code calls `slow`: the best's
   points a win larger in 0.7% (mistakes, n=670) / 2.2% (found, n=316), P(win) higher in 0.7% / 8.9%; the gain is in
   others' self-draws (the largest part of the gain in 82% / 66%) and deal-ins (17% / 27%); P(deal in) lower in 62% / 80%.
   These are far hands (3+ away on average), late (17–22 left), against a likely-ready opponent (0.72–0.75), where the
   tile thrown is no more dangerous now. → zh 「打%1$s比較快（%2$s），但神經網路寧可打%3$s慢一步（%4$s）：你離聽牌還遠，
   這時少放槍、別送牌讓別人做快比較要緊。」 en "…: you are far from ready, and not dealing in or feeding others matters
   more now."
10. **`explain_call_late`** 別為了快一步%2$s：手牌少了三張就很難防守. The code says it for any call with 25 or fewer left;
    of the call-late mistakes 35% (simple2, n=562) / 42% (simple1) do not lower shanten at all. With an opponent truly
    ready a safe discard was always there (melds_defense: 0 of ~400,000), so 很難防守 is too strong; the network's P(deal
    in) after the call: +7.5 points (a step faster, played on) / +2.8 (no faster). The last clause holds. → by
    shanten: faster: zh 「牌牆只剩 %1$d 張，別為了快一步%2$s：這時自己胡牌的機會不多，%2$s了放槍的機會卻變大。」 no faster: zh
    「牌牆只剩 %1$d 張，%2$s了也沒有變快，放槍的機會卻變大。」 en "Only %1$d tiles left: don't %2$s just to get a step closer:
    your own chance to win is small now, and a call raises your chance of dealing in." / "Only %1$d tiles left: %2$s gets
    you no closer and raises your chance of dealing in."
11. **`explain_rule_call_late`** 40 張以上幾乎都會吃碰，剩 11–25 張約一半. verify_calls: 40+ left 87% (closed 84%, melded
    93%); 11–25 61% (closed 50%, melded 65%). → zh 「會減少向聽數的吃碰，神經網路在牌牆剩 40 張以上約九成會吃碰（門清約八成五），
    剩 11–25 張約六成（門清約一半），剩 10 張以內約 2 成。」 en "…about 90% of the time with 40 or more tiles left (85%
    concealed), about 60% with 11 to 25 (half concealed), about 20% with 10 or fewer."
12. **`explain_rule_call_closed`** 門清時離聽牌還遠就少吃碰；一兩向聽時吃碰完能聽牌或快很多再動作. verify_calls, a lowering
    call, more than 25 left: closed and three away chow 64%, pung a number 85%, an honor 98% (melded 75 / 83 / 96%); two
    away 91% / 97% / 99.7% closed, the same as melded: two away the network calls although the call does not make it
    ready, and closed hands do not call less (under simple1, no 門清 tai, closed three away chows 69%: the tai costs ~5
    points of calling). → zh 「門清三向聽以上時，吃要挑（神經網路約六成會吃）；兩向聽以內會快一步的吃碰幾乎都要。」 en "Concealed
    and three or more away, be choosy with chows (the neural network takes about 60%); two or fewer away, a call that
    brings you closer is almost always right."
13. **`explain_pon_honor`** …也少一張留在手上的危險牌. When the network pungs an honor that does not lower shanten (n=1766),
    its P(deal in) after the pung is *higher* by 1.3 points, lower in only 9% (simple1 13%); its P(win) is higher by 3.7.
    → zh 「%1$s能碰就碰%2$s：就算向聽數不變，碰了胡牌的機會也比較大。」 en "Pung %1$s%2$s: even without getting closer, it
    makes a win likelier."
14. **`explain_rule_win_wait`** 剩 40 張以上約一半. verify_calls (3+ copies of the waits left): 40+ left, the network passes
    45% closed (calls.py full set 57%), 24% melded (29%), 27% in all; most such hands are melded. → zh 「早巡聽得多（3 張以上）
    時，神經網路常過水等自摸（剩 40 張以上門清約一半、有副露約四分之一）；剩不到 25 張就幾乎都胡。」 en "…(with 40 or more tiles
    left about half the time concealed, a quarter with melds); …"

### Untestable with this data, or the network's judgment by construction

- `explain_wait_easier` 聽的牌還在牌牆裡的比較多: the data does not record the wall, and the code says it only when the
  wait's visible count is not larger, so the sentence can contradict the counts on screen. → zh 「打%1$s之後的聽牌比較容易胡：
  神經網路估計別人比較會打出來，或比較好摸到。」
- `explain_wait_other`, `explain_shape_big` (the reasons after 例如/或): guesses at the network's reasons; the figures do
  not single one out.
- `explain_push` 只是讓自己變慢, `explain_win_take`, `explain_call_yes`, `explain_pon_same*`: the network's own comparison
  that triggered the lesson; nothing further to measure.
- `explain_win_wait` 自摸…比一家放槍值錢得多: the scoring rules (three payers), not a statistic.
