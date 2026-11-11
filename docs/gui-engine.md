# The GUI engine

`cpp/command/guiengine.h` is the engine every interface uses: the Android app (through JNI), the web page (the
same C++ built to WebAssembly) and `mahjong gui` (one JSON request per line on stdin, one answer per line on
stdout, for a server and for tests). The header lists every request and every JSON form; this page shows how they
fit together. The design follows Thirteen's GuiEngine (`$HOME/Thirteen/cpp/command/guiengine.h`).

## Stateless: a hand is its record

The engine keeps no game. A hand is a RECORD: the rules, the setup (dealer, east seat, round wind, streak), the
whole wall in drawing order, and every decision so far. Each request carries the record and the engine replays it
(well under a millisecond), so the caller owns the state, can save it as is (a finished record is a full replay of
the hand), and can resume anywhere.

The record holds the wall, so it shows every hidden tile. **Only the authority holds it**: the app in practice, the
room (on the server, or on the phone that hosts a nearby game) in a battle. A player's screen is built only from
`view` for that player's seat, which hides what the seat cannot see: other players' hands and draws, the kinds of
their concealed kongs, and, while several players may claim a discard, which of them still have to decide.

## A practice hand

```
>> {"cmd":"new","rules":"simple1,see","setup":{"dealer":0,"east":-1,"round":0,"streak":0},"seed":"demo"}
<< {"game":{"rules":"simple1,see","setup":{...},"wall":"7z2p9p7s9s5f2s4m...","actions":[]}}
```

The human plays seat 1; the bots play the others. `advance` lets the bots decide until the human must (or the hand
is over):

```
>> {"cmd":"advance","game":GAME,"players":["greedy",null,"greedy","greedy"],"seed":"demo/0"}
<< {"game":{..., "actions":[[0,"discard","4z"]]}}
```

`view` gives the screen of a seat: the seat's POSITION (its hand, the drawn tile, every player's melds, flowers
and discards, tiles left in the wall), the phase, whose turn it is, the seat's legal actions, and the events since
an index (to animate only what is new):

```
>> {"cmd":"view","game":GAME,"seat":1}
<< {"phase":"self","current":1,"my_turn":true,"seat_winds":[0,1,2,3],
    "position":{"me":1,"hand":"134589m349p24579s147z","drawn":"7z",...,"wall_left":...},
    "legal":[{"type":"discard","tile":"1m"},{"type":"discard","tile":"3m"},...],
    "events":[{"seat":1,"type":"flower","tile":"5f"},{"seat":2,"type":"flower","tile":"2f"},
              {"seat":0,"type":"flower","tile":"6f"},{"seat":1,"type":"flower","tile":"7f"},
              {"seat":0,"type":"discard","tile":"4z","drawn":false},
              {"seat":1,"type":"draw","tile":"7z","replacement":false}],
    "event_count":6,"result":null}
```

The human's choice goes in with `act` (checked against the legal actions, appended to the record), then the bots
again:

```
>> {"cmd":"act","game":GAME,"seat":1,"action":{"type":"discard","tile":"1m"}}
<< {"game":{..., "actions":[[0,"discard","4z"],[1,"discard","1m"]]}}
>> {"cmd":"advance","game":GAME,"players":["greedy",null,"greedy","greedy"],"seed":"demo/1"}
<< {"game":{..., "actions":[...,[1,"discard","1m"],[2,"pass"],[2,"discard","2z"],[3,"discard","2z"],[0,"discard","5z"]]}}
>> {"cmd":"view","game":GAME,"seat":1,"since":6}
<< {..., "events":[{"seat":1,"type":"discard","tile":"1m","drawn":false},
                   {"seat":2,"type":"draw","tile":null,"replacement":false},
                   {"seat":2,"type":"discard","tile":"2z","drawn":true}, ...], "event_count":14}
```

`[2,"pass"]` is seat 2 declining to chow the 1m (only seats that may claim a discard decide on it). Other players'
draws come with tile null; `drawn` on a discard tells a tile thrown straight from the draw (摸切) from one taken from
the hand.

Loop: `view` (show, animate the new events), `act` when it is the human's turn, `advance`, until `phase` is
`over`. Then `view` has `result` (winners and their tai items, payments, the next dealer and streak) and `hands`
(every hand, revealed). `next` deals the next hand of a game (一將): dealer and streak from the result, the round
wind moving on when the deal comes back to the starting dealer, `game_over` after the north round.

