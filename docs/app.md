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
| 練習 Practice | Against three bots (the engine's `greedy`, tile efficiency), for one hand, a round or a game; the result of each hand (winner, hand, tai items, payments, next dealer) with its review; the game's totals. No hint or analysis during play. |
| 對戰 Battles | A table game (1 to 4 people, bots in the empty seats) or a same-tiles contest, on a battle server; the app also with phones nearby. Lobby with a 6-digit PIN, the host's settings, the table with a clock per decision, each hand's result with the standings, the final standings and the player's hands. docs/battle.md. |
| 分析 Analysis | A position set up by hand, then the engine's figures for each legal action. A finished hand's decisions are analyzed from its review (records). |
| 紀錄 Records | Practice hands, table battles and contests (battles as groups of hands); favorites; statistics; each hand opens its review: step by step, everyone's tiles at a tap, each decision's analysis with the action taken marked. |
| 設定 Settings | Rules (tai preset, flower preset, base, every parameter), practice's length, the bots' pace, text size, colors (table green or minimal), dark mode, about. |

- **The table** is laid out from the room it has (`web/src/lib/layout.ts`, ported as `android/.../ui/TableLayout.kt`,
  the same arithmetic): wide (a phone sideways, a tablet, a computer) or tall (a phone upright). Landscape is the
  main layout; portrait works; a computer gets a side panel with the scores and keyboard play (web).
- **The engine** is `cpp/` behind `GuiEngine` (docs/gui-engine.md), stateless (a hand is its record, replayed per
  request): JNI in the app, WebAssembly in a worker on the page, `mahjong gui` processes on the server. No ONNX
  Runtime yet; Thirteen's structure for it is kept (see "Later").
- **Shared between the two**: the strings (`android/app/src/main/res/values/strings.xml`, which the page reads through
  `web/scripts/strings.mjs`), the tile art (`art/make_tiles.py` writes `TileArt.kt` and `tile-art.ts`), the table's
  layout arithmetic, the records' JSON form, the battle protocol (Kotlin in `protocol/`, a TypeScript port in
  `web/src/lib/battle.svelte.ts`).

## Built and checked

- What was built, in `$WORK/` (not in the repository):
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

- **Defaults**: 簡單 tai and 無花 (the user's choice, memory `rules-decisions`); base 1 (底 1 台 = 1 point), i.e.
  payments are "base + tai" per payer. The presets and every parameter's name and value come from the engine
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

1. **Test on real devices**: the release APK on a phone (sideways and upright), rooms nearby on two or three phones,
   the page on a phone's browser (its full-screen request when sideways).
2. **Hosting** the battle server (the user decides where); then the app's default server and a mention on the Battle
   screen.
3. **Nearby**: no backup host (the room dies with the host's phone); no reconnect for a guest who drops.
4. **A tutorial** or first-run help (Thirteen has one): none yet.
5. **The analysis** is tile efficiency only: no defense (danger of a discard), no tai-aware expected value. The network
   (docs/plan.md) is meant to replace it; its figures plug in as described under "Later".
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

## Later: the network

- The engine gets the network's figures into `analyze` (more number fields per action, e.g. a win rate and an
  expected score), which the app and the page show as they come, and a bot spec (`net:...`) that the settings'
  bot list (`BOTS` in `Settings.kt`, `web/src/lib/settings.svelte.ts`) and the server's `Validation.BOTS` then name.
- The app links ONNX Runtime as Thirteen's does (`android/app/src/main/cpp/CMakeLists.txt`, `build-ort.sh` and
  `ort-ops.config` there); the page loads the network with the engine (Thirteen's `virtual:engine-files` and
  `stored.ts`, already here for the WebAssembly); the server's image adds the network file.

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
