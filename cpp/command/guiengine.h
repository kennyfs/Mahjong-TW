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
//          Or a hand taken on from another's position with the tiles that seat has not seen dealt again (a review's
//          branch with its unseen tiles reshuffled): {"rules", "setup", "base": {"game": RECORD, "seat": s,
//          "seed": S}, "actions": [...]} (no wall): the base game is replayed, the seat's position taken from it
//          with its history (Game::loadPosition, the seed deals what the seat cannot see), then the actions apply.
// SETUP    {"dealer": s, "east": s (-1: the dealer), "round": wind, "streak": n}
// ACTION   {"type": TYPE, "tile": TILE}: TYPE is discard, tsumo, ankan, kakan, pass, ron, pon, kan, chi, declare; the tile is
//          the discard, the kong's kind, a chow's lowest tile, or (ron, pon, kan) the claimed tile; tsumo, pass and
//          declare (a ready hand, docs/rules.md 5.1; the discard follows as its own action) have none. A chow also lists "tiles" (its three).
// MELD     {"type": chi|pon|kan|ankan|kakan, "tile": T (a chow's lowest; null: another player's concealed kong),
//          "tiles": [T...], "called": T (the claimed tile), "from": s}
// DISCARD  {"tile": T, "drawn": bool (the tile just drawn, 摸切), "called": bool (taken by a meld)}
// POSITION what one seat knows (Position in game.h):
//          {"setup": SETUP, "me": s, "hand": TILES, "drawn": T|null, "after_call": bool, "missed": bool?,
//           "claim": {"seat": s, "tile": T, "rob_kong": bool}|null,
//           "players": [{"melds": [MELD], "flowers": TILES, "discards": [DISCARD]} x4], "wall_left": n}
//          On its own turn (claim null) the hand has 17 tiles less 3 per meld, 16 less 3 per meld otherwise.
//          A player that declared a ready hand also has "declared": tianting|diting|baoting; "declaring": true when the
//          deciding seat has declared and its discard is due.
// EVENT    {"seat": s, "type": draw|flower|discard|chi|pon|kan|ankan|kakan|tsumo|ron|flower_win|exhausted|declare,
//           "tile": T|null}, plus "replacement" (draw), "drawn" (discard), "tiles" (chi), "declared" (declare). Others' draws and concealed
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
//                             "hands": [TILES x4] when over or with reveal,
//                             "ready": {T: [T]}: on the seat's own turn to discard, each legal discard that leaves
//                             the hand ready, with what it then waits on (聽牌提示; {} otherwise)}
//   {"cmd": "act", "game": RECORD, "seat": s, "action": ACTION}      checked, appended -> {"game": RECORD}
//   {"cmd": "bot", "game": RECORD, "seat": s, "player": SPEC, "seed": S?}   -> {"action": ACTION}
//   {"cmd": "advance", "game": RECORD, "players": [SPEC|null x4], "seed": S?, "steps": n?}
//       The bots (non-null) decide until a null seat must decide or the hand is over, or for at most `steps` decision
//       points (each one: every bot that must decide then)  -> {"game": RECORD}
//   {"cmd": "pending", "game": RECORD}
//       Who must decide now, as the room (the authority) sees it  -> {"phase", "current", "pending": [seats that must
//       still decide], "actions": n (the record's), "event_count": n}
//   {"cmd": "next", "game": RECORD, "start_dealer": s, "seed": S?}
//       The next hand of a game (一將) after this one: the dealer and streak from the result, the round wind moving on
//       when the deal comes back to start_dealer  -> {"game": RECORD, "game_over": bool (after the north round)}
//   {"cmd": "analyze", "game": RECORD, "seat": s, "model": FILE?, "hand": TILES?, "drawn": T?, "seed": S?}
//   or  {"cmd": "analyze", "rules": SPEC, "position": POSITION, "seed": S?, "model": FILE?}
//       Every legal action of the seat  -> ANALYSIS: {"source": "efficiency", "actions": [FIGURES]}, FIGURES being
//       {"action": ACTION, "shanten": n (-1 a win), "ukeire": n, "waits": [{"tile", "left", "ron_tai", "tsumo_tai"}],
//        "win_tai": n}.
//       With a model (a network's .onnx; a build with ONNX Runtime): "source": "network", and each FIGURES also has
//       "q" (the expected result of the hand, in points), "policy" (how often the network chooses it), "win", "tsumo",
//       "deal_in", "others_tsumo" (the chances of those ends), "tai" (of a win), "parts": [3] (what winning, dealing in
//       and others' self-draws add to q), and for a discard "danger" (the chance that it deals in now),
//       "danger_each": [3] and "danger_tai": [3] (per opponent: the seats 1, 2, 3 places after the deciding one); the
//       answer also has "best" (an index into actions), "value" (its q), "policy_value", and "opponents":
//       [{"seat", "ready": [4] (the chances of being ready, 1, 2, 3 or more tiles away), "danger": [34 kinds],
//         "danger_tai": [34]} x3].
//       A game with "hand" (and, on the seat's own turn, "drawn": the tile drawn of it): the seat's position in the
//       game with that hand in place of its own. The table stays the game's and so does its history, which the
//       network reads (how long ago a seat called, which kinds are safe against whom); a POSITION by itself has only
//       the plain history of its discards, so with calls on the table its figures differ from the game's.
//   {"cmd": "model", "model": FILE?}   -> {"available": bool (this build runs networks), "features": version
//       (when the file loads)}
//   {"cmd": "review", "game": RECORD (a finished hand), "seat": s, "model": FILE?}
//       Every decision of the seat that had a choice  -> {"seat", "decisions": [{"step": the record's action index,
//       "played": index into the analysis' actions, "analysis": ANALYSIS, "truth": [{"seat", "shanten", "missed":
//       bool (it passed a win this turn), "waits": [{"tile", "tai"}]} x3 opponents], "loss": best q - played q}],
//       "result": the seat's points, and with a model "first_value" (the value at the first decision), "loss" (the
//       decisions' sum), "luck" (result - first_value + loss), "unit" (a typical payment under the rules)}
//   {"cmd": "steps", "game": RECORD}
//       Which actions took effect, for a review to step through  -> {"taken": [bool per action: everything on a
//       seat's own turn; of the answers to a discard or an added kong only the claim that was made (a win before a
//       pung or a kong before a chow; a pass never)], "over": [per action: the index of the claim that took its
//       place, or -1], "last": the action the play ended with (the first win taken; without a win the last action
//       taken; -1 without any)}
//   {"cmd": "whatif", "model": FILE, "seed": S?, and "game": RECORD, "seat": s, "hand": TILES?, "drawn": T?  or
//    "rules": SPEC, "position": POSITION}
//       The seat's hand as it rests between turns (without the tile just drawn), tried with every kind it has not
//       seen  -> {"draws": [{"tile", "left", "value", "action": the best ACTION after drawing it, "figures": its
//       FIGURES, "next": the second best ACTION|null, "next_loss"}], "claims": [{"seat", "tile", "left", "best":
//       index, "options": [FIGURES]} for each discard of another seat that leaves a choice]}
//   {"cmd": "tai", "rules": SPEC, "hand": TILES, "melds": [MELD], "win": T, "tsumo", "robbed", "last",
//    "replacement", "heavenly", "earthly", "human": bool?, "seat_wind", "round_wind": wind?, "flowers": TILES?}
//       -> {"total": n, "items": [...], "waits": TILES (before the winning tile)}
//   {"cmd": "rules", "rules": SPEC?}
//       -> {"spec": full spec, "values": {param: value}, "tai_presets": [...], "flower_presets": [...],
//           "items": [{"param", "name"}]}
// Player SPECs as in players.h (random, greedy, net:model=FILE[,mode=q|policy|sample]). Seeds are strings; without
// one, a random seed.
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
    // A hand taken on from another's position (RECORD above): its "base" as given, null for a hand from its deal.
    nlohmann::json base;
  };
  nlohmann::json record(const Record& r);
  Record parseRecord(const nlohmann::json& j);
  // Starts `game` (made with the record's rules) and applies the actions; throws StringError on an illegal one.
  // `holdLast`: the last action is applied held (Game::hold): a last discard nobody may claim stops there.
  void replay(const Record& r, Game& game, bool holdLast = false);
}

#endif // COMMAND_GUIENGINE_H_
