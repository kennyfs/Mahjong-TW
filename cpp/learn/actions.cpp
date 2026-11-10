#include "../learn/actions.h"

#include "../core/global.h"

using namespace std;

int Learn::actionIndex(const Game& game, const Action& a) {
  switch(a.type) {
    case Action::DISCARD: return A_DISCARD + a.kind;
    case Action::ANKAN:
    case Action::KAKAN: return A_KONG + a.kind;
    case Action::TSUMO: return A_TSUMO;
    case Action::RON: return A_RON;
    case Action::PASS: return A_PASS;
    case Action::PON: return A_PON;
    case Action::MINKAN: return A_MINKAN;
    case Action::CHI: return A_CHI + (game.claimTile() - a.kind);
    default: break;
  }
  ASSERT_UNREACHABLE;
  return -1;
}

Action Learn::actionOf(const Game& game, int seat, int index) {
  vector<Action> legal;
  game.legalActions(seat, legal);
  for(const Action& a : legal)
    if(actionIndex(game, a) == index)
      return a;
  throw StringError("Action " + Global::intToString(index) + " is not legal for S" + Global::intToString(seat));
}
