#ifndef LEARN_SAMPLING_H_
#define LEARN_SAMPLING_H_

#include "../core/rand.h"
#include "../game/game.h"

// The rules and the setup of a training hand (docs/plan.md 3.4, rules drawn per hand).
namespace Learn {
  // "mix" draws by the plan's table; anything else is a rules spec used for every hand.
  Rules sampleRules(const std::string& spec, Rand& rand);
  // A random dealer and round wind, the streak 0 to 3 (60, 25, 10, 5%); the dealer is east nine times in ten.
  HandSetup sampleSetup(Rand& rand);
}

#endif  // LEARN_SAMPLING_H_
