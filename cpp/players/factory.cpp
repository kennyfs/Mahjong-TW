#include "../players/players.h"

#include "../core/global.h"

using namespace std;

// cpp/players/netplayer.cpp
unique_ptr<Player> makeNetPlayer(const string& spec);
// cpp/players/mcplayer.cpp
unique_ptr<Player> makeMcPlayer(const string& spec);

unique_ptr<Player> makePlayer(const string& spec) {
  if(spec.rfind("net:", 0) == 0)
    return makeNetPlayer(spec);
  if(spec == "mc" || spec.rfind("mc:", 0) == 0)
    return makeMcPlayer(spec);
  unique_ptr<Player> basic = makeBasicPlayer(spec);
  if(!basic)
    throw StringError("Unknown player: " + spec);
  return basic;
}
