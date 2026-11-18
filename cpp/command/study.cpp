#include "../command/commands.h"

#include "../command/play.h"
#include "../core/global.h"
#include "../game/shape.h"
#include "../learn/actions.h"
#include "../learn/features.h"
#include "../learn/netmodel.h"
#include "../learn/outcome.h"
#include "../players/analysis.h"
#include "../players/players.h"

#include <nlohmann/json.hpp>
#include <tclap/CmdLine.h>

#include <atomic>
#include <cmath>
#include <fstream>
#include <iostream>
#include <mutex>
#include <thread>

using namespace std;
using json = nlohmann::json;

// Data to find what a player could learn from the network: four copies of the network play (drawing from the policy
// at a temperature), and at every decision with a choice the network's figures for every action are written beside
// tile efficiency's (`greedy`) choice, what the seat could see of the others, and what was hidden. One JSON line per
// decision:
//   h, s (seat), ph (phase), w (tiles left), tn (the seat's discards so far), dl (dealer), st (streak), sw, rw (seat
//   and round wind), hd (the hand, 34 digits), ml (melds: [type, kind]), fl (flowers), dr (the tile drawn, or the
//   discard to answer), cs (whose discard: places after me), sh (shanten now), vis (the kinds seen, 34 digits),
//   me (ready within 1, 2, 4, 8 draws), V (the policy's mean of q),
//   op [3]: per opponent 1, 2, 3 places after: [P ready, P one away, true shanten (0-3), melds, discards, flowers,
//           dealer, declared, tsumogiri at the end of its discards, discards since its last discard from the hand,
//           that discard's kind (-1), discards since its last call (-1), missed win, waits (a bit mask, the kinds it
//           would win on now with enough tai)],
//   dc [3]: each opponent's discards in order, kind + 34 if tsumogiri + 68 if called; mydc: the seat's own,
//   a: per legal action [type, kind, q, pol, sh, uk, dsum, dmax, deal, rb, qwin, qdeal, qtsumo, pwin, pdeal, wl, wk,
//      wtai]: tiles from ready and improving tiles after it (Analysis::efficiency), the network's danger of a discard
//      summed and at most over the opponents, which opponents would win on it now (bits), the shape it breaks (0
//      nothing, 1 a pair or a partial set, 2 a complete set; -1 not a discard), q in its three parts (my wins, my
//      deal-ins, others' self-draws), the chance I win and I deal in, and when ready after it the copies left of its
//      waits, their kinds and the best tai,
//   with --shape three more per action (shapeOf),
//   g (greedy's action, an index into a), p (the action played).
// And one line per hand: h, end, pts [4], win (winners), lo (loser), sd (self-draw), dl (dealer).

static double r3(double x) {
  return std::round(x * 1e3) / 1e3;
}

// The most complete sets, then partial ones (pairs, two of a chow), of one suit's counts.
static void bestBlocks(int c[9], int i, int sets, int parts, int& bs, int& bp) {
  while(i < 9 && c[i] == 0)
    i++;
  if(i >= 9) {
    if(sets > bs || (sets == bs && parts > bp)) {
      bs = sets;
      bp = parts;
    }
    return;
  }
  if(c[i] >= 3) {
    c[i] -= 3;
    bestBlocks(c, i, sets + 1, parts, bs, bp);
    c[i] += 3;
  }
  if(i <= 6 && c[i + 1] > 0 && c[i + 2] > 0) {
    c[i]--, c[i + 1]--, c[i + 2]--;
    bestBlocks(c, i, sets + 1, parts, bs, bp);
    c[i]++, c[i + 1]++, c[i + 2]++;
  }
  if(c[i] >= 2) {
    c[i] -= 2;
    bestBlocks(c, i, sets, parts + 1, bs, bp);
    c[i] += 2;
  }
  if(i <= 7 && c[i + 1] > 0) {
    c[i]--, c[i + 1]--;
    bestBlocks(c, i, sets, parts + 1, bs, bp);
    c[i]++, c[i + 1]++;
  }
  if(i <= 6 && c[i + 2] > 0) {
    c[i]--, c[i + 2]--;
    bestBlocks(c, i, sets, parts + 1, bs, bp);
    c[i]++, c[i + 2]++;
  }
  c[i]--;
  bestBlocks(c, i, sets, parts, bs, bp);
  c[i]++;
}

