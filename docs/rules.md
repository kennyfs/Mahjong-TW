# Rules of Taiwanese mahjong (台灣十六張麻將)

The rules this project implements. Where anything else conflicts with this file, this file wins. Sections 1 to 8
are the play; section 9 is the scoring (台數), section 10 the flower settings and section 11 the rule parameters and
presets. Where the usual sources disagree (the Wikibooks article, the MJ888 table, the 2025 Mahjong Masters
tournament rules, 明星3缺1), the choice is noted, and most of them are parameters.

Terms used throughout:

| Term | Chinese | Meaning |
|---|---|---|
| Kind | 牌種 | One of the 34 tile kinds (or the 8 flowers). |
| Set | 面子 | A chow (順子: three consecutive numbers of one suit) or a pung (刻子: three of a kind). A kong (槓) counts as a pung. |
| Pair | 眼、將 | Two of a kind. |
| Meld | 副露 | A set shown on the table: a chow (吃), a pung (碰), an exposed kong (明槓) or an added kong (加槓); a concealed kong (暗槓) is shown but stays concealed for scoring. |
| Ready | 聽牌 | One tile away from winning. Its waits (聽的牌) are the kinds that complete it. |
| Self-draw | 自摸 | Winning on a tile one draws. |
| Discard win | 胡、放槍 | Winning on another player's discard; the discarder 放槍. |
| Dealer | 莊家 | The player who starts the hand. |
| Streak | 連莊 | How many times in a row the dealer has kept the deal. |
| Base, tai | 底、台 | A win is worth one base plus its tai (section 7). |

## 1. Players and tiles

1. Exactly 4 players.
2. 136 tiles: four of each of 34 kinds: 1 to 9 of characters (萬, `m`), dots (筒, `p`) and bamboo (條, `s`); the
   winds east, south, west and north (東南西北, `1z` to `4z`); and the dragons white, green and red (白發中, `5z` to `7z`).
3. With flowers (section 10), also 8 flowers, one of each: the seasons spring, summer, autumn, winter (春夏秋冬, `1f` to
   `4f`) and the plants plum, orchid, chrysanthemum, bamboo (梅蘭菊竹, `5f` to `8f`). 144 tiles in all.
4. Numbers and winds and dragons together are honors (字牌) and terminals (么九) only where a score item says so.

## 2. Seats, winds and the dealer

1. The seats play in turn order: each player's next player (下家) plays after them, and their previous player (上家)
   before them.
2. The round wind (圈風) is east, south, west or north. Each seat has a seat wind (門風): by default the dealer is east
   and the following seats south, west and north. (At a real table the dice decide where east is; the engine takes
   the east seat as part of a hand's setup, the dealer by default.)
3. Seasons and plants belong to the seat winds in order: east 春 梅, south 夏 蘭, west 秋 菊, north 冬 竹.

## 3. The wall, the deal and flowers

1. The tiles are shuffled into a wall. Ordinary draws take tiles from its front; replacement draws (補牌, after a
   kong or a flower) from its back.
2. Each player is dealt 16 tiles, and the dealer draws one more (開門), so the dealer starts with 17.
3. **Flowers (補花).** A player who holds a flower shows it at once and draws a replacement from the back. After the
   deal, the players show their flowers and draw replacements in turn order starting from the dealer, and repeat
   passes until nobody holds a flower. During play, a flower drawn is shown and replaced at once. Showing flowers is
   automatic: it is never a decision.
4. **The end of the wall.** The last 16 tiles of the wall (`keep`) are never drawn: when only 16 remain, the hand
   ends. A replacement draw (kong or flower) takes a tile from the back, so the 16 kept tiles move forward by one and
   the tile that would have been drawn last is kept instead; there are always 16. A kong may only be declared while
   an ordinary draw is still possible; a flower drawn as the last tile before the mark ends the hand without a
   replacement (an exhaustive draw).

## 4. A turn

1. The dealer starts by discarding (or winning, or declaring a kong). Then, in turn order, each player draws a tile
   and either wins by self-draw, declares a concealed or added kong, or discards one tile.
2. **Concealed kong (暗槓):** four of a kind in the hand. **Added kong (加槓):** the fourth tile added to one's own pung.
   Both are declared on one's own turn after a draw (not right after a chow or a pung), then a replacement is drawn
   and the turn goes on. Several kongs in one turn are allowed. A concealed kong is laid face down: the others see
   that it is a kong, not its kind.
3. **Calls on a discard.** Any other player may claim it to win (胡), to pung (碰, two of the kind in hand) or to make
   an exposed kong (明槓, three in hand, then a replacement draw). Only the discarder's next player may chow (吃) it, with
   two tiles of the same suit that make a chow with it; there may be up to three ways. After a chow or a pung the
   caller discards any tile (no restriction on discarding the same kind). The turn continues from the caller.
4. **Priority:** a win comes before a pung or a kong, which comes before a chow. With `multi_ron=0` (截胡: `simple1`,
   `standard`, `masters`) only one player can win on a discard: the first one in turn order after the discarder. With
   `multi_ron=1` (一炮多響: `simple2`) every player who claims it wins, and the discarder pays each.
5. Players decide on a discard at the same time, without seeing each other's choices.
6. On the last discard (nothing left to draw) only a win may be claimed.

## 5. Winning

1. A winning hand is 17 tiles: the melds and the hand together form five sets and one pair. There are no other
   winning shapes (no seven pairs, no thirteen orphans, no 嚦咕嚦咕) except the flower wins of section 10.
2. A player may win on their own draw (including a replacement draw), on another player's discard, or on the tile of
   another player's added kong (搶槓, robbing the kong; it counts as that player's discard). A concealed kong cannot
   be robbed.
