#include "../learn/branch.h"

#include "../core/global.h"

using namespace std;

// Plays a started hand to the end (as Play::playOut, which lives with the commands).
static void finish(Game& game, Player* const players[4], Rand& rand) {
  vector<Action> legal;
  while(game.phase() != Game::OVER) {
    vector<int> pending = game.pending();
    vector<pair<int, Action>> decisions;
    for(int s : pending) {
      game.legalActions(s, legal);
      decisions.push_back({s, players[s]->decide(game, s, legal, rand)});
    }
    for(auto& d : decisions)
      game.act(d.first, d.second);
  }
}

// Applies `action` for the seat that decides next, then the recorded actions of the seats deciding on the same
// discard after it (decisions on a discard are simultaneous: the others keep what they chose).
static void applyWithGroup(Game& g, const Learn::HandRecord& rec, int decision, int action, int seatDecisions[4]) {
  const int others = g.phase() == Game::SELF ? 0 : (int)g.pending().size() - 1;
  const int s = g.pending()[0];
  g.act(s, Learn::actionOf(g, s, action));
  if(seatDecisions)
    seatDecisions[s]++;
  for(int j = 1; j <= others; j++) {
    const int s2 = g.pending()[0];
    g.act(s2, Learn::actionOf(g, s2, rec.actions[decision + j]));
    if(seatDecisions)
      seatDecisions[s2]++;
  }
}

Game Learn::branch(const HandRecord& rec, int decision, int action, int seatDecisions[4]) {
  if(decision < 0 || decision >= (int)rec.actions.size())
    throw StringError("No such decision in the record");
  if(seatDecisions)
    for(int s = 0; s < 4; s++)
      seatDecisions[s] = 0;
  Replay replay(rec);
  while(replay.index() < decision) {
    if(seatDecisions)
      seatDecisions[replay.seat()]++;
    replay.step();
  }
  Game g = replay.game();
  applyWithGroup(g, rec, decision, action, seatDecisions);
  return g;
}

int Learn::labelAllActions(HandRecord& rec, Player* const players[4], Rand& rand) {
  int count = 0;
  size_t next = 0;
  vector<Action> legal;
  Replay replay(rec);
  // Marks are in the order of their decisions.
  for(size_t i = 1; i < rec.marks.size(); i++)
    if(rec.marks[i].decision <= rec.marks[i - 1].decision)
      throw StringError("A record's marks are out of order");
  while(!replay.done() && next < rec.marks.size()) {
    const int i = replay.index();
    if(rec.marks[next].decision == i) {
      Mark& mark = rec.marks[next++];
      const Game& base = replay.game();
      const int s = replay.seat();
      base.legalActions(s, legal);
      for(const Action& a : legal) {
        const int index = actionIndex(base, a);
        Game g = base;
        applyWithGroup(g, rec, i, index, nullptr);
        finish(g, players, rand);
        MarkEntry* entry = nullptr;
        for(MarkEntry& e : mark.entries)
          if(e.action == index)
            entry = &e;
        if(!entry) {
          mark.entries.push_back(MarkEntry());
          entry = &mark.entries.back();
          entry->action = (uint8_t)index;
        }
        entry->hasOutcome = true;
        entry->outcome = Outcome::of(g.result());
        count++;
      }
    }
    replay.step();
  }
  return count;
}
