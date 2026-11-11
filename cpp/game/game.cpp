#include "../game/game.h"

#include "../core/global.h"
#include "../game/shape.h"

#include <algorithm>
#include <sstream>

using namespace std;

// ---------------------------------------------------------------------------------------------------------------------
// Printing

string Action::toString() const {
  switch(type) {
    case DISCARD: return "discard " + Tiles::name(kind);
    case TSUMO: return "tsumo";
    case ANKAN: return "ankan " + Tiles::name(kind);
    case KAKAN: return "kakan " + Tiles::name(kind);
    case PASS: return "pass";
    case RON: return "ron";
    case PON: return "pon";
    case MINKAN: return "kan";
    case CHI: return "chi " + Tiles::toString(vector<int>{kind, kind + 1, kind + 2});
    case DECLARE: return "declare";
    default: return "?";
  }
}

string Event::toString() const {
  static const char* const NAMES[] = {"draw", "flower", "discard", "chi", "pon", "kan", "ankan", "kakan",
                                      "tsumo", "ron", "flower-win", "exhausted", "declare"};
  string s = "S" + Global::intToString(seat) + " " + NAMES[type];
  if(type == EXHAUSTED)
    return s;
  if(type == DECLARE)
    return s + (extra == 1 ? " tianting" : " diting");
  if(type == CHI)
    return s + " " + Tiles::name(tile) + " -> " + Tiles::toString(vector<int>{extra, extra + 1, extra + 2});
  s += " " + Tiles::name(tile);
  if(type == DRAW && extra)
    s += " (replacement)";
  if(type == DISCARD && extra)
    s += " (drawn)";
  return s;
}

string HandResult::toString(double base) const {
  ostringstream out;
  if(exhausted)
    out << "Exhaustive draw.";
  for(size_t i = 0; i < winners.size(); i++) {
    out << "S" << winners[i] << (flowerWin ? " flower win" : selfDraw ? " self-draws " : " wins on ")
        << (winTile >= 0 && !flowerWin ? Tiles::name(winTile) : "");
    if(loser >= 0)
      out << " from S" << loser;
    out << ": " << tai[i].toString();
    if(i + 1 < winners.size())
      out << "; ";
  }
  if(dealerTai > 0 && !exhausted)
    out << " (dealer tai " << dealerTai << ")";
  out << " | points";
  for(int s = 0; s < 4; s++)
    out << " " << points(s, base);
  out << " | next dealer S" << nextDealer << " streak " << nextStreak;
  return out.str();
}

// ---------------------------------------------------------------------------------------------------------------------
// Setup

Game::Game(const Rules& rules) : rules_(rules) {}

vector<uint8_t> Game::shuffledWall(const Rules& rules, Rand& rand) {
  vector<uint8_t> wall;
  wall.reserve(144);
  for(int k = 0; k < Tiles::NUM_KINDS; k++)
    for(int i = 0; i < 4; i++)
      wall.push_back((uint8_t)k);
  if(rules.hasFlowers())
    for(int f = 0; f < Tiles::NUM_FLOWERS; f++)
      wall.push_back((uint8_t)(Tiles::FLOWER_BASE + f));
  for(int i = (int)wall.size() - 1; i > 0; i--) {
    int j = (int)rand.nextUInt((uint32_t)(i + 1));
    swap(wall[i], wall[j]);
  }
  return wall;
}

void Game::start(const HandSetup& setup, Rand& rand) { start(setup, shuffledWall(rules_, rand)); }

