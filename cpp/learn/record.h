#ifndef LEARN_RECORD_H_
#define LEARN_RECORD_H_

#include "../learn/actions.h"
#include "../learn/outcome.h"

#include <vector>

// A hand as training keeps it (docs/plan.md 3.2, records): the rules, the setup, the wall and every decision, plus
// the decisions marked for training with what is known about their actions. Features and targets are recomputed
// from it by replaying (cpp/learn/expand.h), so a hand takes a few hundred bytes.
namespace Learn {
  // One legal action of a marked decision.
  struct MarkEntry {
    uint8_t action = 0;       // action index
    bool hasProb = false;     // the probability the playing policy gave it
    bool hasOutcome = false;  // how the hand ended after it (stage 0: every action; stage 1: a fork)
    float prob = 0.0f;
    Outcome outcome;
  };
  struct Mark {
    uint16_t decision = 0;  // index into HandRecord::actions
    std::vector<MarkEntry> entries;
  };

  struct HandRecord {
    Rules rules;
    HandSetup setup;                 // with the east seat resolved
    std::vector<uint8_t> wall;       // in drawing order
    bool wallFromSeed = false;       // the wall is wallOf(rules, wallSeed), and stored as the seed
    uint64_t wallSeed = 0;
    uint8_t playerTypes[4] = {0, 0, 0, 0};  // Features::PlayerType
    std::vector<uint8_t> actions;    // the action index of every decision, in the order they were applied
    std::vector<Mark> marks;

    static std::vector<uint8_t> wallOf(const Rules& rules, uint64_t seed);
    // Appends the record; the rules go by their index in the file's table.
    void serialize(std::vector<uint8_t>& out, uint32_t rulesIndex) const;
    static uint32_t rulesIndexOf(const uint8_t* data);
    static HandRecord parse(const uint8_t* data, size_t len, const Rules& rules);
  };

  // The rules as the numbers of Rules::params(), for a file's rules table.
  std::vector<float> rulesToVector(const Rules& rules);
  Rules rulesFromVector(const float* values, int count);

  // The symmetries of the tiles (docs/plan.md section 1): the suits exchanged, each suit reversed (1 <-> 9), the
  // dragons exchanged. Applied to a record's wall and actions it gives another legal hand with the same results.
  struct Symmetry {
    uint8_t suit[3] = {0, 1, 2};    // suit s becomes suit[s]
    bool flip[3] = {false, false, false};
    uint8_t dragon[3] = {0, 1, 2};
    static Symmetry random(uint64_t seed);
    bool identity() const;
    int tile(int t) const;
    // An action index of the original hand, given the claimed tile (-1: none) in the transformed hand.
    int action(int index, int claimTile) const;
  };

  // Replays a record's decisions one at a time, optionally through a symmetry.
  class Replay {
   public:
    Replay(const HandRecord& rec, const Symmetry& sym = Symmetry());
    Game& game() { return game_; }
    const Game& game() const { return game_; }
    int index() const { return next_; }
    bool done() const { return next_ >= (int)rec_.actions.size(); }
    // Who decides next, and the index of its recorded action.
    int seat() const;
    int actionIndex() const;
    // An action index of the record as it is in the replayed hand.
    int mapped(int index) const { return sym_.action(index, game_.claimTile()); }
    void step();

   private:
    const HandRecord& rec_;
    Symmetry sym_;
    Game game_;
    int next_ = 0;
  };
}

#endif  // LEARN_RECORD_H_
