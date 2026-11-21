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
    // Tile efficiency: a discard by the hand alone, the fewest steps from ready (shanten) and then the most unseen
    // tiles that bring it closer (ukeire), the same figures as the analysis (players/analysis.cpp).
    // eps: the chance of a random discard on its own turn.
    // calls: -1 never chows or pungs; 0 only for a lower shanten (2.0.0 and before); 1 also when the call keeps the
    //   distance; 2 for a lower shanten, or the same with more improving tiles than passing (more than (1 + gain) times
    //   as many; the default, gain 1: more than twice), the closest call and then the most improving tiles.
    // kongs: -1 none; 0 every added kong, a concealed one when the shanten is no worse than the best discard's (2.0.0
    //   and before); 1 any kong with a shanten no worse (the default); 2 any kong with a shanten no worse and, at the
    //   same shanten, no fewer improving tiles. (Never a kong of another's discard.)
    // The defaults (calls 2 with gain 1, kongs 1) are the simplest of the rules that played best in matches (the variant
    // against the old one or the default, with two copies of the network, 40,000 deals x 4 seatings, per hand;
    // simple1,none, seed v1 unless said): kongs 1 +0.013 +- 0.002, kongs 2 +0.012 +- 0.003, no kongs -0.010 +- 0.004;
    // calls 2 with gain 0 -0.032 +- 0.011, gain 0.5 +0.013 +- 0.008, gain 1 +0.025 +- 0.007 (seed v2 +0.022 +- 0.007;
    // simple2,see +0.043 +- 0.010), gain 2 +0.017 +- 0.004, gain 3 +0.016 +- 0.004; calls 1 -0.376 +- 0.014; no calls
    // -1.175 +- 0.013. ($WORK/greedy-variants.)
    GreedyPlayer(double eps, int calls, double gain, int kongs, const string& spec)
      : eps_(eps), calls_(calls), gain_(gain), kongs_(kongs), spec_(spec) {}
    string name() const override { return spec_; }

    // The best discard of a 3 numSets + 2 hand: (shanten, -ukeire, keep value) smallest.
    static int bestDiscard(const Counts& hand, int numSets, const Counts& visible, int* outShanten, int* outUkeire = nullptr) {
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
      if(outUkeire)
        *outUkeire = bestU;
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
        // A ready hand is declared whenever it may be (報聽: with at least 5 winning tiles left after the best
        // discard); a declared hand has one discard, or only those that keep it ready (the first of them with the
        // most winning tiles left).
        auto bestReady = [&](int* outLeft) {
          int best = -1, bestLeft = -1;
          for(const Action& a : legal) {
            if(a.type != Action::DISCARD)
              continue;
            Counts c = st.hand;
            c[a.kind]--;
            uint64_t waits = Tai::waits(c, st.melds);
            if(waits == 0)
              continue;
            int left = 0;
            for(int k = 0; k < Tiles::NUM_KINDS; k++)
              if(waits & ((uint64_t)1 << k))
                left += max(0, 4 - (int)visible[k]);
            if(left > bestLeft) {
              bestLeft = left;
              best = a.kind;
            }
          }
          *outLeft = bestLeft;
          return best;
        };
        if(const Action* a = findType(legal, Action::DECLARE)) {
          int left;
          bestReady(&left);
          if(game.declareType(seat) != 3 || left >= 5)
            return *a;
        }
        if(st.declared) {
          int left;
          int best = bestReady(&left);
          if(best < 0)
            for(const Action& a : legal)
              if(a.type == Action::DISCARD)
                best = a.kind;
          return Action(Action::DISCARD, best);
        }
        if(eps_ > 0.0 && rand.nextDouble() < eps_) {
          vector<Action> discards;
          for(const Action& a : legal)
            if(a.type == Action::DISCARD)
              discards.push_back(a);
          return discards[rand.nextUInt((uint32_t)discards.size())];
        }
        if(kongs_ == 0)
          if(const Action* a = findType(legal, Action::KAKAN))
            return *a;
        int s0, u0;
        int discard = bestDiscard(st.hand, numSets, visible, &s0, &u0);
        // A kong (concealed: four tiles out and a set fewer to make; added: one tile out) no worse than that discard.
        const Action* kong = nullptr;
        for(const Action& a : legal) {
          if(kongs_ < 0 || (a.type != Action::ANKAN && (a.type != Action::KAKAN || kongs_ == 0)))
            continue;
          Counts c = st.hand;
          int n = numSets;
          if(a.type == Action::ANKAN) {
            c[a.kind] -= 4;
            n--;
          }
          else
            c[a.kind]--;
          int s = Shape::shanten(c, n);
          int u = kongs_ == 2 ? Greedy::ukeire(c, n, visible, s) : u0;
          if(s < s0 || (s == s0 && u >= u0)) {
            if(kongs_ != 2)
              return a;
            kong = &a;
            s0 = s;
            u0 = u;
          }
        }
        if(kong)
          return *kong;
        return Action(Action::DISCARD, discard);
      }

      // A discard to call: a chow or pung that leaves the hand closer to ready (calls 2: or as close with more
      // improving tiles; calls 1: any that keeps the distance).
      if(calls_ < 0)
        return Action(Action::PASS);
      int s0 = Shape::shanten(st.hand, numSets);
      Action best(Action::PASS);
      int bestS = s0, bestU = calls_ == 2 ? (int)(Greedy::ukeire(st.hand, numSets, visible, s0) * (1.0 + gain_)) : 1 << 30;
      if(calls_ == 1)
        bestS = s0 + 1;
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
        int s1, u1;
        bestDiscard(c, numSets - 1, visible, &s1, &u1);
        if(s1 < bestS || (s1 == bestS && u1 > bestU)) {
          bestU = calls_ == 2 ? u1 : 1 << 30;  // (else by shanten only: the first call that reaches it)
          bestS = s1;
          best = a;
        }
      }
      return best;
    }

   private:
    double eps_;
    int calls_;
    double gain_;
    int kongs_;
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
    // greedy:eps=0.1,calls=more (or none): variations for training data; calls=ukeire[,gain=R], kongs=none|old|shanten|
    // figures: the rules for calls and kongs (the constructor; kongs=old: 2.0.0 and before).
    double eps = 0.0, gain = 1.0;
    int calls = 2, kongs = 1;
    if(spec.size() > 7) {
      for(const string& tok : Global::split(spec.substr(7), ',')) {
        size_t eq = tok.find('=');
        string key = eq == string::npos ? tok : tok.substr(0, eq), value = eq == string::npos ? "" : tok.substr(eq + 1);
        if(key == "eps" && Global::tryStringToDouble(value, eps) && eps >= 0.0 && eps <= 1.0)
          continue;
        if(key == "calls" && (value == "more" || value == "none" || value == "normal" || value == "ukeire")) {
          calls = value == "more" ? 1 : value == "none" ? -1 : value == "ukeire" ? 2 : 0;
          continue;
        }
        if(key == "gain" && Global::tryStringToDouble(value, gain) && gain >= 0.0)
          continue;
        if(key == "kongs" && (value == "none" || value == "old" || value == "shanten" || value == "figures")) {
          kongs = value == "none" ? -1 : value == "old" ? 0 : value == "shanten" ? 1 : 2;
          continue;
        }
        throw StringError("Bad option for the greedy player: " + tok);
      }
    }
    return unique_ptr<Player>(new GreedyPlayer(eps, calls, gain, kongs, spec));
  }
  return nullptr;
}
