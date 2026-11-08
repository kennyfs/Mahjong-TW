#ifndef GAME_GAME_H_
#define GAME_GAME_H_

#include "../core/rand.h"
#include "../game/rules.h"
#include "../game/tai.h"
#include "../game/tiles.h"

#include <string>
#include <vector>

// One hand (局) of docs/rules.md, as a state machine. The players decide through legalActions and act; flowers,
// replacement draws, flower wins and the end of the wall are handled inside.
//
// Phases:
//   SELF      the current player has drawn (or called) and must win, declare a kong or discard;
//   REACT     players decide on a discard (win, pung, kong, chow or pass), all of them before it is resolved;
//   ROB_KONG  players decide whether to rob an added kong;
//   OVER      the hand is over (result()).

struct HandSetup {
  int dealer = 0;
  int eastSeat = -1;  // the seat whose seat wind is east; -1: the dealer
  int roundWind = 0;  // 0-3: east, south, west, north
  int streak = 0;     // the dealer's streak (連莊)
};

struct Action {
  enum Type : uint8_t { DISCARD = 0, TSUMO, ANKAN, KAKAN, PASS, RON, PON, MINKAN, CHI, NUM_TYPES };
  Type type = PASS;
  uint8_t kind = 0;  // DISCARD, ANKAN, KAKAN: the kind; CHI: the lowest kind of the chow; PON, MINKAN, RON: the discard

  Action() {}
  Action(Type t, int k = 0) : type(t), kind((uint8_t)k) {}
  bool operator==(const Action& o) const { return type == o.type && kind == o.kind; }
  bool operator!=(const Action& o) const { return !(*this == o); }
  std::string toString() const;
};

// The public record of a hand. DRAW events are private to their seat (the tile is hidden from the others).
struct Event {
  enum Type : uint8_t { DRAW, FLOWER, DISCARD, CHI, PON, MINKAN, ANKAN, KAKAN, TSUMO, RON, FLOWER_WIN, EXHAUSTED };
  Type type;
  int8_t seat;
  uint8_t tile;   // DRAW, FLOWER, DISCARD, kongs, wins: the tile; CHI: the called tile
  uint8_t extra;  // DRAW: 1 for a replacement draw; DISCARD: 1 when it was the tile just drawn; CHI: the lowest kind
  std::string toString() const;
};

struct Discard {
  uint8_t kind;
  bool tsumogiri;  // the tile just drawn (摸切)
  bool called;     // taken by a chow, pung or kong
};

struct SeatState {
  Counts hand{};
  std::vector<Meld> melds;
  uint8_t flowers = 0;
  std::vector<Discard> discards;
  bool missedWin = false;  // 過水
  int draws = 0;           // ordinary draws so far
  bool hasDiscarded = false;
  bool declaredKong = false;
};

struct HandResult {
  bool exhausted = false;
  std::vector<int> winners;
  int loser = -1;  // the discarder (or the robbed player); -1 for a self-draw, a flower win paid by all, or a draw
  int winTile = -1;
  bool selfDraw = false;
  bool flowerWin = false;
  std::vector<Tai::Result> tai;  // the hand's tai, per winner
  int dealerTai = 0;             // the dealer's tai (dealer + streak), added to payments involving the dealer
  int baseDelta[4] = {0, 0, 0, 0};
  int taiDelta[4] = {0, 0, 0, 0};
  int nextDealer = 0;
  int nextStreak = 0;

  // The result of a seat in tai, with the base worth `base` tai.
  double points(int seat, double base) const { return baseDelta[seat] * base + taiDelta[seat]; }
  std::string toString(double base) const;
};

// What one seat knows: the setup, its own hand, everyone's melds (others' concealed kongs with kind UNKNOWN),
// flowers and discards, the tiles left, and whose decision it is. A game gives it (Game::positionOf), and a position
// entered by hand (for analysis) loads into a game with the hidden tiles dealt at random (Game::loadPosition).
struct Position {
  static constexpr uint8_t UNKNOWN = 255;
  struct Player {
    std::vector<Meld> melds;
    uint8_t flowers = 0;
    std::vector<Discard> discards;
  };
  HandSetup setup;
  int me = 0;
  Counts hand{};
  // Deciding on its own turn (SELF): the tile just drawn, -1 after a chow or pung (afterCall) or when unknown.
  int drawn = -1;
  bool afterCall = false;
  // Deciding on another player's discard instead: that player, and the tile (-1: its last discard). With robKong,
  // on the tile of its added kong (its pung still shows three).
  int claimSeat = -1;
  int claimTile = -1;
  bool robKong = false;
  Player players[4];
  int wallLeft = -1;  // -1: whatever the tiles leave
};

class Game {
 public:
  enum Phase { SELF, REACT, ROB_KONG, OVER };

