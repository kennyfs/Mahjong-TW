#include "../players/players.h"

#include "../command/play.h"
#include "../core/global.h"

#include <algorithm>

using namespace std;

namespace {
  // mc[:playouts=N]: flat Monte Carlo, the style of the Computer Olympiad programs, as a yardstick. The tiles the
  // seat cannot see are dealt at random; in each deal every action still considered is tried and the hand finished
  // by greedy players, with the same random numbers for every action. N playouts (default 400) are spent by
  // sequential halving: in each round the actions left share the round's deals, and the better half by mean result
  // stays. A win is always taken. (A hand's result swings by several points whatever the discard, so a few dozen
  // playouts per action say almost nothing: with 30 deals for every action it wins 1% of its hands.)
  class McPlayer : public Player {
   public:
    McPlayer(int playouts, const string& spec) : playouts_(playouts), spec_(spec), greedy_(makeBasicPlayer("greedy")) {}
    string name() const override { return spec_; }

    Action decide(const Game& game, int seat, const vector<Action>& legal, Rand& rand) override {
      if(legal.size() == 1)
        return legal[0];
      for(const Action& a : legal)
        if(a.type == Action::TSUMO || a.type == Action::RON)
          return a;
      const Position pos = game.positionOf(seat);
      const double base = game.rules().base;
      Player* players[4] = {greedy_.get(), greedy_.get(), greedy_.get(), greedy_.get()};
      vector<double> sum(legal.size(), 0.0);
      vector<size_t> alive(legal.size());
      for(size_t i = 0; i < legal.size(); i++)
        alive[i] = i;
      int rounds = 0;
      for(size_t n = legal.size(); n > 1; n = (n + 1) / 2)
        rounds++;
      vector<Action> others;
      Game sample(game.rules());
      for(int round = 0; round < rounds; round++) {
        const int deals = max(1, playouts_ / rounds / (int)alive.size());
        for(int s = 0; s < deals; s++) {
          sample.loadPosition(pos, rand);
          const uint64_t playSeed = rand.nextUInt64();
          for(size_t i : alive) {
            Game g = sample;
            Rand playRand(playSeed);
            // Decisions on a discard are simultaneous: the other seats answer by greedy on the same state.
            vector<pair<int, Action>> decisions;
            for(int p : g.pending()) {
              if(p == seat) {
                decisions.push_back({p, legal[i]});
                continue;
              }
              g.legalActions(p, others);
              decisions.push_back({p, greedy_->decide(g, p, others, playRand)});
            }
            for(auto& d : decisions)
              g.act(d.first, d.second);
            Play::playOut(g, players, playRand);
            sum[i] += g.result().points(seat, base);
          }
        }
        // The actions left have all seen the same deals, so their sums compare.
        sort(alive.begin(), alive.end(), [&](size_t a, size_t b) { return sum[a] > sum[b]; });
        alive.resize((alive.size() + 1) / 2);
      }
      return legal[alive[0]];
    }

   private:
    int playouts_;
    string spec_;
    unique_ptr<Player> greedy_;
  };
}

unique_ptr<Player> makeMcPlayer(const string& spec) {
  int playouts = 400;
  if(spec.size() > 3) {
    for(const string& tok : Global::split(spec.substr(3), ',')) {
      size_t eq = tok.find('=');
      string key = eq == string::npos ? tok : tok.substr(0, eq), value = eq == string::npos ? "" : tok.substr(eq + 1);
      if(key == "playouts" && Global::tryStringToInt(value, playouts) && playouts > 0)
        continue;
      throw StringError("Bad option for the mc player: " + tok);
    }
  }
  return unique_ptr<Player>(new McPlayer(playouts, spec));
}
