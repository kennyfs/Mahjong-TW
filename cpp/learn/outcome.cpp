#include "../learn/outcome.h"

#include <algorithm>

using namespace std;

Learn::Outcome Learn::Outcome::of(const HandResult& r) {
  Outcome o;
  for(size_t i = 0; i < r.winners.size(); i++) {
    int w = r.winners[i];
    o.winners |= (uint8_t)(1 << w);
    o.tai[w] = (uint8_t)min(255, max(0, r.tai[i].total));
  }
  o.loser = (int8_t)(r.winners.empty() ? -1 : r.loser);
  return o;
}

double Learn::Outcome::points(int seat, const Rules& rules, const HandSetup& setup) const {
  int dealerTai = rules.dealer + rules.streak * setup.streak;
  double total = 0.0;
  for(int w = 0; w < 4; w++) {
    if(!won(w))
      continue;
    for(int p = 0; p < 4; p++) {
      if(!pays(p, w))
        continue;
      double amount = rules.base + tai[w] + ((w == setup.dealer || p == setup.dealer) ? dealerTai : 0);
      if(w == seat)
        total += amount;
      if(p == seat)
        total -= amount;
    }
  }
  return total;
}

uint16_t Learn::Outcome::events(int me) const {
  uint16_t bits = 0;
  for(int w = 0; w < 4; w++) {
    if(!won((me + w) % 4))
      continue;
    if(loser < 0)
      bits |= (uint16_t)(1 << (4 * w + w));
    else
      bits |= (uint16_t)(1 << (4 * w + (loser - me + 4) % 4));
  }
  return bits;
}

void Learn::eventCoefficients(const Rules& rules, const HandSetup& setup, int me, float coefP[NUM_EVENTS],
                              float coefT[NUM_EVENTS]) {
  double dealerTai = rules.dealer + rules.streak * setup.streak;
  int dealerRel = (setup.dealer - me + 4) % 4;  // 0: I am the dealer
  // I win by self-draw: three payers; the dealer's tai from each if I am the dealer, else from the dealer alone.
  coefP[0] = (float)(3 * rules.base + (dealerRel == 0 ? 3 : 1) * dealerTai);
  coefT[0] = 3.0f;
  for(int j = 1; j <= 3; j++) {
    float pair = (float)(rules.base + ((dealerRel == 0 || dealerRel == j) ? dealerTai : 0.0));
    coefP[j] = pair;  // I win on j's discard
    coefT[j] = 1.0f;
    coefP[3 + j] = -pair;  // j wins on my discard
    coefT[3 + j] = -1.0f;
    coefP[6 + j] = -pair;  // j wins by self-draw
    coefT[6 + j] = -1.0f;
  }
}
