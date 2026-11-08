# Handoff: the app and the web page

For the session that builds the interfaces (the Android app, the web page, and the battle server). Written by the
engine session on 2026-11-09. Read it whole before starting.

## 1. Where things stand

- The engine is done for now (`master`): the rules ([rules.md](rules.md)), scoring, the play of a hand, two bots
  (`random`, `greedy`), a tile-efficiency analysis, and the JSON engine for interfaces
  ([gui-engine.md](gui-engine.md), `cpp/command/guiengine.h`). `mahjong runtests` passes.
- There is **no network yet**. Training waits until the user has confirmed the family's rules; it comes after your
  work ([plan.md](plan.md) section 4). Until then the bots are `greedy` and the analysis is tile efficiency. Build
  everything so the network drops in later: a bot is a spec string (`greedy` now, a `net:...` spec later), and the
  analysis screen shows whatever fields an answer has (`source: efficiency` now, `network` later with a probability
  and an expected score per action).
- The rules may still change a little (tai items, maybe details of the flow). Take the rules' names and values
  from the engine (`{"cmd":"rules"}`), never hard-code a tai table in the UI.

## 2. Your job

A first version that is decent to use, not a finished product: the user will ask for changes later, in this same
session (all later app work happens here). Sessions never run at the same time, so you have the checkout and the
machine's quota to yourself.

- Work on the branch `ui` (made from `master` and checked out by the engine session); commit only there. The user fast-forwards `master` to it later, so
  never rewrite `master` and never rebase onto anything.
- Commits are not signed (`git -c commit.gpgsign=false commit`); the user re-signs them at home. Don't push.
- Messages and files in the repo in English; your answers to the user in Traditional Chinese with Taiwan usage
  (軟體、網路、資料、程式、檔案…). The app's own text is Traditional Chinese (Taiwan) first, kept in string
  resources so other languages can come later.
- Changes to `cpp/` only when the interfaces need them (a request the engine lacks, a bug): small separate
  commits, with tests in `cpp/tests/tests.cpp`, `docs/gui-engine.md` updated, and no change to the rules of play.

## 3. Learn from Thirteen first

`$HOME/Thirteen` is the user's previous project of the same kind (十三張: an AI, an Android app, a web
page, a battle server). Since about v1.1.1 it was developed entirely on this machine, so every tool, script and
emulator it used is here. Copy its structure, its features and its look, adapted to mahjong; its history shows
what the user wants.

- Its `README.md`, `android/README.md`, `web/README.md`, `docs/battle.md`, `docs/tips.md`, `docs/privacy.md`.
- Its git history (`git log`, 71 commits, tags v1.0.0 to v2.1.1), especially the app and web commits
  (`git log --stat -- android web server protocol`): their messages explain why each change was made.
- **The user's own requests in the Thirteen sessions**, extracted to
  `$WORK/handoff/thirteen-user-messages.md` (100 messages): the best source of the user's
  taste (what they asked for, what they complained about). The full transcripts are in
  `~/.claude/projects/-HOME-Thirteen/*.jsonl` if you need context; they are large, so grep them.
- Its memory notes, `~/.claude/projects/-HOME-Thirteen/memory/`: the screenshot standard, the layout
  of the work disk, the quota, beta version naming, the reduced ONNX Runtime build.
- Screenshots and reviews of earlier versions in `$THIRTEEN_WORK/` (`shots/`, `web-shots/`,
  `i18n-shots/`, `desk-visual/`, `desk-review-2/`, `tut-shots/`, `tutorial-summary.md`).
- The parts to reuse: `android/app/src/main/cpp/thirteen_jni.cpp` (JNI over the GuiEngine), `web/engine/`
  and `web/build-engine.sh` (the engine as WebAssembly in a worker), `protocol/` (rooms, messages, validation;
  pure Kotlin shared by server and app), `server/` (Ktor over WebSockets with a pool of `gui` processes; Docker,
  Caddy), `android/device-tests.sh` and the screenshot tests, `web/scripts/shots.py` and `desktest.py`
  (playwright).

Don't edit anything inside `Thirteen/` or `thirteen-files/`; copy what you need.

## 4. What to build

### Screens and features (as in Thirteen)

- **練習 (practice)**: the player against three bots. Settings: the rules, the length (one hand, one round 一圈,
  a full game 一將), the bot. Each hand ends on a result screen (the winner's hand, the tai items with their names,
  the payments, the next dealer and streak), and a game on its totals.
- **對戰 (battles)**: with people, through a server or with phones nearby (Nearby Connections; the web page:
  server only), bots in the empty seats. See below for the modes.
- **分析 (analysis)**: set up a position by hand (the player's tiles, melds, flowers, discards of every seat,
  dealer, winds, tiles left, and whether it is the player's turn or a decision on someone's discard) and see the
  engine's figures for every legal action. Also reached from any decision of a finished hand.
- **紀錄 (records)**: every finished hand kept as its record (rules, setup, wall, decisions, who played which seat),
  replayed step by step, each decision opening the analysis; favorites, as in Thirteen.
- **規則 / 設定 (rules, settings)**: flower presets in this order: 無花 (`none`), 見花見台 (`see`), 正花 (`seat`);
  tai presets 簡單 (`simple`), 標準 (`standard`), 大師 (`masters`); the two chosen apart, default `simple,none`,
  base 1 tai to 1 tai (底台 1:1); an advanced list of every parameter. Look at Thirteen's Rules page.
- A short tutorial and first-run tour, as Thirteen has (later is fine; the user will ask).