static pair<int, int> blocks(const Counts& h) {
  int sets = 0, parts = 0;
  for(int s = 0; s < 3; s++) {
    int c[9];
    for(int i = 0; i < 9; i++)
      c[i] = h[s * 9 + i];
    int bs = -1, bp = -1;
    bestBlocks(c, 0, 0, 0, bs, bp);
    sets += bs;
    parts += bp;
  }
  for(int k = 27; k < 34; k++) {
    if(h[k] >= 3)
      sets++;
    else if(h[k] == 2)
      parts++;
  }
  return {sets, parts};
}

static string digits(const Counts& c) {
  string s(34, '0');
  for(int k = 0; k < 34; k++)
    s[k] = (char)('0' + min(9, (int)c[k]));
  return s;
}

// Beyond the improving tiles (with --shape), of a 3 numSets + 1 hand `c` and its shanten `sh`, the seat seeing `vis`:
//   ready (sh 0): the copies left of its waits; the copies left of the draws after which a discard leaves it ready on
//     more; the mean of that better number over them;
//   one away (sh 1): the copies left of the draws that make it ready; the mean copies left of the waits it then has
//     (after the best discard); the copies left of the draws after which it waits on 5 or more;
//   two away (sh 2): the copies left of the draws that lower it; the mean improving tiles after them (the best
//     discard); 0.
static int leftOf(const Counts& vis, int k) {
  return max(0, 4 - (int)vis[k]);
}
static int waitsLeft(const Counts& c, int numSets, const Counts& vis) {
  const uint64_t w = Shape::rawWaits(c, numSets);
  int n = 0;
  for(int k = 0; k < 34; k++)
    if(w & ((uint64_t)1 << k))
      n += leftOf(vis, k);
  return n;
}
// The most copies left of the waits of a ready hand reached by one discard from the 3 numSets + 2 hand `c` (-1: none).
static int bestReady(Counts& c, int numSets, const Counts& vis) {
  int best = -1;
  for(int d = 0; d < 34; d++) {
    if(c[d] == 0)
      continue;
    c[d]--;
    if(Shape::shanten(c, numSets) == 0)
      best = max(best, waitsLeft(c, numSets, vis));
    c[d]++;
  }
  return best;
}
static void shapeOf(Counts c, int numSets, int sh, const Counts& vis, double out[3]) {
  out[0] = out[1] = out[2] = 0.0;
  if(sh == 0) {
    const int w0 = waitsLeft(c, numSets, vis);
    const uint64_t wins = Shape::rawWaits(c, numSets);
    int imp = 0;
    double sum = 0.0;
    for(int t = 0; t < 34; t++) {
      const int l = leftOf(vis, t);
      if(l == 0 || (wins & ((uint64_t)1 << t)))
        continue;
      c[t]++;
      const int b = bestReady(c, numSets, vis);
      c[t]--;
      if(b > w0) {
        imp += l;
        sum += (double)l * b;
      }
    }
    out[0] = w0;
    out[1] = imp;
    out[2] = imp ? sum / imp : 0.0;
  }
  else if(sh == 1) {
    int n = 0, good = 0;
    double sum = 0.0;
    for(int t = 0; t < 34; t++) {
      const int l = leftOf(vis, t);
      if(l == 0)
        continue;
      c[t]++;
      if(Shape::shanten(c, numSets) == 0) {
        const int b = bestReady(c, numSets, vis);
        n += l;
        sum += (double)l * b;
        if(b >= 5)
          good += l;
      }
      c[t]--;
    }
    out[0] = n;
    out[1] = n ? sum / n : 0.0;
    out[2] = good;
  }
  else if(sh == 2) {
    int n = 0;
    double sum = 0.0;
    for(int t = 0; t < 34; t++) {
      const int l = leftOf(vis, t);
      if(l == 0)
        continue;
      c[t]++;
      if(Shape::shanten(c, numSets) < 2) {
        int best = 0;
        for(int d = 0; d < 34; d++) {
          if(c[d] == 0)
            continue;
          c[d]--;
          if(Shape::shanten(c, numSets) == 1)
            best = max(best, Greedy::ukeire(c, numSets, vis, 1));
          c[d]++;
        }
        n += l;
        sum += (double)l * best;
      }
      c[t]--;
    }
    out[0] = n;
    out[1] = n ? sum / n : 0.0;
  }
}

