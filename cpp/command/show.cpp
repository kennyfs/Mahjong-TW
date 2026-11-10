#include "../command/commands.h"

#include "../command/play.h"

#include <tclap/CmdLine.h>

#include <iostream>

using namespace std;

static string handString(const SeatState& st) {
  string s = Tiles::toString(st.hand);
  for(const Meld& m : st.melds)
    s += " [" + m.toString() + "]";
  if(st.flowers) {
    vector<int> fl;
    for(int f = 0; f < 8; f++)
      if(st.flowers & (1 << f))
        fl.push_back(Tiles::FLOWER_BASE + f);
    s += " {" + Tiles::toString(fl) + "}";
  }
  return s;
}

int Commands::show(vector<string> args) {
  vector<string> specs;
  string rulesSpec, seed;
  bool verbose;
  try {
    TCLAP::CmdLine cmd("Plays one hand and prints it.", ' ', "0");
    TCLAP::MultiArg<string> playerArg("p", "player", "Player spec for seats 0-3 (default greedy)", false, "SPEC");
    TCLAP::ValueArg<string> rulesArg("", "rules", "Rules spec (default simple1,none)", false, "simple1,none", "SPEC");
    TCLAP::ValueArg<string> seedArg("s", "seed", "Seed (default: random)", false, "", "SEED");
    TCLAP::SwitchArg verboseArg("v", "verbose", "Print the deciding player's hand before each decision", false);
    cmd.add(playerArg);
    cmd.add(rulesArg);
    cmd.add(seedArg);
    cmd.add(verboseArg);
    cmd.parse(args);
    specs = playerArg.getValue();
    rulesSpec = rulesArg.getValue();
    seed = seedArg.getValue();
    verbose = verboseArg.getValue();
  }
  catch(TCLAP::ArgException& e) {
    cerr << "Error: " << e.error() << " for argument " << e.argId() << endl;
    return 1;
  }
  while(specs.size() < 4)
    specs.push_back("greedy");
  if(seed.empty())
    seed = Global::uint64ToHexString(Rand().nextUInt64());
  Rules rules = Rules::parse(rulesSpec);
  unique_ptr<Player> owned[4];
  Player* players[4];
  for(int s = 0; s < 4; s++) {
    owned[s] = makePlayer(specs[s]);
    players[s] = owned[s].get();
  }
  Rand rand(seed);
  Game game(rules);
  HandSetup setup = Play::randomSetup(rand);
  game.start(setup, rand);
  static const char* const WINDS[4] = {"E", "S", "W", "N"};
  cout << "Seed " << seed << ", rules " << rulesSpec << ", round wind " << WINDS[setup.roundWind] << ", dealer S"
       << setup.dealer << ", streak " << setup.streak << endl;
  for(int s = 0; s < 4; s++)
    cout << "S" << s << " (" << WINDS[game.seatWind(s)] << ", " << specs[s] << "): " << handString(game.seat(s))
         << endl;
  size_t printed = game.events().size();
  auto printNew = [&](const Game& g) {
    for(; printed < g.events().size(); printed++)
      cout << "  " << g.events()[printed].toString() << endl;
  };
  Play::playOut(game, players, rand, [&](const Game& g, int s, const Action& a) {
    printNew(g);
    if(verbose)
      cout << "    S" << s << " " << handString(g.seat(s)) << " -> " << a.toString() << endl;
  });
  printNew(game);
  cout << "Final hands:" << endl;
  for(int s = 0; s < 4; s++)
    cout << "S" << s << ": " << handString(game.seat(s)) << endl;
  cout << game.result().toString(rules.base) << endl;
  return 0;
}
