#include "../learn/features.h"

#include "../game/shape.h"

#include <algorithm>
#include <cstring>

using namespace std;

namespace {
  inline int relSeat(int seat, int me) { return (seat - me + 4) % 4; }
  inline float capped(double x, double hi = 1.5) { return (float)min(x, hi); }
  inline float taiFeature(double tai) { return (float)min(tai / 8.0, 4.0); }

  // A ready hand of 3 numSets + 1 tiles: its waits, their unseen copies and the tai of winning on them.
  struct Ready {
    uint64_t waits = 0;
    int copies = 0;
    int kinds = 0;
    double ronTai = 0.0, tsumoTai = 0.0;  // means over the unseen copies (over the kinds when none is left)
    int ron[Tiles::NUM_KINDS];            // per wait; -1 when the win is below min_tai
    int tsumo[Tiles::NUM_KINDS];
  };

  void readyInfo(const Game& game, int seat, const Counts& hand, const vector<Meld>& melds, const Counts& visible,
                 Ready& out) {
    const Rules& rules = game.rules();
    out = Ready();
    out.waits = Tai::waits(hand, melds);
    if(out.waits == 0)
      return;
    Tai::WinInfo win;
    win.concealed = hand;
    win.melds = melds;
    win.flowers = game.seat(seat).flowers;
    win.seatWind = game.seatWind(seat);
    win.roundWind = game.setup().roundWind;
    double ronSum = 0, tsumoSum = 0, ronPlain = 0, tsumoPlain = 0;
    for(int k = 0; k < Tiles::NUM_KINDS; k++) {
      if(!(out.waits & ((uint64_t)1 << k)))
        continue;
      int left = max(0, 4 - (int)visible[k]);
      win.concealed[k]++;
      win.winTile = k;
      win.selfDraw = false;
      int ron = Tai::compute(rules, win).total;
      win.selfDraw = true;
      int tsumo = Tai::compute(rules, win).total;
      win.concealed[k]--;
      out.ron[k] = ron >= rules.minTai ? ron : -1;
      out.tsumo[k] = tsumo >= rules.minTai ? tsumo : -1;
      out.kinds++;
      out.copies += left;
      ronSum += left * max(0, out.ron[k]);
      tsumoSum += left * max(0, out.tsumo[k]);
      ronPlain += max(0, out.ron[k]);
      tsumoPlain += max(0, out.tsumo[k]);
    }
    if(out.copies > 0) {
      out.ronTai = ronSum / out.copies;
      out.tsumoTai = tsumoSum / out.copies;
    }
    else {
      out.ronTai = ronPlain / out.kinds;
      out.tsumoTai = tsumoPlain / out.kinds;
    }
  }

  // The hand after a chow or pung (3 numSets + 2 tiles) and its best discard: fewest tiles from ready, then the most
  // improving tiles, then the better wait. Ties give the same numbers, so the order of the kinds does not matter.
  struct AfterCall {
    int shanten = 100;
    int ukeire = -1;
    bool ready = false;
    int copies = -1;
    double ronTai = -1.0, tsumoTai = -1.0;
  };
  AfterCall afterCall(const Game& game, int seat, const Counts& hand, const vector<Meld>& melds,
                      const Counts& visible) {
    const int numSets = 5 - (int)melds.size();
    AfterCall best;
    Counts c = hand;
    int s[Tiles::NUM_KINDS];
    for(int k = 0; k < Tiles::NUM_KINDS; k++) {
      if(c[k] == 0)
        continue;
      c[k]--;
      s[k] = Shape::shanten(c, numSets);
      best.shanten = min(best.shanten, s[k]);
      c[k]++;
    }
    for(int k = 0; k < Tiles::NUM_KINDS; k++) {
      if(c[k] == 0 || s[k] != best.shanten)
        continue;
      c[k]--;
      int u = Features::improving(c, numSets, visible, best.shanten, nullptr);
      if(u >= best.ukeire) {
        if(u > best.ukeire) {
          best.ukeire = u;
          best.copies = -1;
          best.ronTai = best.tsumoTai = -1.0;
        }
        if(best.shanten == 0) {
          Ready r;
          readyInfo(game, seat, c, melds, visible, r);
          best.ready = true;
          if(r.copies > best.copies || (r.copies == best.copies &&
             (r.ronTai > best.ronTai || (r.ronTai == best.ronTai && r.tsumoTai > best.tsumoTai)))) {
            best.copies = r.copies;
            best.ronTai = r.ronTai;
            best.tsumoTai = r.tsumoTai;
          }
        }
      }
      c[k]++;
    }
    return best;
  }

