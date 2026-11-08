#include "../command/play.h"

using namespace std;

void Play::playOut(Game& game, Player* const players[4], Rand& rand,
                   const function<void(const Game&, int, const Action&)>& onDecision) {
  vector<Action> legal;
  while(game.phase() != Game::OVER) {
    vector<int> pending = game.pending();
    // Decisions on a discard are simultaneous: everyone decides on the same state before any is applied.
    vector<pair<int, Action>> decisions;
    for(int s : pending) {
      game.legalActions(s, legal);
      Action a = players[s]->decide(game, s, legal, rand);
      decisions.push_back({s, a});
    }
    for(auto& d : decisions) {
      if(onDecision)
        onDecision(game, d.first, d.second);
      game.act(d.first, d.second);
    }
  }
}

HandSetup Play::randomSetup(Rand& rand) {
  HandSetup setup;
  setup.dealer = 0;
  setup.roundWind = (int)rand.nextUInt(4);
  double u = rand.nextDouble();
  setup.streak = u < 0.60 ? 0 : u < 0.85 ? 1 : u < 0.95 ? 2 : 3;
  return setup;
}