void Game::start(const HandSetup& setup, const vector<uint8_t>& wall) {
  if((int)wall.size() != rules_.numTiles())
    throw StringError("Wall has " + Global::intToString((int)wall.size()) + " tiles, rules need " +
                      Global::intToString(rules_.numTiles()));
  setup_ = setup;
  eastSeat_ = setup.eastSeat < 0 ? setup.dealer : setup.eastSeat;
  wall_ = wall;
  front_ = 0;
  back_ = (int)wall_.size();
  for(int s = 0; s < 4; s++)
    seats_[s] = SeatState();
  events_.clear();
  result_ = HandResult();
  anyCall_ = false;
  claimSeat_ = claimTile_ = -1;
  claimLast_ = false;
  phase_ = SELF;

  // Deal four tiles at a time, four times, then the dealer's 17th tile.
  int pendingFlowers[4] = {0, 0, 0, 0};
  auto give = [&](int s, int t) {
    if(Tiles::isFlower(t)) {
      seats_[s].flowers |= (uint8_t)(1 << (t - Tiles::FLOWER_BASE));
      pendingFlowers[s]++;
      log(Event::FLOWER, s, t);
    }
    else {
      seats_[s].hand[t]++;
    }
  };
  for(int round = 0; round < 4; round++)
    for(int i = 0; i < 4; i++) {
      int s = (setup_.dealer + i) % 4;
      for(int j = 0; j < 4; j++)
        give(s, wall_[front_++]);
    }
  give(setup_.dealer, wall_[front_++]);
  // Replace flowers in passes, starting from the dealer, until nobody holds one.
  bool any = true;
  while(any) {
    any = false;
    for(int i = 0; i < 4; i++) {
      int s = (setup_.dealer + i) % 4;
      int n = pendingFlowers[s];
      pendingFlowers[s] = 0;
      for(int j = 0; j < n; j++) {
        any = true;
        give(s, wall_[--back_]);
      }
    }
  }
  if(checkFlowerWin())
    return;
  // The dealer's first turn has no drawn tile (a heavenly hand scores its best winning tile).
  enterSelf(setup_.dealer, -1, false);
}

void Game::log(Event::Type t, int seat, int tile, int extra) {
  Event e;
  e.type = t;
  e.seat = (int8_t)seat;
  e.tile = (uint8_t)tile;
  e.extra = (uint8_t)extra;
  events_.push_back(e);
}

// ---------------------------------------------------------------------------------------------------------------------
// Drawing

void Game::drawFor(int s, bool replacement, bool kongReplacement) {
  bool flowerReplacement = false;
  while(true) {
    // The last `keep` tiles are never drawn, replacements included (docs/rules.md 3.4).
    if(back_ - front_ - rules_.keep <= 0 || back_ <= front_) {
      finishExhausted();
      return;
    }
    int t = replacement ? wall_[--back_] : wall_[front_++];
    if(!replacement)
      seats_[s].draws++;
    if(Tiles::isFlower(t)) {
      seats_[s].flowers |= (uint8_t)(1 << (t - Tiles::FLOWER_BASE));
      log(Event::FLOWER, s, t);
      if(checkFlowerWin())
        return;
      replacement = true;
      flowerReplacement = true;
      continue;
    }
    seats_[s].hand[t]++;
    log(Event::DRAW, s, t, replacement ? 1 : 0);
    enterSelf(s, t, false);
    replacementWin_ = flowerReplacement || kongReplacement;
    return;
  }
}

bool Game::checkFlowerWin() {
  if(!rules_.hasFlowers())
    return false;
  int seven = -1, one = -1;
  for(int s = 0; s < 4; s++) {
    int n = __builtin_popcount(seats_[s].flowers);
    if(n == 8 && rules_.eightFlowers > 0) {
      finishFlowerWin(s, -1, rules_.eightFlowers);
      return true;
    }
    if(n == 7)
      seven = s;
    if(n == 1)
      one = s;
  }
  if(seven >= 0 && one >= 0 && rules_.sevenRobOne > 0) {
    finishFlowerWin(seven, one, rules_.sevenRobOne);
    return true;
  }
  return false;
}

void Game::enterSelf(int s, int drawn, bool afterCall) {
  phase_ = SELF;
  cur_ = s;
  drawn_ = drawn;
  afterCall_ = afterCall;
  replacementWin_ = false;
  for(int i = 0; i < 4; i++) {
    mustDecide_[i] = decided_[i] = canRon_[i] = false;
  }
  mustDecide_[s] = true;
}

// ---------------------------------------------------------------------------------------------------------------------
// Winning

