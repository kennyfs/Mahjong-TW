#ifndef LEARN_FEATURES_H_
#define LEARN_FEATURES_H_

#include "../game/game.h"
#include "../learn/actions.h"

// What the networks see (docs/plan.md 3.2): 34 kind tokens, 4 seat tokens (the deciding seat first, then the seats
// after it) and one global vector. The observation (O) holds what the deciding seat may know; the full state (F)
// adds what only training knows, for the critic. Nothing names a suit or a wind: a kind's features say what it is to
// the players (round wind, somebody's seat wind), and the network adds the kind's type and rank by position, so
// exchanging suits changes nothing.
namespace Features {
  constexpr int VERSION = 1;
  constexpr int NUM_KINDS = Tiles::NUM_KINDS;
  constexpr int NUM_SEATS = 4;

  // ----- Kind tokens, observation -----
  enum KindObs : int {
    KO_HAND = 0,                         // 4: I hold at least 1, 2, 3, 4
    KO_DRAWN = KO_HAND + 4,              // 1: the tile just drawn
    KO_CLAIM,                            // 1: the tile on offer (a discard, or the tile of an added kong)
    KO_MELD,                             // 4 seats x 6: lowest, middle, highest tile of a chow (count / 2), pung,
                                         //   exposed or added kong, concealed kong (when its kind is known)
    KO_DISC = KO_MELD + 4 * 6,           // 4 seats x 8: count / 4, any, any from the hand, any straight from the
                                         //   draw, when the first one was (its number / 20), how recent the last
                                         //   one is (1 / (1 + the seat's discards since)), the seat's latest
                                         //   discard, taken by a call
    KO_UNSEEN = KO_DISC + 4 * 8,         // 4: at least 1, 2, 3, 4 copies I have not seen
    KO_SAFE = KO_UNSEEN + 4,             // 3 opponents: it cannot win on this kind now (discarded since its last
                                         //   discard and let go: docs/rules.md 5.4)
    KO_ROLE = KO_SAFE + 3,               // 5: the round wind; the seat wind of each seat
    KO_CAN_DISCARD = KO_ROLE + 5,        // 1
    KO_DISCARD_SHANTEN,                  // 5: tiles from ready after discarding it: 0, 1, 2, 3, 4 or more
    KO_DISCARD_BEST = KO_DISCARD_SHANTEN + 5,  // 1: no discard leaves fewer
    KO_DISCARD_UKEIRE,                   // 2: unseen tiles that improve the hand after it / 40, their kinds / 13
    KO_DISCARD_READY = KO_DISCARD_UKEIRE + 2,  // 4: when it leaves the hand ready: unseen copies of the waits / 12,
                                         //   kinds of waits / 5, mean tai of a win by discard / 8, by self-draw / 8
    KO_CAN_KONG = KO_DISCARD_READY + 4,  // 2: a kong of it is legal; and leaves the hand no further from ready
    KO_DRAW = KO_CAN_KONG + 2,           // 4: getting one improves my hand; wins it; then the tai by discard / 8 and
                                         //   by self-draw / 8. On my own turn: after some discard that leaves the
                                         //   fewest tiles from ready.
    KIND_OBS = KO_DRAW + 4
  };

  // ----- Seat tokens, observation -----
  enum SeatObs : int {
    SO_REL = 0,                  // 4: me, the next seat, the seat across, the previous seat
    SO_DEALER = SO_REL + 4,      // 1
    SO_WIND,                     // 4: seat wind
    SO_MELDS = SO_WIND + 4,      // 4: melds / 5, exposed ones / 5, kongs / 4, concealed kongs / 4
    SO_HAND = SO_MELDS + 4,      // 1: tiles in hand / 17
    SO_FLOWERS,                  // 5: flowers / 8, of its own seat / 2, seasons / 4, plants / 4, their tai / 8
    SO_DISCARDS = SO_FLOWERS + 5,  // 1: discards made / 20
    SO_TSUMOGIRI,                // 2: the last discard came straight from the draw; how many in a row / 5
    SO_CALL = SO_TSUMOGIRI + 2,  // 2: has called; discards since its last call / 10
    SO_TURN = SO_CALL + 2,       // 2: discarded the tile on offer; is on turn
    SEAT_OBS = SO_TURN + 2
  };

