#ifndef PLAYERS_ANALYSIS_H_
#define PLAYERS_ANALYSIS_H_

#include "../game/game.h"

#include <vector>

// Figures for each legal action of a seat, from what that seat can see: tile efficiency now, the network's estimates
// later.
namespace Analysis {
  struct Wait {
    int tile;
    int left;      // copies the seat has not seen
    int ronTai;    // the hand's tai winning on it by discard (-1: below min_tai)
    int tsumoTai;  // and by self-draw
  };
  struct ActionInfo {
    Action action;
    // After the action (after a chow or pung, after its best discard): tiles from ready, -1 for a win.
    int shanten = 99;
    // Unseen tiles that would lower it.
    int ukeire = 0;
    // When ready after the action: what it waits on.
    std::vector<Wait> waits;
    // TSUMO and RON: the hand's tai.
    int winTai = -1;
  };

  // Every legal action of `seat` (none when it has nothing to decide).
  std::vector<ActionInfo> efficiency(const Game& game, int seat);
}

#endif // PLAYERS_ANALYSIS_H_