Tai::WinInfo Game::winInfo(int s, int tile, bool selfDraw, bool robbedKong) const {
  const SeatState& st = seats_[s];
  Tai::WinInfo w;
  w.concealed = st.hand;
  if(!selfDraw)
    w.concealed[tile]++;
  w.melds = st.melds;
  w.flowers = st.flowers;
  w.winTile = tile;
  w.selfDraw = selfDraw;
  w.robbedKong = robbedKong;
  w.lastTile = selfDraw ? (back_ - front_ - rules_.keep <= 0) : claimLast_;
  w.replacement = selfDraw && replacementWin_;
  bool isDealer = s == setup_.dealer;
  w.heavenly = selfDraw && isDealer && !st.hasDiscarded && !st.declaredKong;
  w.earthly = selfDraw && !isDealer && st.draws == 1 && !st.hasDiscarded && !anyCall_ && !st.declaredKong;
  w.human = !selfDraw && !isDealer && !st.hasDiscarded && !anyCall_ && !robbedKong;
  w.declared = st.declared;
  w.seatWind = seatWind(s);
  w.roundWind = setup_.roundWind;
  return w;
}

int Game::winTai(int s) const {
  const SeatState& st = seats_[s];
  if(st.missedWin || phase_ == OVER)
    return -1;
  int numSets = 5 - (int)st.melds.size();
  int tile;
  bool selfDraw;
  if(phase_ == SELF) {
    if(s != cur_ || afterCall_)
      return -1;
    if(!Shape::isComplete(st.hand, numSets))
      return -1;
    // The dealer's first turn has no drawn tile: score the hand's best tile.
    if(drawn_ < 0) {
      int best = -1;
      for(int k = 0; k < Tiles::NUM_KINDS; k++) {
        if(st.hand[k] == 0)
          continue;
        Tai::Result r = Tai::compute(rules_, winInfo(s, k, true, false));
        best = max(best, r.total);
      }
      return best >= rules_.minTai ? best : -1;
    }
    tile = drawn_;
    selfDraw = true;
  }
  else {
    if(s == claimSeat_)
      return -1;
    tile = claimTile_;
    selfDraw = false;
    Counts c = st.hand;
    c[tile]++;
    if(!Shape::isComplete(c, numSets))
      return -1;
  }
  Tai::Result r = Tai::compute(rules_, winInfo(s, tile, selfDraw, phase_ == ROB_KONG));
  return r.total >= rules_.minTai ? r.total : -1;
}

// ---------------------------------------------------------------------------------------------------------------------
// Actions

vector<int> Game::pending() const {
  vector<int> out;
  if(phase_ == OVER)
    return out;
  for(int i = 0; i < 4; i++) {
    int s = (cur_ + i) % 4;
    if(mustDecide_[s] && !decided_[s])
      out.push_back(s);
  }
  return out;
}

bool Game::canChi(int s, int kind, int low) const {
  if(Tiles::isHonor(kind) || low < 0 || Tiles::isHonor(low) || Tiles::suitOf(low) != Tiles::suitOf(kind) ||
     low % 9 > 6 || kind < low || kind > low + 2)
    return false;
  for(int k = low; k <= low + 2; k++)
    if(k != kind && seats_[s].hand[k] == 0)
      return false;
  return true;
}

void Game::legalActions(int s, vector<Action>& out) const {
  out.clear();
  if(phase_ == OVER || !mustDecide_[s] || decided_[s])
    return;
  const SeatState& st = seats_[s];
  if(phase_ == SELF) {
    if(st.declaring) {
      // The discard of a declaration leaves the hand ready.
      Counts c = st.hand;
      for(int k = 0; k < Tiles::NUM_KINDS; k++) {
        if(c[k] == 0)
          continue;
        c[k]--;
        if(Tai::waits(c, st.melds) != 0)
          out.push_back(Action(Action::DISCARD, k));
        c[k]++;
      }
      return;
    }
    if(winTai(s) >= 0)
      out.push_back(Action(Action::TSUMO));
    if(st.declared) {
      // A locked hand: win, or discard the tile drawn.
      out.push_back(Action(Action::DISCARD, drawn_));
      return;
    }
    if(declareType(s) != 0)
      out.push_back(Action(Action::DECLARE));
    if(!afterCall_ && wallLeft() >= 1) {
      for(int k = 0; k < Tiles::NUM_KINDS; k++)
        if(st.hand[k] == 4)
          out.push_back(Action(Action::ANKAN, k));
      for(const Meld& m : st.melds)
        if(m.type == Meld::PON && st.hand[m.kind] > 0)
          out.push_back(Action(Action::KAKAN, m.kind));
    }
    for(int k = 0; k < Tiles::NUM_KINDS; k++)
      if(st.hand[k] > 0)
        out.push_back(Action(Action::DISCARD, k));
    return;
  }
  int t = claimTile_;
  if(canRon_[s])
    out.push_back(Action(Action::RON, t));
  if(phase_ == REACT && !claimLast_ && !st.declared) {
    if(st.hand[t] >= 2)
      out.push_back(Action(Action::PON, t));
    if(st.hand[t] >= 3)
      out.push_back(Action(Action::MINKAN, t));
    if(s == (claimSeat_ + 1) % 4)
      for(int low = t - 2; low <= t; low++)
        if(canChi(s, t, low))
          out.push_back(Action(Action::CHI, low));
  }
  out.push_back(Action(Action::PASS));
}

