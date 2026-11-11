#include "../command/commands.h"

#include "../core/timer.h"
#include "../learn/branch.h"
#include "../learn/datafile.h"
#include "../learn/features.h"
#include "../learn/netmodel.h"
#include "../learn/sampling.h"

#include <tclap/CmdLine.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <chrono>
#include <iostream>
#include <mutex>
#include <thread>

using namespace std;

// Stage 1 of docs/plan.md 3.4: hands between the newest network (and, in some hands, earlier networks or scripted
// players), written as records with the decisions marked for training.
//
// Each thread plays many hands at once and evaluates all their pending decisions in one batch per network. A hand's
// main line is played first; then some of the learner's decisions are marked (half evenly, half in proportion to
// what the policy gives up there by the network's own action values), and at one or two of them another action is
// played and the hand finished again by the same players (a fork), with the same random numbers for the players'
// choices. With --greedy-labels the marks get stage 0's labels instead: every legal action finished by greedy
// players.

namespace {
  uint64_t mix(uint64_t x) {
    x += 0x9E3779B97F4A7C15ULL;
    x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ULL;
    x = (x ^ (x >> 27)) * 0x94D049BB133111EBULL;
    return x ^ (x >> 31);
  }
  // The random number of a seat's n-th decision of a hand: the same in the main line and in its forks.
  double uniformOf(uint64_t key, int seat, int n) {
    return (double)(mix(key ^ mix((uint64_t)seat * 4096 + (uint64_t)n)) >> 11) * (1.0 / 9007199254740992.0);
  }

  struct Opponent {
    string spec;
    shared_ptr<const Learn::NetModel> model;  // null: a scripted player
    int batch = -1;                           // the index of its network among the thread's batches
  };

  // The decisions of one network waiting in a thread.
  struct ModelBatch {
    const Learn::NetModel* model = nullptr;
    int n = 0;
    vector<float> kind, seat, global;
    vector<uint8_t> legal;
    vector<Learn::NetOutput> out;
    void reserve(int rows) {
      kind.resize((size_t)rows * Features::NUM_KINDS * Features::KIND_OBS);
      seat.resize((size_t)rows * Features::NUM_SEATS * Features::SEAT_OBS);
      global.resize((size_t)rows * Features::globalObsSize());
      legal.resize((size_t)rows * Learn::NUM_ACTIONS);
      out.resize(rows);
    }
  };

  // A decision of the main line by a learner seat with a choice.
  struct Decision {
    int index = 0;      // in the record's actions
    float weight = 0;   // max Q - sum pi Q: what the policy gives up by the network's own action values
    int first = 0;      // its legal actions and their probabilities, in Slot::actions and Slot::probs
    int count = 0;
    int played = 0;
  };

  struct Fork {
    int mark;
    int action;
  };

  // One hand being played.
  struct Slot {
    bool active = false;
    Learn::HandRecord rec;
    unique_ptr<Game> game;
    Rand rand;
    uint64_t key = 0;
    Player* script[4] = {nullptr, nullptr, nullptr, nullptr};
    int batch[4] = {-1, -1, -1, -1};  // the network of each seat; -1: scripted
    int seatDecisions[4] = {0, 0, 0, 0};
    vector<Decision> decisions;
    vector<uint8_t> actions;
    vector<float> probs;
    vector<Fork> forks;  // still to play; the last one is being played when inFork
    bool inFork = false;
    // The decisions being made now.
    vector<int> pending;
    int chosen[4] = {0, 0, 0, 0};
    int row[4] = {-1, -1, -1, -1};
    bool waiting = false;
  };

  struct Totals {
    int64_t hands = 0, decisions = 0, marks = 0, forks = 0, evals = 0, batches = 0, labelled = 0;
    // By the type of the player (Features::PlayerType): seats played, their results, wins, deal-ins.
    int64_t seats[3] = {0, 0, 0}, wins[3] = {0, 0, 0}, dealIns[3] = {0, 0, 0};
    double points[3] = {0, 0, 0};
    void add(const Totals& o) {
      hands += o.hands;
      decisions += o.decisions;
      marks += o.marks;
      forks += o.forks;
      evals += o.evals;
      batches += o.batches;
      labelled += o.labelled;
      for(int i = 0; i < 3; i++) {
        seats[i] += o.seats[i];
        wins[i] += o.wins[i];
        dealIns[i] += o.dealIns[i];
        points[i] += o.points[i];
      }
    }
  };
}

