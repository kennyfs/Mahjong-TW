#ifndef LEARN_OUTCOME_H_
#define LEARN_OUTCOME_H_

#include "../game/game.h"

// How a hand ended, in six bytes: who won, who paid, and each winner's tai. Everything the learning targets need
// follows from it and the hand's rules and setup (docs/plan.md 3.2, outcome events).
namespace Learn {
  struct Outcome {
    uint8_t winners = 0;  // bit s: seat s won; 0 for an exhaustive draw
    int8_t loser = -1;    // who pays a win on a discard (a robbed kong, seven robbing one); -1: everybody (a self-draw)
    uint8_t tai[4] = {0, 0, 0, 0};  // the hand's tai of each winner, without the dealer's (at most 255)

    static Outcome of(const HandResult& r);
    bool exhausted() const { return winners == 0; }
    bool won(int seat) const { return (winners >> seat) & 1; }
    // Whether `payer` pays `winner`.
    bool pays(int payer, int winner) const { return won(winner) && payer != winner && (loser < 0 || loser == payer); }
    // The result of `seat` in tai, as HandResult::points.
    double points(int seat, const Rules& rules, const HandSetup& setup) const;

    // The events from one seat's view, as 16 bits: bit 4 w + p is "the seat w places after me wins from the seat p
    // places after me" (p == w: by self-draw, from everybody). The ten that involve me (w == 0 or p == 0 or w == p)
    // make up my result; the other six are the others' results among themselves.
    uint16_t events(int me) const;
  };

  // The ten events of a seat's own result, in the order the network predicts them:
  //   0      I win by self-draw
  //   1-3    I win on the discard of the seat 1, 2, 3 places after me
  //   4-6    that seat wins on my discard
  //   7-9    that seat wins by self-draw
  constexpr int NUM_EVENTS = 10;
  // The bit of Outcome::events for each of them.
  constexpr int EVENT_BIT[NUM_EVENTS] = {0, 1, 2, 3, 4, 8, 12, 5, 10, 15};
  // Whose tai counts in each: 0 me, otherwise the seat that many places after me.
  constexpr int EVENT_WINNER[NUM_EVENTS] = {0, 0, 0, 0, 1, 2, 3, 1, 2, 3};

  // The result of seat `me` from its events (Thirteen's compose_q):
  //   sum over the events e of  prob[e] * coefP[e] + tai[e] * coefT[e],
  // where prob[e] is the chance of the event and tai[e] the expected tai of its winner, counted as 0 when the event
  // does not happen. The coefficients hold the base, the dealer's tai and the number of payers, so the sum is exact.
  void eventCoefficients(const Rules& rules, const HandSetup& setup, int me, float coefP[NUM_EVENTS],
                         float coefT[NUM_EVENTS]);
}

#endif  // LEARN_OUTCOME_H_
