# The app and the web page

The first prototype of the Android app (`android/`), the web page (`web/`) and the battle server (`server/`,
`protocol/`), made in one session on branch `ui` (2026-11-08 and 09) from `docs/ui-handoff.md`. This file is the
handoff to whoever continues: what exists, how it fits together, what was decided on the way and why, what the user
said, and what is missing. The other docs: [android/README.md](../android/README.md), [web/README.md](../web/README.md),
[docs/battle.md](battle.md), [docs/gui-engine.md](gui-engine.md).

## What exists

Both the app and the page have the same five screens, in Traditional Chinese (Taiwan):

| Screen | What |
|---|---|
| 練習 Practice | Against three bots (the network by default, or the engine's `greedy`, tile efficiency), for one hand, a round or a game; the result of each hand (winner, hand, tai items, payments, next dealer, the network's summary of the player's play) with its review; the game's totals. The analysis during play only on request (設定 → 練習時顯示分析): such a hand counts for nothing (not in the totals or the records' statistics; its record is marked 提示). |
| 對戰 Battles | A table game (1 to 4 people, bots in the empty seats) or a same-tiles contest, on a battle server; the app also with phones nearby. The entry asks first only a new room or joining one (the app: online or nearby too); a new room then offers its kinds, joining asks the PIN. Lobby with a 6-digit PIN, the host's settings, the table with a clock per decision, each hand's result with the standings, the final standings and the player's hands. docs/battle.md. |
| 分析 Analysis | A position set up by hand, or a step of a review taken there, with the network's figures for each legal action as it changes, and the tables of draws and of claims (docs/analysis.md section 4). |
| 紀錄 Records | Practice hands, table battles and contests (battles as groups of hands); favorites; statistics of the network's reviews; each hand opens its review: the graph of the value, the decisions worth a look, each decision's verdict and figures, everyone's tiles at a tap. |
| 設定 Settings | Help (the rules explained, the tour), rules (tai preset, flower preset, base, every parameter with its explanation), practice's length, the bots and their pace, the level of mistakes, 聽牌提示 (off by default: a dot over each discard that leaves the hand ready, on the player's turn), 音效 (off, soft, full), text size, colors (table green, minimal, or 跟隨桌布: the wallpaper's, Android 12 and later), dark mode, about (the version and the network's id). |

- **Help** (0.2.0, as Thirteen's but not a copy): 規則說明, the rules explained in eight pages (`Guide.svelte`,
  `ui/GuideScreen.kt`): the rules to explain chosen first (the settings' at the start), then the goal with a winning
  hand, the tiles (and the flowers), a turn and the calls, waits, tai and payments, a quiz (which tiles a ready hand
  waits for; the answers are the engine's), and the way out: practice, under the rules explained when they differ
  from the settings', or the tour. Where the flowers and the tai come up the same choice is there again (換換看), so a
  change shows at once in the words and the tiles; every value (base, dealer, each tai) is the engine's. 導覽, the
  tour, lights the hand, the top of practice and each tab in turn with a few words (`Tour.svelte`, `ui/Tour.kt`). The
  first start shows a welcome card once: the rules explained, the tour, or play.
- **0.3.0** (the user's requests after 0.2.0, 2026-11-11; app and page alike):
  - The table: the round, the wall and the dealer's streak sit in the top-left corner and the screen's buttons
    (rules, new game; a battle's rules and leave) in the top-right; the box in the middle is gone on a wide table
    (a slim one stays upright), so the rivers are larger: a phone sideways about 31 → 35 px a tile, upright 32 → 35.
    A river holds 16 tiles at full size across and 15 at the sides (2 rows of 8; 3 columns), a longer one shrinks.
  - The turn: when the player must discard without a drawn tile (the dealer's first, after a claim) the last tile
    stands apart like a drawn one and the hand has a pulsing yellow frame (also with a drawn tile). (0.5.0: no
    longer set apart, at the user's word; the frame alone.)
  - A claim: the discard to claim is enlarged and pulsing in its river, and lit in the claim buttons (tiles 0.8 of the
    hand's). A chow shows the claimed tile in the middle.
  - Sideways, the player's flowers sit above the name plate, and the player's melds shrink when the row is short of
    room, never the hand (a real phone's screenshot of 0.2.0 showed the drawn tile squeezed small).
  - The end of a hand: the table stays 2.2 s (everyone's tiles, 胡／自摸, 放槍 over the discarder; also at a draw)
    before the result; a tap skips. Records are written at once.
  - The review: the expected value at every step (目前期望: at the player's decision its value; after it, the action
    taken's; before the first, the starting value; at the end the result); at an opponent's step the list names the
    opponent; the action taken is first, it and the best are filled, both tags when they are the same; each row's
    figure is the action's expected value, with the loss against the best before it in small type when it counts; an
    i button explains the figures. Its end offers 下一局 (practice: the next hand; records: the next of the group).
    The back button is in the side column's top left (it covered the left player's name on a computer); the app's
    side column sideways has two rows of buttons at its foot: the big jumps above, the steps below.
  - The rules page never changes its height (the changed mark keeps its room; reset is greyed); each parameter's i
    opens a dialog.
  - No money in any wording (points, 分); separators (．) between figures (連 2 拉 2．3 台).
  - The app's practice tab has the page's tile icon.

- **The table** is laid out from the room it has (`web/src/lib/layout.ts`, ported as `android/.../ui/TableLayout.kt`,
  the same arithmetic): wide (a phone sideways, a tablet, a computer) or tall (a phone upright). Landscape is the
  main layout; portrait works; a computer gets a side panel with the scores and keyboard play (web).
- **The engine** is `cpp/` behind `GuiEngine` (docs/gui-engine.md), stateless (a hand is its record, replayed per
  request): JNI in the app, WebAssembly in a worker on the page, `mahjong gui` processes on the server. The network
  (an actor's `.onnx`, iteration 50 of stage 1 in 0.1.0) runs in ONNX Runtime, linked into the JNI library in the
  app and built into the WebAssembly on the page; the server has none yet (its bots are `greedy`).
- **Shared between the two**: the strings (`android/app/src/main/res/values/strings.xml`, which the page reads through
  `web/scripts/strings.mjs`), the tile art (`art/make_tiles.py` writes `TileArt.kt` and `tile-art.ts`), the table's
  layout arithmetic, the records' JSON form, the battle protocol (Kotlin in `protocol/`, a TypeScript port in
  `web/src/lib/battle.svelte.ts`).

- **0.3.1** (2026-11-12; `analysis` merged into `master`, and what `master` had changed since): `simple2` counts
  every pung of a wind or of a dragon (the preset's description says so); declared ready hands (天聽, 地聽,
  docs/rules.md 5.1) under `standard` and `masters`: the action 宣告聽牌 among the player's choices on the first turn
  (`ClaimBar`, key T on a computer), after which only the discards that keep the hand ready can be made and later
  only the tile drawn; 天聽 or 地聽 is called out over the player and stays on the name plate; the two items have
  their help texts in the rules. The position editor does not set a declared hand.

- **0.3.2** (2026-11-12, the user's requests that night; app and page alike):
  - The words over a player (吃, 碰, 槓, 天聽, 胡, 自摸, 放槍, 流局) stay two seconds, and a tap anywhere (a key on a
    computer) lets them go at once; a practice game's computer players wait for them (`web/src/lib/callouts.ts`,
    `Callouts.kt`: the table says when words went up, the practice loop waits for them after its usual pace). A
    flower's words stay brief and nothing waits for them. One at a time over a player; another hand's words go.
  - They are set in colored blocks, a character each, about twice a tile of the hand (two characters: smaller
    blocks), laid down askew: a call's gold, a win's vermilion and larger, the one who dealt in dark; under a win's
    and under 放槍 the player's name (beside them on a wide table). Tried and dropped that night: a seal's plate with
    a burst behind a win's (two shapes), a badge with a burst's edge, and tiles of the table's own (they read as
    tiles in play). On a tall table the others' words stand under their strip (the tiles just called stay in
    sight) and the player's own over their name plate's row (not over their river, where the tile dealt in is).
  - A declared ready hand: on the player's turn the tiles that may not be discarded are darker (after declaring,
    all but those that keep the hand ready; later all but the one drawn).
  - The result: under each player's points, what they are made of (`底 2＋1 台`: the bases, and the tai with the
    dealer's where they count; the result's `base_delta` and `tai_delta`).
  - A review's list: a row that loses a grade's worth against the best is tinted in the grade's color (yellow,
    orange, red), the one taken more strongly (it was filled as the best is).
  - The engine: a declared hand with no legal discard by the rule (it cannot happen in play) may discard anything
    rather than have no action.

- **0.4.0** (2026-11-12, after a second opinion on the screens; app and page alike). The user had another model
  (Gemini) look at 138 screenshots of 0.3.2 (`$WORK/ui-review-20261112/`: `prompt.md`, its
  answer `comment.md`, shapes it drew in `svg-examples.md`) and asked for what it found to be taken up. Each point
  was checked against the screenshots first; nearly all held.
  - **The words over the players** (its P1 and P5). They covered what the moment was about: the tile dealt in, the
    meld just made, the winner's hand. Now each stands where nothing of that moment lies: a call's and a win's over
    the river of the player who made them (the tiles called are in the melds and in another player's river; the
    winner's hand is at their seat), 放槍 over the tiles of the one who dealt in, clear of every river (the tile
    dealt is in theirs: a tall table's others at the top of their columns, a wide table's beside the rivers and
    along the top, the player's own over the foot of their hand), 流局 in the middle. A player's own words over
    their river go when they discard into it. And the shape: the blocks, a character each, still read as large
    tiles (upright rectangles with a tile's edge under them), so a word is now on one plaque with cut corners (no
    tile has them), a lip under it and a fine line inside its edge, a little askew (2 degrees); the name under a
    win's and under 放槍 is a tab over the plaque's lower edge; a flower's are a small jade plaque of the same
    shape. Gold, vermilion and dark as before. Its advice to make the words a fifth smaller was not taken as said:
    the user asked for about twice a tile of the hand, and the plaque of one character is that wide (the character
    itself is smaller than it was in its block).
  - **The result's tai** (P2): the total said `共 0 台` beside `莊家 1 台`, and the payments under it said `1 台`.
    The total now says what every payer pays in tai: the dealer's tai is in it when every payment has it (a win by
    the dealer, or on the dealer's discard); for another's self-drawn win, which only the dealer pays more for, it
    follows the total as `＋莊家 N 台（莊家多付）`. The winner's concealed tiles keep together on a line of their
    own where they do not fit after the melds (they were torn across two lines).
  - **A review's hand** (P3, P9): a tile chosen there (to see its figures) is not lifted (its tag ran into the row
    above, or into the line of figures); its face and frame say it is chosen. The tags of the other discards are
    dark enough to read on the light pages too.
  - **The line of a tile's figures over the hand** (P4): not shown where the list is beside the table (its row
    there is lit, and comes into sight); on a wide table without the list it keeps between the side players, clear
    of the player's own name plate.
  - **Shown hands on a tall table** (P6): the others' strip, grown to show their hands, keeps 14 px clear of the
    rivers under it, and a shown hand's rack is a darker tray with a light edge (it was a lighter strip that the
    rivers' tiles ran into).
  - **A phone held sideways** (P7): the others' concealed tiles are a count on their name plates there, as on an
    upright phone (a table lower than 500 px): three rows of backs took the side players' room from their melds.
    A name plate's note (天聽, a review's 聽 18%) comes last and alone goes to a second line; the score stays
    beside the name. Where a side column is too narrow for the name, the count and the score, the count and the
    score go under the name together (the name was cut to `電…`).
  - **The count of mistakes** (P8): `疑問手 1` over a hand whose one mistake was a 大失誤 (the count was named by
    the level's lowest grade). Each grade is now counted by its own name from the worst down (`大失誤 1・疑問手
    2`); the records' average a hand says `失誤以上`.
  - **A decision's list** (P9): a bar's parts left of its line (where the action is worse than the best) are
    hatched, those right of it filled, and the legend says so: a long green bar on a blunder's row read as a good
    chance of winning. With rows of numbers alone, the line over the list says what the figure at each row's right
    is (it held the help button alone).
  - **The rules' parameters** (P10): the flowers' mode as a number (0, 1, 2) is not listed; the three cards above
    choose it.
  - **The result's buttons** stay in sight where the card is taller than its room (a phone held sideways in a
    review, a hand of melds with its tai and the payments' parts): the whole card scrolled, and the buttons were
    under the screen's edge (seen on the emulator; a tap meant for 下一局 went nowhere). In the app the winner's
    tiles are also a little smaller where that keeps them on one line (down to 22 dp).
  - **Page titles** on a wide window keep to the page's column (the records, the settings, the rules): the title
    and its button stood in the window's far corners over a centered column.
  - Not taken: fading the edges of scrolling pages (the screenshots caught lists mid-scroll); a wider side column
    for shown hands on a computer (the room it would take is the rivers', which were nearly empty in the
    screenshot); `第 N 局` moved into the table's box (the bar over an upright table has the rules' button and the
    new game's, so it stays). Its note that a result's screenshot showed no sheet was the script's timing.
  - **The manual pace** (the user's request; the settings' pace has 手動 beside 慢, 中, 快). The computer players
    do nothing until the player presses 下一步 (Space or Enter on a computer). One press is one thing that shows
    on the table, as the table settles it: a discard (with the draw before it), a call, a declaration, a win. What
    the three answer to a discard is settled together by the rules' precedence before any of it shows, so a chow
    that a pung or a win comes before never shows: the next step is worked out ahead (the engine's `advance` by one
    decision point, which the seed makes the same whenever it is asked) and held until the press. Only what shows
    waits: when all three let a discard go, the game goes on to the next turn by itself (a button there would say
    that one of them could have called), so the button means only that somebody's turn or call comes next. The
    player's own call is answered at once, also when another's comes before it; the table then says so for a
    few seconds (`你的吃沒有成立：電腦 1 的碰優先`), at every pace. The words of a call stay until the next tap at
    this pace, and so does the table at a hand's end. The button: on a tall table at the end of the hand's first
    row (no tile's place while it is not the player's turn, and not where the drawn tile comes, so that a tap too
    many discards nothing); on a wide one in the corner over the hand's right end. (Further in, left of the right
    player's column, it covered the fifth row of that player's discards in a long hand; in the corner the right
    player's hand shown at the end can reach it on a low table, until the tap it waits for.)
- **0.5.0** (2026-11-12 afternoon, the user's bugs and requests; app and page alike):
  - **A seat's second meld went missing** on an upright phone (the user's screenshots: `10 張` beside one meld, so
    13 tiles; with a third meld all three showed). The others' columns over the rivers were sized for a name plate
    of 40 dp and the meld lines, without the column's padding and the gap under the plate (12 dp); the app's
    melds are a `FlowRow`, which leaves out an item that does not fit the height it is given, so the second line
    vanished (three melds shrank to two a line and fit). The strip now has the room it needs, and the melds are
    also fitted to the height under the plate as measured, so a taller plate (a larger font) makes them smaller
    rather than lost. The page had the same arithmetic (its lines overflowed rather than vanished): fixed alike.
    Checked by `mahjong-files/bin/check_melds.py`, which plays practice hands on the emulator and at every look
    adds each seat's count to the meld tiles in its column (test tag `seat_N`): never under 16.
  - **No tile set apart when none was drawn** (the dealer's first discard, the discard after a call): the hand's
    last tile no longer moves right as if drawn; the pulsing frame says whose turn it is.
  - **The records' end, for the developer** (as Thirteen's): every record as one JSON file (`mahjong-records-
    DATE.json`: `app`, `version`, `time`, `hands`, `battles`, each hand with its whole `game` record, so a hand
    played can be replayed exactly), through the system's share sheet (分享; the app by a FileProvider copy in the
    cache, the page where the browser can share files) or saved (匯出). And 分數歸零: practice's game starts
    again from its first hand (東風圈第 1 局) with the totals at zero; the records stay.
  - The network is no longer called a beta (測試版) in the settings, the about text and the analysis' note.

- **0.6.0** (2026-11-12 evening, the user's requests; app and page alike):
  - **A call's or a win's moment** over the whole window (CallFx; the advanced settings could keep to the words, till they went after 0.8.0): a
    dark veil, two black wedges from opposite corners, a band askew with speed lines over its whole width, the word
    on its plaque and whose it is. A chow or a pung: the tile called lifts off the river where it lies, drops into
    the gap between the caller's own tiles, and the meld flies to its exact place among the caller's melds, scaled
    to their size (kept out of sight there till it lands). A win: the winner's hand with the winning tile dropping
    into the gap at its end (from the river of who dealt in; a self-draw from above, lit), a light over it, and
    who dealt in under the name. It lasts the words' time of the pace (slow 2.0 s, normal 1.5, fast 1.0; 0.6.1:
    three and a half times that, 7.0 / 5.25 / 3.5 s, the user finding it too short); a tap lets it go. The table's own words for it are not shown under it (at the manual pace they stay for after it). A
    hand's result waits for it, and the table holds 1.2 s after it (2.2 s without one). The app draws it over every
    screen and bar (in the table alone it left the felt's cutout margin and the controls beside it uncovered).
    A computer player's discard after its own chow or pung follows the moment (or the words) at once, without
    the pace of a turn (the user: the moment already shows what was taken; Claims.justCalled, lib/claims.ts).
  - **Claims taken first**: when the player could claim another's tile, the computer players that may claim it
    decide first (lib/claims.ts, Claims.kt); when one of their claims comes before every one the player could
    make, it is made at once without the player's buttons, after the discard has stayed a while (slow 2.4 s,
    normal 1.6, fast 0.9; the manual pace: the player's tap), its moment says 搶碰 / 搶胡 / 搶明槓 (搶 in its own
    color) and the table says `電腦 1 搶碰（你本來可以吃）` after it. A computer player's claim taken by another's
    is shown as an ordinary one (the computer players do not learn of claims that did not stand either). Claims
    already in the record (a branch from the player's own decision there) are weighed the same.
  - **The review's branch** (分支, from one of the player's decisions): the hand played on with the computer players
    moving by themselves; 上一步 / 下一步 within it, a choice changed drops what came after; 重洗看不到的牌 deals the
    unseen tiles again (the engine's `base` record, docs/gui-engine.md); the result without the next dealer; not
    recorded. The app: beside the table in a column when sideways, under it upright.
  - The lists' green and red figures bold and darker in the light theme; the felt strip's words light, its figures
    a fixed light green and red; of （自摸 3.7%・0.9 台） only the chance is colored. The records' overview: words,
    figure, then a bar in a loss's color. The computer players play the network's policy.
  - Words of a call by a player with an empty river (a pung of the game's first discard) stood at the screen's
    top-left corner in the app (an empty river had no bounds): placed by the river's own place now.
- **0.7.0** (2026-11-13; the moments as the user picked them with the tuning tool of the branch `fx-dev`, which keeps
  the tool, `?tune`, and the looks not picked; its defaults are these). Someone found the whole-screen moment of
  every chow and pung tiresome:
  - **The pace in one file for the app and the page**: `android/app/src/main/res/raw/timing.json` (the page copies
    it, scripts/strings.mjs, and reads it in lib/timing.ts). Every time at the normal pace in ms; the slow and the
    fast pace are factors of it (`speed`: 1.3 and 0.75), except the times in `fixed` (a notice's: reading); the
    manual pace is the normal one. `style` names the looks picked (the branch's tool reads it).
  - **Calls by the caller** (SmallFx): a slanted dark panel over the caller's river, kept inside the table, the
    word large at its foot; it slides in from the left by part of its width, slowing (`call_in`); the tile called
    lifts off the river into the gap between the caller's tiles (`call_drop`); it stays (`call_hold`); the meld
    flies to its place among the caller's melds (`call_out`). Kongs too (a concealed one of another's in backs; an
    added one's fourth tile from above).
  - **A computer player's pace**: `bot_turn` before its own turn, counted from the end of a moment or of the words;
    `bot_after_call` after its own chow or pung; `bot_claim` before its call on a discard, and only then (a pause
    whenever someone could call told the player so: lib/claims.ts othersCall); `rob_show`, the discard's stay before
    a claim taken from the player.
  - **A hand's end**: another's deal-in small by them (`dealin_*`), the player's own over the whole screen (the
    table losing its color, dark at the top and clearer downwards, 放槍 sinking in with the tile: SadFx, `sad`);
    the hands shown; a pause (`reveal`); each winner in turn over the whole screen: a win on a discard, a
    self-draw, a flower win (the flowers; 七搶一's eighth from where it lies). An exhaustive draw: a pause
    (`show_wait`), the hands shown, a pause, its moment. 天聽 and 地聽 over the whole screen, without a pause. The
    player's own win: gold, with turning rays and sparks; another's self-draw or win on the player's discard: steel
    blue, quieter; another's win on another's discard, and a draw: graphite (the player loses nothing).
  - **A result's moment stops at 78%** (the light has run over the hand), its plaque and tiles still, the rays and
    speed lines going on, till a tap; a tap before that lets all of the hand's end go. The result then comes at
    once; with no moment (words alone) the table waits for a tap. ≫ 按一下以繼續 at the bottom right once the
    result's moment starts. Practice, a review's branch, a table battle (its 90 s to be ready run from the hand's
    end, as before). A review's hand's end: small by the player, × `review_end` (0.5); the player's own deal-in
    shows its tile; the winner's name clear of the word.
  - **The hand** stands to the left, the melds at the right end (the first at the far right, each after it to its
    left: none moves when another comes). The player's own discard moves from the hand to the river
    (`discard_move`); once it is halfway there the hand closes its gap (`hand_move`: the drawn tile to its place, the
    tiles between a tile over; the user's, after trying it). Moves from a river (a call's tile, a win's) are slow at
    both ends. A full-screen moment's tiles in
    two lines when one would make them smaller than 34 px. 補花 by its seat's tiles (its flowers come in as before).
  - Fixed: a practice game (or a review's branch) dealt while the last one's loop waited stalled (after 重新開始:
    the dealer, a computer player, never played); with two moments in a row, what waited for them went on at the
    first's end (the table told the moments over between them); a moment's words came after it at the manual pace.
  - **The app** alike: Timing.kt reads the pace (Speed keeps no times of its own). ui/Moments.kt: the moments
    (FxHost: the queue, the stop at 78% and the tap; FxOverlay draws them over the whole window, and ≫
    按一下以繼續 at the table's bottom right), ui/SmallFx.kt, ui/SadFx.kt (the table's color taken by a Saturation
    blend over the window where Android 10 and up can, and darker), ui/CallFx.kt; TableView turns the events into
    words and moments (TableEvents) and times them (MomentsClock); ui/HandMotion.kt moves the hand (armed as the
    view with the discard is composed, before it is laid out: the tiles' places before are still known; each tile and
    the river's new tile move as they are placed); while the discard flies, the parts of the table that hold the
    river are drawn over the hand (overWhileFlying), and the hand's moving tiles over the ones that stay, as on the
    page. With the system's animations off the words alone (as the page's reduced motion). Practice's and the
    branch's loops by number, as the page's.
- **0.7.1** (2026-11-13; the user's report on 0.7.0 and their requests):
  - **A computer player's pace** 900 ms for all four (`bot_turn`, `bot_claim`, `bot_after_call`, `rob_show`; the
    user's). **Another's discard flies in** to their river from the table's edge on their side (the top, the left,
    the right; fading in at first, slow at both ends) in the last `discard_move` of the computer player's pace: at
    900 and 500, still for 400, then 500 in flight; their turn is over when it lands, and none flies before the
    tile before it has landed. (A discard nobody may claim comes in one view with the next player's draw, and
    flowers: the last discard of a view flies when only those come after it.) A call on it (`bot_claim`) or a claim taken from the player (`rob_show`) shows that
    long after the discard, its flight included, or after the words or moment over it (an added kong's). With the
    moments off (the settings, the system's animations, the page's reduced motion) nothing flies and the pace is
    whole (the page's own small slide of a new discard is gone: the two move alike). Claims.kt and lib/claims.ts
    `BotPace` for practice and a review's branch on both. (A table battle's computer players: the server's own
    pace, protocol's `BOT_TURN_MS`, 900 too, the claim window 1 s as before; the table shows their moves as they
    come.)
  - **The player's melds over the hand, at its left** (the user: whatever the screen), the first at the far left and
    each after it to its right (none moves when another comes), the flowers after them. Wide: a line of its own over
    the hand (the name plate over it at the left, as before; the melds' tiles 0.7 of the hand's), which a phone held
    sideways pays for with about 50 px of the rivers' height. Tall: the line over the hand had them, at its right
    end; now at its left, the name plate at the right end.
  - Fixed in the app: a computer player's discard after its own chow or pung came with the call's moment, not after
    it: the loop asked whether words or a moment were up before the table, a composition later, had started them
    (CalloutClock.saw / settle: it waits for the table to have looked; the page's engine answers after its effects).
  - Fixed in the app: a moment's parts that stand out of its box (a call's plaque, the caller's name, the slant of
    the panel, a plaque's tilt and shade) were cut off while it slid in or faded: Android draws a layer that is not
    wholly opaque apart, at its own size. Those layers have room around them now (Moments.kt roomyLayer).
  - Fixed in the app: a call's meld showed at its place before its moment brought it, for the frame before the
    moment started (the moments start in an effect, a frame after the view; longer for the session's first moment on
    a slow device). The melds that the new events' calls made are out of sight from that first frame (TableView
    comingMelds), as on the page (whose effect runs before the browser paints).
- **0.8.0** (2026-11-13): 報聽, and the page's deal and sounds.
  - **報聽** (docs/rules.md 5.1; in the app since the engine's features version 3, network 221 of the run s3): a
    ready hand may be declared on any later turn too, with a concealed hand; it is locked and a win adds `baoting`
    tai. Off in every preset (the user's): the rules' list has it, the player turns it on. A declared hand's only
    discard plays by itself (at a computer player's pace). 聽牌提示 (the settings, off by default: a dot over each
    discard that leaves the hand ready) and the hand and claim buttons as the user asked (e359db8) came with it.
  - **The deal** (ui/DealFx.kt, the page's DealFx.svelte): a practice hand's 65 tiles shuffled face down in the
    middle of the table (`deal_shuffle`), a pause (`deal_pause`, 100 ms), then all dealt at once to their seats
    (`deal_fly`, each up to 90 ms apart), and the player's hand turning face up one tile after another from the left
    (`deal_flip`). Meanwhile the hand, the others' backs and everyone's flowers are not there and nothing can be
    played; the computer players wait for it (Practice `dealt`: CalloutClock); a touch lets it go; once for a hand
    (DealShown: not again when the table comes back to it). With the moments off (the settings, the system's
    animations) none.
  - **Sounds** (Sounds.kt: SoundPool; the files are the page's web/public/sounds, copied in by the build's
    copySounds, a name's '-' as '_'): the shuffle; one knock of tiles as the deal lands; a discard as it lands; a
    call's or a kong's tile knocking in, then a tile set down as the meld lands; a flower; 天聽, 地聽, 報聽 two
    mallet notes; the hands shown at a hand's end, by what it is to the player (their win, another's they pay for,
    another's, a draw); with the result's moment a jingle (success, failure, neither; without the moments 400 ms
    after the hands). The settings' 音效: 關, 小聲, 正常 (the page's levels). None while the app is not on the screen.
- **After 0.8.0** (2026-11-13, the user's; its version the local session's, likely 1.0.0):
  - **The advanced settings (設定 > 進階) are gone**, each fixed at what the user chose: a decision's list shows bars
    (DecisionPanel), a review's hand shows each discard's cost and its danger (tileMarks), and the moments always
    show (the words alone only with the system's animations off: FxClock.enabled; the page's reduced motion). Their
    words went, and 39 more no code used; an old saved setting is ignored.
  - **跟隨桌布** (Material You, Android 12 and later; as Thirteen's): the app's colors from the wallpaper
    (dynamicLight/DarkColorScheme), the felt its primary (in the dark its primary container).
  - **Records**: 清除紀錄 at the very bottom, beside 分數歸零 (not in the title bar).
  - **The shuffle 2.5 s** (`deal_shuffle` 2500; the pause and the rest as before), at the same pace: a tile moves
    between as many spots as fill it, one about every 470 ms (DealFx LEG_MS: five legs now, three before). Its sound
    made again 2.7 s long, five waves of pushes (web/scripts/make_sounds.py; the other sounds byte for byte the
    same).
  - **The battle server's default `mahjong.1li.tw`** (app and page, the user's): where none was set; the page's
    cleared field is still its own server.
  - **A test build's icon**: the moon in its top right, over the tile's corner on a disc of the night felt (as
    Thirteen's test icon).
- **Play with the analysis** (2026-11-14, the user's; the page first, the app to follow):
  - **練習時顯示分析** (the settings; on in a hand at any point, on till its end, and that hand counts for
    nothing): the costs and dangers on the tiles, the choices' costs on their buttons, the opponents' chances of
    being ready on their plates with the tile each most likely waits on (可能聽, from 30%), a chosen tile's figures
    over the hand, and the tiles a ready hand waits with on blue faces (the engine's `wait_tiles`, and on the
    player's turn `ready_tiles` for the tile chosen or else the drawn one: Shape::waitTiles, every tile but the
    groups that stay the same whichever wait comes). The list of every action (詳細分析) only beside the table where
    there is room for the panel (a computer's): the table says enough to play by. The tour's step after the
    settings' introduces it with 聽牌提示.
  - **The review's branch and the analysis' 從這裡打** play with the analysis shown at every decision; the list
    (詳細分析) beside the table on a large window, else in its place (DecisionView, where the player may play too);
    controls in a column at the right on a wide window; 上一個決定／下一個決定; 重洗看不到的牌 asks first.
  - **The player's standing** on their own plate in every game (practice, battles, the branch): 聽牌, or 1 to 3
    向聽 (the engine's `shanten`), nothing further away; the plate keeps its width whether it shows or not.
  - **Skeletons** in place of spinners: the table's shape while the engine downloads or a battle's first view comes
    (TableSkeleton), a list's while the computer players play or the engine works (PanelSkeleton).
  - **The settings' longer words behind ⓘ** (練習長度 with what 一局, 一圈 and 一將 are; the pace; 聽牌提示;
    練習時顯示分析; the level of mistakes); 一將（四圈） is 一將.
  - A lifted tile goes down at a tap anywhere else; with the tags, a chosen tile rises a little (and under a
    computer's mouse).
  - **Then** (the user's): the settings' 練習輔助 holds 聽牌提示 (the dots, and now the blue wait tiles; never in a
    battle: Table `hints`) and 顯示分析. The list beside the table keeps the chosen tile's row within 20 to 80% of
    its height and goes back to its top when the mouse goes far from the hand (lib/scroll.ts); it is grey through the
    deal. Another's win waits `win_wait` (900 ms, the hands still hidden; a discard flying in that step lands first)
    before its moment, a self-draw's and a deal-in's alike. The branch's 重洗看不到的牌 is on at first. The about
    text says what the app does.

## Built and checked

- What was built, in `$WORK/` (not in the repository):
  - `apk/mahjong-0.8.0.apk` (2026-11-13): 0.8.0 above, network 221 of the run s3 (arm64-v8a, the debug key as
    before). Its debug twin on the emulator, upright, the system's animations on (`bin/check_deal.py`): a new game's
    deal recorded and looked at frame by frame (the shuffle in the middle, the tiles flying to the four seats, the
    hand turning up; the dealer, a computer player, discarded only after it); the system's audio service listed the
    app's sounds starting (the shuffle, the deal's knock 1.8 s after it, the discards), no SoundPool error, no crash;
    the settings' 音效 row. The release APK itself was not run. The page: built, `svelte-check` clean, every scene
    of fx-dev's tool replayed in Firefox without an error, the sounds fetched from their new place.
  - `apk/mahjong-0.7.1.apk` (2026-11-13): 0.7.1 above, network 211 (arm64-v8a, the debug key as 0.7.0's). On the
    emulator with the system's animations on, screen recordings looked at frame by frame:
    - a test build with the moments' slides and fades ten times as long (`call_in` 1500, `call_out` 2500, a deal-in's
      alike): the player's pung's panel slid in and faded whole (its plaque, the name over it and the panel's slant
      not cut off); a review stepped to its end: who dealt in, then the win, each faded whole (no shade left).
    - the debug build, sideways and upright (simple2 with flowers): practice hands to their ends, the player chowing;
      a computer player's chow, its moment, then its discard; the melds over the hand at the left (sideways under the
      name plate; upright with the plate at the right end).
    - a test build with the flight six times as long (`discard_move` 3000, `bot_turn` 3500): the others' discards in
      from the right and the top edges, also one that came with the next player's draw, which the first try had
      missed (put right on both; the page then: 25 of 25 others' discards in a hand flew, 12 of them with a draw).
    - the release build's x86_64 twin (`MAHJONG_ABIS=x86_64`, not kept): a hand with three of the player's chows to
      its end, its result and review; a call's meld out of sight from the first frame till its moment brings it (it
      had shown for a frame: put right); no crash. The arm64 APK itself was not run.
    The page: built, `svelte-check` clean; headless Firefox: the pace (a computer player's discards 900 apart; its
    discard after its call's moment, still for 400, then 500 in flight), the flights' frames, the melds over the hand
    sideways, upright and at 1920×1080.
  - `apk/mahjong-0.7.0.apk` (2026-11-13): 0.7.0 above, network 211. Its debug twin on the emulator, sideways, with
    the system's animations on (the emulator's script turns them off): two practice hands played by a script, the
    player pung and chowing where it could; at each hand's end the result's moment stopped at 78% with ≫
    按一下以繼續, and one tap brought the result; no crash. The screen recorded and looked at frame by frame: a
    pung's panel over the player's river, its meld landing at the right end; the player's discards, the drawn tile
    and tiles from the middle of the hand (the hand moving once the tile was halfway). What the frames showed and
    was put right: the discard went under the hand's tiles as it left them, and the drawn tile under the tiles it
    passed on its way to its place. The release build as its x86_64 twin (`MAHJONG_ABIS=x86_64`, not kept): it
    started, played on and took a discard, no crash; the arm64 APK itself was not run. Upright not looked at. The
    page: built, `svelte-check` clean (the halfway move not looked at in a browser).
  - `apk/mahjong-0.6.0.apk` (2026-11-12 evening): 0.6.0 above, network 211. Its debug twin on the emulator, with
    practice hands made by hand (the dealer's first discard 5m; the player may chow or pung it, another may pung or
    win on it): a chow taken by a pung, a pung taken by a win (the greedy bot), the player's own chow (its meld
    landing beside the name plate), sideways and upright, frames and a screen recording; a branch from the claim
    taken (its 搶胡 again, the result); the moment over the whole window. The release APK itself (arm64 only) was
    not run on the x86_64 emulator. The page: built, `svelte-check` clean, the same cases in Firefox, the branch's
    moment measured to cover the window sideways and upright.
  - `apk/mahjong-0.5.0.apk` (2026-11-12 afternoon): 0.5.0 above, network 211. Its debug twin on the emulator:
    `bin/check_melds.py` (6 practice hands, 70 looks at a seat with two melds or more, none short; the old layout
    built for comparison: 46 looks short in 3 hands, as the user's screenshots), the records' share sheet, the file
    saved to Downloads and read back (8 hands, each with its whole game), 分數歸零 (東風圈第 1 局, ±0). The page:
    built, `svelte-check` clean, the records' end and 分數歸零 in Firefox, and `shots-battle.py` (two browsers, a
    table game and a contest on a local server, one reloading mid-contest) passed (`shots/web-battle-050/`).
  - `apk/mahjong-0.4.1.apk` (2026-11-12): 0.4.0 with network 211 of the run `s2` in place of iteration 50 of `s1`
    (`models/actor_s2_211.fp16.onnx`; the page's `engine-files/actor.onnx` too). Its predictions are a little
    better and its play is no stronger that a match shows (docs/training.md; the float16 file, one against three of
    the network before, 1,500 deals of `simple2,see`: −0.04 ± 0.07). Built and the APK's network checked by its
    hash; not walked through on the emulator (the code is 0.4.0's).
  - `apk/mahjong-0.4.0.apk` (2026-11-12): 0.4.0 above. The page: `scratch/manual/manual_check.py` at phone, phone
    sideways and laptop sizes over four seeds (every press of 下一步 shows exactly one thing of the computer players',
    nothing of theirs shows without one except a call that comes before the player's own, which is said; the words
    stay; the table at a hand's end stays until a tap), `scratch/ui/callout_check.py` (the other paces: two seconds,
    as before), and the screenshot scripts (the table, the review, the declarations) at the three sizes, into
    `ui-after-20261112/` beside the reviewed set `ui-review-20261112/`. The app: the debug build walked through on
    the emulator by `shots.py` sideways and upright (`shots/android-0.4/`, `shots/android-0.4-port/`): a hand, its
    result and review, the records, the settings and rules, and its new step `manual` (the pace set to 手動 in the
    settings, a hand played by pressing 下一步, the words of its calls, its end and result). The release build
    too, as its x86_64 twin (`apk/mahjong-0.4.0-x86.apk`, `MAHJONG_ABIS=x86_64`; `shots/android-0.4-release/`): the
    arm64 APK itself dies on the x86_64 emulator while its translation loads ONNX Runtime, as before. What the
    emulator showed and was put right: the result's buttons under the screen's edge (above), and in `shots.py`
    the welcome card missed when it came after the navigation, and the list's last button tapped by its first
    sliver. Once, on a first start after a boot, every screenshot was the splash screen while the app ran under
    it (the script went on finding its buttons); it did not come again in five more runs.
  - `apk/mahjong-0.3.2.apk` (2026-11-12): 0.3.2 above. Built and type-checked; the page went through
    `scratch/ui/callout_check.py` (the words' two seconds, the computer players' wait, the tap that lets them go) and
    the screenshot scripts at phone, phone sideways and laptop sizes (`shots/web-032*/`); the app was not walked
    through on the emulator.
  - `apk/mahjong-0.3.1.apk` (`master` after the merge of `analysis`, 2026-11-12; `bin/android-analysis.sh release`, which builds this checkout now): the
    engine of features version 2 with the network of iteration 50 taken over to it
    (`models/actor_50_v2.fp16.onnx`, `python/upgrade_features.py`). Built and type-checked (the page too), not
    walked through on the emulator: a declaration has not been seen on a screen yet.
  - `apk/mahjong-0.3.0.apk` (branch `analysis`, 2026-11-12): the requests after 0.2.0 (above); arm64-v8a, 15 MB.
    The debug build went through `shots.py` sideways and upright (`shots/android-030/`), the page through
    `scratch/ui-analysis/web_review_check.py` at five sizes (`shots/web-030-review/`).
  - `apk/mahjong-0.2.0.apk` (branch `analysis`, 2026-11-11): the rules explained, the tour and the welcome card, the
    battle entry in steps; arm64-v8a only, 14.7 MB (below). The same debug key, so it installs over 0.1.0 keeping the
    records. Its x86_64 twin ran on the emulator; the debug build went through `shots.py` (`shots/android-020/`).
    The page's battle test (`web/scripts/shots-battle.py`: two browsers, a table game and a contest, on a local
    server) passed through the new entry (`shots/web-020-battle/`).
  - `apk/mahjong-0.1.0.apk` (branch `analysis`, 2026-11-11): the first beta with the network, its review and its
    analysis; release build, signed with the same debug key, so it installs over 0.0.15 keeping the records. Run on
    the emulator through practice, the review, the analysis, the records, the settings and the rules.
  - `apk/mahjong-0.0.15.apk`: the release APK (minified with R8), signed with the debug key (no release key exists
    yet: android/README.md, "Signing"); it was installed on the emulator and played through practice, a review, the
    analysis and a table game on the local server.
  - `web/`: the page's build (`web/dist`), to be served by any static file server (battles need a battle server at
    the same address, docs/battle.md).
  - `shots/android/`: the app on the Pixel 9 emulator, sideways (`-land`) and upright (`-port`); `shots/web/`: the
    page at phone-land, phone, fhd and laptop; `shots/web/battle/`: two browsers in a table game and a contest;
    `shots/tiles-sheet.png`: every tile face.
- The app was run on the Pixel 9 emulator (the screenshot standard: 2424 x 1080, 420 dpi, font 1.15, hole cutout),
  driven by `android/scripts/shots.py`: a practice hand played to its end, its review and a decision's analysis, the
  analysis screen, the records, the settings and rules, the battle screen, and a table game on the local battle
  server, each sideways and upright.
- The page was checked with Playwright (Firefox) at phone landscape and portrait, 1920 x 1080 and 1366 x 768
  (`web/scripts/shots*.py`), and two browsers played a table game and a contest against each other on the local
  server (`shots-battle.py`), one reloading mid-hand.
- The server: 10 tests (`gradle -p server --offline --no-daemon :protocol:test test`), the Docker images built and
  answered (docs/battle.md).
- Not run: rooms on phones nearby (an emulator has no Nearby Connections), the release APK on a real phone, the page
  on a real phone's browser, the server on the internet.

## Decisions made here (provisional unless the user confirmed them)

- **Defaults**: 簡單1 tai (southern rules, base 2) and 無花; the tai presets are 簡單1, 簡單2 (the family's),
  標準, 大師 (docs/rules.md section 9, changed 2026-11-10 after this prototype); payments are "base + tai" per payer. The presets and every parameter's name and value come from the engine
  (`{"cmd":"rules"}`), never from a table in the UI.
- **Names**: the app is 台灣麻將 (`app_name`); package `io.github.kennyfs.mahjong` (as Thirteen's
  `io.github.kennyfs.thirteen`); a test build (`MAHJONG_ID_SUFFIX=.test`, android/README.md) is
  `io.github.kennyfs.mahjong.test`, 台麻測試 under a night icon. The tabs: 練習, 對戰, 分析, 紀錄, 設定.
- **Discarding**: a tap lifts a tile and a second tap discards it, or a tile dragged upwards is discarded: one tap
  alone never discards (a phone's mistaken tap). On a computer one click discards and the keyboard plays (← → Enter,
  H G P C, Space). Claims are buttons over the hand: win first, then kongs, pung, each way of a chow with its three
  tiles, pass; sideways their foot level with the foot of the player's melds and flowers (right of them; over them
  only when those reach under the buttons: the next player's melds stay clear; not by the hand, whose length changes
  often). A tile lifted while the others play stays lifted (one tap plays it on the player's turn) till the hand
  itself changes; with a claim to decide on and no tile to play, a tap lifts nothing and a lifted tile goes down (the
  user's, 2026-11-13). The drawn tile comes in from over the hand once another's discard that came with it (nobody
  could claim it) has landed in its river.
- **Tile sizes** (the user's request): the hand's tiles are the largest (about 43 dp on the standard phone either
  way; floors of 30 dp upright and 24 dp sideways); an upright phone wraps the hand after 8 tiles (two rows; the drawn tile, or a dealer's 17th, ends the second);
  the rivers stay at 26 dp or more while the hand can give room; the others' concealed tiles are drawn as backs when
  sideways and are a count on their name plates when upright. Upright, the others' name plates have two lines (name
  over count and score): the user's font scale 1.15 does not fit one line in a third of the width.
- **Tiles** are drawn from paths (`art/make_tiles.py`, glyphs from Noto Serif CJK, OFL): an ivory face with a blue back
  that shows on the felt; flowers as their characters in color. Same art in the app and on the page.
- **No AI help while playing**, anywhere (the handoff's rule): the analysis is offered only for finished hands
  (review) and for positions set up by hand.
- **The analysis** is the engine's tile efficiency (shanten, the tiles that help with copies left, waits and their tai
  for ron and tsumo), ranked win > fewer shanten > more tiles; the list marks the best and the action taken. Fields
  a network adds later are shown as they come (`extra` in `Model.kt`, unknown number fields on the page).
- **Practice**: the app (or page) is the authority: it holds the record and lets the bots decide one decision point at
  a time with a pause (`speed`), so each bot's move shows. A game in play survives a restart. Changing the rules,
  length or bot deals a new game when the rules page or setting is left.
- **Records**: every finished hand is kept with its whole record (rules, setup, wall, decisions) and who played each
  seat, so it can be replayed and analyzed; at most 3000, the oldest non-favorites dropped first; clearing keeps the
  favorites. App: a JSON file in its files directory. Page: localStorage.
- **Battles**: see docs/battle.md. The claim window is the same 1 s after every discard whether anyone may claim (so
  a pause tells nothing); per-decision time limits 5 to 60 s (default 15); on a timeout or after leaving, the room's
  bot decides. A same-tiles contest's bots decide the same way for the same play, so only the player's own decisions
  differ between tables.
- **Phones nearby** (app): the host's phone runs the room (the same Hub and Room as the server, `HubLimits.NEARBY`:
  one room, 8 contest players) and advertises it; others find it and join with the PIN shown on the host. If the host
  leaves or loses the connection, the room is gone (no backup host).
- **Hosting**: nothing deployed (the user's decision). The page's battles use its own address; the app has no default
  server and asks for an address on the Battle screen.
- **Local network access** (Android 17): a server at a local address (192.168.x.x, 10.x, a `.local` name, the
  emulator's 10.0.2.2) needs the runtime permission `ACCESS_LOCAL_NETWORK`, or the connection silently times out. The
  app asks for it on Create or Join when the address looks local (`isLocalNetwork`, `battle/Links.kt`); a public
  name that resolves to a local address is not caught (the player then sees a time-out).
- **Navigation**: the app uses a back stack per screen in `AppModel` and `BackHandler` (no navigation3: one dependency
  fewer); during a battle's table the bar and rail are hidden and Back asks before leaving. The page keeps the screen
  in the address (`#/play`, `#/records/review`), so a reload and the browser's Back work.
- **Landscape is full screen** in the app (the system bars come back with a swipe), as Thirteen's; sideways, the
  felt reaches under the camera's cutout while the tiles keep clear of it.
- **Name plates**: one line (wind, name, dealer mark, tiles held, score); when a sideways seat's column is too narrow
  (a long name at font scale 1.15), the figures go under the name rather than cutting it; upright, always two lines.
- **Languages**: zh-TW only (the user's request); English (US) may follow, no others.

## What the user said, and what was done

- "Tiles must not be too small, or they are hard to read; the hand's tiles must be big to tap; an upright phone
  shows at most about 8 tiles a row." → The sizes above; 8 a row upright on both the app and the page.
- "Only Taiwan Traditional Chinese for now; English (US) optional; no other languages." → zh-TW strings only.
- Everything else is the handoff's (docs/ui-handoff.md).

## Missing or rough (the next steps, roughly in order)

**Bugs the user found on the prototype (2026-11-10, APK 0.0.15), fixed in 0.1.0:**

- Android: tapping the base's + or − button (底) crashed the app, and 所有參數 (every parameter) never expanded. One
  cause: the engine writes the rules' values as numbers with a fraction (`2.0`), which the app's `RulesInfo` read as
  integers; the decoding failed, a `runCatching` hid it (no parameters to show), and the base's buttons threw it
  uncaught. The values are read as numbers now, every decoding failure is the engine's error, and the rules page
  shows it.
- Sometimes a tile could not be discarded: the lifted tile's touch area rose with it, leaving the lower part of its
  place dead to the second tap. The touch area now covers both the tile's place and the lifted tile (app and page).


1. **Test on real devices**: the release APK on a phone (sideways and upright), rooms nearby on two or three phones,
   the page on a phone's browser (its full-screen request when sideways).
2. **Hosting** the battle server (the user decides where); then the app's default server and a mention on the Battle
   screen.
3. **Nearby**: no backup host (the room dies with the host's phone); no reconnect for a guest who drops.
4. **Size**: compressing the native libraries in the APK would make the download about 8 MB (from 14.7), but the
   installed app larger (Android keeps an extracted copy beside the APK); declined by the user (2026-11-11): they
   stay uncompressed, as Thirteen's. Below.
5. **The analysis** has the network since 0.1.0 (docs/analysis.md); a better network replaces the file.
6. **English (US)** strings (`values-en/strings.xml`; the page would need its language picked from the browser).
7. **Records export and import** (Thirteen's app has export and share): `records_export` is in the strings, unused.
8. **Polish**: animations of tiles moving (only the callouts animate now), sound and haptics, predictive back, a
   tablet's side panel in the app (the page has one on a computer), TalkBack and keyboard focus checked through; a
   connection error shows the system's message (English) in parentheses, which could become plain words; upright, the analysis screen's palette takes the lower half, so the section being
   edited may need a scroll.
9. **Tests**: the app has no unit or instrumented tests (Thirteen's ScreenshotTest is a model); `RoomTest` misses
   resuming, leaving and the claim window's timing.
10. **Docker** was built with the legacy builder only (this host has no BuildKit).

## The APK's size (0.2.0)

0.1.0's APK was 30.4 MB; 0.2.0's is 14.7 MB, by Thirteen's ways and one more (the user's request: smaller, and only
arm64-v8a for the phone):

| Part (bytes in the APK) | 0.1.0 | 0.2.0 | How |
|---|---|---|---|
| ONNX Runtime (`libonnxruntime.so`, stored) | 10.6 M arm64 + 11.0 M x86_64 | 9.95 M arm64 | arm64-v8a only (`abiFilters`; `MAHJONG_ABIS=x86_64` for the emulator); the app's own reduced build (`android/build-ort.sh`, `ort-ops.config`: this network's operators only, where Thirteen's covered Thirteen's too); the AAR's would be 34 M |
| The engine (`libmahjong_jni.so`, stored) | 1.41 M + 1.42 M | 0.66 M | one ABI; only the JNI functions exported, unused code dropped at link time (`-fvisibility=hidden`, `--gc-sections`) |
| The network (`actor.onnx`, deflated) | 3.93 M | 1.98 M | weights stored as float16 (`python/fp16_weights.py`), computed in float32 |
| The code (`classes.dex`, deflated) | 1.91 M | 1.93 M | R8 as before |

Float16 weights, measured (`$WORK/scratch/ui-analysis/fp16_check.py`, not in the
repository: 60 hands of 簡單1・無花 played by `greedy`, every seat reviewed by
both networks, 2970 decisions, 27832 actions): every figure within 0.001 of float32's (q: mean difference 0.0002, at
most 0.0009; win, deal-in, policy, danger: at most 0.0008); the best action the same in 2938 of 2970 decisions (the
other 32 were near ties); a review's total loss at most 0.0007 apart. The web page ships the same file (2.2 MB instead
of 4.3). Its id differs from float32's, so records reviewed by 0.1.0 are reviewed again when opened.

## Later: the network on the server

The app and the page have the network (0.1.0). The server's bots are still `greedy`: its image would add the network
file and a build of `mahjong` with ONNX Runtime, and `Validation.BOTS` would name `net:...` (when a stable version is
released).

## Commands

```bash
source $HOME/env.sh; export TMPDIR=$WORK/tmp
# The engine and its tests
cmake -S cpp -B cpp/build -DCMAKE_BUILD_TYPE=Release && cmake --build cpp/build -j 32 && cpp/build/mahjong runtests
# The battle server and its tests, then a local server for the page and the emulator (docs/battle.md)
gradle -p server --offline --no-daemon :protocol:test test installDist
MAHJONG_BINARY=$PWD/cpp/build/mahjong HOST=127.0.0.1 PORT=8091 server/build/install/mahjong-server/bin/mahjong-server &
# The web page (web/README.md): the engine as WebAssembly once, then the dev server or a build
(cd web && source $THIRTEEN_WORK/emsdk/emsdk_env.sh && JOBS=16 ./build-engine.sh && npm run dev -- --port 5180)
# The app (android/README.md): a debug APK, the release APK, the screenshots on the emulator
(cd android && set -a && . ./local.env && set +a && $GRADLE --offline --no-daemon assembleDebug)
(cd android && GRADLE_FLAGS=--offline MAHJONG_APK_DIR=$WORK/apk ./build-apk.sh)
(cd android && GRADLE_FLAGS=--offline BATTLE_SERVER=10.0.2.2:8091 ./device-tests.sh)
```
