#ifndef LEARN_BRANCH_H_
#define LEARN_BRANCH_H_

#include "../learn/record.h"
#include "../players/players.h"

// Branches of a recorded hand (docs/plan.md 3.2): another action at one of its decisions, on the same wall.
namespace Learn {
  // The hand of the record with its decision `decision` replaced by `action` (an action index, legal there): replayed
  // to that decision, the action applied, and the seats that decide on the same discard after it given the actions
  // they chose. seatDecisions, if set, gets how many decisions each seat has made by then.
  Game branch(const HandRecord& rec, int decision, int action, int seatDecisions[4] = nullptr);

  // For every mark of the record: the outcome of each legal action of its decision, the hand finished by the given
  // players (stage 0's labels, with greedy players). Entries the mark already has keep their probabilities.
  // Returns how many outcomes were set.
  int labelAllActions(HandRecord& rec, Player* const players[4], Rand& rand);
}

#endif  // LEARN_BRANCH_H_
