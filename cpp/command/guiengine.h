#ifndef COMMAND_GUIENGINE_H_
#define COMMAND_GUIENGINE_H_

#include "../game/game.h"
#include "../players/players.h"

#include <nlohmann/json.hpp>

#include <map>
#include <memory>

// The engine of the GUIs (the Android app through JNI, the web page as WebAssembly, `mahjong gui` on stdin and
// stdout): one JSON request, one JSON answer; an error answers {"error": MESSAGE}. It keeps no game: a hand travels as
// its RECORD, replayed on every request (well under a millisecond). docs/gui-engine.md has examples.
//
// Tiles are names: "1m"-"9m", "1p"-"9p", "1s"-"9s", "1z"-"7z" (東 南 西 北 白 發 中), "1f"-"8f" (春 夏 秋 冬 梅 蘭 菊 竹);
// TILES is a list of them in one string ("123m55z"). Seats are 0-3 in turn order (each seat's next player, 下家, is
// seat + 1 mod 4). Winds are 0-3: east, south, west, north.
//
// RECORD   {"rules": SPEC, "setup": SETUP, "wall": "1m5z3p...", "actions": [[seat, TYPE] or [seat, TYPE, TILE], ...]}
//          The wall in drawing order (ordinary draws from the front, replacements from the back), two characters a
//          tile. The actions are every decision in order; on a discard the deciding seats' decisions may come in any
//          order. SPEC is a rules spec of docs/rules.md section 11.
// SETUP    {"dealer": s, "east": s (-1: the dealer), "round": wind, "streak": n}
// ACTION   {"type": TYPE, "tile": TILE}: TYPE is discard, tsumo, ankan, kakan, pass, ron, pon, kan, chi; the tile is
//          the discard, the kong's kind, a chow's lowest tile, or (ron, pon, kan) the claimed tile; tsumo and pass
//          have none. A chow also lists "tiles" (its three).
// MELD     {"type": chi|pon|kan|ankan|kakan, "tile": T (a chow's lowest; null: another player's concealed kong),
//          "tiles": [T...], "called": T (the claimed tile), "from": s}
// DISCARD  {"tile": T, "drawn": bool (the tile just drawn, 摸切), "called": bool (taken by a meld)}
// POSITION what one seat knows (Position in game.h):
//          {"setup": SETUP, "me": s, "hand": TILES, "drawn": T|null, "after_call": bool,
//           "claim": {"seat": s, "tile": T, "rob_kong": bool}|null,
//           "players": [{"melds": [MELD], "flowers": TILES, "discards": [DISCARD]} x4], "wall_left": n}
//          On its own turn (claim null) the hand has 17 tiles less 3 per meld, 16 less 3 per meld otherwise.
// EVENT    {"seat": s, "type": draw|flower|discard|chi|pon|kan|ankan|kakan|tsumo|ron|flower_win|exhausted,
//           "tile": T|null}, plus "replacement" (draw), "drawn" (discard), "tiles" (chi). Others' draws and concealed
//          kongs have tile null unless revealed.
// RESULT   {"exhausted", "winners": [{"seat", "tai", "items": [{"item": PARAM, "name": 中文, "tai"}]}], "loser": s|-1,
//           "win_tile": T|null, "self_draw", "flower_win", "dealer_tai", "points": [4] (base + tai, in tai, with the
//           rules' base), "base_delta": [4], "tai_delta": [4], "next_dealer", "next_streak"}
//
// Requests:
//   {"cmd": "new", "rules": SPEC, "setup": SETUP?, "seed": S?}       a new hand    -> {"game": RECORD}
//   {"cmd": "view", "game": RECORD, "seat": s, "since": n?, "reveal": bool?}
//       What seat s sees  -> {"rules": full spec, "position": POSITION, "phase": self|react|rob_kong|over,
//                             "current": s, "seat_winds": [4], "my_turn": bool, "legal": [ACTION],
//                             "events": [EVENT from index `since`], "event_count": n, "result": RESULT|null,
//                             "hands": [TILES x4] when over or with reveal}
//   {"cmd": "act", "game": RECORD, "seat": s, "action": ACTION}      checked, appended -> {"game": RECORD}
//   {"cmd": "bot", "game": RECORD, "seat": s, "player": SPEC, "seed": S?}   -> {"action": ACTION}
//   {"cmd": "advance", "game": RECORD, "players": [SPEC|null x4], "seed": S?}
//       The bots (non-null) decide until a null seat must decide or the hand is over  -> {"game": RECORD}
//   {"cmd": "next", "game": RECORD, "start_dealer": s, "seed": S?}
//       The next hand of a game (一將) after this one: the dealer and streak from the result, the round wind moving on
//       when the deal comes back to start_dealer  -> {"game": RECORD, "game_over": bool (after the north round)}
//   {"cmd": "analyze", "game": RECORD, "seat": s}  or  {"cmd": "analyze", "rules": SPEC, "position": POSITION, "seed": S?}
//       Every legal action of the seat  -> {"source": "efficiency", "actions": [{"action": ACTION, "shanten": n
//       (-1 a win), "ukeire": n, "waits": [{"tile", "left", "ron_tai", "tsumo_tai"}], "win_tai": n}]}
//   {"cmd": "tai", "rules": SPEC, "hand": TILES, "melds": [MELD], "win": T, "tsumo", "robbed", "last",
//    "replacement", "heavenly", "earthly", "human": bool?, "seat_wind", "round_wind": wind?, "flowers": TILES?}
//       -> {"total": n, "items": [...], "waits": TILES (before the winning tile)}
//   {"cmd": "rules", "rules": SPEC?}
//       -> {"spec": full spec, "values": {param: value}, "tai_presets": [...], "flower_presets": [...],
//           "items": [{"param", "name"}]}
// Player SPECs as in players.h (random, greedy). Seeds are strings; without one, a random seed.
class GuiEngine {
 public:
  // Throws StringError on a bad request.
  nlohmann::json handle(const nlohmann::json& req);
  // One request line to one answer line.
  std::string handleLine(const std::string& line);

 private:
  std::map<std::string, std::unique_ptr<Player>> players;
  Player* player(const std::string& spec);
};

// JSON forms (also for tests and tools).
namespace GuiJson {
  nlohmann::json action(const Action& a, int claimTile);
  Action parseAction(const nlohmann::json& j);
  nlohmann::json position(const Position& p);
  Position parsePosition(const nlohmann::json& j);

  struct Record {
    std::string rules;
    HandSetup setup;
    std::vector<uint8_t> wall;
    std::vector<std::pair<int, Action>> actions;
  };
  nlohmann::json record(const Record& r);
  Record parseRecord(const nlohmann::json& j);
  // Starts `game` (made with the record's rules) and applies the actions; throws StringError on an illegal one.
  void replay(const Record& r, Game& game);
}

#endif // COMMAND_GUIENGINE_H_