  void oneHotShanten(float* f, int shanten) { f[min(max(shanten, 0), 4)] = 1.0f; }

  Meld makeMeld(Meld::Type t, int kind, int called, int from) {
    Meld m;
    m.type = t;
    m.kind = (uint8_t)kind;
    m.called = (uint8_t)called;
    m.from = (int8_t)from;
    return m;
  }

  struct RuleScales {
    vector<int> index;    // into Rules::params()
    vector<float> scale;
    RuleScales() {
      const vector<Rules::Param>& params = Rules::params();
      for(size_t i = 0; i < params.size(); i++) {
        string name = params[i].name;
        if(name == "flowers")
          continue;
        float s = 1.0f / 8.0f;
        if(name == "base")
          s = 1.0f / 4.0f;
        else if(name == "keep")
          s = 1.0f / 16.0f;
        else if(name == "draw_streak" || name == "multi_ron" || name == "duting_loose")
          s = 1.0f;
        index.push_back((int)i);
        scale.push_back(s);
      }
    }
  };
  const RuleScales& ruleScales() {
    static const RuleScales scales;
    return scales;
  }
}

const int Features::NUM_RULE_FEATURES = 3 + (int)ruleScales().index.size();

int Features::globalObsSize() { return GO_RULES + NUM_RULE_FEATURES; }

int Features::improving(const Counts& hand, int numSets, const Counts& visible, int shanten, uint64_t* kinds) {
  Counts c = hand;
  int total = 0;
  uint64_t mask = 0;
  for(int k = 0; k < Tiles::NUM_KINDS; k++) {
    // Only a kind next to what the hand holds can bring it closer.
    bool near = c[k] > 0;
    if(!near && k < 27) {
      int n = k % 9;
      for(int d = -2; d <= 2 && !near; d++)
        if(d != 0 && n + d >= 0 && n + d < 9 && c[k + d] > 0)
          near = true;
    }
    if(!near || c[k] >= 4)
      continue;
    c[k]++;
    if(Shape::shanten(c, numSets) < shanten) {
      mask |= (uint64_t)1 << k;
      total += max(0, 4 - (int)visible[k]);
    }
    c[k]--;
  }
  if(kinds)
    *kinds = mask;
  return total;
}

