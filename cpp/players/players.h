#ifndef PLAYERS_PLAYERS_H_
#define PLAYERS_PLAYERS_H_

#include "../core/rand.h"
#include "../game/game.h"

#include <memory>
#include <string>
#include <vector>

// A player decides for one seat, using only what that seat may see: its own hand (game.seat(seat).hand), everyone's
// melds, flowers and discards, and game.visibleCounts(seat).
class Player {
 public:
  virtual ~Player() {}
  virtual std::string name() const = 0;
  // `legal` is game.legalActions(seat), never empty.
  virtual Action decide(const Game& game, int seat, const std::vector<Action>& legal, Rand& rand) = 0;
};

// Specs:
//   random   wins whenever it can, otherwise a uniformly random legal action;
//   greedy   wins whenever it can; discards for the fewest tiles from ready (shanten), then the most tiles that
//            improve it (ukeire); calls a chow or pung only when it lowers shanten; declares kongs that do not
//            raise it; never defends. Variations for training data, as greedy:eps=0.1,calls=more: eps is the chance
//            of a random discard; calls=more also calls when that keeps the shanten, calls=none never calls.
//   mc[:playouts=N]
//            flat Monte Carlo, as a yardstick: deals the unseen tiles at random, finishes the hand after each action
//            with greedy players, and plays the best mean result; N playouts a decision (default 400), spent by
//            sequential halving.
//   net:model=FILE[,mode=q|policy|sample][,temp=T]
//            the network (cpp/learn/netmodel.h, needs ONNX Runtime): mode=q plays the action with the best expected
//            result composed from its outcome events (the default), mode=policy the policy head's first choice,
//            mode=sample draws from the policy at temperature T.
std::unique_ptr<Player> makePlayer(const std::string& spec);
// The players that need nothing but the engine (random, greedy); nullptr for any other spec.
std::unique_ptr<Player> makeBasicPlayer(const std::string& spec);

namespace Greedy {
  // The number of unseen tiles (by `visible`) that lower the shanten of a 3 numSets + 1 hand.
  int ukeire(const Counts& hand, int numSets, const Counts& visible, int shanten);
}

#endif // PLAYERS_PLAYERS_H_