**No AI help while playing.** In practice and in battles there is no analysis, hint or suggested tile while a hand
is in play: only after the hand is over (practice: from its result screen; battles: after the battle) and from the
records. Showing the player's own legal actions (the 吃/碰/槓/胡/過 buttons) is not help; anything computed from
the engine's analysis is.

### Battle modes

- **Table game (牌桌)**: one table, 1 to 4 people, bots in the other seats, a set length; points as usual.
- **Same-tiles contest (同牌賽)**, the counterpart of Thirteen's puzzle contest: everyone plays the same hands,
  each at their own table, in the same seat, with the same wall and the same bots (seeded from the hand and the
  record's length, so they answer alike to alike play). The score is the points of the hand; the total decides.
  This removes the luck of the deal and measures whole hands, many decisions deep, without needing an evaluator.
  It works now with `greedy` bots.
- A one-decision puzzle (何切, which tile to discard) scored by how much each choice loses against the network's
  best, as Thirteen scores arrangements, can come later, after the network exists and only with positions where the
  choices differ clearly. Not for this version.

The room is the only authority, as in Thirteen: it keeps the record (which holds the whole wall) and sends each
player only `view` for their seat. Each decision has a time limit; a seat that runs out of time, or whose player
left, gets the bot's choice. After every discard the room waits the same short time whether or not anyone can
claim it, so the pause never tells that a player is deciding. The server runs locally and in Docker as Thirteen's does;
where to host it is the user's call (ask before deploying anything).

### The table

Landscape first: mahjong is played on a table and the user plays it that way. Portrait should work too (Thirteen
works well in both); it lets people play in more places. The desktop web page gets its own large-screen layout, not
the phone's stretched (Thirteen has one; see its desktop reviews).

Things a mahjong table has to show clearly: the player's 16 or 17 tiles (the drawn tile set apart) and melds; each
player's melds, flowers and discards in order (the called ones marked; others' concealed kongs face down); the
seat winds, the round wind, the dealer and the streak (連N); tiles left in the wall; whose turn it is. Claim
choices appear as buttons with the options (a chow with several ways shows each). Discarding by tap and drag; on
the desktop, by keyboard too. Animate draws, discards, calls and flowers briefly; the events since the last view
come with each answer for this.

Tiles: 34 kinds plus the 8 flowers 春夏秋冬梅蘭菊竹, legible at phone size (17 tiles across a portrait screen
leaves about 60 px each). Draw them yourself as SVG or use a set whose license fits GPLv3 (note the source and
license in the repo); many free sets lack Taiwanese flowers.

### Engine use

Exactly as in [gui-engine.md](gui-engine.md): the same C++ through JNI on Android and as WebAssembly on the web,
`mahjong gui` processes on the server. The app is fully offline except for battles. No ONNX Runtime yet (no
network); keep Thirteen's structure so it can be added (Thirteen's reduced ORT build, `build-ort.sh`).

## 5. Environment

- Android: `source $HOME/env.sh` (SDK, AVD home, Gradle 9.8.2), then set
  `TMPDIR=$WORK/tmp` (env.sh points it at Thirteen's). Thirteen's `android/local.env`
  has the same paths.
- **The screenshot standard is the user's phone** (a Pixel 10; the Pixel 9 AVD stands in): 2424 x 1080 px, 420
  dpi, font scale 1.15, hole cutout; landscape first, then portrait. Make your own AVD the same way as Thirteen's
  (`avdmanager -d pixel_9`, no skin override; see Thirteen's `device-tests.sh` and memory `screenshot-standard.md`),
  named for this project, on its own emulator port (not 5554, which Thirteen uses), and shut it down when done.
  Other font scales and tablets are variants; very large text (font 2.0) matters little.
- Web: Svelte 5 + Vite like Thirteen; emscripten in `$THIRTEEN_WORK/emsdk`; playwright in
  `$THIRTEEN_WORK/pwenv` for screenshots (phone landscape and portrait viewports at the same
  standard, and desktop sizes such as 1920 x 1080 and 1366 x 768).
- Look at your screenshots after every change that shows, and iterate (Thirteen's sessions did this loop
  constantly); judge them at the standard above.

## 6. House rules

- Everything generated goes under `$WORK/` (apk/, shots/, logs/, tmp/, ...); never loose
  folders directly in `$HOME`, and nothing in the home directory (tiny).
- Save the full output of every command you run to a log file under `mahjong-files/logs/` (`cmd 2>&1 | tee ...`);
  filter when reading the log, not in the command.
- At most 32 cores (3200% CPU) and 100 GB RAM in total; builds with `-j` 32 or fewer. Wait on background jobs
  through their completion notice (or Monitor with an until-loop), not sleep loops; match processes with
  `pgrep -u $USER -fa "[n]ame"`.
- Never send files with SendUserFile: put builds and screenshots under `mahjong-files/` and give the path; the user
  fetches them with rsync or sftp.
- `reference/` is not committed.
- The project memory in `~/.claude/projects/-HOME-Mahjong-TW/memory/` (shared with the engine session)
  has the user's decisions on rules, resources and files; keep it up to date.

## 7. When you finish

Leave the branch committed and clean, with the build and test steps in `android/README.md` and `web/README.md` and
the layout table in `README.md` updated; build an APK into `mahjong-files/apk/` and the web page into
`mahjong-files/` too; then tell the user, in Traditional Chinese, what exists, where the APK, web build and
screenshots are, and what is missing or rough. Then stop: the next step is the user's (confirming the rules).