void Game::act(int s, const Action& a) {
  vector<Action> legal;
  legalActions(s, legal);
  if(find(legal.begin(), legal.end(), a) == legal.end())
    throw StringError("Illegal action for S" + Global::intToString(s) + ": " + a.toString());
  SeatState& st = seats_[s];

  if(phase_ == SELF) {
    // Not declaring a possible self-drawn win is a missed win, which the discard or kong below then ends.
    switch(a.type) {
      case Action::TSUMO: {
        int tile = drawn_;
        if(tile < 0) {
          // The dealer's first turn: the best tile to count as the winning one.
          int best = -1;
          for(int k = 0; k < Tiles::NUM_KINDS; k++) {
            if(st.hand[k] == 0)
              continue;
            int v = Tai::compute(rules_, winInfo(s, k, true, false)).total;
            if(v > best) {
              best = v;
              tile = k;
            }
          }
        }
        finishWin({s}, -1, tile, true, false);
        return;
      }
      case Action::ANKAN: {
        st.hand[a.kind] -= 4;
        Meld m;
        m.type = Meld::ANKAN;
        m.kind = m.called = a.kind;
        m.from = -1;
        st.melds.push_back(m);
        st.declaredKong = true;
        st.missedWin = false;
        log(Event::ANKAN, s, a.kind);
        drawFor(s, true, true);
        return;
      }
      case Action::KAKAN: {
        st.hand[a.kind]--;
        st.missedWin = false;
        log(Event::KAKAN, s, a.kind);
        claimSeat_ = s;
        claimTile_ = a.kind;
        claimLast_ = false;
        phase_ = ROB_KONG;
        bool any = false;
        for(int i = 0; i < 4; i++) {
          mustDecide_[i] = decided_[i] = canRon_[i] = false;
          if(i == s)
            continue;
          if(winTai(i) >= 0) {
            canRon_[i] = mustDecide_[i] = true;
            any = true;
          }
        }
        if(!any && !hold_)
          resolveRob();
        return;
      }
      case Action::DECLARE: {
        st.declared = (uint8_t)declareType(s);
        st.declaring = true;
        log(Event::DECLARE, s, 0, st.declared);
        return;
      }
      case Action::DISCARD: {
        st.declaring = false;
        st.hand[a.kind]--;
        Discard d;
        d.kind = a.kind;
        d.tsumogiri = a.kind == drawn_;
        d.called = false;
        st.discards.push_back(d);
        st.missedWin = false;
        st.hasDiscarded = true;
        log(Event::DISCARD, s, a.kind, d.tsumogiri ? 1 : 0);
        afterDiscard(s, a.kind);
        return;
      }
      default:
        ASSERT_UNREACHABLE;
    }
  }

  // REACT or ROB_KONG.
  decision_[s] = a;
  decided_[s] = true;
  for(int i = 0; i < 4; i++)
    if(mustDecide_[i] && !decided_[i])
      return;
  if(phase_ == REACT)
    resolveReact();
  else
    resolveRob();
}