int Commands::study(vector<string> args) {
  string modelPath, rulesSpec, seed, outPath;
  int numHands, numThreads;
  double temp;
  bool shape;
  try {
    TCLAP::CmdLine cmd("Plays hands between four copies of a network and writes, at every decision with a choice, the "
                       "network's figures for every action beside tile efficiency's choice, what the seat could see "
                       "and what was hidden (cpp/command/study.cpp lists the fields).", ' ', "0");
    TCLAP::ValueArg<string> modelArg("m", "model", "The network (.onnx)", true, "", "FILE");
    TCLAP::ValueArg<string> rulesArg("", "rules", "Rules spec (default simple2,see)", false, "simple2,see", "SPEC");
    TCLAP::ValueArg<int> handsArg("n", "hands", "Number of hands (default 1000)", false, 1000, "N");
    TCLAP::ValueArg<int> threadsArg("t", "threads", "Threads (default 1)", false, 1, "N");
    TCLAP::ValueArg<double> tempArg("", "temp", "The players' temperature over the policy (default 1)", false, 1.0, "T");
    TCLAP::ValueArg<string> seedArg("s", "seed", "Seed (default: random)", false, "", "SEED");
    TCLAP::ValueArg<string> outArg("o", "out", "Output file (JSON lines)", true, "", "FILE");
    cmd.add(modelArg);
    cmd.add(rulesArg);
    cmd.add(handsArg);
    cmd.add(threadsArg);
    cmd.add(tempArg);
    cmd.add(seedArg);
    TCLAP::SwitchArg shapeArg("", "shape", "Add each discard's shape beyond its improving tiles (shapeOf): three more "
                              "numbers to each action", false);
    cmd.add(outArg);
    cmd.add(shapeArg);
    cmd.parse(args);
    modelPath = modelArg.getValue();
    rulesSpec = rulesArg.getValue();
    numHands = handsArg.getValue();
    numThreads = max(1, threadsArg.getValue());
    temp = tempArg.getValue();
    seed = seedArg.getValue();
    outPath = outArg.getValue();
    shape = shapeArg.getValue();
  }
  catch(TCLAP::ArgException& e) {
    cerr << "Error: " << e.error() << " for argument " << e.argId() << endl;
    return 1;
  }
  if(seed.empty())
    seed = Global::uint64ToHexString(Rand().nextUInt64());
  const Rules rules = Rules::parse(rulesSpec);
  shared_ptr<const Learn::NetModel> model = Learn::NetModel::load(modelPath);
  ofstream out(outPath);
  if(!out)
    throw StringError("Cannot write " + outPath);
  mutex outMutex;
  atomic<int> nextHand(0);

  auto worker = [&]() {
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
      Rand rand(seed + ":" + Global::intToString(h) + ":play");
      game.start(setup, wall);
      while(game.phase() != Game::OVER) {
        vector<int> pending = game.pending();
        vector<pair<int, Action>> decisions;
        for(int s : pending) {
          game.legalActions(s, legal);
          if(legal.size() == 1) {
            decisions.push_back({s, legal[0]});
            continue;
          }
          uint8_t mask[Learn::NUM_ACTIONS];
          Features::observation(game, s, kind.data(), seatObs.data(), glob.data(), mask);
          model->evaluate(1, kind.data(), seatObs.data(), glob.data(), no.get());
          float coefP[Learn::NUM_EVENTS], coefT[Learn::NUM_EVENTS], q[Learn::NUM_ACTIONS];
          Learn::eventCoefficients(rules, game.setup(), s, coefP, coefT);
          no->compose(coefP, coefT, q);
          // q in parts: my wins (events 0-3), my deal-ins (4-6), others' self-draws (7-9).
          float part[3][Learn::NUM_ACTIONS];
          for(int k = 0; k < 3; k++) {
            float cp[Learn::NUM_EVENTS], ct[Learn::NUM_EVENTS];
            for(int e = 0; e < Learn::NUM_EVENTS; e++) {
              const bool in = k == 0 ? e < 4 : k == 1 ? (e >= 4 && e < 7) : e >= 7;
              cp[e] = in ? coefP[e] : 0.0f;
              ct[e] = in ? coefT[e] : 0.0f;
            }
            no->compose(cp, ct, part[k]);
          }
          // The players: an action drawn from the policy at the temperature.
          float top = -1e30f;
          for(const Action& x : legal)
            top = max(top, no->policy[Learn::actionIndex(game, x)]);
          vector<double> pol(legal.size()), weight(legal.size());
          double sum = 0.0, wsum = 0.0;
          for(size_t i = 0; i < legal.size(); i++) {
            const double l = no->policy[Learn::actionIndex(game, legal[i])] - top;
            pol[i] = exp(l);
            weight[i] = exp(l / temp);
            sum += pol[i];
            wsum += weight[i];
          }
          double u = rand.nextDouble() * wsum;
          size_t chosen = 0;
          for(size_t i = 0; i < legal.size(); i++) {
            chosen = i;
            u -= weight[i];
            if(u < 0.0)
              break;
          }
          const Action played = legal[chosen];
          decisions.push_back({s, played});

          const SeatState& me = game.seat(s);
          const int numSets = 5 - (int)me.melds.size();
          json j;
          j["h"] = h;
          j["s"] = s;
          j["ph"] = (int)game.phase();
          j["w"] = game.wallLeft();
          j["tn"] = (int)me.discards.size();
          j["dl"] = game.dealer() == s ? 1 : 0;
          j["st"] = game.setup().streak;
          j["sw"] = game.seatWind(s);
          j["rw"] = game.setup().roundWind;
          j["hd"] = digits(me.hand);
          json ml = json::array();
          for(const Meld& m : me.melds)
            ml.push_back(json::array({(int)m.type, (int)m.kind}));
          j["ml"] = ml;
          j["fl"] = __builtin_popcount(me.flowers);
          const bool self = game.phase() == Game::SELF;
          j["dr"] = self ? game.drawnTile() : game.claimTile();
          j["cs"] = self ? 0 : (game.claimSeat() - s + 4) % 4;
          j["sh"] = Shape::shanten(me.hand, numSets);
          const Counts vis = game.visibleCounts(s);
          j["vis"] = digits(vis);
          j["me"] = json::array({r3(no->readyWithin[0]), r3(no->readyWithin[1]), r3(no->readyWithin[2]),
                                 r3(no->readyWithin[3])});

          // The opponents.
          json op = json::array(), dc = json::array();
          uint64_t waitsOf[3] = {0, 0, 0};
          // Discards since each seat's last call, from the record.
          int sinceCall[4] = {-1, -1, -1, -1};
          for(const Event& e : game.events()) {
            if(e.type == Event::DISCARD && sinceCall[e.seat] >= 0)
              sinceCall[e.seat]++;
            if(e.type == Event::CHI || e.type == Event::PON || e.type == Event::MINKAN)
              sinceCall[e.seat] = 0;
          }
          for(int r = 1; r <= 3; r++) {
            const int p = (s + r) % 4;
            const SeatState& sp = game.seat(p);
            const int shanten = Shape::shanten(sp.hand, 5 - (int)sp.melds.size());
            uint64_t w = 0;
            if(shanten == 0 && !sp.missedWin) {
              const uint64_t waits = Tai::waits(sp.hand, sp.melds);
              for(int k = 0; k < Features::NUM_KINDS; k++) {
                if(!(waits & ((uint64_t)1 << k)))
                  continue;
                if(Tai::compute(rules, game.winInfo(p, k, false, false)).total < rules.minTai)
                  continue;
                w |= (uint64_t)1 << k;
              }
            }
            waitsOf[r - 1] = w;
            int tail = 0;
            for(int i = (int)sp.discards.size() - 1; i >= 0 && sp.discards[i].tsumogiri; i--)
              tail++;
            int lastTe = -1, lastTeKind = -1;
            for(int i = (int)sp.discards.size() - 1; i >= 0; i--)
              if(!sp.discards[i].tsumogiri) {
                lastTe = (int)sp.discards.size() - 1 - i;
                lastTeKind = sp.discards[i].kind;
                break;
              }
            op.push_back(json::array({r3(no->ready[r - 1][0]), r3(no->ready[r - 1][1]), min(max(shanten, 0), 3),
                                      (int)sp.melds.size(), (int)sp.discards.size(), __builtin_popcount(sp.flowers),
                                      game.dealer() == p ? 1 : 0, (int)sp.declared, tail, lastTe, lastTeKind,
                                      sinceCall[p], sp.missedWin ? 1 : 0, w}));
            json d = json::array();
            for(const Discard& x : sp.discards)
              d.push_back(x.kind + (x.tsumogiri ? 34 : 0) + (x.called ? 68 : 0));
            dc.push_back(d);
          }
          j["op"] = op;
          j["dc"] = dc;
          json mydc = json::array();
          for(const Discard& x : me.discards)
            mydc.push_back(x.kind + (x.tsumogiri ? 34 : 0) + (x.called ? 68 : 0));
          j["mydc"] = mydc;

          // Every action.
          const vector<Analysis::ActionInfo> eff = Analysis::efficiency(game, s);
          const pair<int, int> b0 = blocks(me.hand);
          const Action g = greedy->decide(game, s, legal, unused);
          json ja = json::array();
          double V = 0.0;
          int gi = -1;
          for(size_t i = 0; i < legal.size(); i++) {
            const Action& x = legal[i];
            const int idx = Learn::actionIndex(game, x);
            V += pol[i] / sum * q[idx];
            if(x == g)
              gi = (int)i;
            int sh = 99, uk = 0, wl = 0, wk = 0, wtai = -1;
            for(const Analysis::ActionInfo& info : eff)
              if(info.action == x) {
                sh = info.shanten;
                uk = info.ukeire;
                for(const Analysis::Wait& wt : info.waits) {
                  wl += wt.left;
                  wk++;
                  wtai = max(wtai, max(wt.ronTai, wt.tsumoTai));
                }
              }
            double dsum = 0.0, dmax = 0.0;
            int deal = 0, rb = -1;
            if(x.type == Action::DISCARD) {
              for(int r = 0; r < 3; r++) {
                dsum += no->danger[r][x.kind];
                dmax = max(dmax, (double)no->danger[r][x.kind]);
                if(waitsOf[r] & ((uint64_t)1 << x.kind))
                  deal |= 1 << r;
              }
              Counts c = me.hand;
              c[x.kind]--;
              const pair<int, int> b1 = blocks(c);
              rb = b1.first < b0.first ? 2 : (b1.second < b0.second ? 1 : 0);
            }
            double pwin = 0.0, pdeal = 0.0;
            for(int e = 0; e < 4; e++)
              pwin += no->events[idx][e];
            for(int e = 4; e < 7; e++)
              pdeal += no->events[idx][e];
            ja.push_back(json::array({(int)x.type, (int)x.kind, r3(q[idx]), r3(pol[i] / sum), sh, uk, r3(dsum), r3(dmax),
                                      deal, rb, r3(part[0][idx]), r3(part[1][idx]), r3(part[2][idx]), r3(pwin),
                                      r3(pdeal), wl, wk, wtai}));
            if(shape) {
              double sp[3] = {0.0, 0.0, 0.0};
              if(x.type == Action::DISCARD && sh >= 0 && sh <= 2) {
                Counts c = me.hand;
                c[x.kind]--;
                shapeOf(c, numSets, sh, vis, sp);
              }
              ja.back().push_back(r3(sp[0]));
              ja.back().push_back(r3(sp[1]));
              ja.back().push_back(r3(sp[2]));
            }
          }
          j["V"] = r3(V);
          j["a"] = ja;
          j["g"] = gi;
          j["p"] = (int)chosen;
          buf += j.dump();
          buf += "\n";
        }
        for(auto& d : decisions)
          game.act(d.first, d.second);
      }
      const HandResult& res = game.result();
      json e;
      e["h"] = h;
      e["end"] = 1;
      e["pts"] = json::array();
      for(int s = 0; s < 4; s++)
        e["pts"].push_back(res.points(s, rules.base));
      e["win"] = res.winners;
      e["lo"] = res.loser;
      e["sd"] = res.selfDraw ? 1 : 0;
      e["dl"] = setup.dealer;
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