  explicit Game(const Rules& rules);

  static std::vector<uint8_t> shuffledWall(const Rules& rules, Rand& rand);
  void start(const HandSetup& setup, Rand& rand);
  void start(const HandSetup& setup, const std::vector<uint8_t>& wall);

  Phase phase() const { return phase_; }
  // The seats that must decide now: the current seat in SELF, the seats with a choice in REACT and ROB_KONG, and only
  // those that have not decided yet.
  std::vector<int> pending() const;
  void legalActions(int seat, std::vector<Action>& out) const;
  // Throws StringError on an illegal action.
  void act(int seat, const Action& a);
  // Held: a discard (or an added kong) that nobody may claim (or rob) does not go on to the next draw; the game stays
  // in REACT (ROB_KONG) with nobody to decide. For showing the discard on its own, before what follows it (a room
  // shows that only after its claim window, docs/gui-engine.md); a held game takes no more actions.
  void hold(bool on) { hold_ = on; }

  const Rules& rules() const { return rules_; }
  const HandSetup& setup() const { return setup_; }
  int dealer() const { return setup_.dealer; }
  int current() const { return cur_; }
  int seatWind(int seat) const { return (seat - eastSeat_ + 4) % 4; }
  const SeatState& seat(int s) const { return seats_[s]; }
  // Tiles that can still be drawn (0 at the end of the wall).
  int wallLeft() const { int n = back_ - front_ - rules_.keep; return n < 0 ? 0 : n; }
  // In REACT: the discard; in ROB_KONG: the kong's tile.
  int claimSeat() const { return claimSeat_; }
  int claimTile() const { return claimTile_; }
  // In SELF: the tile just drawn, -1 after a chow or a pung (and for the dealer's first turn).
  int drawnTile() const { return drawn_; }
  bool afterCall() const { return afterCall_; }
  const std::vector<Event>& events() const { return events_; }
  const HandResult& result() const { return result_; }
  const std::vector<uint8_t>& wall() const { return wall_; }

  // What `seat` knows now (in SELF for the current seat, in REACT and ROB_KONG for the deciding seats).
  Position positionOf(int seat) const;
  // Replaces the state with a position: the tiles `me` cannot see are dealt at random to the other hands (and to the
  // others' unknown concealed kongs) and to the wall. Unknown history counts as plain (no missed win, a draw for each
  // discard). Throws StringError on an impossible position (more than four of a kind, wrong hand size).
  void loadPosition(const Position& p, Rand& rand);

  // The tiles of each kind that `seat` can see: its own hand and melds, the others' melds (except concealed kongs)
  // and every discard not called.
  Counts visibleCounts(int seat) const;

  // The tai `seat` would score by winning now: on its drawn tile in SELF, or on the claim tile in REACT and ROB_KONG.
  // -1 when it cannot win (not complete, missed win, or below min_tai).
  int winTai(int seat) const;
  Tai::WinInfo winInfo(int seat, int tile, bool selfDraw, bool robbedKong) const;

 private:
  const Rules rules_;
  HandSetup setup_;
  int eastSeat_ = 0;
  std::vector<uint8_t> wall_;
  int front_ = 0, back_ = 0;
  SeatState seats_[4];
  Phase phase_ = OVER;
  int cur_ = 0;
  int drawn_ = -1;
  bool afterCall_ = false;
  bool replacementWin_ = false;  // the drawn tile is a replacement after a flower, a concealed or an added kong
  bool anyCall_ = false;         // a chow, pung or exposed kong has been made
  int claimSeat_ = -1, claimTile_ = -1;
  bool claimLast_ = false;  // the claim is on the last discard
  bool hold_ = false;
  // REACT and ROB_KONG: per seat, whether it must decide, its options and its decision.
  bool mustDecide_[4] = {false, false, false, false};
  bool decided_[4] = {false, false, false, false};
  bool canRon_[4] = {false, false, false, false};
  Action decision_[4];
  std::vector<Event> events_;
  HandResult result_;

  void log(Event::Type t, int seat, int tile, int extra = 0);
  // Draws for `seat` (from the back for a replacement), showing and replacing flowers, and enters SELF; or ends the
  // hand at the end of the wall or on a flower win.
  void drawFor(int seat, bool replacement, bool kongReplacement);
  bool checkFlowerWin();
  void enterSelf(int seat, int drawn, bool afterCall);
  void afterDiscard(int seat, int kind);
  void resolveReact();
  void resolveRob();
  void finishWin(const std::vector<int>& winners, int loser, int tile, bool selfDraw, bool robbedKong);
  void finishFlowerWin(int winner, int loser, int tai);
  void finishExhausted();
  void settle();
  bool canChi(int seat, int kind, int low) const;
};

#endif // GAME_GAME_H_
