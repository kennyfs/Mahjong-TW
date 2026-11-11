#include "../players/players.h"

#include "../core/global.h"
#include "../game/shape.h"

#include <algorithm>

using namespace std;

static const Action* findType(const vector<Action>& legal, Action::Type t) {
  for(const Action& a : legal)
    if(a.type == t)
      return &a;
  return nullptr;
}

namespace {
  class RandomPlayer : public Player {
   public:
    string name() const override { return "random"; }
    Action decide(const Game&, int, const vector<Action>& legal, Rand& rand) override {
      if(const Action* a = findType(legal, Action::TSUMO))
        return *a;
      if(const Action* a = findType(legal, Action::RON))
        return *a;
      if(const Action* a = findType(legal, Action::DECLARE))
        return *a;
      return legal[rand.nextUInt((uint32_t)legal.size())];
    }
  };

  // How much a kind is worth keeping when shanten and ukeire tie: honors first, then terminals, then the rest.
  int keepValue(int k) {
    if(Tiles::isHonor(k))
      return 0;
    int n = k % 9;
    return n == 0 || n == 8 ? 1 : (n == 1 || n == 7 ? 2 : 3);
  }

  class GreedyPlayer : public Player {
   public:
    // eps: the chance of a random discard on its own turn. calls: -1 never chows or pungs, 0 only when that brings
    // the hand closer to ready, 1 also when it keeps the distance.
    GreedyPlayer(double eps, int calls, const string& spec) : eps_(eps), calls_(calls), spec_(spec) {}
    string name() const override { return spec_; }

    // The best discard of a 3 numSets + 2 hand: (shanten, -ukeire, keep value) smallest.
    static int bestDiscard(const Counts& hand, int numSets, const Counts& visible, int* outShanten) {
      int bestKind = -1, bestS = 100, bestU = -1, bestV = 100;
      Counts c = hand;
      for(int k = 0; k < Tiles::NUM_KINDS; k++) {
        if(c[k] == 0)
          continue;
        c[k]--;
        int s = Shape::shanten(c, numSets);
        if(s <= bestS) {
          int u = Greedy::ukeire(c, numSets, visible, s);
          int v = keepValue(k);
          if(s < bestS || u > bestU || (u == bestU && v < bestV)) {
            bestKind = k;
            bestS = s;
            bestU = u;
            bestV = v;
          }
        }
        c[k]++;
      }
      if(outShanten)
        *outShanten = bestS;
      return bestKind;
    }

    Action decide(const Game& game, int seat, const vector<Action>& legal, Rand& rand) override {
      if(const Action* a = findType(legal, Action::TSUMO))
        return *a;
      if(const Action* a = findType(legal, Action::RON))
        return *a;
      const SeatState& st = game.seat(seat);
      int numSets = 5 - (int)st.melds.size();
      Counts visible = game.visibleCounts(seat);

      if(game.phase() == Game::SELF) {
        // A ready hand is declared whenever it may be; a declared hand has one discard, or only those that keep it
        // ready (the first of them with the most winning tiles left).
        if(const Action* a = findType(legal, Action::DECLARE))
          return *a;
        if(st.declared) {
          int best = -1, bestLeft = -1;
          for(const Action& a : legal) {
            if(a.type != Action::DISCARD)
              continue;
            Counts c = st.hand;
            c[a.kind]--;
            uint64_t waits = Tai::waits(c, st.melds);
            int left = 0;
            for(int k = 0; k < Tiles::NUM_KINDS; k++)
              if(waits & ((uint64_t)1 << k))
                left += max(0, 4 - (int)visible[k]);
            if(left > bestLeft) {
              bestLeft = left;
              best = a.kind;
            }
          }
          return Action(Action::DISCARD, best);
        }
        if(eps_ > 0.0 && rand.nextDouble() < eps_) {
          vector<Action> discards;
          for(const Action& a : legal)
            if(a.type == Action::DISCARD)
              discards.push_back(a);
          return discards[rand.nextUInt((uint32_t)discards.size())];
        }
        if(const Action* a = findType(legal, Action::KAKAN))
          return *a;
        int s0;
        int discard = bestDiscard(st.hand, numSets, visible, &s0);
        for(const Action& a : legal) {
          if(a.type != Action::ANKAN)
            continue;
          Counts c = st.hand;
          c[a.kind] -= 4;
          if(Shape::shanten(c, numSets - 1) <= s0)
            return a;
        }
        return Action(Action::DISCARD, discard);
      }

      // A discard to call: only a chow or pung that lowers shanten (or keeps it, for a player that likes to call).
      if(calls_ < 0)
        return Action(Action::PASS);
      int s0 = Shape::shanten(st.hand, numSets);
      Action best(Action::PASS);
      int bestS = s0 + (calls_ > 0 ? 1 : 0);
      for(const Action& a : legal) {
        if(a.type != Action::PON && a.type != Action::CHI)
          continue;
        Counts c = st.hand;
        if(a.type == Action::PON) {
          c[a.kind] -= 2;
        }
        else {
          int t = game.claimTile();
          for(int k = a.kind; k <= a.kind + 2; k++)
            if(k != t)
              c[k]--;
        }
        int s1;
        bestDiscard(c, numSets - 1, visible, &s1);
        if(s1 < bestS) {
          bestS = s1;
          best = a;
        }
      }
      return best;
    }

   private:
    double eps_;
    int calls_;
    string spec_;
  };
}

int Greedy::ukeire(const Counts& hand, int numSets, const Counts& visible, int shanten) {
  Counts c = hand;
  int total = 0;
  for(int k = 0; k < Tiles::NUM_KINDS; k++) {
    int left = 4 - visible[k];
    if(left <= 0)
      continue;
    bool near = c[k] > 0;
    if(!near && k < 27) {
      int n = k % 9;
      for(int d = -2; d <= 2 && !near; d++)
        if(d != 0 && n + d >= 0 && n + d < 9 && c[k + d] > 0)
          near = true;
    }
    if(!near)
      continue;
    c[k]++;
    if(Shape::shanten(c, numSets) < shanten)
      total += left;
    c[k]--;
  }
  return total;
}

unique_ptr<Player> makeBasicPlayer(const string& spec) {
  if(spec == "random")
    return unique_ptr<Player>(new RandomPlayer());
  if(spec == "greedy" || spec.rfind("greedy:", 0) == 0) {
    // greedy:eps=0.1,calls=more (or none): variations for training data.
    double eps = 0.0;
    int calls = 0;
    if(spec.size() > 7) {
      for(const string& tok : Global::split(spec.substr(7), ',')) {
        size_t eq = tok.find('=');
        string key = eq == string::npos ? tok : tok.substr(0, eq), value = eq == string::npos ? "" : tok.substr(eq + 1);
        if(key == "eps" && Global::tryStringToDouble(value, eps) && eps >= 0.0 && eps <= 1.0)
          continue;
        if(key == "calls" && (value == "more" || value == "none" || value == "normal")) {
          calls = value == "more" ? 1 : value == "none" ? -1 : 0;
          continue;
        }
        throw StringError("Bad option for the greedy player: " + tok);
      }
    }
    return unique_ptr<Player>(new GreedyPlayer(eps, calls, spec));
  }
  return nullptr;
}