An authority that paces the bots (a battle room, or practice showing each bot's turn on its own) advances one
decision point at a time: `advance` with `"steps": 1` lets every bot that must decide now decide, once, and stops.
`pending` tells it who must decide, as only the authority may know (a seat's `view` never says which other seats
still have to decide on a discard):

```
>> {"cmd":"pending","game":GAME}
<< {"phase":"react","current":0,"pending":[1,3],"actions":12,"event_count":17}
```

A room shows every discard at once and what follows it (the next draw, or a claim) only after a short claim window
that is the same whether anyone may claim or not, so the pause tells nobody who could. A `view` with `"hold": true`
stops at a last discard (or added kong) that nobody may claim (or rob): its last event is the discard, `phase` is
`react` (`rob_kong`) with nobody to decide, and the next draw is not in it yet. Where someone may claim, it is the
plain view.

Bot seeds: a bot spec that uses randomness (`random`, the network later with sampling) decides from the request's
seed. Pass a seed built from the hand and the record's length (as above) and a replay, or another player of the
same hand, meets the same bot choices. `greedy` uses no randomness.

## Decisions on a discard

After a discard the phase is `react`: every seat that may claim it (ron, pon, kan, chi for the next player) decides,
in any order, and the engine resolves them together (ron over pon/kan over chi; 截胡: of several
winners, the first after the discarder). A seat with nothing to claim is not asked. `my_turn` says whether this seat must still decide; the legal
actions then include `pass`. A chow is `{"type":"chi","tile":LOWEST,"tiles":[...]}`; the claimed tile of ron, pon
and kan is filled in by the engine. `rob_kong` is the same for a 加槓 that someone may rob (ron or pass).

## Analysis

```
>> {"cmd":"analyze","game":GAME,"seat":1}
<< {"source":"efficiency","actions":[
     {"action":{"type":"discard","tile":"1m"},"shanten":4,"ukeire":44,"waits":[],"win_tai":-1},
     {"action":{"type":"discard","tile":"9p"},"shanten":4,...}, ...]}
```

One entry per legal action of the seat, from what that seat sees: shanten after it (−1 a win), ukeire (unseen tiles
that lower the shanten), and when it leaves the hand ready, the waits with copies left and the tai by discard and by
self-draw. After a pung or chow the figures are those after its best discard. This is tile efficiency only; when the
network exists, `source` becomes `network` and each action gets its probability and expected score as well (the
fields will be added, none removed), so a screen should show what is present.

The analysis screen (a position set up by hand) sends a POSITION instead of a game:

```
>> {"cmd":"analyze","rules":"simple1,none","position":{"setup":{"dealer":0,"east":-1,"round":0,"streak":0},
     "me":0,"hand":"123456789m234s5m7z","drawn":"5m","after_call":false,"claim":null,
     "players":[{"melds":[{"type":"ankan","tile":"9p"}],"flowers":"","discards":[]},
                {"melds":[],"flowers":"","discards":[]},{"melds":[],"flowers":"","discards":[]},
                {"melds":[],"flowers":"","discards":[]}],
     "wall_left":40}}
```

The engine fills the unseen tiles at random (the seed), so the hand must be possible: 17 tiles less 3 per meld on
one's own turn (the drawn tile among them), 16 less 3 per meld when deciding on another player's discard (`claim`
names the seat and tile), at most four of a kind counting everything visible. A bad position answers an error that
says why; show it.

## Scoring and rules

`tai` scores a winning hand (the scoring help screen, and checking a hand entered by hand). `rules` turns a spec
into the full spec and every parameter's value, and lists the presets: tai presets `simple1`, `simple2`, `standard`, `masters`
and flower presets `none`, `see`, `seat` (in that order: the order to show them). A spec is a comma list of presets
and `param=value` (docs/rules.md section 11), e.g. `simple1,see,base=2`; flowers and tai are set apart, so a settings
screen has the two preset rows and an advanced list of parameters (the `items` give each tai item's Chinese name).

## Errors

Every failure answers `{"error": MESSAGE}` (a bad request, an illegal action, an impossible position), never a crash.
A client shows it or, for an action, treats it as a bug: it only sends actions from `legal`.

## Embedding

The answer is a function of the request line, so the embedding is a few lines, as in Thirteen:

- Android: a JNI function `handle(String): String` over one `GuiEngine` (Thirteen: `android/app/src/main/cpp/`),
  called off the main thread.
- Web: emscripten builds `engine_handle(const char*)` (Thirteen: `web/engine/engine.cpp`, `web/build-engine.sh`)
  into a Web Worker.
- Server: a pool of `mahjong gui` processes, one request line in, one answer line out (Thirteen:
  `server/.../EnginePool.kt`, and `protocol/.../GameEngine.kt` for the requests a room makes).

Requests are built by the client from checked values; nothing a remote player sends reaches the engine as text.

## Declared ready hands

Under rules with `tianting` or `diting` (docs/rules.md 5.1) a seat's actions on its first turn may include
`{"type":"declare"}` (in a record: `[seat,"declare"]`). After it the same seat decides again, and its only actions
are the discards that leave the hand ready. The event is `{"seat":s,"type":"declare","tile":null,"declared":
"tianting"|"diting"}`. In a position, a player that has declared carries `"declared":"tianting"|"diting"`, and
`"declaring":true` says that the deciding seat has declared and still owes the discard; the `tai` request takes
`"declared"` as well. A declared seat is only asked about a discard it can win on, and on its own turn it has one
discard (the tile drawn) besides a win.

