#ifndef COMMAND_PLAY_H_
#define COMMAND_PLAY_H_

#include "../core/rand.h"
#include "../game/game.h"
#include "../players/players.h"

#include <functional>

namespace Play {
  // Plays a started hand to the end, asking players[seat] for each decision.
  // `onDecision`, if set, sees each decision after it is made.
  void playOut(Game& game, Player* const players[4], Rand& rand,
               const std::function<void(const Game&, int, const Action&)>& onDecision = nullptr);

  // A random setup for single-hand play: dealer 0, a random round wind and a streak of 0 to 3 (60%, 25%, 10%, 5%).
  HandSetup randomSetup(Rand& rand);
}

#endif // COMMAND_PLAY_H_
