# Mahjong-TW (台灣十六張麻將)

An AI for Taiwanese 16-tile mahjong, with a light network meant to run on a phone or a web page on the CPU. Modeled
on the author's Thirteen (十三張) project.

- Rules: [docs/rules.md](docs/rules.md). The flower settings (none, 見花見台, 正花) and the tai table (simple,
  standard, masters, or any values) are parameters.
- Plan: [docs/plan.md](docs/plan.md).

Status: the C++ engine (rules, scoring, the play of a hand) and two baseline players. No network yet.

## Layout

| Path | What |
|---|---|
| `cpp/core/`, `cpp/external/` | Utilities copied unchanged from KataGo v1.18.2 via Thirteen (random numbers, hashing, timers, threads, command-line parsing, JSON). See `LICENSE-KataGo`. |
| `cpp/game/tiles.*` | Tile kinds, names, parsing (`123m 456p 11z 5f`). |
| `cpp/game/shape.*` | Winning shapes, waits, readings (every way to split a hand into sets and a pair), shanten. |
| `cpp/game/rules.*` | Rule parameters and presets (docs/rules.md section 11). |
| `cpp/game/tai.*` | Melds and the tai of a winning hand (section 9). |
| `cpp/game/game.*` | One hand as a state machine: deal, flowers, draws, calls and their priority, missed wins, robbing a kong, the end of the wall, flower wins, payments and the next dealer. |
| `cpp/players/` | Players: `random`, `greedy` (tile efficiency, no defense). |
| `cpp/command/` | Subcommands; `mahjong help` lists them. |
| `cpp/tests/` | Unit tests. |

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
build/mahjong match -p greedy -p random -p greedy -p random -n 5000 -t 32 --rules simple,none

# One hand, printed (-v: the deciding player's hand before each decision).
build/mahjong show -s demo1 --rules standard,seat

# The tai of a winning hand.
build/mahjong tai --hand 111222333z44z123456m --win 6m --tsumo --round-wind S --rules standard

# Speed.
build/mahjong benchmark
```

Rules specs are comma-separated presets and `name=value` overrides, e.g. `simple,see,base=2,draw_streak=0`
(docs/rules.md section 11).

## License

GPLv3: see [LICENSE.md](LICENSE.md). The files copied from KataGo (`cpp/core/`, `cpp/external/`) keep their MIT
license, in `LICENSE-KataGo`.