int Game::declareType(int s) const {
  const SeatState& st = seats_[s];
  if(phase_ != SELF || s != cur_ || st.declared || st.hasDiscarded || anyCall_ || !st.melds.empty())
    return 0;
  if(rules_.tianting <= 0 && rules_.diting <= 0)
    return 0;
  // Ready after some discard. 天聽: the dealer, or a player whose dealt tiles were ready before the first draw;
  // 地聽: another player whose first discard leaves the hand ready. Nobody has called so far.
  bool ready = false;
  Counts c = st.hand;
  for(int k = 0; k < Tiles::NUM_KINDS && !ready; k++) {
    if(c[k] == 0)
      continue;
    c[k]--;
    ready = Tai::waits(c, st.melds) != 0;
    c[k]++;
  }
  if(!ready)
    return 0;
  bool heavenly = s == setup_.dealer;
  if(!heavenly && drawn_ >= 0) {
    c[drawn_]--;
    heavenly = Tai::waits(c, st.melds) != 0;
    c[drawn_]++;
  }
  int type = heavenly ? 1 : 2;
  return (type == 1 ? rules_.tianting : rules_.diting) > 0 ? type : 0;
}

void Game::afterDiscard(int d, int kind) {
  claimSeat_ = d;
  claimTile_ = kind;
  claimLast_ = wallLeft() <= 0;
  phase_ = REACT;
  bool any = false;
  for(int i = 0; i < 4; i++) {
    mustDecide_[i] = decided_[i] = canRon_[i] = false;
    if(i == d)
      continue;
    canRon_[i] = winTai(i) >= 0;
    vector<Action> legal;
    mustDecide_[i] = true;
    legalActions(i, legal);
    mustDecide_[i] = legal.size() > 1;  // more than PASS
    any = any || mustDecide_[i];
  }
  if(!any && !hold_)
    resolveReact();
}

void Game::resolveReact() {
  int d = claimSeat_, t = claimTile_;
  vector<int> rons;
  int ponSeat = -1, chiSeat = -1;
  for(int i = 1; i < 4; i++) {
    int s = (d + i) % 4;
    if(!mustDecide_[s])
      continue;
    const Action& a = decision_[s];
    if(canRon_[s] && a.type != Action::RON)
      seats_[s].missedWin = true;
    if(a.type == Action::RON)
      rons.push_back(s);
    else if(a.type == Action::PON || a.type == Action::MINKAN)
      ponSeat = s;
    else if(a.type == Action::CHI)
      chiSeat = s;
  }
  if(!rons.empty()) {
    if(!rules_.multiRon)
      rons.resize(1);
    finishWin(rons, d, t, false, false);
    return;
  }
  int caller = ponSeat >= 0 ? ponSeat : chiSeat;
  if(caller < 0) {
    drawFor((d + 1) % 4, false, false);
    return;
  }
  seats_[d].discards.back().called = true;
  anyCall_ = true;
  SeatState& st = seats_[caller];
  const Action& a = decision_[caller];
  Meld m;
  m.called = (uint8_t)t;
  m.from = (int8_t)d;
  if(a.type == Action::CHI) {
    m.type = Meld::CHI;
    m.kind = a.kind;
    for(int k = a.kind; k <= a.kind + 2; k++)
      if(k != t)
        st.hand[k]--;
    st.melds.push_back(m);
    log(Event::CHI, caller, t, a.kind);
    enterSelf(caller, -1, true);
    return;
  }
  m.kind = (uint8_t)t;
  if(a.type == Action::PON) {
    m.type = Meld::PON;
    st.hand[t] -= 2;
    st.melds.push_back(m);
    log(Event::PON, caller, t);
    enterSelf(caller, -1, true);
    return;
  }
  m.type = Meld::MINKAN;
  st.hand[t] -= 3;
  st.melds.push_back(m);
  st.declaredKong = true;
  log(Event::MINKAN, caller, t);
  drawFor(caller, true, false);
}