void Features::observation(const Game& game, int me, float* kind, float* seats, float* global, uint8_t* legalOut) {
  const Rules& rules = game.rules();
  const HandSetup& setup = game.setup();
  const int globalSize = globalObsSize();
  memset(kind, 0, sizeof(float) * NUM_KINDS * KIND_OBS);
  memset(seats, 0, sizeof(float) * NUM_SEATS * SEAT_OBS);
  memset(global, 0, sizeof(float) * globalSize);
  memset(legalOut, 0, Learn::NUM_ACTIONS);

  vector<Action> legal;
  game.legalActions(me, legal);
  for(const Action& a : legal)
    legalOut[Learn::actionIndex(game, a)] = 1;

  const SeatState& st = game.seat(me);
  const Counts visible = game.visibleCounts(me);
  const int numSets = 5 - (int)st.melds.size();
  const Game::Phase phase = game.phase();
  const bool self = phase == Game::SELF;
  const int claim = self ? -1 : game.claimTile();
  const vector<Event>& events = game.events();
  auto K = [&](int k) { return kind + k * KIND_OBS; };

  // ----- What is on the table -----
  for(int k = 0; k < NUM_KINDS; k++) {
    float* f = K(k);
    for(int i = 0; i < st.hand[k] && i < 4; i++)
      f[KO_HAND + i] = 1.0f;
    int left = max(0, 4 - (int)visible[k]);
    for(int i = 0; i < left; i++)
      f[KO_UNSEEN + i] = 1.0f;
  }
  if(self && game.drawnTile() >= 0)
    K(game.drawnTile())[KO_DRAWN] = 1.0f;
  if(claim >= 0)
    K(claim)[KO_CLAIM] = 1.0f;

  for(int p = 0; p < 4; p++) {
    const SeatState& sp = game.seat(p);
    const int r = relSeat(p, me);
    for(const Meld& m : sp.melds) {
      int base = KO_MELD + 6 * r;
      if(m.type == Meld::CHI) {
        for(int i = 0; i < 3; i++)
          K(m.kind + i)[base + i] += 0.5f;
      }
      else if(m.type == Meld::PON)
        K(m.kind)[base + 3] = 1.0f;
      else if(m.type == Meld::ANKAN) {
        if(p == me)
          K(m.kind)[base + 5] = 1.0f;
      }
      else
        K(m.kind)[base + 4] = 1.0f;
    }
    const int n = (int)sp.discards.size();
    for(int i = 0; i < n; i++) {
      const Discard& d = sp.discards[i];
      float* f = K(d.kind) + KO_DISC + 8 * r;
      f[0] += 0.25f;
      f[1] = 1.0f;
      f[d.tsumogiri ? 3 : 2] = 1.0f;
      if(f[4] == 0.0f)
        f[4] = capped((i + 1) / 20.0);
      f[5] = 1.0f / (float)(n - i);
      if(i == n - 1)
        f[6] = 1.0f;
      if(d.called)
        f[7] = 1.0f;
    }
  }

  // A kind discarded (or added to a kong) since an opponent's last discard cannot be won by that opponent now.
  {
    bool collecting[4] = {true, true, true, true};
    int numOpen = 3;
    collecting[me] = false;
    // The tile on offer is not safe yet: the others are deciding on it as I am.
    bool onOffer = !self;
    for(int i = (int)events.size() - 1; i >= 0 && numOpen > 0; i--) {
      const Event& e = events[i];
      if(e.type != Event::DISCARD && e.type != Event::KAKAN)
        continue;
      for(int p = 0; p < 4; p++)
        if(collecting[p] && p != e.seat && !onOffer)
          K(e.tile)[KO_SAFE + relSeat(p, me) - 1] = 1.0f;
      onOffer = false;
      // The seat's own discard ends its list: its hand has changed since whatever came before. So does the added
      // kong it has just declared (while the others decide whether to rob it).
      if(collecting[e.seat]) {
        collecting[e.seat] = false;
        numOpen--;
      }
    }
  }

  for(int w = 0; w < 4; w++) {
    float* f = K(Tiles::EAST + w);
    if(w == setup.roundWind)
      f[KO_ROLE] = 1.0f;
    for(int p = 0; p < 4; p++)
      if(game.seatWind(p) == w)
        f[KO_ROLE + 1 + relSeat(p, me)] = 1.0f;
  }

  // ----- My hand, as the engine computes it -----
  float* actions = global + GO_ACTIONS;
  auto special = [&](int index) { return actions + (index - Learn::A_TSUMO) * GA_SIZE; };
  int myShanten;
  if(self) {
    int s[NUM_KINDS];
    int bestS = 100;
    Counts c = st.hand;
    for(int k = 0; k < NUM_KINDS; k++) {
      if(c[k] == 0)
        continue;
      c[k]--;
      s[k] = Shape::shanten(c, numSets);
      bestS = min(bestS, s[k]);
      c[k]++;
    }
    myShanten = Shape::shanten(st.hand, numSets);
    uint64_t wanted = 0;
    int winRon[NUM_KINDS], winTsumo[NUM_KINDS];
    for(int k = 0; k < NUM_KINDS; k++)
      winRon[k] = winTsumo[k] = -1;
    Ready ready;
    for(int k = 0; k < NUM_KINDS; k++) {
      if(c[k] == 0)
        continue;
      float* f = K(k);
      c[k]--;
      uint64_t mask;
      int u = improving(c, numSets, visible, s[k], &mask);
      f[KO_CAN_DISCARD] = 1.0f;
      oneHotShanten(f + KO_DISCARD_SHANTEN, s[k]);
      if(s[k] == bestS) {
        f[KO_DISCARD_BEST] = 1.0f;
        wanted |= mask;
      }
      f[KO_DISCARD_UKEIRE] = capped(u / 40.0);
      f[KO_DISCARD_UKEIRE + 1] = capped(__builtin_popcountll(mask) / 13.0);
      if(s[k] == 0) {
        readyInfo(game, me, c, st.melds, visible, ready);
        f[KO_DISCARD_READY] = capped(ready.copies / 12.0);
        f[KO_DISCARD_READY + 1] = capped(ready.kinds / 5.0);
        f[KO_DISCARD_READY + 2] = taiFeature(ready.ronTai);
        f[KO_DISCARD_READY + 3] = taiFeature(ready.tsumoTai);
        for(int w = 0; w < NUM_KINDS; w++) {
          if(!(ready.waits & ((uint64_t)1 << w)))
            continue;
          winRon[w] = max(winRon[w], max(0, ready.ron[w]));
          winTsumo[w] = max(winTsumo[w], max(0, ready.tsumo[w]));
        }
      }
      c[k]++;
    }
    // What I would be looking for after a discard that leaves the fewest tiles from ready.
    for(int k = 0; k < NUM_KINDS; k++) {
      float* f = K(k);
      if(wanted & ((uint64_t)1 << k))
        f[KO_DRAW] = 1.0f;
      if(winRon[k] >= 0) {
        f[KO_DRAW] = 1.0f;
        f[KO_DRAW + 1] = 1.0f;
        f[KO_DRAW + 2] = taiFeature(winRon[k]);
        f[KO_DRAW + 3] = taiFeature(winTsumo[k]);
      }
    }
    for(const Action& a : legal) {
      if(a.type == Action::ANKAN || a.type == Action::KAKAN) {
        float* f = K(a.kind);
        f[KO_CAN_KONG] = 1.0f;
        c[a.kind] -= a.type == Action::ANKAN ? 4 : 1;
        int after = Shape::shanten(c, a.type == Action::ANKAN ? numSets - 1 : numSets);
        c[a.kind] += a.type == Action::ANKAN ? 4 : 1;
        if(after <= bestS)
          f[KO_CAN_KONG + 1] = 1.0f;
      }
      else if(a.type == Action::TSUMO) {
        float* g = special(Learn::A_TSUMO);
        g[GA_LEGAL] = 1.0f;
        g[GA_READY + 2] = taiFeature(max(0, game.winTai(me)));
      }
    }
  }
  else {
    myShanten = Shape::shanten(st.hand, numSets);
    uint64_t mask;
    int u0 = improving(st.hand, numSets, visible, myShanten, &mask);
    for(int k = 0; k < NUM_KINDS; k++)
      if(mask & ((uint64_t)1 << k))
        K(k)[KO_DRAW] = 1.0f;
    Ready ready;
    if(myShanten == 0) {
      readyInfo(game, me, st.hand, st.melds, visible, ready);
      for(int k = 0; k < NUM_KINDS; k++) {
        if(!(ready.waits & ((uint64_t)1 << k)))
          continue;
        float* f = K(k);
        f[KO_DRAW] = 1.0f;
        f[KO_DRAW + 1] = 1.0f;
        f[KO_DRAW + 2] = taiFeature(max(0, ready.ron[k]));
        f[KO_DRAW + 3] = taiFeature(max(0, ready.tsumo[k]));
      }
    }
    Counts c;
    vector<Meld> melds;
    for(const Action& a : legal) {
      float* g = special(Learn::actionIndex(game, a));
      g[GA_LEGAL] = 1.0f;
      if(a.type == Action::RON) {
        g[GA_READY + 1] = taiFeature(max(0, game.winTai(me)));
        continue;
      }
      AfterCall after;
      if(a.type == Action::PASS) {
        after.shanten = myShanten;
        after.ukeire = u0;
        if(myShanten == 0) {
          after.ready = true;
          after.copies = ready.copies;
          after.ronTai = ready.ronTai;
          after.tsumoTai = ready.tsumoTai;
        }
      }
      else {
        c = st.hand;
        melds = st.melds;
        if(a.type == Action::CHI) {
          for(int k = a.kind; k <= a.kind + 2; k++)
            if(k != claim)
              c[k]--;
          melds.push_back(makeMeld(Meld::CHI, a.kind, claim, game.claimSeat()));
        }
        else {
          c[claim] -= a.type == Action::PON ? 2 : 3;
          melds.push_back(makeMeld(a.type == Action::PON ? Meld::PON : Meld::MINKAN, claim, claim, game.claimSeat()));
        }
        if(a.type == Action::MINKAN) {
          // A kong draws its replacement next: the hand as it is.
          after.shanten = Shape::shanten(c, numSets - 1);
          after.ukeire = improving(c, numSets - 1, visible, after.shanten, nullptr);
          if(after.shanten == 0) {
            Ready r;
            readyInfo(game, me, c, melds, visible, r);
            after.ready = true;
            after.copies = r.copies;
            after.ronTai = r.ronTai;
            after.tsumoTai = r.tsumoTai;
          }
        }
        else {
          after = afterCall(game, me, c, melds, visible);
        }
      }
      oneHotShanten(g + GA_SHANTEN, after.shanten);
      if(after.shanten < myShanten)
        g[GA_LOWERS] = 1.0f;
      g[GA_UKEIRE] = capped(after.ukeire / 40.0);
      if(after.ready) {
        g[GA_READY] = capped(after.copies / 12.0);
        g[GA_READY + 1] = taiFeature(after.ronTai);
        g[GA_READY + 2] = taiFeature(after.tsumoTai);
      }
    }
  }

  // ----- Seats -----
  uint8_t shown = 0;
  int totalDiscards = 0;
  for(int p = 0; p < 4; p++) {
    const SeatState& sp = game.seat(p);
    float* f = seats + relSeat(p, me) * SEAT_OBS;
    f[SO_REL + relSeat(p, me)] = 1.0f;
    if(p == setup.dealer)
      f[SO_DEALER] = 1.0f;
    const int wind = game.seatWind(p);
    f[SO_WIND + wind] = 1.0f;
    int open = 0, kongs = 0, ankans = 0;
    for(const Meld& m : sp.melds) {
      open += m.isOpen() ? 1 : 0;
      kongs += m.isKong() ? 1 : 0;
      ankans += m.type == Meld::ANKAN ? 1 : 0;
    }
    f[SO_MELDS] = sp.melds.size() / 5.0f;
    f[SO_MELDS + 1] = open / 5.0f;
    f[SO_MELDS + 2] = kongs / 4.0f;
    f[SO_MELDS + 3] = ankans / 4.0f;
    f[SO_HAND] = Tiles::total(sp.hand) / 17.0f;
    shown |= sp.flowers;
    const int numFlowers = __builtin_popcount(sp.flowers);
    const int own = ((sp.flowers >> wind) & 1) + ((sp.flowers >> (wind + 4)) & 1);
    const int seasons = __builtin_popcount(sp.flowers & 0x0F), plants = __builtin_popcount(sp.flowers & 0xF0);
    f[SO_FLOWERS] = numFlowers / 8.0f;
    f[SO_FLOWERS + 1] = own / 2.0f;
    f[SO_FLOWERS + 2] = seasons / 4.0f;
    f[SO_FLOWERS + 3] = plants / 4.0f;
    int flowerTai = 0;
    if(rules.flowers == Rules::SEE_FLOWERS)
      flowerTai = rules.flowerTai * numFlowers;
    else if(rules.flowers == Rules::SEAT_FLOWERS)
      flowerTai = rules.flowerTai * own + rules.flowerSet * ((seasons == 4 ? 1 : 0) + (plants == 4 ? 1 : 0));
    f[SO_FLOWERS + 4] = taiFeature(flowerTai);
    const int n = (int)sp.discards.size();
    totalDiscards += n;
    f[SO_DISCARDS] = capped(n / 20.0);
    int run = 0;
    while(run < n && sp.discards[n - 1 - run].tsumogiri)
      run++;
    f[SO_TSUMOGIRI] = run > 0 ? 1.0f : 0.0f;
    f[SO_TSUMOGIRI + 1] = capped(run / 5.0, 1.0);
    if(open > 0) {
      f[SO_CALL] = 1.0f;
      int since = 0;
      for(int i = (int)events.size() - 1; i >= 0; i--) {
        const Event& e = events[i];
        if(e.seat != p)
          continue;
        if(e.type == Event::CHI || e.type == Event::PON || e.type == Event::MINKAN)
          break;
        if(e.type == Event::DISCARD)
          since++;
      }
      f[SO_CALL + 1] = capped(since / 10.0);
    }
    if(!self && p == game.claimSeat())
      f[SO_TURN] = 1.0f;
    if(self && p == game.current())
      f[SO_TURN + 1] = 1.0f;
  }

  // ----- The table and the rules -----
  global[GO_DEALER_TAI] = taiFeature(rules.dealer + rules.streak * setup.streak);
  global[GO_STREAK] = capped(setup.streak / 4.0, 2.0);
  global[GO_ROUND_WIND + setup.roundWind] = 1.0f;
  global[GO_WALL] = game.wallLeft() / 80.0f;
  global[GO_WALL + 1] = game.wallLeft() <= 0 ? 1.0f : 0.0f;
  global[GO_TURN] = totalDiscards / 80.0f;
  if(rules.hasFlowers())
    global[GO_FLOWERS_LEFT] = (8 - __builtin_popcount(shown)) / 8.0f;
  global[GO_SHANTEN + min(max(myShanten, -1), 4) + 1] = 1.0f;
  if(st.missedWin)
    global[GO_MISSED] = 1.0f;
  global[GO_PHASE + (self ? (game.afterCall() ? 1 : 0) : (phase == Game::REACT ? 2 : 3))] = 1.0f;
  float* rf = global + GO_RULES;
  rf[rules.flowers] = 1.0f;
  const RuleScales& scales = ruleScales();
  const vector<Rules::Param>& params = Rules::params();
  for(size_t i = 0; i < scales.index.size(); i++) {
    const Rules::Param& p = params[scales.index[i]];
    double v = p.intField ? (double)(rules.*p.intField) : rules.*p.doubleField;
    rf[3 + i] = (float)(v * scales.scale[i]);
  }
}

