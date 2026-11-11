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
| 練習 Practice | Against three bots (the network by default, or the engine's `greedy`, tile efficiency), for one hand, a round or a game; the result of each hand (winner, hand, tai items, payments, next dealer, the network's summary of the player's play) with its review; the game's totals. No hint or analysis during play. |
| 對戰 Battles | A table game (1 to 4 people, bots in the empty seats) or a same-tiles contest, on a battle server; the app also with phones nearby. The entry asks first only a new room or joining one (the app: online or nearby too); a new room then offers its kinds, joining asks the PIN. Lobby with a 6-digit PIN, the host's settings, the table with a clock per decision, each hand's result with the standings, the final standings and the player's hands. docs/battle.md. |
| 分析 Analysis | A position set up by hand, or a step of a review taken there, with the network's figures for each legal action as it changes, and the tables of draws and of claims (docs/analysis.md section 4). |
| 紀錄 Records | Practice hands, table battles and contests (battles as groups of hands); favorites; statistics of the network's reviews; each hand opens its review: the graph of the value, the decisions worth a look, each decision's verdict and figures, everyone's tiles at a tap. |
| 設定 Settings | Help (the rules explained, the tour), rules (tai preset, flower preset, base, every parameter with its explanation), practice's length, the bots and their pace, how a decision's list shows and the level of mistakes, text size, colors (table green or minimal), dark mode, about (the version and the network's id). |

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
    stands apart like a drawn one and the hand has a pulsing yellow frame (also with a drawn tile).
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

## Built and checked

- What was built, in `$WORK/` (not in the repository):
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
  `io.github.kennyfs.thirteen`). The tabs: 練習, 對戰, 分析, 紀錄, 設定.
- **Discarding**: a tap lifts a tile and a second tap discards it, or a tile dragged upwards is discarded: one tap
  alone never discards (a phone's mistaken tap). On a computer one click discards and the keyboard plays (← → Enter,
  H G P C, Space). Claims are buttons over the hand: win first, then kongs, pung, each way of a chow with its three
  tiles, pass.
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
   connection error shows the system's message (English) in parentheses, which could become plain words; a hand of
   0 tai shows 「共 0 台」 beside 「莊家 1 台」 (the dealer's tai counts only in payments with the dealer), which a
   wording could make plainer; upright, the analysis screen's palette takes the lower half, so the section being
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