int Commands::selfplay(vector<string> args) {
  string modelPath, rulesSpec, seed, outDir, play, progressPath;
  vector<string> opponentSpecs;
  int64_t numHands;
  int numThreads, numSlots, handsPerFile, marksPerHand;
  double opponentRate, forksPerHand, temperature;
  bool greedyLabels;
  try {
    TCLAP::CmdLine cmd(
      "Plays hands with the network and writes them as training records, with marked decisions and forks "
      "(docs/plan.md 3.4, stage 1).", ' ', "0");
    TCLAP::ValueArg<string> modelArg("m", "model", "The network that learns (.onnx)", true, "", "FILE");
    TCLAP::MultiArg<string> opponentArg("", "opponent", "Another player for some seats: net:model=FILE or a scripted "
                                        "player (greedy, ...). May be given several times.", false, "SPEC");
    TCLAP::ValueArg<double> opponentRateArg("", "opponent-rate", "Share of hands with one to three seats played by "
                                            "the opponents (default 0.2)", false, 0.2, "P");
    TCLAP::ValueArg<int64_t> handsArg("n", "hands", "Hands to play (default 10000)", false, 10000, "N");
    TCLAP::ValueArg<int> threadsArg("t", "threads", "Threads (default 1)", false, 1, "N");
    TCLAP::ValueArg<int> slotsArg("", "slots", "Hands in play per thread, evaluated together (default 64)", false, 64,
                                  "N");
    TCLAP::ValueArg<string> rulesArg("", "rules", "Rules spec, or mix for the training mix (default)", false, "mix",
                                     "SPEC");
    TCLAP::ValueArg<string> seedArg("s", "seed", "Seed (default: random)", false, "", "SEED");
    TCLAP::ValueArg<string> outArg("o", "out", "Output directory", true, "", "DIR");
    TCLAP::ValueArg<int> perFileArg("", "hands-per-file", "Hands per file (default 10000)", false, 10000, "N");
    TCLAP::ValueArg<string> progressArg("", "progress", "A file rewritten every few seconds with: the seed, the hands "
                                        "finished, the hands to play, the seconds so far", false, "", "FILE");
    TCLAP::ValueArg<int> marksArg("", "marks", "Decisions marked for training per hand (default 8)", false, 8, "N");
    TCLAP::ValueArg<double> forksArg("", "forks", "Forks per hand, on average (default 1.5)", false, 1.5, "X");
    TCLAP::ValueArg<double> tempArg("", "temp", "Temperature of the policy the networks play by (default 1)", false,
                                    1.0, "T");
    TCLAP::ValueArg<string> playArg("", "play", "How the networks choose: sample (from the policy, default) or q "
                                    "(the best composed result)", false, "sample", "MODE");
    TCLAP::SwitchArg greedyArg("", "greedy-labels", "No forks: label every legal action of the marked decisions by "
                               "greedy playouts (stage 0's labels on the network's positions)", false);
    cmd.add(modelArg);
    cmd.add(opponentArg);
    cmd.add(opponentRateArg);
    cmd.add(handsArg);
    cmd.add(threadsArg);
    cmd.add(slotsArg);
    cmd.add(rulesArg);
    cmd.add(seedArg);
    cmd.add(outArg);
    cmd.add(perFileArg);
    cmd.add(progressArg);
    cmd.add(marksArg);
    cmd.add(forksArg);
    cmd.add(tempArg);
    cmd.add(playArg);
    cmd.add(greedyArg);
    cmd.parse(args);
    modelPath = modelArg.getValue();
    opponentSpecs = opponentArg.getValue();
    opponentRate = opponentRateArg.getValue();
    numHands = handsArg.getValue();
    numThreads = max(1, threadsArg.getValue());
    numSlots = max(1, slotsArg.getValue());
    rulesSpec = rulesArg.getValue();
    seed = seedArg.getValue();
    outDir = outArg.getValue();
    handsPerFile = max(1, perFileArg.getValue());
    progressPath = progressArg.getValue();
    marksPerHand = max(0, marksArg.getValue());
    forksPerHand = max(0.0, forksArg.getValue());
    temperature = tempArg.getValue();
    play = playArg.getValue();
    greedyLabels = greedyArg.getValue();
  }
  catch(TCLAP::ArgException& e) {
    cerr << "Error: " << e.error() << " for argument " << e.argId() << endl;
    return 1;
  }
  if(play != "sample" && play != "q")
    throw StringError("--play is sample or q");
  if(temperature <= 0.0)
    throw StringError("--temp must be positive");
  const bool playQ = play == "q";
  if(seed.empty())
    seed = Global::uint64ToHexString(Rand().nextUInt64());
  {
    Rand check("check");
    Learn::sampleRules(rulesSpec, check);  // validate
  }

  // The networks: the learner first, then the opponents' (each file once).
  vector<shared_ptr<const Learn::NetModel>> models;
  models.push_back(Learn::NetModel::load(modelPath));
  vector<Opponent> opponents;
  for(const string& spec : opponentSpecs) {
    Opponent o;
    o.spec = spec;
    if(spec.rfind("net:model=", 0) == 0) {
      o.model = Learn::NetModel::load(spec.substr(10));
      for(size_t i = 0; i < models.size(); i++)
        if(models[i] == o.model)
          o.batch = (int)i;
      if(o.batch < 0) {
        o.batch = (int)models.size();
        models.push_back(o.model);
      }
    }
    else if(!makeBasicPlayer(spec))
      throw StringError("An opponent is net:model=FILE or a scripted player: " + spec);
    opponents.push_back(o);
  }
  std::filesystem::create_directories(outDir);

  atomic<int64_t> nextHand(0);
  atomic<int64_t> handsDone(0);
  Totals totals;
  mutex totalsMutex;
  ClockTimer timer;

  auto worker = [&](int threadIdx) {
    Totals local;
    vector<ModelBatch> batches(models.size());
    for(size_t i = 0; i < models.size(); i++) {
      batches[i].model = models[i].get();
      batches[i].reserve(numSlots * 3);
    }
    vector<unique_ptr<Player>> scripts;
    for(const Opponent& o : opponents)
      scripts.push_back(o.model ? nullptr : makeBasicPlayer(o.spec));
    unique_ptr<Player> greedy = makeBasicPlayer("greedy");
    Player* reference[4] = {greedy.get(), greedy.get(), greedy.get(), greedy.get()};
    Learn::DataFile file;
    int fileIdx = 0;
    auto flush = [&]() {
      if(file.numHands() == 0)
        return;
      file.write(outDir + "/" + Global::strprintf("selfplay_%s_t%03d_%04d.npz", seed.c_str(), threadIdx, fileIdx++));
    };
    vector<Slot> slots(numSlots);
    vector<Action> legal;

    auto startHand = [&](Slot& s) {
      const int64_t h = nextHand.fetch_add(1);
      if(h >= numHands) {
        s.active = false;
        return;
      }
      s.active = true;
      s.rand.init(seed + ":" + Global::int64ToString(h));
      s.rec = Learn::HandRecord();
      s.rec.rules = Learn::sampleRules(rulesSpec, s.rand);
      s.rec.setup = Learn::sampleSetup(s.rand);
      s.rec.wallFromSeed = true;
      s.rec.wallSeed = s.rand.nextUInt64();
      s.rec.wall = Learn::HandRecord::wallOf(s.rec.rules, s.rec.wallSeed);
      s.key = s.rand.nextUInt64();
      for(int seat = 0; seat < 4; seat++) {
        s.script[seat] = nullptr;
        s.batch[seat] = 0;
        s.rec.playerTypes[seat] = Features::PLAYER_LEARNER;
        s.seatDecisions[seat] = 0;
      }
      if(!opponents.empty() && s.rand.nextDouble() < opponentRate) {
        // One to three seats, drawn evenly, are played by opponents.
        int order[4] = {0, 1, 2, 3};
        for(int i = 3; i > 0; i--)
          swap(order[i], order[s.rand.nextUInt((uint32_t)(i + 1))]);
        const int count = 1 + (int)s.rand.nextUInt(3);
        for(int i = 0; i < count; i++) {
          const size_t o = s.rand.nextUInt((uint32_t)opponents.size());
          const int seat = order[i];
          if(opponents[o].model) {
            s.batch[seat] = opponents[o].batch;
            s.rec.playerTypes[seat] = Features::PLAYER_NET;
          }
          else {
            s.batch[seat] = -1;
            s.script[seat] = scripts[o].get();
            s.rec.playerTypes[seat] = Features::PLAYER_SCRIPT;
          }
        }
      }
      s.decisions.clear();
      s.actions.clear();
      s.probs.clear();
      s.forks.clear();
      s.inFork = false;
      s.waiting = false;
      s.game.reset(new Game(s.rec.rules));
      s.game->start(s.rec.setup, s.rec.wall);
    };

    // The main line is over: mark decisions and choose the forks.
    auto finishMain = [&](Slot& s) {
      const HandResult& res = s.game->result();
      local.hands++;
      local.decisions += (int64_t)s.rec.actions.size();
      for(int seat = 0; seat < 4; seat++) {
        const int type = s.rec.playerTypes[seat];
        local.seats[type]++;
        local.points[type] += res.points(seat, s.rec.rules.base);
        if(res.loser == seat && !res.flowerWin)
          local.dealIns[type]++;
      }
      for(int w : res.winners)
        local.wins[s.rec.playerTypes[w]]++;

      const int n = (int)s.decisions.size();
      const int k = min(marksPerHand, n);
      vector<int> order(n);
      for(int i = 0; i < n; i++)
        order[i] = i;
      // Half of the marks evenly, the others in proportion to the weights, all different.
      const int even = k / 2;
      for(int i = 0; i < k; i++) {
        int j = i + (int)s.rand.nextUInt((uint32_t)(n - i));
        if(i >= even) {
          double total = 0.0;
          for(int t = i; t < n; t++)
            total += max(0.0f, s.decisions[order[t]].weight);
          if(total > 0.0) {
            double u = s.rand.nextDouble() * total;
            for(j = i; j < n - 1; j++) {
              u -= max(0.0f, s.decisions[order[j]].weight);
              if(u < 0.0)
                break;
            }
          }
        }
        swap(order[i], order[j]);
      }
      order.resize(k);
      sort(order.begin(), order.end());
      for(int i : order) {
        const Decision& d = s.decisions[i];
        Learn::Mark mark;
        mark.decision = (uint16_t)d.index;
        for(int a = 0; a < d.count; a++) {
          Learn::MarkEntry e;
          e.action = s.actions[d.first + a];
          e.hasProb = true;
          e.prob = s.probs[d.first + a];
          mark.entries.push_back(e);
        }
        s.rec.marks.push_back(mark);
      }
      local.marks += k;
      if(greedyLabels || k == 0)
        return;

      // Forks: at some marks another action, drawn from the policy (70%), from the policy at temperature 2 (25%)
      // or evenly (5%).
      int numForks = (int)forksPerHand + (s.rand.nextDouble() < forksPerHand - floor(forksPerHand) ? 1 : 0);
      numForks = min(numForks, k);
      vector<int> at(k);
      for(int i = 0; i < k; i++)
        at[i] = i;
      for(int i = 0; i < numForks; i++) {
        swap(at[i], at[i + (int)s.rand.nextUInt((uint32_t)(k - i))]);
        const Decision& d = s.decisions[order[at[i]]];
        const double r = s.rand.nextDouble();
        vector<double> w(d.count);
        double total = 0.0;
        for(int a = 0; a < d.count; a++) {
          const double p = max((double)s.probs[d.first + a], 1e-6);
          w[a] = s.actions[d.first + a] == d.played ? 0.0 : (r < 0.70 ? p : (r < 0.95 ? sqrt(p) : 1.0));
          total += w[a];
        }
        double u = s.rand.nextDouble() * total;
        int pick = -1;
        for(int a = 0; a < d.count; a++) {
          if(w[a] <= 0.0)
            continue;
          pick = a;
          u -= w[a];
          if(u < 0.0)
            break;
        }
        if(pick >= 0)
          s.forks.push_back({at[i], (int)s.actions[d.first + pick]});
      }
    };

    // The hand of the slot is over (its main line or a fork): go on to the next fork, or write it and deal anew.
    auto lineOver = [&](Slot& s) {
      if(!s.inFork) {
        finishMain(s);
      }
      else {
        const Fork f = s.forks.back();
        s.forks.pop_back();
        for(Learn::MarkEntry& e : s.rec.marks[f.mark].entries) {
          if(e.action != f.action)
            continue;
          e.hasOutcome = true;
          e.outcome = Learn::Outcome::of(s.game->result());
        }
        local.forks++;
      }
      if(!s.forks.empty()) {
        const Fork& f = s.forks.back();
        s.inFork = true;
        s.game.reset(new Game(Learn::branch(s.rec, s.rec.marks[f.mark].decision, f.action, s.seatDecisions)));
        return;
      }
      if(greedyLabels)
        local.labelled += Learn::labelAllActions(s.rec, reference, s.rand);
      file.add(s.rec);
      handsDone.fetch_add(1);
      if(file.numHands() >= handsPerFile)
        flush();
      startHand(s);
    };

    // Applies the decisions chosen for the seats now pending.
    auto apply = [&](Slot& s) {
      Game& g = *s.game;
      for(size_t j = 0; j < s.pending.size(); j++) {
        const int seat = s.pending[j];
        const Action a = Learn::actionOf(g, seat, s.chosen[j]);
        if(!s.inFork)
          s.rec.actions.push_back((uint8_t)s.chosen[j]);
        s.seatDecisions[seat]++;
        g.act(seat, a);
      }
      s.waiting = false;
    };

    // Plays the slot on until it needs a network (or its thread has no hands left).
    auto advance = [&](Slot& s) {
      while(s.active) {
        Game& g = *s.game;
        if(g.phase() == Game::OVER) {
          lineOver(s);
          continue;
        }
        s.pending = g.pending();
        if(s.pending.empty())
          throw StringError("A hand that is not over has nobody to decide");
        bool needNet = false;
        for(size_t j = 0; j < s.pending.size(); j++) {
          const int seat = s.pending[j];
          g.legalActions(seat, legal);
          s.row[j] = -1;
          if(legal.size() == 1) {
            s.chosen[j] = Learn::actionIndex(g, legal[0]);
            continue;
          }
          if(s.batch[seat] < 0) {
            s.chosen[j] = Learn::actionIndex(g, s.script[seat]->decide(g, seat, legal, s.rand));
            continue;
          }
          ModelBatch& b = batches[s.batch[seat]];
          const int r = b.n++;
          Features::observation(g, seat, b.kind.data() + (size_t)r * Features::NUM_KINDS * Features::KIND_OBS,
                                b.seat.data() + (size_t)r * Features::NUM_SEATS * Features::SEAT_OBS,
                                b.global.data() + (size_t)r * Features::globalObsSize(),
                                b.legal.data() + (size_t)r * Learn::NUM_ACTIONS);
          s.row[j] = r;
          needNet = true;
        }
        if(needNet) {
          s.waiting = true;
          return;
        }
        apply(s);
      }
    };

    // The networks have answered: choose for the slot's seats that asked.
    auto choose = [&](Slot& s) {
      const Game& g = *s.game;
      for(size_t j = 0; j < s.pending.size(); j++) {
        if(s.row[j] < 0)
          continue;
        const int seat = s.pending[j];
        const ModelBatch& b = batches[s.batch[seat]];
        const Learn::NetOutput& out = b.out[s.row[j]];
        const uint8_t* mask = b.legal.data() + (size_t)s.row[j] * Learn::NUM_ACTIONS;
        // The policy over the legal actions.
        double p[Learn::NUM_ACTIONS];
        float top = -1e30f;
        for(int a = 0; a < Learn::NUM_ACTIONS; a++)
          if(mask[a])
            top = max(top, out.policy[a]);
        double sum = 0.0;
        for(int a = 0; a < Learn::NUM_ACTIONS; a++) {
          p[a] = mask[a] ? exp((double)(out.policy[a] - top) / temperature) : 0.0;
          sum += p[a];
        }
        for(int a = 0; a < Learn::NUM_ACTIONS; a++)
          p[a] /= sum;
        const bool learner = s.rec.playerTypes[seat] == Features::PLAYER_LEARNER;
        const bool record = learner && !s.inFork;
        float q[Learn::NUM_ACTIONS];
        if(playQ || record) {
          float coefP[Learn::NUM_EVENTS], coefT[Learn::NUM_EVENTS];
          Learn::eventCoefficients(g.rules(), g.setup(), seat, coefP, coefT);
          out.compose(coefP, coefT, q);
        }
        int pick = -1;
        if(playQ) {
          for(int a = 0; a < Learn::NUM_ACTIONS; a++)
            if(mask[a] && (pick < 0 || q[a] > q[pick]))
              pick = a;
        }
        else {
          double u = uniformOf(s.key, seat, s.seatDecisions[seat]);
          for(int a = 0; a < Learn::NUM_ACTIONS; a++) {
            if(!mask[a])
              continue;
            pick = a;
            u -= p[a];
            if(u < 0.0)
              break;
          }
        }
        s.chosen[j] = pick;
        if(record) {
          Decision d;
          d.index = (int)s.rec.actions.size() + (int)j;
          d.first = (int)s.actions.size();
          d.played = pick;
          double best = -1e30, mean = 0.0;
          for(int a = 0; a < Learn::NUM_ACTIONS; a++) {
            if(!mask[a])
              continue;
            s.actions.push_back((uint8_t)a);
            s.probs.push_back((float)p[a]);
            best = max(best, (double)q[a]);
            mean += p[a] * q[a];
          }
          d.count = (int)s.actions.size() - d.first;
          d.weight = (float)(best - mean);
          s.decisions.push_back(d);
        }
      }
    };

    for(Slot& s : slots)
      startHand(s);
    while(true) {
      for(ModelBatch& b : batches)
        b.n = 0;
      bool any = false;
      for(Slot& s : slots) {
        if(!s.active)
          continue;
        advance(s);
        any = any || s.waiting;
      }
      if(!any)
        break;
      for(ModelBatch& b : batches) {
        if(b.n == 0)
          continue;
        b.model->evaluate(b.n, b.kind.data(), b.seat.data(), b.global.data(), b.out.data());
        local.evals += b.n;
        local.batches++;
      }
      for(Slot& s : slots) {
        if(!s.active || !s.waiting)
          continue;
        choose(s);
        apply(s);
      }
    }
    flush();
    lock_guard<mutex> lock(totalsMutex);
    totals.add(local);
  };

  // For whoever watches the run: the progress file is replaced whole (written aside, then renamed).
  atomic<bool> finished(false);
  auto writeProgress = [&]() {
    const string tmp = progressPath + ".tmp";
    {
      ofstream out(tmp);
      out << seed << " " << handsDone.load() << " " << numHands << " " << (int64_t)timer.getSeconds() << endl;
    }
    std::error_code ec;
    std::filesystem::rename(tmp, progressPath, ec);
  };
  thread progress;
  if(!progressPath.empty())
    progress = thread([&]() {
      int ticks = 0;
      while(!finished.load()) {
        if(ticks++ % 10 == 0)
          writeProgress();
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
      }
      writeProgress();
    });

  vector<thread> threads;
  for(int t = 0; t < numThreads; t++)
    threads.emplace_back(worker, t);
  for(thread& t : threads)
    t.join();
  finished.store(true);
  if(progress.joinable())
    progress.join();

  const double secs = max(timer.getSeconds(), 1e-9);
  const double h = (double)max<int64_t>(1, totals.hands);
  cout << "Seed " << seed << ", rules " << rulesSpec << ": " << totals.hands << " hands in " << secs << " s ("
       << (int)(totals.hands / secs) << " hands/s), " << totals.decisions / h << " decisions, " << totals.marks / h
       << " marks and " << totals.forks / h << " forks a hand";
  if(greedyLabels)
    cout << ", " << totals.labelled / h << " actions labelled";
  cout << "; " << totals.evals << " evaluations (" << (int)(totals.evals / secs) << "/s) in batches of "
       << (double)totals.evals / max<int64_t>(1, totals.batches) << endl;
  static const char* const TYPE_NAMES[3] = {"learner", "other networks", "scripted"};
  for(int i = 0; i < 3; i++) {
    if(totals.seats[i] == 0)
      continue;
    const double n = (double)totals.seats[i];
    cout << Global::strprintf("  %-14s %9lld seats: result %+.3f, win %.4f, deal-in %.4f", TYPE_NAMES[i],
                              (long long)totals.seats[i], totals.points[i] / n, totals.wins[i] / n,
                              totals.dealIns[i] / n)
         << endl;
  }
  return 0;
}