void Game::resolveRob() {
  int k = claimSeat_, t = claimTile_;
  vector<int> rons;
  for(int i = 1; i < 4; i++) {
    int s = (k + i) % 4;
    if(!mustDecide_[s])
      continue;
    if(decision_[s].type == Action::RON)
      rons.push_back(s);
    else
      seats_[s].missedWin = true;
  }
  if(!rons.empty()) {
    if(!rules_.multiRon)
      rons.resize(1);
    finishWin(rons, k, t, false, true);
    return;
  }
  SeatState& st = seats_[k];
  for(Meld& m : st.melds)
    if(m.type == Meld::PON && m.kind == t)
      m.type = Meld::KAKAN;
  st.declaredKong = true;
  claimSeat_ = claimTile_ = -1;
  drawFor(k, true, true);
}

// ---------------------------------------------------------------------------------------------------------------------
// Ending

void Game::finishWin(const vector<int>& winners, int loser, int tile, bool selfDraw, bool robbedKong) {
  result_ = HandResult();
  result_.winners = winners;
  result_.loser = loser;
  result_.winTile = tile;
  result_.selfDraw = selfDraw;
  for(int w : winners) {
    Tai::Result r = Tai::compute(rules_, winInfo(w, tile, selfDraw, robbedKong));
    result_.tai.push_back(r);
    log(selfDraw ? Event::TSUMO : Event::RON, w, tile);
    if(!selfDraw)
      seats_[w].hand[tile]++;
  }
  settle();
}

void Game::finishFlowerWin(int winner, int loser, int tai) {
  result_ = HandResult();
  result_.winners = {winner};
  result_.loser = loser;
  result_.flowerWin = true;
  result_.selfDraw = loser < 0;
  Tai::Result r;
  r.items.push_back({loser < 0 ? Tai::EIGHT_FLOWERS : Tai::SEVEN_ROB_ONE, tai});
  r.total = tai;
  result_.tai.push_back(r);
  log(Event::FLOWER_WIN, winner, 0);
  settle();
}

void Game::finishExhausted() {
  result_ = HandResult();
  result_.exhausted = true;
  log(Event::EXHAUSTED, cur_, 0);
  settle();
}