3. **No furiten.** A player may win on a kind they discarded earlier.
4. **Missed win (過水).** A player who could win and does not (by passing on a discard or a robbed kong, or by not
   declaring a self-drawn win) cannot win, by discard or by self-draw, until they next discard a tile or declare a
   concealed or added kong.
5. A win needs at least `min_tai` tai (default 0: a win without tai, 屁胡, is allowed). Winning is always optional.
6. A hand without a winner when the wall ends (3.4) is an exhaustive draw (流局). Nobody pays.

## 6. The hand's end and the next dealer

1. If the dealer wins, the dealer keeps the deal and the streak grows by one.
2. If another player wins, the next seat becomes the dealer and the streak returns to 0.
3. After an exhaustive draw the dealer keeps the deal. With `draw_streak=1` (default) the streak grows by one; with
   `draw_streak=0` it stays the same.
4. There is no upper limit on the streak.
5. A round (圈) ends when every seat has dealt once; the round wind then moves on (east, south, west, north). Four
   rounds make one game (一將). Training and evaluation play single hands with random round winds, dealers and streaks;
   the objective is the expected result of each hand.

## 7. Payments

A win is worth `base + tai` per payer, where `base` is the base (底) measured in tai (2 in `simple1`, 1 in the
other presets: 底 and 台 equal)
and `tai` is the hand's tai (section 9), plus the dealer's tai when the dealer is involved:

- **Self-draw:** each of the other three pays.
- **Discard (or robbed kong):** only the discarder pays.
- **Dealer's tai (莊家台, 連莊拉莊):** `dealer + streak × N`, where N is the dealer's streak (`simple1` and `simple2`: 1 + N, 連一拉一 is
  2; `standard` and `masters`: 1 + 2N, 連一拉一 is 3, 連二拉二 is 5). It is added to every payment between the dealer and another player: when the dealer wins, each payer
  pays it; when another player wins, the dealer pays it if the dealer pays.

Points are zero-sum. Results are reported in tai, so `base` only changes how a base compares with a tai.

## 8. Not implemented

Declared ready hands (天聽, 地聽/MIGI, 宣告聽牌), 嚦咕嚦咕, special draws (four kongs, four winds), penalties (詐胡,
相公), dora and other online-only items (寶牌, 一發, 斷么九, …).

## 9. Scoring: tai (台數)

The hand's tai is the sum of the items below that apply, under the rules' values (0 turns an item off). Every way of
reading the hand as five sets and a pair is tried, with the winning tile in any set or the pair it can complete, and
the reading with the most tai counts. The dealer's tai of section 7 is not part of the hand's tai.

The columns are the tai presets (section 11), listed in this order:

- `simple1` (簡單1, the default; southern Taiwan, usually without flowers; confirmed 2026-11-10): only dealer,
  streak (1 + N) and self-draw. Base 2 tai (底:台 = 2:1), 截胡.
- `simple2` (簡單2; the user's family rules, confirmed 2026-11-10): dealer, streak (1 + N), concealed, self-draw (a
  concealed self-draw is 3: 門清一摸三), single wait (strict) and pure suit (1). Base 1 tai, 一炮多響.
