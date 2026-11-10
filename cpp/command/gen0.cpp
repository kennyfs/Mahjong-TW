#include "../command/commands.h"

#include "../command/play.h"
#include "../core/timer.h"
#include "../learn/datafile.h"
#include "../learn/features.h"
#include "../learn/sampling.h"

#include <tclap/CmdLine.h>

#include <atomic>
#include <filesystem>
#include <iostream>
#include <mutex>
#include <thread>

using namespace std;

// Stage 0 of docs/plan.md 3.4: hands between greedy players (with variations), branched at a few decisions. There
// every legal action is tried and the hand finished by plain greedy players on the same wall, so the record holds
// the exact outcome of each action under greedy play.

namespace {
  // The players of the main line, drawn per seat.
  const char* const STYLES[] = {"greedy", "greedy:eps=0.08", "greedy:eps=0.25", "greedy:calls=none",
                                "greedy:calls=more"};
  const double STYLE_WEIGHTS[] = {0.55, 0.15, 0.10, 0.10, 0.10};
  constexpr int NUM_STYLES = 5;

  struct Totals {
    int64_t hands = 0, decisions = 0, marks = 0, labelled = 0;
  };
}

int Commands::gen0(vector<string> args) {
  int64_t numHands;
  int numThreads, handsPerFile;
  string rulesSpec, seed, outDir;
  double discardRate, reactRate, rareRate;
  try {
    TCLAP::CmdLine cmd(
      "Writes stage 0 training data: hands between greedy players with the outcome of every legal action at a few "
      "decisions of each (docs/plan.md 3.4).", ' ', "0");
    TCLAP::ValueArg<int64_t> handsArg("n", "hands", "Hands to play (default 10000)", false, 10000, "N");
    TCLAP::ValueArg<int> threadsArg("t", "threads", "Threads (default 1)", false, 1, "N");
    TCLAP::ValueArg<string> rulesArg("", "rules", "Rules spec, or mix for the training mix (default)", false, "mix",
                                     "SPEC");
    TCLAP::ValueArg<string> seedArg("s", "seed", "Seed (default: random)", false, "", "SEED");
    TCLAP::ValueArg<string> outArg("o", "out", "Output directory", true, "", "DIR");
    TCLAP::ValueArg<int> perFileArg("", "hands-per-file", "Hands per file (default 20000)", false, 20000, "N");
    TCLAP::ValueArg<double> discardArg("", "discard-rate", "Share of own-turn decisions labelled (default 0.08)",
                                       false, 0.08, "P");
    TCLAP::ValueArg<double> reactArg("", "react-rate", "Share of answers to a discard labelled (default 0.12)", false,
                                     0.12, "P");
    TCLAP::ValueArg<double> rareArg("", "rare-rate", "Share of decisions with a win or a kong on offer labelled "
                                    "(default 0.5)", false, 0.5, "P");
    cmd.add(handsArg);
    cmd.add(threadsArg);
    cmd.add(rulesArg);
    cmd.add(seedArg);
    cmd.add(outArg);
    cmd.add(perFileArg);
    cmd.add(discardArg);
    cmd.add(reactArg);
    cmd.add(rareArg);
    cmd.parse(args);
    numHands = handsArg.getValue();
    numThreads = max(1, threadsArg.getValue());
    rulesSpec = rulesArg.getValue();
    seed = seedArg.getValue();
    outDir = outArg.getValue();
    handsPerFile = max(1, perFileArg.getValue());
    discardRate = discardArg.getValue();
    reactRate = reactArg.getValue();
    rareRate = rareArg.getValue();
  }
  catch(TCLAP::ArgException& e) {
    cerr << "Error: " << e.error() << " for argument " << e.argId() << endl;
    return 1;
  }
  if(seed.empty())
    seed = Global::uint64ToHexString(Rand().nextUInt64());
  {
    Rand check("check");
    Learn::sampleRules(rulesSpec, check);  // validate
  }
  std::filesystem::create_directories(outDir);

  atomic<int64_t> nextHand(0);
  Totals totals;
  mutex totalsMutex;
  ClockTimer timer;

  auto worker = [&](int threadIdx) {
    unique_ptr<Player> styles[NUM_STYLES];
    for(int i = 0; i < NUM_STYLES; i++)
      styles[i] = makePlayer(STYLES[i]);
    Player* reference[4] = {styles[0].get(), styles[0].get(), styles[0].get(), styles[0].get()};
    Learn::DataFile file;
    Totals local;
    int fileIdx = 0;
    auto flush = [&]() {
      if(file.numHands() == 0)
        return;
      file.write(outDir + "/" + Global::strprintf("gen0_%s_t%03d_%04d.npz", seed.c_str(), threadIdx, fileIdx++));
    };
    vector<Action> legal;
    while(true) {
      int64_t h = nextHand.fetch_add(1);
      if(h >= numHands)
        break;
      Rand rand(seed + ":" + Global::int64ToString(h));
      Learn::HandRecord rec;
      rec.rules = Learn::sampleRules(rulesSpec, rand);
      rec.setup = Learn::sampleSetup(rand);
      rec.wallFromSeed = true;
      rec.wallSeed = rand.nextUInt64();
      rec.wall = Learn::HandRecord::wallOf(rec.rules, rec.wallSeed);
      Player* players[4];
      for(int s = 0; s < 4; s++) {
        players[s] = styles[rand.nextUInt(STYLE_WEIGHTS, NUM_STYLES)].get();
        rec.playerTypes[s] = Features::PLAYER_SCRIPT;
      }

      // The main line; each decision is marked for labelling at the rate of its kind.
      Game game(rec.rules);
      game.start(rec.setup, rec.wall);
      vector<bool> marked;
      Play::playOut(game, players, rand, [&](const Game& g, int s, const Action& a) {
        g.legalActions(s, legal);
        bool rare = false;
        for(const Action& x : legal)
          rare = rare || x.type == Action::TSUMO || x.type == Action::RON || x.type == Action::ANKAN ||
                 x.type == Action::KAKAN || x.type == Action::MINKAN;
        double rate = rare ? rareRate : (g.phase() == Game::SELF ? discardRate : reactRate);
        marked.push_back(legal.size() >= 2 && rand.nextDouble() < rate);
        rec.actions.push_back((uint8_t)Learn::actionIndex(g, a));
      });
      local.hands++;
      local.decisions += (int64_t)rec.actions.size();

      // Replay it, and at each marked decision finish the hand once per legal action.
      Learn::Replay replay(rec);
      while(!replay.done()) {
        const int i = replay.index();
        if(marked[i]) {
          const Game& base = replay.game();
          const int s = replay.seat();
          base.legalActions(s, legal);
          // Decisions on a discard are simultaneous: the seats after this one keep the action they chose.
          const int others = base.phase() == Game::SELF ? 0 : (int)base.pending().size() - 1;
          Learn::Mark mark;
          mark.decision = (uint16_t)i;
          for(const Action& a : legal) {
            Game g = base;
            g.act(s, a);
            for(int j = 1; j <= others; j++) {
              int s2 = g.pending()[0];
              g.act(s2, Learn::actionOf(g, s2, rec.actions[i + j]));
            }
            Play::playOut(g, reference, rand);
            Learn::MarkEntry e;
            e.action = (uint8_t)Learn::actionIndex(base, a);
            e.hasOutcome = true;
            e.outcome = Learn::Outcome::of(g.result());
            mark.entries.push_back(e);
          }
          local.marks++;
          local.labelled += (int64_t)mark.entries.size();
          rec.marks.push_back(mark);
        }
        replay.step();
      }
      file.add(rec);
      if(file.numHands() >= handsPerFile)
        flush();
    }
    flush();
    lock_guard<mutex> lock(totalsMutex);
    totals.hands += local.hands;
    totals.decisions += local.decisions;
    totals.marks += local.marks;
    totals.labelled += local.labelled;
  };

  vector<thread> threads;
  for(int t = 0; t < numThreads; t++)
    threads.emplace_back(worker, t);
  for(thread& t : threads)
    t.join();

  double secs = timer.getSeconds();
  cout << "Seed " << seed << ", rules " << rulesSpec << ": " << totals.hands << " hands, " << totals.marks
       << " labelled decisions (" << (double)totals.marks / max<int64_t>(1, totals.hands) << " per hand, "
       << (double)totals.labelled / max<int64_t>(1, totals.marks) << " actions each) in " << secs << " s: "
       << (int)(totals.hands / max(secs, 1e-9)) << " hands/s, " << (int)(totals.marks / max(secs, 1e-9))
       << " decisions/s" << endl;
  return 0;
}
