#include "../command/commands.h"

#include "../command/guiengine.h"
#include "../command/play.h"
#include "../core/global.h"
#include "../game/shape.h"
#include "../learn/netmodel.h"
#include "../learn/outcome.h"
#include "../players/analysis.h"
#include "../players/players.h"

#include <tclap/CmdLine.h>

#include <atomic>
#include <cmath>
#include <fstream>
#include <iostream>
#include <mutex>
#include <thread>

using namespace std;
using json = nlohmann::json;

// What a review of a hand would show (docs/analysis.md): hands are played between four players, and at every
// decision of the reviewed roles that has a choice a network is asked from that seat's view. One JSON line per such
// decision:
//   h, seat, role, t (the decision's number in the hand), phase, wall, turn (the seat's discards so far),
//   legal [action index], q [expected result], pol [policy], played (index into legal), greedy (action index),
//   ready [3][4] and danger [3][34] (the opponents 1, 2, 3 places after the seat),
//   and what the seat could not see: sh [3] (0 ready, 1, 2, 3 or more away), missed [3], waits [3] (bit masks of the
//   kinds the opponent would win on now).
// With --full also: names, ev and tai (the ten events of every action), dtai, waits_tai, coefP, coefT, pos (the
// POSITION of cpp/command/guiengine.h), hands (everybody's), eff (tile efficiency), act.
// And one line per hand: h, end, points [4], roles [4] (the role in each seat), winners, loser, self_draw,
// exhausted, win_tile, dealer, decisions.
// python/review.py reads them.

static double r4(double x) {
  return std::round(x * 1e4) / 1e4;
}

static string handString(const SeatState& st) {
  string s = Tiles::toString(st.hand);
  for(const Meld& m : st.melds)
    s += " [" + m.toString() + "]";
  return s;
}

