# Mahjong-TW (台灣十六張麻將)

An AI for Taiwanese 16-tile mahjong, with a light network meant to run on a phone or a web page on the CPU. Modeled
on the author's Thirteen (十三張) project.

- Rules: [docs/rules.md](docs/rules.md). The flower settings (none, 見花見台, 正花) and the tai table (simple1, simple2,
  standard, masters, or any values) are parameters.
- Plan: [docs/plan.md](docs/plan.md).

- The engine's JSON interface for the app and the web page: [docs/gui-engine.md](docs/gui-engine.md).
- The app and the web page, their status, decisions and what is missing: [docs/app.md](docs/app.md); battles and the
  battle server: [docs/battle.md](docs/battle.md).

Status: the C++ engine (rules, scoring, the play of a hand), two baseline players, tile-efficiency analysis, and the
JSON engine for interfaces; the first prototype of the Android app ([android/](android/README.md)), the web page
([web/](web/README.md)) and the battle server (branch `ui`): practice against bots, battles (online, and phones nearby
in the app), the analysis of positions and of every decision of a finished hand, records, rules and settings. No
network yet: the bots and the analysis use tile efficiency.

## Layout

| Path | What |
|---|---|
| `cpp/core/`, `cpp/external/` | Utilities copied unchanged from KataGo v1.18.2 via Thirteen (random numbers, hashing, timers, threads, command-line parsing, JSON). See `LICENSE-KataGo`. |
| `cpp/game/tiles.*` | Tile kinds, names, parsing (`123m 456p 11z 5f`). |
| `cpp/game/shape.*` | Winning shapes, waits, readings (every way to split a hand into sets and a pair), shanten. |
| `cpp/game/rules.*` | Rule parameters and presets (docs/rules.md section 11). |
| `cpp/game/tai.*` | Melds and the tai of a winning hand (section 9). |
| `cpp/game/game.*` | One hand as a state machine: deal, flowers, draws, calls and their priority, missed wins, robbing a kong, the end of the wall, flower wins, payments and the next dealer. |
| `cpp/players/players.*` | Players: `random`, `greedy` (tile efficiency, no defense). |
| `cpp/players/analysis.*` | Figures for each legal action (shanten, improving tiles, waits and their tai): the analysis shown in the app until the network exists. |
| `cpp/command/guiengine.*` | The JSON engine of the interfaces (docs/gui-engine.md); `mahjong gui` serves it on stdin and stdout. |
| `cpp/command/` | Subcommands; `mahjong help` lists them. |
| `cpp/tests/` | Unit tests. |
| `art/` | `make_tiles.py`: the tiles' faces as paths (glyphs from Noto Serif CJK), written for the app (`TileArt.kt`) and the page (`tile-art.ts`). |
| `android/` | The Android app (Kotlin, Jetpack Compose; the engine through JNI): [android/README.md](android/README.md). |
| `web/` | The web page (Svelte 5, Vite; the engine as WebAssembly): [web/README.md](web/README.md). |
| `protocol/` | The battle protocol, rooms and their limits (Kotlin), shared by the server and the app: [docs/battle.md](docs/battle.md). |
| `server/` | The battle server (Ktor over WebSockets, `mahjong gui` processes as the engine), its Docker image and Caddy site. |

## Building

C++17, CMake 3.18 or later.

```bash
cd cpp
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
build/mahjong runtests
```

`-DUSE_NATIVE=OFF` drops `-march=native`.

## Commands

```bash
# Duplicate matches: each wall is played once per rotation of the four players (--all-orders: all 24 orders).
build/mahjong match -p greedy -p random -p greedy -p random -n 5000 -t 32 --rules simple1,none

# One hand, printed (-v: the deciding player's hand before each decision).
build/mahjong show -s demo1 --rules standard,seat

# The tai of a winning hand.
build/mahjong tai --hand 111222333z44z123456m --win 6m --tsumo --round-wind S --rules standard

# Speed.
build/mahjong benchmark

# The interfaces' JSON engine: one request per line in, one answer per line out.
echo '{"cmd":"new","rules":"simple1,see","seed":"x"}' | build/mahjong gui
```

Rules specs are comma-separated presets and `name=value` overrides, e.g. `simple1,see,base=2,draw_streak=0`
(docs/rules.md section 11).

## License

GPLv3: see [LICENSE.md](LICENSE.md). The files copied from KataGo (`cpp/core/`, `cpp/external/`) keep their MIT
license, in `LICENSE-KataGo`.