void Game::settle() {
  phase_ = OVER;
  for(int i = 0; i < 4; i++)
    mustDecide_[i] = decided_[i] = false;
  int dealer = setup_.dealer;
  result_.dealerTai = rules_.dealer + rules_.streak * setup_.streak;
  if(result_.exhausted) {
    result_.nextDealer = dealer;
    result_.nextStreak = setup_.streak + (rules_.drawStreak ? 1 : 0);
    return;
  }
  bool dealerWon = false;
  for(size_t i = 0; i < result_.winners.size(); i++) {
    int w = result_.winners[i];
    dealerWon = dealerWon || w == dealer;
    int handTai = result_.tai[i].total;
    for(int p = 0; p < 4; p++) {
      if(p == w)
        continue;
      if(result_.loser >= 0 && p != result_.loser)
        continue;
      int amount = handTai + ((w == dealer || p == dealer) ? result_.dealerTai : 0);
      result_.baseDelta[w]++;
      result_.baseDelta[p]--;
      result_.taiDelta[w] += amount;
      result_.taiDelta[p] -= amount;
    }
  }
  if(dealerWon) {
    result_.nextDealer = dealer;
    result_.nextStreak = setup_.streak + 1;
  }
  else {
    result_.nextDealer = (dealer + 1) % 4;
    result_.nextStreak = 0;
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Information

Counts Game::visibleCounts(int s) const {
  Counts c = seats_[s].hand;
  for(int p = 0; p < 4; p++) {
    for(const Meld& m : seats_[p].melds) {
      if(m.type == Meld::ANKAN && p != s)
        continue;
      for(int t : m.tiles())
        c[t]++;
    }
    for(const Discard& d : seats_[p].discards)
      if(!d.called)
        c[d.kind]++;
  }
  return c;
}

// ---------------------------------------------------------------------------------------------------------------------
// Positions

Position Game::positionOf(int s) const {
  Position p;
  p.setup = setup_;
  p.setup.eastSeat = eastSeat_;
  p.me = s;
  p.hand = seats_[s].hand;
  p.missedWin = seats_[s].missedWin;
  if(phase_ == SELF && cur_ == s) {
    p.drawn = drawn_;
    p.afterCall = afterCall_;
    p.declaring = seats_[s].declaring;
  }
  else if((phase_ == REACT || phase_ == ROB_KONG) && s != claimSeat_) {
    p.claimSeat = claimSeat_;
    p.claimTile = claimTile_;
    p.robKong = phase_ == ROB_KONG;
  }
  for(int q = 0; q < 4; q++) {
    Position::Player& pl = p.players[q];
    pl.melds = seats_[q].melds;
    if(q != s)
      for(Meld& m : pl.melds)
        if(m.type == Meld::ANKAN)
          m.kind = m.called = Position::UNKNOWN;
    pl.flowers = seats_[q].flowers;
    pl.discards = seats_[q].discards;
    pl.declared = seats_[q].declared;
  }
  p.wallLeft = wallLeft();
  return p;
}

vector<Event> Game::plainHistory(const Position& p) {
  vector<Event> out;
  if(p.me < 0 || p.me > 3 || p.claimSeat > 3)
    return out;
  int left[4];
  int total = 0;
  for(int s = 0; s < 4; s++) {
    left[s] = (int)p.players[s].discards.size();
    total += left[s];
  }
  // Backwards from the last discard: the claimed one, or that of the seat before `me`.
  int s = p.claimSeat >= 0 ? p.claimSeat : (p.me + 3) % 4;
  while(total > 0) {
    if(left[s] > 0) {
      const Discard& d = p.players[s].discards[--left[s]];
      Event e;
      e.type = Event::DISCARD;
      e.seat = (int8_t)s;
      e.tile = d.kind;
      e.extra = d.tsumogiri ? 1 : 0;
      out.push_back(e);
      total--;
    }
    s = (s + 3) % 4;
  }
  reverse(out.begin(), out.end());
  return out;
}

void Game::loadPosition(const Position& p, Rand& rand, const vector<Event>& history) {
  loadPosition(p, rand);
  // A position where nobody can claim has gone on to the next draw: its events are the game's own from there.
  if(phase_ != OVER && ((p.claimSeat >= 0) == (phase_ != SELF)))
    events_ = history;
}

void Game::loadPosition(const Position& p, Rand& rand) {
  if(p.me < 0 || p.me > 3 || p.claimSeat > 3 || p.claimSeat == p.me)
    throw StringError("Bad seat in position");
  setup_ = p.setup;
  eastSeat_ = setup_.eastSeat < 0 ? setup_.dealer : setup_.eastSeat;
  events_.clear();
  result_ = HandResult();
  anyCall_ = false;
  int used[Tiles::NUM_KINDS] = {0};
  uint8_t flowersShown = 0;
  for(int s = 0; s < 4; s++) {
    seats_[s] = SeatState();
    const Position::Player& pl = p.players[s];
    seats_[s].melds = pl.melds;
    seats_[s].flowers = pl.flowers;
    seats_[s].discards = pl.discards;
    seats_[s].declared = pl.declared;
    if(flowersShown & pl.flowers)
      throw StringError("A flower shown twice");
    flowersShown |= pl.flowers;
    for(const Meld& m : pl.melds) {
      if(m.kind == Position::UNKNOWN) {
        if(m.type != Meld::ANKAN || s == p.me)
          throw StringError("Only another player's concealed kong can be of unknown kind");
        continue;
      }
      for(int t : m.tiles())
        used[t]++;
      if(m.isOpen())
        anyCall_ = true;
    }
    for(const Discard& d : pl.discards)
      if(!d.called)
        used[d.kind]++;
  }
  if(flowersShown && !rules_.hasFlowers())
    throw StringError("Flowers shown, but the rules have none");
  for(int k = 0; k < Tiles::NUM_KINDS; k++)
    used[k] += p.hand[k];
  bool react = p.claimSeat >= 0;
  int claimTile = p.claimTile;
  if(react && claimTile < 0) {
    if(p.robKong || p.players[p.claimSeat].discards.empty())
      throw StringError("A claim needs its tile");
    claimTile = p.players[p.claimSeat].discards.back().kind;
  }
  if(react && p.robKong)
    used[claimTile]++;
  // Others' concealed kongs of unknown kind: any kind nobody has seen.
  for(int s = 0; s < 4; s++)
    for(Meld& m : seats_[s].melds) {
      if(m.kind != Position::UNKNOWN)
        continue;
      vector<int> free;
      for(int k = 0; k < Tiles::NUM_KINDS; k++)
        if(used[k] == 0)
          free.push_back(k);
      if(free.empty())
        throw StringError("No kind left for a concealed kong");
      int k = free[rand.nextUInt((uint32_t)free.size())];
      m.kind = m.called = (uint8_t)k;
      used[k] += 4;
    }
  for(int k = 0; k < Tiles::NUM_KINDS; k++)
    if(used[k] > 4)
      throw StringError("More than four of " + Tiles::name(k));
  int mine = Tiles::total(p.hand) + 3 * (int)p.players[p.me].melds.size();
  if(mine != (react ? 16 : 17))
    throw StringError("The hand has " + Global::intToString(Tiles::total(p.hand)) + " tiles; with " +
                      Global::intToString((int)p.players[p.me].melds.size()) + " melds it needs " +
                      Global::intToString((react ? 16 : 17) - 3 * (int)p.players[p.me].melds.size()));
  seats_[p.me].hand = p.hand;
  seats_[p.me].missedWin = p.missedWin;

  // Deal what `me` cannot see.
  vector<uint8_t> pool;
  for(int k = 0; k < Tiles::NUM_KINDS; k++)
    for(int i = used[k]; i < 4; i++)
      pool.push_back((uint8_t)k);
  for(int i = (int)pool.size() - 1; i > 0; i--)
    swap(pool[i], pool[rand.nextUInt((uint32_t)(i + 1))]);
  size_t next = 0;
  for(int s = 0; s < 4; s++) {
    if(s == p.me)
      continue;
    int need = 16 - 3 * (int)seats_[s].melds.size();
    if(need < 1 || next + need > pool.size())
      throw StringError("Not enough tiles for the other hands");
    for(int i = 0; i < need; i++)
      seats_[s].hand[pool[next++]]++;
  }
  wall_.assign(pool.begin() + next, pool.end());
  if(rules_.hasFlowers())
    for(int f = 0; f < Tiles::NUM_FLOWERS; f++)
      if(!(flowersShown & (1 << f)))
        wall_.push_back((uint8_t)(Tiles::FLOWER_BASE + f));
  for(int i = (int)wall_.size() - 1; i > 0; i--)
    swap(wall_[i], wall_[rand.nextUInt((uint32_t)(i + 1))]);
  int remaining = (int)wall_.size();
  int target = p.wallLeft >= 0 ? p.wallLeft + rules_.keep : remaining;
  front_ = max(0, remaining - target);
  back_ = remaining;

  for(int s = 0; s < 4; s++) {
    SeatState& st = seats_[s];
    st.hasDiscarded = !st.discards.empty();
    st.draws = (int)st.discards.size();
    for(const Meld& m : st.melds)
      st.declaredKong = st.declaredKong || m.isKong();
  }
  if(!react) {
    enterSelf(p.me, p.drawn, p.afterCall);
    seats_[p.me].declaring = p.declaring && seats_[p.me].declared != 0;
    return;
  }
  cur_ = p.claimSeat;
  drawn_ = -1;
  afterCall_ = false;
  replacementWin_ = false;
  claimSeat_ = p.claimSeat;
  claimTile_ = claimTile;
  claimLast_ = !p.robKong && wallLeft() <= 0;
  phase_ = p.robKong ? ROB_KONG : REACT;
  for(int i = 0; i < 4; i++) {
    mustDecide_[i] = decided_[i] = canRon_[i] = false;
    if(i == claimSeat_)
      continue;
    canRon_[i] = winTai(i) >= 0;
    if(p.robKong) {
      mustDecide_[i] = canRon_[i];
      continue;
    }
    vector<Action> legal;
    mustDecide_[i] = true;
    legalActions(i, legal);
    mustDecide_[i] = legal.size() > 1;
  }
  // Nobody can claim: the play goes on, as after any discard.
  if(pending().empty()) {
    if(p.robKong)
      resolveRob();
    else
      resolveReact();
  }
}