int Commands::review(vector<string> args) {
  string modelPath, rulesSpec, seed, outPath;
  vector<string> specs;
  vector<int> roles;
  int numHands, numThreads;
  bool full;
  try {
    TCLAP::CmdLine cmd(
      "Plays hands and writes, for every decision of the reviewed roles, what a network says about it from that "
      "seat's view (action values, policy, the opponents' readiness and danger) beside what was hidden from it. "
      "Role r sits at seat (r + hand) % 4, so every role plays every seat.", ' ', "0");
    TCLAP::ValueArg<string> modelArg("m", "model", "The reviewing network (.onnx)", true, "", "FILE");
    TCLAP::MultiArg<string> playerArg("p", "player", "Player spec of roles 0-3 (default: the model, sampling)", false,
                                      "SPEC");
    TCLAP::MultiArg<int> roleArg("r", "role", "A role to review (default: 0)", false, "N");
    TCLAP::ValueArg<string> rulesArg("", "rules", "Rules spec (default simple1,none)", false, "simple1,none", "SPEC");
    TCLAP::ValueArg<int> handsArg("n", "hands", "Number of hands (default 1000)", false, 1000, "N");
    TCLAP::ValueArg<int> threadsArg("t", "threads", "Threads (default 1)", false, 1, "N");
    TCLAP::ValueArg<string> seedArg("s", "seed", "Seed (default: random)", false, "", "SEED");
    TCLAP::ValueArg<string> outArg("o", "out", "Output file (JSON lines)", true, "", "FILE");
    TCLAP::SwitchArg fullArg("", "full", "Add the position, every action's events, the tile efficiency and the "
                             "hidden hands (for examples)", false);
    cmd.add(modelArg);
    cmd.add(playerArg);
    cmd.add(roleArg);
    cmd.add(rulesArg);
    cmd.add(handsArg);
    cmd.add(threadsArg);
    cmd.add(seedArg);
    cmd.add(outArg);
    cmd.add(fullArg);
    cmd.parse(args);
    modelPath = modelArg.getValue();
    specs = playerArg.getValue();
    roles = roleArg.getValue();
    rulesSpec = rulesArg.getValue();
    numHands = handsArg.getValue();
    numThreads = max(1, threadsArg.getValue());
    seed = seedArg.getValue();
    outPath = outArg.getValue();
    full = fullArg.getValue();
  }
  catch(TCLAP::ArgException& e) {
    cerr << "Error: " << e.error() << " for argument " << e.argId() << endl;
    return 1;
  }
  if(specs.size() > 4)
    throw StringError("At most four players");
  while(specs.size() < 4)
    specs.push_back("net:model=" + modelPath + ",mode=sample");
  if(roles.empty())
    roles.push_back(0);
  bool reviewed[4] = {false, false, false, false};
  for(int r : roles) {
    if(r < 0 || r > 3)
      throw StringError("A role is 0 to 3");
    reviewed[r] = true;
  }
  if(seed.empty())
    seed = Global::uint64ToHexString(Rand().nextUInt64());
  const Rules rules = Rules::parse(rulesSpec);
  shared_ptr<const Learn::NetModel> model = Learn::NetModel::load(modelPath);
  for(const string& s : specs)
    makePlayer(s);  // validate
  ofstream out(outPath);
  if(!out)
    throw StringError("Cannot write " + outPath);
  mutex outMutex;
  atomic<int> nextHand(0);

  auto worker = [&]() {
    unique_ptr<Player> players[4];
    for(int p = 0; p < 4; p++)
      players[p] = makePlayer(specs[p]);
    unique_ptr<Player> greedy = makeBasicPlayer("greedy");
    Rand unused("greedy");
    vector<float> kind((size_t)Features::NUM_KINDS * Features::KIND_OBS), seatObs((size_t)4 * Features::SEAT_OBS),
      glob((size_t)Features::globalObsSize());
    unique_ptr<Learn::NetOutput> no(new Learn::NetOutput());
    Game game(rules);
    vector<Action> legal;
    while(true) {
      const int h = nextHand.fetch_add(1);
      if(h >= numHands)
        break;
      string buf;
      Rand dealRand(seed + ":" + Global::intToString(h));
      vector<uint8_t> wall = Game::shuffledWall(rules, dealRand);
      HandSetup setup = Play::randomSetup(dealRand);
      Player* bySeat[4];
      int roleAt[4];
      for(int r = 0; r < 4; r++) {
        bySeat[(r + h) % 4] = players[r].get();
        roleAt[(r + h) % 4] = r;
      }
      Rand rand(seed + ":" + Global::intToString(h) + ":play");
      game.start(setup, wall);
      int t = 0;
      while(game.phase() != Game::OVER) {
        // Decisions on a discard are simultaneous: everyone decides on the same state before any is applied.
        vector<int> pending = game.pending();
        vector<pair<int, Action>> decisions;
        for(int s : pending) {
          game.legalActions(s, legal);
          const Action a = bySeat[s]->decide(game, s, legal, rand);
          decisions.push_back({s, a});
          if(legal.size() < 2 || !reviewed[roleAt[s]])
            continue;
          uint8_t mask[Learn::NUM_ACTIONS];
          Features::observation(game, s, kind.data(), seatObs.data(), glob.data(), mask);
          model->evaluate(1, kind.data(), seatObs.data(), glob.data(), no.get());
          float coefP[Learn::NUM_EVENTS], coefT[Learn::NUM_EVENTS], q[Learn::NUM_ACTIONS];
          Learn::eventCoefficients(rules, game.setup(), s, coefP, coefT);
          no->compose(coefP, coefT, q);
          json j;
          j["h"] = h;
          j["seat"] = s;
          j["role"] = roleAt[s];
          j["t"] = t;
          j["phase"] = (int)game.phase();
          j["wall"] = game.wallLeft();
          j["turn"] = (int)game.seat(s).discards.size();
          json jLegal = json::array(), jQ = json::array(), jPol = json::array(), jNames = json::array(),
               jEv = json::array(), jTai = json::array();
          float top = -1e30f;
          for(const Action& x : legal)
            top = max(top, no->policy[Learn::actionIndex(game, x)]);
          double sum = 0.0;
          for(const Action& x : legal)
            sum += exp((double)(no->policy[Learn::actionIndex(game, x)] - top));
          int playedAt = -1;
          for(size_t i = 0; i < legal.size(); i++) {
            const int idx = Learn::actionIndex(game, legal[i]);
            jLegal.push_back(idx);
            jQ.push_back(r4(q[idx]));
            jPol.push_back(r4(exp((double)(no->policy[idx] - top)) / sum));
            if(legal[i] == a)
              playedAt = (int)i;
            if(full) {
              jNames.push_back(legal[i].toString());
              json e = json::array(), ta = json::array();
              for(int k = 0; k < Learn::NUM_EVENTS; k++) {
                e.push_back(r4(no->events[idx][k]));
                ta.push_back(r4(no->tai[idx][k]));
              }
              jEv.push_back(e);
              jTai.push_back(ta);
            }
          }
          j["legal"] = jLegal;
          j["q"] = jQ;
          j["pol"] = jPol;
          j["played"] = playedAt;
          j["greedy"] = Learn::actionIndex(game, greedy->decide(game, s, legal, unused));
          json jReady = json::array(), jDanger = json::array(), jDangerTai = json::array(), jShanten = json::array(),
               jWaits = json::array(), jWaitsTai = json::array(), jMissed = json::array();
          for(int r = 1; r <= 3; r++) {
            const int p = (s + r) % 4;
            const SeatState& sp = game.seat(p);
            json rr = json::array(), dd = json::array(), dt = json::array();
            for(int c = 0; c < 4; c++)
              rr.push_back(r4(no->ready[r - 1][c]));
            for(int k = 0; k < Features::NUM_KINDS; k++) {
              dd.push_back(r4(no->danger[r - 1][k]));
              dt.push_back(r4(no->dangerTai[r - 1][k]));
            }
            jReady.push_back(rr);
            jDanger.push_back(dd);
            jDangerTai.push_back(dt);
            // The truth, as the labels of the heads (cpp/learn/expand.cpp).
            const int shanten = Shape::shanten(sp.hand, 5 - (int)sp.melds.size());
            jShanten.push_back(min(max(shanten, 0), 3));
            jMissed.push_back(sp.missedWin ? 1 : 0);
            uint64_t w = 0;
            json wt = json::array();
            if(shanten == 0 && !sp.missedWin) {
              const uint64_t waits = Tai::waits(sp.hand, sp.melds);
              for(int k = 0; k < Features::NUM_KINDS; k++) {
                if(!(waits & ((uint64_t)1 << k)))
                  continue;
                const int tai = Tai::compute(rules, game.winInfo(p, k, false, false)).total;
                if(tai < rules.minTai)
                  continue;
                w |= (uint64_t)1 << k;
                wt.push_back(json::array({Tiles::name(k), tai}));
              }
            }
            jWaits.push_back(w);
            jWaitsTai.push_back(wt);
          }
          j["ready"] = jReady;
          j["danger"] = jDanger;
          j["sh"] = jShanten;
          j["missed"] = jMissed;
          j["waits"] = jWaits;
          if(full) {
            j["names"] = jNames;
            j["ev"] = jEv;
            j["tai"] = jTai;
            j["dtai"] = jDangerTai;
            j["waits_tai"] = jWaitsTai;
            json cp = json::array(), ct = json::array();
            for(int k = 0; k < Learn::NUM_EVENTS; k++) {
              cp.push_back(coefP[k]);
              ct.push_back(coefT[k]);
            }
            j["coefP"] = cp;
            j["coefT"] = ct;
            j["pos"] = GuiJson::position(game.positionOf(s));
            json jHands = json::array();
            for(int p = 0; p < 4; p++)
              jHands.push_back(handString(game.seat(p)));
            j["hands"] = jHands;
            json jEff = json::array();
            for(const Analysis::ActionInfo& info : Analysis::efficiency(game, s)) {
              json w = json::array();
              for(const Analysis::Wait& wt : info.waits)
                w.push_back(json::array({Tiles::name(wt.tile), wt.left, wt.ronTai, wt.tsumoTai}));
              jEff.push_back(json{{"a", info.action.toString()}, {"sh", info.shanten}, {"uk", info.ukeire}, {"w", w},
                                  {"wt", info.winTai}});
            }
            j["eff"] = jEff;
            j["act"] = a.toString();
          }
          buf += j.dump();
          buf += "\n";
        }
        for(auto& d : decisions) {
          game.act(d.first, d.second);
          t++;
        }
      }
      const HandResult& res = game.result();
      json e;
      e["h"] = h;
      e["end"] = true;
      e["points"] = json::array();
      e["roles"] = json::array();
      for(int s = 0; s < 4; s++) {
        e["points"].push_back(res.points(s, rules.base));
        e["roles"].push_back(roleAt[s]);
      }
      e["winners"] = res.winners;
      e["loser"] = res.loser;
      e["self_draw"] = res.selfDraw;
      e["exhausted"] = res.exhausted;
      e["win_tile"] = res.winTile >= 0 ? Tiles::name(res.winTile) : "";
      e["dealer"] = setup.dealer;
      e["decisions"] = t;
      if(full) {
        e["text"] = res.toString(rules.base);
        json jHands = json::array();
        for(int p = 0; p < 4; p++)
          jHands.push_back(handString(game.seat(p)));
        e["hands"] = jHands;
      }
      buf += e.dump();
      buf += "\n";
      lock_guard<mutex> lock(outMutex);
      out << buf;
    }
  };

  vector<thread> threads;
  for(int i = 0; i < numThreads; i++)
    threads.emplace_back(worker);
  for(thread& th : threads)
    th.join();
  cout << "Seed " << seed << ", " << numHands << " hands, rules " << rulesSpec << ": " << outPath << endl;
  return 0;
}
