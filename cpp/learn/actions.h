#ifndef LEARN_ACTIONS_H_
#define LEARN_ACTIONS_H_

#include "../game/game.h"

// The network's action space (docs/plan.md 3.2): one output per kind for a discard and for a kong on one's own turn
// (concealed or added: at most one of them is possible for a kind), and nine outputs that need no kind.
namespace Learn {
  constexpr int NUM_ACTIONS = 77;
  constexpr int A_DISCARD = 0;  // + kind
  constexpr int A_KONG = 34;    // + kind
  constexpr int A_TSUMO = 68;
  constexpr int A_RON = 69;
  constexpr int A_PASS = 70;
  constexpr int A_PON = 71;
  constexpr int A_MINKAN = 72;
  constexpr int A_CHI = 73;     // + where the claimed tile sits in the chow: 0 lowest, 1 middle, 2 highest
  constexpr int A_DECLARE = 76; // a ready hand (天聽, 地聽), before the discard that goes with it
  constexpr int NUM_SPECIAL = 9;  // the outputs from A_TSUMO on

  // The index of a legal action of `game` (the claimed tile tells the three chows apart).
  int actionIndex(const Game& game, const Action& a);
  // The legal action of `seat` with this index. Throws StringError when it is not legal now.
  Action actionOf(const Game& game, int seat, int index);
}

#endif  // LEARN_ACTIONS_H_