void Features::fullState(const Game& game, int me, const uint8_t playerTypes[4], float* kind, float* seats,
                         float* global) {
  memset(kind, 0, sizeof(float) * NUM_KINDS * KIND_FULL);
  memset(seats, 0, sizeof(float) * NUM_SEATS * SEAT_FULL);
  memset(global, 0, sizeof(float) * GLOBAL_FULL);
  auto K = [&](int k) { return kind + k * KIND_FULL; };
  const vector<uint8_t>& wall = game.wall();
  const int front = game.wallFront(), back = game.wallBack(), wallLeft = game.wallLeft();
  // If nobody calls, the seat after the current one draws next, and so on around the table.
  const int nextDrawer = (game.current() + 1) % 4;

  int firstDraw[4] = {-1, -1, -1, -1};
  for(int i = 0; i < wallLeft; i++) {
    int t = wall[front + i];
    int p = (nextDrawer + i) % 4;
    if(Tiles::isFlower(t))
      continue;
    if(firstDraw[p] < 0)
      firstDraw[p] = t;
    float* f = K(t);
    f[KF_WALL] += 0.25f;
    float& next = f[KF_NEXT + relSeat(p, me)];
    if(next == 0.0f)
      next = 1.0f / (float)(1 + i / 4);
  }
  for(int j = 0; j < wallLeft; j++) {
    int t = wall[back - 1 - j];
    if(Tiles::isFlower(t))
      continue;
    float& f = K(t)[KF_WALL + 1];
    if(f == 0.0f)
      f = 1.0f / (float)(1 + j);
  }
  for(int i = front + wallLeft; i < back; i++)
    if(!Tiles::isFlower(wall[i]))
      K(wall[i])[KF_WALL + 2] += 0.25f;

  Counts liveWall{};
  for(int i = 0; i < wallLeft; i++)
    if(!Tiles::isFlower(wall[front + i]))
      liveWall[wall[front + i]]++;

  for(int p = 0; p < 4; p++) {
    const SeatState& sp = game.seat(p);
    const int r = relSeat(p, me);
    const int numSets = 5 - (int)sp.melds.size();
    const int tiles = Tiles::total(sp.hand);
    const bool afterDraw = tiles == 3 * numSets + 2;
    float* f = seats + r * SEAT_FULL;
    const int shanten = Shape::shanten(sp.hand, numSets);
    f[SF_SHANTEN + min(max(shanten, -1), 4) + 1] = 1.0f;
    if(sp.missedWin)
      f[SF_MISSED] = 1.0f;
    f[SF_PLAYER + min<int>(playerTypes[p], 2)] = 1.0f;
    if(r > 0) {
      for(int k = 0; k < NUM_KINDS; k++)
        for(int i = 0; i < sp.hand[k] && i < 4; i++)
          K(k)[KF_HAND + 4 * (r - 1) + i] = 1.0f;
      for(const Meld& m : sp.melds)
        if(m.type == Meld::ANKAN)
          K(m.kind)[KF_ANKAN + r - 1] = 1.0f;
    }
    if(afterDraw)
      continue;  // only the deciding seat on its own turn, whose hand the observation describes
    const Counts visible = game.visibleCounts(p);
    uint64_t useful;
    int u = improving(sp.hand, numSets, visible, shanten, &useful);
    f[SF_UKEIRE] = capped(u / 40.0);
    uint64_t waits = 0;
    if(shanten == 0) {
      Ready ready;
      readyInfo(game, p, sp.hand, sp.melds, visible, ready);
      waits = ready.waits;
      int inWall = 0, inHands = 0;
      for(int k = 0; k < NUM_KINDS; k++) {
        if(!(waits & ((uint64_t)1 << k)))
          continue;
        inWall += liveWall[k];
        for(int q = 0; q < 4; q++)
          if(q != p)
            inHands += game.seat(q).hand[k];
        if(r > 0) {
          float* g = K(k) + KF_WAIT + 3 * (r - 1);
          g[0] = 1.0f;
          g[1] = taiFeature(max(0, ready.ron[k]));
          g[2] = taiFeature(max(0, ready.tsumo[k]));
        }
      }
      f[SF_WAITS] = capped(inWall / 8.0);
      f[SF_WAITS + 1] = capped(inHands / 8.0);
    }
    if(r > 0)
      for(int k = 0; k < NUM_KINDS; k++)
        if((useful | waits) & ((uint64_t)1 << k))
          K(k)[KF_USEFUL + r - 1] = 1.0f;
    if(firstDraw[p] >= 0) {
      if((useful | waits) & ((uint64_t)1 << firstDraw[p]))
        f[SF_NEXT_DRAW] = 1.0f;
      if(waits & ((uint64_t)1 << firstDraw[p]))
        f[SF_NEXT_DRAW + 1] = 1.0f;
    }
  }
}