  // ----- The global vector, observation -----
  // The special actions (Learn::A_TSUMO on) each get a block of GA_SIZE numbers.
  enum SpecialAction : int {
    GA_LEGAL = 0,      // 1
    GA_SHANTEN,        // 5: tiles from ready after it (after a chow or pung: after its best discard): 0 to 4 or more
    GA_LOWERS = GA_SHANTEN + 5,  // 1: fewer than now
    GA_UKEIRE,         // 1: improving tiles after it / 40
    GA_READY,          // 3: ready after it: unseen copies of the waits / 12, mean tai by discard / 8, by self-draw / 8;
                       //   for a win: the hand's tai / 8 in the slot of how it is won
    GA_SIZE = GA_READY + 3
  };
  extern const int NUM_RULE_FEATURES;  // 3 for the flower mode, then every other parameter of Rules::params()
  enum GlobalObsFixed : int {
    GO_DEALER_TAI = 0,              // 1: the dealer's tai this hand / 8
    GO_STREAK,                      // 1: / 4
    GO_ROUND_WIND,                  // 4
    GO_WALL = GO_ROUND_WIND + 4,    // 2: tiles left to draw / 80; none left
    GO_TURN = GO_WALL + 2,          // 1: discards so far / 80
    GO_FLOWERS_LEFT,                // 1: flowers not shown yet / 8
    GO_SHANTEN,                     // 6: my hand: complete, ready, 1, 2, 3, 4 or more from ready
    GO_MISSED = GO_SHANTEN + 6,     // 1: I am barred from winning (missed win)
    GO_PHASE,                       // 4: my turn after a draw, my turn after a call, a discard to answer, a kong to rob
    GO_ACTIONS = GO_PHASE + 4,      // NUM_SPECIAL x GA_SIZE
    GO_RULES = GO_ACTIONS + Learn::NUM_SPECIAL * GA_SIZE  // NUM_RULE_FEATURES
  };
  int globalObsSize();

  // ----- The full state, added for the critic -----
  enum KindFull : int {
    KF_HAND = 0,                // 3 opponents x 4: it holds at least 1, 2, 3, 4 in hand
    KF_ANKAN = KF_HAND + 12,    // 3: its concealed kong
    KF_WAIT = KF_ANKAN + 3,     // 3 x 3: it is ready and waits on this kind; the tai of that win by discard / 8 and
                                //   by self-draw / 8
    KF_USEFUL = KF_WAIT + 9,    // 3: one more of this kind improves its hand
    KF_NEXT = KF_USEFUL + 3,    // 4 seats: 1 / (1 + how many of its draws come before it draws this kind), if nobody
                                //   calls; 0 when it never does
    KF_WALL = KF_NEXT + 4,      // 3: copies among the tiles still to be drawn / 4; 1 / (1 + its place among the
                                //   replacement tiles); copies left over at the end / 4
    KIND_FULL = KF_WALL + 3
  };
  enum SeatFull : int {
    SF_SHANTEN = 0,             // 6: complete, ready, 1, 2, 3, 4 or more from ready
    SF_MISSED = SF_SHANTEN + 6, // 1
    SF_UKEIRE,                  // 1: tiles it has not seen that improve its hand / 40
    SF_WAITS,                   // 2: copies of its waits still to be drawn / 8, in the other hands / 8
    SF_NEXT_DRAW = SF_WAITS + 2,  // 2: its next draw (if nobody calls) improves its hand; wins it
    SF_PLAYER = SF_NEXT_DRAW + 2, // 3: who plays it: the learning network, another network, a scripted player
    SEAT_FULL = SF_PLAYER + 3
  };
  constexpr int GLOBAL_FULL = 1;  // reserved (always 0)

  // Who plays a seat (SF_PLAYER).
  enum PlayerType : uint8_t { PLAYER_LEARNER = 0, PLAYER_NET = 1, PLAYER_SCRIPT = 2 };

  // The observation of `seat`, which must have a decision to make. kind: [34 * KIND_OBS], seats:
  // [4 * SEAT_OBS], global: [globalObsSize()], legal: [Learn::NUM_ACTIONS] (1 for legal actions).
  void observation(const Game& game, int seat, float* kind, float* seats, float* global, uint8_t* legal);
  // The extra features of the full state. kind: [34 * KIND_FULL], seats: [4 * SEAT_FULL], global: [GLOBAL_FULL].
  void fullState(const Game& game, int seat, const uint8_t playerTypes[4], float* kind, float* seats, float* global);

  // Unseen tiles (by `visible`) that lower the shanten of a hand of 3 numSets + 1 tiles, and their kinds as a mask.
  int improving(const Counts& hand, int numSets, const Counts& visible, int shanten, uint64_t* kinds);
}

#endif  // LEARN_FEATURES_H_
