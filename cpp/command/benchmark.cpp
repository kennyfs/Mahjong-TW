#include "../command/commands.h"

#include "../command/play.h"
#include "../core/timer.h"
#include "../game/shape.h"

#include <tclap/CmdLine.h>

#include <iostream>

using namespace std;

int Commands::benchmark(vector<string> args) {
  int numHands;
  string rulesSpec;
  try {
    TCLAP::CmdLine cmd("Times the building blocks on one thread.", ' ', "0");
    TCLAP::ValueArg<int> handsArg("n", "hands", "Hands per player type (default 2000)", false, 2000, "N");
    TCLAP::ValueArg<string> rulesArg("", "rules", "Rules spec (default simple1,none)", false, "simple1,none", "SPEC");
    cmd.add(handsArg);
    cmd.add(rulesArg);
    cmd.parse(args);
    numHands = handsArg.getValue();
    rulesSpec = rulesArg.getValue();
  }
  catch(TCLAP::ArgException& e) {
    cerr << "Error: " << e.error() << " for argument " << e.argId() << endl;
    return 1;
  }
  Rules rules = Rules::parse(rulesSpec);
  Rand rand("benchmark");

  // Shanten of random 16-tile hands.
  {
    vector<Counts> hands;
    for(int i = 0; i < 20000; i++) {
      vector<uint8_t> wall = Game::shuffledWall(Rules::parse("simple1,none"), rand);
      Counts c{};
      for(int j = 0; j < 16; j++)
        c[wall[j]]++;
      hands.push_back(c);
    }
    ClockTimer timer;
    int64_t sum = 0;
    for(int rep = 0; rep < 5; rep++)
      for(const Counts& c : hands)
        sum += Shape::shanten(c, 5);
    double secs = timer.getSeconds();
    cout << "shanten (16 tiles): " << secs / (5.0 * hands.size()) * 1e6 << " us (checksum " << sum << ")" << endl;
  }

  for(const char* spec : {"random", "greedy"}) {
    unique_ptr<Player> owned[4];
    Player* players[4];
    for(int s = 0; s < 4; s++) {
      owned[s] = makePlayer(spec);
      players[s] = owned[s].get();
    }
    Game game(rules);
    ClockTimer timer;
    int64_t decisions = 0;
    for(int h = 0; h < numHands; h++) {
      game.start(Play::randomSetup(rand), rand);
      Play::playOut(game, players, rand, [&](const Game&, int, const Action&) { decisions++; });
    }
    double secs = timer.getSeconds();
    cout << "hands with 4 " << spec << ": " << numHands / secs << " hands/s, " << secs / numHands * 1e3
         << " ms/hand, " << (double)decisions / numHands << " decisions/hand" << endl;
  }
  return 0;
}
