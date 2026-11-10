#include "../learn/expand.h"

#include "../core/global.h"
#include "../game/shape.h"
#include "../players/players.h"

#include <algorithm>
#include <cstring>

using namespace std;

void Learn::expand(const HandRecord& rec, int markIndex, const Symmetry& sym, const Row& row) {
  if(markIndex < 0 || markIndex >= (int)rec.marks.size())
    throw StringError("No such mark in the record");
  const Mark& mark = rec.marks[markIndex];
  if(mark.decision >= rec.actions.size())
    throw StringError("A mark beyond the record's decisions");
  Replay replay(rec, sym);
  while(replay.index() < mark.decision)
    replay.step();
  Game& game = replay.game();
  const Rules& rules = rec.rules;
  const int me = replay.seat();

  // ----- Inputs -----
  Features::observation(game, me, row.kind, row.seat, row.global, row.legal);
  Features::fullState(game, me, rec.playerTypes, row.kindFull, row.seatFull, row.globalFull);

  // ----- The decision -----
  const int played = replay.actionIndex();
  *row.played = played;
  memset(row.mu, 0, sizeof(float) * NUM_ACTIONS);
  memset(row.known, 0, NUM_ACTIONS);
  memset(row.events, 0, sizeof(uint16_t) * NUM_ACTIONS);
  memset(row.tai, 0, NUM_ACTIONS * 4);
  auto setOutcome = [&](int index, const Outcome& o) {
    row.known[index] = 1;
    row.events[index] = o.events(me);
    for(int r = 0; r < 4; r++)
      row.tai[index * 4 + r] = o.tai[(me + r) % 4];
  };
  for(const MarkEntry& e : mark.entries) {
    int index = replay.mapped(e.action);
    if(e.hasProb)
      row.mu[index] = e.prob;
    if(e.hasOutcome)
      setOutcome(index, e.outcome);
  }
  eventCoefficients(rules, rec.setup, me, row.coef, row.coef + NUM_EVENTS);
  int numLegal = 0;
  for(int i = 0; i < NUM_ACTIONS; i++)
    numLegal += row.legal[i];
  row.info[0] = (float)rules.base;
  row.info[1] = (float)(rules.dealer + rules.streak * rec.setup.streak);
  row.info[3] = game.phase() == Game::SELF ? 0.0f : (game.phase() == Game::REACT ? 1.0f : 2.0f);
  row.info[4] = (float)numLegal;
  row.info[5] = (float)mark.decision / (float)max<size_t>(1, rec.actions.size());
  row.info[6] = (float)game.wallLeft();
  row.info[7] = (float)me;

  // ----- Labels of the present -----
  memset(row.oppHand, 0, 3 * 34);
  memset(row.danger, 0, 3 * 34);
  memset(row.dangerTai, 0, 3 * 34);
  memset(row.nextChow, 0, 34);
  for(int r = 1; r <= 3; r++) {
    const int p = (me + r) % 4;
    const SeatState& sp = game.seat(p);
    for(int k = 0; k < 34; k++)
      row.oppHand[(r - 1) * 34 + k] = sp.hand[k];
    const int shanten = Shape::shanten(sp.hand, 5 - (int)sp.melds.size());
    row.oppShanten[r - 1] = (uint8_t)min(max(shanten, 0), 3);
    if(shanten != 0 || sp.missedWin)
      continue;
    const uint64_t waits = Tai::waits(sp.hand, sp.melds);
    for(int k = 0; k < 34; k++) {
      if(!(waits & ((uint64_t)1 << k)))
        continue;
      int tai = Tai::compute(rules, game.winInfo(p, k, false, false)).total;
      if(tai < rules.minTai)
        continue;
      row.danger[(r - 1) * 34 + k] = 1;
      row.dangerTai[(r - 1) * 34 + k] = (uint8_t)min(255, tai);
    }
  }
  {
    const Counts& next = game.seat((me + 1) % 4).hand;
    for(int k = 0; k < 27; k++) {
      const int n = k % 9;
      for(int low = max(0, n - 2); low <= min(6, n); low++) {
        bool ok = true;
        for(int i = low; i <= low + 2; i++)
          if(i != n && next[k - n + i] == 0)
            ok = false;
        if(ok)
          row.nextChow[k] = 1;
      }
    }
  }

  {
    static thread_local unique_ptr<Player> greedy = makeBasicPlayer("greedy");
    static thread_local Rand unused("greedy");
    vector<Action> legal;
    game.legalActions(me, legal);
    *row.greedy = actionIndex(game, greedy->decide(game, me, legal, unused));
  }

  // ----- The rest of the hand -----
  static const int WITHIN[4] = {1, 2, 4, 8};
  bool readyWithin[4] = {false, false, false, false};
  int myDraws = 0, drawsLeft = 0;
  size_t seen = game.events().size();
  for(int r = 0; r < 3; r++)
    row.nextDiscard[r] = -1;
  auto note = [&]() {
    const vector<Event>& events = game.events();
    for(; seen < events.size(); seen++) {
      const Event& e = events[seen];
      if(e.type == Event::DRAW) {
        drawsLeft++;
        if(e.seat == me && e.extra == 0)
          myDraws++;
      }
      else if(e.type == Event::DISCARD && e.seat != me) {
        int8_t& d = row.nextDiscard[(e.seat - me + 4) % 4 - 1];
        if(d < 0)
          d = (int8_t)e.tile;
      }
    }
    const SeatState& st = game.seat(me);
    if(Shape::shanten(st.hand, 5 - (int)st.melds.size()) <= 0)
      for(int i = 0; i < 4; i++)
        if(myDraws <= WITHIN[i])
          readyWithin[i] = true;
  };
  note();
  while(!replay.done()) {
    replay.step();
    note();
  }
  if(game.phase() != Game::OVER)
    throw StringError("A record ends before its hand does");
  const HandResult& res = game.result();
  const Outcome main = Outcome::of(res);
  // Stage 0 labels the played action too (its hand finished by the reference players); otherwise it is what happened.
  if(!row.known[played])
    setOutcome(played, main);
  row.info[2] = (float)main.points(me, rules, rec.setup);

  memset(row.taiItems, 0, Tai::NUM_ITEMS);
  memset(row.future, 0, FUTURE_SIZE);
  for(int r = 0; r < 4; r++)
    row.winTile[r] = -1;
  for(size_t i = 0; i < res.winners.size(); i++) {
    const int w = res.winners[i];
    if(!res.flowerWin && res.winTile >= 0)
      row.winTile[(w - me + 4) % 4] = (int8_t)res.winTile;
    if(w != me)
      continue;
    row.future[0] = 1;
    row.future[1] = (uint8_t)min(255, max(0, res.tai[i].total));
    for(const pair<Tai::Item, int>& item : res.tai[i].items)
      row.taiItems[item.first] = 1;
  }
  for(int i = 0; i < 4; i++)
    row.future[2 + i] = readyWithin[i] ? 1 : 0;
  row.future[6] = (uint8_t)min(255, drawsLeft);
  for(int r = 0; r < 4; r++) {
    const SeatState& sp = game.seat((me + r) % 4);
    const int shanten = Shape::shanten(sp.hand, 5 - (int)sp.melds.size());
    row.future[7 + r] = (uint8_t)(min(max(shanten, -1), 3) + 1);
  }
  const SeatState& st = game.seat(me);
  for(int k = 0; k < 34; k++)
    row.finalHand[k] = st.hand[k];
  for(const Meld& m : st.melds)
    for(int t : m.tiles())
      row.finalHand[t]++;
}
