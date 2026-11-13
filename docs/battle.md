# Battles

Two modes, played on the Battle screen (對戰) of the web page and the app, against people on a battle server or (the
app only) on phones nearby:

- **Table game** (開一桌; 1 to 4 people): one table, bots in the empty seats, scored as usual; the most points wins.
  The host sets its length: one hand, one round (一圈) or a full game (一將).
- **Same-tiles contest** (同牌賽; 1 or more people, 32 at most on a server, 8 nearby): everyone plays the same hands
  (the same wall, seat and dealer) at a table of their own against bots that decide the same way for the same play;
  a hand's score is the player's points in it, and the most points in total wins. The host sets the number of hands
  (1, 2, 4, 8, 12 or 16).

The host also sets the time per decision (5 to 60 seconds) and the rules (a tai preset and a flower preset, or the
Rules page's choice with its changed values). No hint or analysis is shown while a hand is played; after each hand
the result and the standings, and at the end the player's hands, each opening the review and every decision's
analysis (from the records, where the battle is kept as a group of its hands).

At a battle's table the other screens are out of reach (no navigation bar or rail); Back asks before leaving. One who
leaves is away for the rest of the battle: the bot decides for their seat.

## Structure

| Part | What |
|---|---|
| `protocol/` | The messages (`Messages.kt`), checks of everything a client sends (`Validation.kt`), a room (`Room.kt`), the rooms of a server (`Hub.kt`) and what a room asks of the engine (`GameEngine.kt`). Pure Kotlin, shared by the server and the app. `RoomTest` covers the rules of play and the limits. |
| `server/` | Ktor (CIO) over WebSockets (`/ws`, plus `/health`), with a pool of `mahjong gui` processes as the engine (`EnginePool.kt`). `server/Dockerfile`, `server/compose.yml` and `server/Caddyfile` deploy it with the web page beside it. |
| `android/.../battle/` | The app's client: links to a server (OkHttp WebSocket) or to a phone nearby (Nearby Connections), and a room hosted on this phone (the same Hub and Room, with the app's JNI engine). `Battle.kt` keeps what the room said. |
| `web/src/lib/battle.svelte.ts` | The web page's client (online rooms only: a browser cannot reach phones nearby), `web/src/components/Battle.svelte` its pages. A reload in a room takes the seat back (the room and token kept in the tab's sessionStorage). |

Messages are JSON, one per WebSocket frame or Nearby payload, with their type in `t` (`PROTOCOL_VERSION` 1). A client
says `hello` (with the protocol version; another version gets `error` VERSION), then `create`, `join` (a 6-digit
PIN) or `resume` (the room id and the secret token it was given on joining, after a dropped connection); then
`settings` and `start` (the host), `act` (a decision: the hand, the decision point `step` it was shown, the action),
`ready` (for the next hand) and `leave`. The room sends `welcome`, `joined`, `state` (the room as everyone may see
it), `table` (the player's own seat's view: the engine's `view`, as events since the last one or `full`, with the time
left to decide), `result` (a closed hand: every table's record and points), `history` (the results so far, after a
resume), `error` and `closed`.

## Fair play and security

The room (on the server, or on the hosting phone) is the only authority:

- **The wall** is shuffled by the engine from a seed made in the room with a `SecureRandom`, and the hand's record
  stays in the room: a player is only sent the engine's view of their own seat (their tiles, what everyone may see,
  their legal actions). Records go out with `result`, when the hand is over.
- **A decision** is accepted only from the seat that must decide now, at the decision point it was shown (`step`),
  with an action checked in Kotlin (its kind and tiles) and then found legal by the engine. Points are the engine's,
  never a client's.
