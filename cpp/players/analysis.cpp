#include "../players/analysis.h"

#include "../game/shape.h"
#include "../players/players.h"

#include <algorithm>

using namespace std;

namespace {
  struct Ctx {
    const Game& game;
    int seat;
    Counts visible;
    const SeatState& st;
  };

  // Fills shanten, ukeire and waits for a 3 numSets + 1 hand with these melds.
  void ready(const Ctx& ctx, const Counts& hand, const vector<Meld>& melds, Analysis::ActionInfo& info) {
    int numSets = 5 - (int)melds.size();
    info.shanten = Shape::shanten(hand, numSets);
    info.ukeire = Greedy::ukeire(hand, numSets, ctx.visible, info.shanten);
    if(info.shanten != 0)
      return;
    uint64_t w = Tai::waits(hand, melds);
    const Rules& rules = ctx.game.rules();
    for(int k = 0; k < Tiles::NUM_KINDS; k++) {
      if(!(w & ((uint64_t)1 << k)))
        continue;
      Analysis::Wait wait;
      wait.tile = k;
      wait.left = max(0, 4 - ctx.visible[k]);
      Tai::WinInfo win;
      win.concealed = hand;
      win.concealed[k]++;
      win.melds = melds;
      win.flowers = ctx.st.flowers;
      win.winTile = k;
      win.seatWind = ctx.game.seatWind(ctx.seat);
      win.roundWind = ctx.game.setup().roundWind;
      int ron = Tai::compute(rules, win).total;
      win.selfDraw = true;
      int tsumo = Tai::compute(rules, win).total;
      wait.ronTai = ron >= rules.minTai ? ron : -1;
      wait.tsumoTai = tsumo >= rules.minTai ? tsumo : -1;
      info.waits.push_back(wait);
    }
  }

  // The best discard of a 3 numSets + 2 hand, by shanten then ukeire.
  void bestDiscard(const Ctx& ctx, const Counts& hand, const vector<Meld>& melds, Analysis::ActionInfo& info) {
    Analysis::ActionInfo best;
    bool any = false;
    Counts c = hand;
    for(int k = 0; k < Tiles::NUM_KINDS; k++) {
      if(c[k] == 0)
        continue;
      c[k]--;
      Analysis::ActionInfo cand;
      ready(ctx, c, melds, cand);
      if(!any || cand.shanten < best.shanten || (cand.shanten == best.shanten && cand.ukeire > best.ukeire)) {
        best = cand;
        any = true;
      }
      c[k]++;
    }
    info.shanten = best.shanten;
    info.ukeire = best.ukeire;
    info.waits = best.waits;
  }

  Meld newMeld(Meld::Type t, int kind, int called, int from) {
    Meld m;
    m.type = t;
    m.kind = (uint8_t)kind;
    m.called = (uint8_t)called;
    m.from = (int8_t)from;
    return m;
  }
}

vector<Analysis::ActionInfo> Analysis::efficiency(const Game& game, int seat) {
  vector<Action> legal;
  game.legalActions(seat, legal);
  vector<ActionInfo> out;
  if(legal.empty())
    return out;
  Ctx ctx{game, seat, game.visibleCounts(seat), game.seat(seat)};
  const Counts& hand = ctx.st.hand;
  const vector<Meld>& melds = ctx.st.melds;
  int t = game.claimTile();
  for(const Action& a : legal) {
    ActionInfo info;
    info.action = a;
    switch(a.type) {
      case Action::TSUMO:
      case Action::RON:
        info.shanten = -1;
        info.winTai = game.winTai(seat);
        break;
      case Action::DISCARD: {
        Counts c = hand;
        c[a.kind]--;
        ready(ctx, c, melds, info);
        break;
      }
      case Action::ANKAN: {
        Counts c = hand;
        c[a.kind] -= 4;
        vector<Meld> m = melds;
        m.push_back(newMeld(Meld::ANKAN, a.kind, a.kind, -1));
        ready(ctx, c, m, info);
        break;
      }
      case Action::KAKAN: {
        Counts c = hand;
        c[a.kind]--;
        vector<Meld> m = melds;
        for(Meld& x : m)
          if(x.type == Meld::PON && x.kind == a.kind)
            x.type = Meld::KAKAN;
        ready(ctx, c, m, info);
        break;
      }
      case Action::PASS:
        ready(ctx, hand, melds, info);
        break;
      case Action::PON: {
        Counts c = hand;
        c[t] -= 2;
        vector<Meld> m = melds;
        m.push_back(newMeld(Meld::PON, t, t, game.claimSeat()));
        bestDiscard(ctx, c, m, info);
        break;
      }
      case Action::MINKAN: {
        Counts c = hand;
        c[t] -= 3;
        vector<Meld> m = melds;
        m.push_back(newMeld(Meld::MINKAN, t, t, game.claimSeat()));
        ready(ctx, c, m, info);
        break;
      }
      case Action::CHI: {
        Counts c = hand;
        for(int k = a.kind; k <= a.kind + 2; k++)
          if(k != t)
            c[k]--;
        vector<Meld> m = melds;
        m.push_back(newMeld(Meld::CHI, a.kind, t, game.claimSeat()));
        bestDiscard(ctx, c, m, info);
        break;
      }
      default:
        break;
    }
    out.push_back(info);
  }
  return out;
}