- Neither has flower wins or tai for a win on a replacement (槓上開花: only its self-draw counts), robbing a kong
  (allowed, no tai) and so on; with `see` each flower is one tai.
- `standard` (MJ888 and Wikibooks, majority where they differ) and `masters` (Mahjong Masters 2025).

| Item | Parameter | simple1 | simple2 | standard | masters | Definition |
|---|---|---:|---:|---:|---:|---|
| 莊家 Dealer | `dealer` | 1 | 1 | 1 | 1 | Section 7. |
| 連莊拉莊 Streak | `streak` | 1 | 1 | 2 | 2 | Per streak count, section 7. |
| 門清 Concealed | `menqing` | 0 | 1 | 1 | 1 | No chow, pung or exposed or added kong (a concealed kong is fine). |
| 自摸 Self-draw | `zimo` | 1 | 1 | 1 | 1 | Winning on one's own draw. |
| 不求人 Concealed self-draw bonus | `menqing_zimo` | 0 | 1 | 1 | 1 | Extra when both concealed and self-draw (門清自摸 = 1 + 1 + this = 3, 門清一摸三). |
| 獨聽 Single wait | `duting` | 0 | 1 | 1 | 1 | Edge, closed or pair wait (邊張, 中洞, 單吊), see 9.1. |
| 平胡 All chows | `pinghu` | 0 | 0 | 2 | 2 | Five chows, a number pair, no honors and no flowers anywhere, a discard win, a two-sided wait and not a single wait (9.1). May combine with concealed. |
| 全求人 All melded | `quanqiu` | 0 | 0 | 2 | 2 | All five sets are melds (chow, pung, exposed or added kong) and the win is on a discard (single wait on the pair). |
| 全求人 adds single wait | `quanqiu_duting` | 0 | 0 | 0 | 1 | Whether 全求人 also scores 獨聽 (masters: 2 + 1 = 3). |
| 半求 All melded, self-drawn | `banqiu` | 0 | 0 | 0 | 1 | As 全求人 but self-drawn (it also scores self-draw and single wait). |
| 圈風 Round wind | `round_wind` | 0 | 0 | 1 | 1 | A pung of the round wind. |
| 門風 Seat wind | `seat_wind` | 0 | 0 | 1 | 1 | A pung of one's seat wind (both when they are the same wind). |
| 三元 Dragon pung | `dragon` | 0 | 0 | 1 | 1 | Per pung of a dragon. |
| 小三元 Little dragons | `xiaosanyuan` | 0 | 0 | 4 | 4 | Two dragon pungs and a dragon pair; replaces their 三元. |
| 大三元 Big dragons | `dasanyuan` | 0 | 0 | 8 | 8 | Three dragon pungs; replaces their 三元. |
| 小四喜 Little winds | `xiaosixi` | 0 | 0 | 8 | 8 | Three wind pungs and a wind pair; the wind items still count. |
| 大四喜 Big winds | `dasixi` | 0 | 0 | 16 | 16 | Four wind pungs. |
| 大四喜 keeps wind items | `dasixi_winds` | 0 | 0 | 0 | 1 | Whether 圈風 and 門風 still count with 大四喜. |
| 碰碰胡 All pungs | `pengpenghu` | 0 | 0 | 4 | 4 | Five pungs (or kongs). |
| 三暗刻 Three concealed pungs | `sananke` | 0 | 0 | 2 | 2 | Concealed pungs (in the hand, or concealed kongs). A pung completed by a discard is not concealed. |
| 四暗刻 Four concealed pungs | `sianke` | 0 | 0 | 5 | 5 | Replaces 三暗刻. |
| 五暗刻 Five concealed pungs | `wuanke` | 0 | 0 | 8 | 8 | Replaces 三暗刻 and 四暗刻. |
| 混一色 Half flush | `hunyise` | 0 | 0 | 4 | 4 | One number suit and honors. |
| 清一色 Pure suit | `qingyise` | 0 | 1 | 8 | 8 | One number suit, no honors. |
| 字一色 All honors | `ziyise` | 0 | 0 | 8 | 16 | Only honors (other items such as pungs and winds still count). |
| 搶槓 Robbing a kong | `qianggang` | 0 | 0 | 1 | 1 | Winning on another player's added kong. |
| 槓上開花 Win on a replacement | `gangshang` | 0 | 0 | 1 | 1 | Self-drawn on a replacement after a flower, a concealed or an added kong (not an exposed kong). |
| 海底撈月 Last tile | `haidi` | 0 | 0 | 1 | 1 | Self-drawn on the last tile; not together with 槓上開花. |
| 河底撈魚 Last discard | `hedi` | 0 | 0 | 1 | 1 | Winning on the last discard. |
| 天胡 Heavenly hand | `tianhu` | 0 | 0 | 24 | 16 | The dealer wins before their first discard (without a kong). Replaces concealed and self-draw. |
| 地胡 Earthly hand | `dihu` | 0 | 0 | 16 | 16 | Another player self-draws on their first draw, with no chow, pung or kong so far. Replaces concealed and self-draw. |
| 人胡 Human hand | `renhu` | 0 | 0 | 0 | 16 | Another player wins on a discard before their first discard, with no chow, pung or kong so far. Replaces concealed. |
| 見風見台 Any wind pung | `any_wind` | 0 | 0 | 0 | 0 | Per wind pung, instead of 圈風 and 門風 (明星3缺1's 見花見字 option). |
| 無字無花 No honors, no flowers | `no_honor_flower` | 0 | 0 | 0 | 0 | (見花見字 option.) |
| 明槓 Kong | `kong_tai` | 0 | 0 | 0 | 0 | Per exposed or added kong (見花見字 option). |
| 暗槓 Concealed kong | `ankong_tai` | 0 | 0 | 0 | 0 | Per concealed kong (見花見字 option). |

### 9.1 Single wait (獨聽)

By default (`duting_loose=0`) a win is a single wait when the hand, before the winning tile, waits on exactly one kind
(kinds of which the player already holds all four do not count; kinds that are all visible elsewhere still count,
so a two-sided wait whose other end is dead is not a single wait). For example 1m 2m waiting on 3m, 1p 3p waiting on 2p
and a lone 5s waiting on 5s are single waits; 3m 5m 5m 5m waiting on 3m or 4m is not.

With `duting_loose=1` (「抓得出來就有台」), a win is a single wait when some reading of the hand puts the winning tile
in an edge, closed or pair wait, even if the hand waited on more kinds: 3m 5m 5m 5m winning on 3m reads as 555m and a
pair wait on 3m.

All chows (平胡) needs a reading in which the winning tile completes a two-sided wait (two consecutive numbers from 2–3
to 7–8), and, by default, a hand that is not a single wait.

## 10. Flower settings

The flower settings are separate from the tai preset. In the order the programs list them:

| Preset | `flowers` | Tiles | Flower tai |
|---|---|---|---|
| `none` (無花) | 0 | 136 | None. |
| `see` (見花見台) | 1 | 144 | `flower_tai` (1) per flower. |
| `seat` (正花) | 2 | 144 | `flower_tai` (1) per flower of one's own seat wind (section 2.3), and `flower_set` (2) per complete set of seasons or plants (花槓). |

With flowers, two flower wins end the hand at once, automatically, when the flowers are shown, and score only their
value (plus the dealer's tai and the base):

- **八仙過海 (`eight_flowers`, 8):** one player holds all 8 flowers. It counts as a self-draw: the other three pay.
- **七搶一 (`seven_rob_one`, 8):** one player holds 7 flowers and another the eighth. The holder of the 7 wins, and the
  holder of the eighth pays as a discarder.

A value of 0 turns the flower win off: they are part of the tai preset (0 in `simple1` and `simple2`, 8 in
`standard` and `masters`), so `see` with the simple presets is just one tai per flower. `flower_set` is 0 in `see`. (No flowers count toward all chows: any flower
rules out 平胡.)

## 11. Rule parameters and presets

A rules spec is a comma-separated list of preset names and `name=value` overrides, applied left to right, for example
`simple1,none` (the default), `standard,seat` or `simple2,see,base=2,draw_streak=0`. Tai presets: `simple1`,
`simple2`, `standard`, `masters` (section 9; they also set the flower wins, `base` and `multi_ron`). Flower presets: `none`, `see`, `seat`
(section 10: the flower mode, `flower_tai` and `flower_set`). The other parameters:

| Parameter | Default | Meaning |
|---|---:|---|
| `base` | 2 | The base (底) in tai (2 in `simple1`, 1 in the other tai presets). |
| `draw_streak` | 1 | 1: an exhaustive draw adds to the dealer's streak; 0: it keeps it. |
| `multi_ron` | 0 | 1: every player who claims a discard wins on it (1 in `simple2`). |
| `keep` | 16 | Tiles left in the wall when it ends. |
| `min_tai` | 0 | The least tai a win needs. |
| `duting_loose` | 0 | Section 9.1. |