- **The pace tells nothing**: after every discard (and added kong) the room waits the same claim window
  (`CLAIM_WINDOW_MS`, 1 s) whether anyone may claim it or not, so a pause does not tell who could; the engine's
  `view` with `"hold": true` stops at the discard for everyone meanwhile (docs/gui-engine.md). A bot's own turn takes
  `BOT_TURN_MS` (0.9 s, as practice's computer players).
- **Running out of time**, or leaving, gives the room's bot's decision (the same bot as the empty seats'). A
  disconnected player is waited for 30 s on each decision, then the bot decides at once; the seat can be taken back
  with its token (only its SHA-256 is kept, compared in constant time). A resume elsewhere closes the old connection.
- **Text from clients** is parsed in one place (`Hub.receive`) into typed messages. Rules are checked against the
  presets and a name pattern and value range, and rebuilt as a spec before the engine sees them (which then checks the
  names); names lose control and format characters (no bidirectional overrides) and are cut at 16 characters.
  Nothing a client sends reaches the engine as text.

What the server limits (the protocol's `HubLimits` and `Limits`, and the environment variables below):

| Limit | Default |
|---|---|
| Message size (WebSocket frame) | 4 KB |
| Messages per connection | 5 a second on average, bursts of 20; 20 bad or refused messages drop the connection |
| Rooms | 200 on a server (`MAX_ROOMS`); 3 open and 20 created an hour per address; 1 on a phone |
| Contest players | 32 on a server, 8 nearby |
| Failed joins or resumes (a wrong PIN or token) | 10 per address per 10 minutes |
| Connections | 2000 (`MAX_CONNECTIONS`), 16 per address (`CONNECTIONS_PER_ADDRESS`) |
| A room's life | 6 hours; a lobby idle for 30 minutes and a finished room after 10 are closed |
| A hand's result | stays up 90 s at most before the next hand |
| A contest's hand | finished by the bots after 30 minutes |
| Engine requests | `ENGINE_WORKERS` processes (2), a queue of `ENGINE_QUEUE` (256), `ENGINE_TIMEOUT_MS` (10 s) each |

The engine is stateless (a hand is its record, replayed on each request; docs/gui-engine.md), so any of the pool's
processes can answer any request, and a crashed one is started again.

## Running it locally

```bash
# The engine (also the server's MAHJONG_BINARY).
cd cpp && cmake -S . -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j 32 --target mahjong && cd ..

# The protocol's and the server's tests, and the server's start script (Gradle 9.8.2 offline: every dependency is
# in the cache at $HOME/.gradle; env.sh sets GRADLE_USER_HOME and puts gradle on the PATH).
source $HOME/env.sh
export TMPDIR=$WORK/tmp
gradle -p server --offline --no-daemon :protocol:test test installDist 2>&1 | tee $WORK/logs/server-build.log

# The server on port 8091, on this machine only. CLAIM_WINDOW_MS and BOT_TURN_MS shorter than the defaults only to test.
MAHJONG_BINARY=$PWD/cpp/build/mahjong HOST=127.0.0.1 PORT=8091 CLAIM_WINDOW_MS=400 BOT_TURN_MS=150 \
  server/build/install/mahjong-server/bin/mahjong-server > $WORK/logs/battle-server-dev.log 2>&1 &
curl -s http://127.0.0.1:8091/health   # ok
```

- The web page: `cd web && npm run dev` (port 5173, or `-- --port 5180`). Vite passes `/ws` on to `BATTLE_SERVER`
  (default `ws://127.0.0.1:8091`), so the page reaches the server at its own address, as behind Caddy. Two browsers
  (or two contexts) can then play each other: `web/scripts/shots-battle.py` does, see web/README.md.
- The app on the emulator: the server's address `10.0.2.2:8091` (the emulator's name for this machine's loopback;
  `ws://` is allowed only for it, localhost and 127.0.0.1, by `res/xml/network_security_config.xml`).
  `android/scripts/shots.py` with `BATTLE_SERVER=10.0.2.2:8091` creates a table game there, see android/README.md.

## The Docker images

`server/Dockerfile` builds, from the repository root (`.dockerignore` keeps the context to `cpp/`, `protocol/`,
`server/`, `web/` and the app's strings):

- `--target runtime`: the battle server (a jlinked JRE, the server and the `mahjong` binary, which runs its unit tests
  in the build), as user 10001, on port 8080.
- `--target site`: Caddy with the web page (the engine built as WebAssembly with emsdk, then `npm run build`) and
  `server/Caddyfile`.

```bash
# JOBS caps each C++ build's jobs (by default every core; this host has 144).
docker build -f server/Dockerfile --target runtime --build-arg JOBS=16 -t mahjong-server:dev . 2>&1 | tee $WORK/logs/docker-build.log
docker build -f server/Dockerfile --target site --build-arg JOBS=16 -t mahjong-site:dev . 2>&1 | tee -a $WORK/logs/docker-build.log

# A quick check: both on a network of their own, the site on port 8094 of this machine.
docker network create mahjong-check
docker run -d --rm --name mahjong --network mahjong-check --read-only --tmpfs /tmp mahjong-server:dev
docker run -d --rm --name mahjong-site --network mahjong-check -e DOMAIN=:80 -p 127.0.0.1:8094:80 mahjong-site:dev
curl -s http://127.0.0.1:8094/health; curl -sI http://127.0.0.1:8094/ | head -3
docker stop mahjong-site mahjong; docker network rm mahjong-check
```

Both built and passed that check on 2026-11-09 (the server image 274 MB, the site 126 MB; about 10 minutes from a
warm cache). This host's Docker is rootless with the legacy builder only (no BuildKit or buildx): the Dockerfile uses
nothing else, and `.dockerignore` names the strings files by explicit paths because the legacy builder ignores a
glob's re-include (`values*/`). With BuildKit elsewhere it should build the same; that is not verified.

`server/compose.yml` runs both: the game server on an internal network only (no ports, no way out: it needs nothing
from the internet), read-only, 2.5 CPUs and 384 MB; Caddy in front, which gets the TLS certificate for `DOMAIN` and
is the only thing reachable:

```bash
DOMAIN=mahjong.example.com docker compose -f server/compose.yml up -d --build
```

### Environment

| Variable | Default | What |
|---|---|---|
| `HOST`, `PORT` | 0.0.0.0, 8080 | Where the server listens. |
| `MAHJONG_BINARY` | /opt/mahjong/mahjong | The engine (`mahjong gui` is run). |
| `ENGINE_WORKERS` | 2 | Engine processes. Each request replays a hand from its record (cheap); two serve many rooms. |
| `ENGINE_QUEUE` | 256 | Requests waiting at most; more are refused (BUSY). |
| `ENGINE_TIMEOUT_MS` | 10000 | A request's time; the process is restarted after one that times out. |
| `MAX_ROOMS` | 200 | Rooms open at once. |
| `MAX_CONNECTIONS`, `CONNECTIONS_PER_ADDRESS` | 2000, 16 | WebSocket connections. |
| `TRUST_PROXY` | 0 | Proxies that append to X-Forwarded-For in front (1 behind Caddy, as in compose.yml). |
| `CLAIM_WINDOW_MS`, `BOT_TURN_MS` | 1000, 900 | The pace (see above); shorter only to test. |

## Hosting: not decided

Nothing is deployed. Where to host the server (and whether) is the project owner's decision, to be asked before
anything goes online. What it needs: a machine with Docker, ports 80 and 443, a domain name pointing at it; one or two
cores and 512 MB are plenty for the defaults. Until then:

- The web page's battles connect to the page's own address (`/ws`): they work wherever the page is served with a
  battle server behind the same address (compose.yml, or `npm run dev` here). A page served alone shows the
  connection error.
- The app has no default server: the player types an address on the Battle screen (it is kept in the settings).
  Once hosting is decided, put the address as the default (`Settings.kt`, `server`) and say so on the screen.
- Phones nearby need no server at all.

## Tests

- `gradle -p server --offline --no-daemon :protocol:test test` (10 tests, with the real engine binary
  `cpp/build/mahjong`, or `MAHJONG_BINARY`: without it the tests that play are skipped, not failed, so build the
  engine first and check that the report says 10 run): `RoomTest`
  (one person and three bots play a hand to its end; two people at a table each see only their own tiles; a decision
  out of turn or not legal is refused; who runs out of time gets the bot's decision; a contest deals everyone the same
  wall; bad settings and unknown rules, a wrong version or PIN are refused; names are cleaned) and `ServerTest` (a
  table hand over real WebSockets; the client addresses the limits count). Not covered by a test yet: resuming,
  leaving, the claim window's timing, the rate limits.
- `web/scripts/shots-battle.py`: two browsers play a one-hand table game and a two-hand contest on the local server,
  one reloading mid-hand; checks both keep the hands and the battles in their records.
- `android/scripts/shots.py` with `BATTLE_SERVER`: the app creates a table game on the local server (allowing
  Android 17's local network access when asked), starts it and shoots its table.
- The app on Android 17 reaches a server on the local network (a private address such as 192.168.x.x, a `.local`
  name, or the emulator's 10.0.2.2) only with the runtime permission `ACCESS_LOCAL_NETWORK`: it asks for it before
  connecting to such an address (`isLocalNetwork` in `battle/Links.kt`); without it the connection just times out.
  A server on the internet needs nothing.
- Nearby rooms were built and type-checked but not run: an emulator has no Nearby Connections. Test them on two
  phones (docs/app.md).
