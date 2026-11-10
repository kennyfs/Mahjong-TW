#ifndef LEARN_EXPAND_H_
#define LEARN_EXPAND_H_

#include "../learn/features.h"
#include "../learn/record.h"

// From a hand's record to a training sample: the inputs of both networks at a marked decision, what is known about
// its actions, and the labels of the heads (docs/plan.md 3.3). Everything is recomputed by replaying the hand.
namespace Learn {
  constexpr int INFO_SIZE = 8;
  // info: 0 the base; 1 the dealer's tai; 2 my result of the hand as played; 3 the decision: 0 my turn, 1 a discard
  // to answer, 2 a kong to rob; 4 legal actions; 5 the decision's place in the hand (0 to 1); 6 tiles left to draw;
  // 7 my seat.
  constexpr int FUTURE_SIZE = 11;
  // future: 0 I won; 1 my hand's tai; 2-5 I was ready (or had won) within my next 1, 2, 4, 8 draws; 6 draws left in
  // the hand (anybody's, replacements too); 7-10 how the seat r places after me ended: 0 complete, 1 ready, 2 one
  // tile from ready, 3 two, 4 three or more.

  // Where one sample goes: a row of each array of a batch.
  struct Row {
    // Inputs (cpp/learn/features.h).
    float* kind;        // [34 * KIND_OBS]
    float* seat;        // [4 * SEAT_OBS]
    float* global;      // [globalObsSize()]
    float* kindFull;    // [34 * KIND_FULL]
    float* seatFull;    // [4 * SEAT_FULL]
    float* globalFull;  // [GLOBAL_FULL]
    uint8_t* legal;     // [NUM_ACTIONS]
    // The decision.
    int32_t* played;    // [1] the action played
    float* mu;          // [NUM_ACTIONS] the playing policy, where it was recorded
    uint8_t* known;     // [NUM_ACTIONS] 1: the hand's end after this action is known
    uint16_t* events;   // [NUM_ACTIONS] Outcome::events from my seat
    uint8_t* tai;       // [NUM_ACTIONS * 4] the tai of the winner r places after me
    float* coef;        // [2 * NUM_EVENTS] eventCoefficients: coefP, then coefT
    float* info;        // [INFO_SIZE]
    // Labels of the present.
    uint8_t* oppHand;     // [3 * 34] copies in the hand of the opponent r places after me
    uint8_t* danger;      // [3 * 34] 1: that opponent would win on a discard of this kind now
    uint8_t* dangerTai;   // [3 * 34] with this tai
    uint8_t* oppShanten;  // [3] 0 ready, 1, 2, 3 or more
    uint8_t* nextChow;    // [34] 1: the next player could chow this kind
    int32_t* greedy;      // [1] the action the plain greedy player would play here (a reference, not a target)
    // Labels from the rest of the hand as played.
    int8_t* winTile;      // [4] the kind the seat r places after me won on; -1
    uint8_t* taiItems;    // [Tai::NUM_ITEMS] the items of my win
    uint8_t* future;      // [FUTURE_SIZE]
    uint8_t* finalHand;   // [34] my copies at the end, melds included
    int8_t* nextDiscard;  // [3] the next discard of the opponent r places after me; -1
  };

  // Fills the row for mark `markIndex` of the record, replayed through the symmetry.
  void expand(const HandRecord& rec, int markIndex, const Symmetry& sym, const Row& row);
}

#endif  // LEARN_EXPAND_H_
