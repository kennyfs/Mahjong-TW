#include "../command/commands.h"

#include "../command/play.h"
#include "../core/timer.h"

#include <tclap/CmdLine.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <iostream>
#include <mutex>
#include <thread>

using namespace std;

namespace {
  struct PlayerStats {
    // Over deals: each deal contributes the player's mean result over its seatings.
    int64_t numDeals = 0;
    double dealSum = 0.0;
    double dealSumSq = 0.0;
    // Over hands.
    int64_t numHands = 0;
    int64_t numWins = 0;
    int64_t numSelfDraws = 0;
    int64_t numDealIns = 0;
    int64_t numDrawn = 0;  // exhaustive draws
    int64_t winTai = 0;
    int64_t numCalls = 0;
    int64_t numKongs = 0;

    void add(const PlayerStats& o) {
      numDeals += o.numDeals;
      dealSum += o.dealSum;
      dealSumSq += o.dealSumSq;
      numHands += o.numHands;
      numWins += o.numWins;
      numSelfDraws += o.numSelfDraws;
      numDealIns += o.numDealIns;
      numDrawn += o.numDrawn;
      winTai += o.winTai;
      numCalls += o.numCalls;
      numKongs += o.numKongs;
    }
  };
}

int Commands::match(vector<string> args) {
  vector<string> specs;
  int numDeals;
  string rulesSpec;
  int numThreads;
  string seed;
  bool allPerms;
  try {
    TCLAP::CmdLine cmd(
      "Plays duplicate deals: each deal (wall and setup) is played once per seating of the four players (4 rotations, "
      "or all 24 orders with --all-orders), so every player plays every seat with the same tiles.", ' ', "0");
    TCLAP::MultiArg<string> playerArg("p", "player", "Player spec (cpp/players/players.h: random, greedy, mc, net:model=FILE). Give exactly four.", true, "SPEC");
    TCLAP::ValueArg<int> dealsArg("n", "deals", "Number of deals (default 1000)", false, 1000, "N");
    TCLAP::ValueArg<string> rulesArg("", "rules", "Rules spec (docs/rules.md section 11, default simple1,none)", false,
                                     "simple1,none", "SPEC");
    TCLAP::ValueArg<int> threadsArg("t", "threads", "Threads (default 1)", false, 1, "N");
    TCLAP::ValueArg<string> seedArg("s", "seed", "Seed (default: random)", false, "", "SEED");
    TCLAP::SwitchArg permsArg("", "all-orders", "Play all 24 seatings instead of the 4 rotations", false);
    cmd.add(playerArg);
    cmd.add(dealsArg);
    cmd.add(rulesArg);
    cmd.add(threadsArg);
    cmd.add(seedArg);
    cmd.add(permsArg);
    cmd.parse(args);
    specs = playerArg.getValue();
    numDeals = dealsArg.getValue();
    rulesSpec = rulesArg.getValue();
    numThreads = threadsArg.getValue();
    seed = seedArg.getValue();
    allPerms = permsArg.getValue();
  }
  catch(TCLAP::ArgException& e) {
    cerr << "Error: " << e.error() << " for argument " << e.argId() << endl;
    return 1;
  }
  if(specs.size() != 4)
    throw StringError("Need exactly four players");
  if(numThreads <= 0)
    numThreads = 1;
  if(seed.empty())
    seed = Global::uint64ToHexString(Rand().nextUInt64());
  Rules rules = Rules::parse(rulesSpec);
  for(const string& s : specs)
    makePlayer(s);  // validate

  // seatings[i][p] = the seat of player p.
  vector<array<int, 4>> seatings;
  if(allPerms) {
    array<int, 4> perm = {0, 1, 2, 3};
    do {
      seatings.push_back(perm);
    } while(next_permutation(perm.begin(), perm.end()));
  }
  else {
    for(int r = 0; r < 4; r++)
      seatings.push_back({r, (r + 1) % 4, (r + 2) % 4, (r + 3) % 4});
  }

  vector<PlayerStats> totals(4);
  // Each pair's difference by deal (the same tiles and seats): its sum and sum of squares, [i * 4 + j] for i < j.
  vector<double> pairSum(16, 0.0), pairSumSq(16, 0.0);
  mutex totalsMutex;
  atomic<int> nextDeal(0);
  ClockTimer timer;

  auto worker = [&]() {
    vector<PlayerStats> local(4);
    vector<double> localPair(16, 0.0), localPairSq(16, 0.0);
    unique_ptr<Player> players[4];
    for(int p = 0; p < 4; p++)
      players[p] = makePlayer(specs[p]);
    Game game(rules);
    while(true) {
      int d = nextDeal.fetch_add(1);
      if(d >= numDeals)
        break;
      Rand dealRand(seed + ":" + Global::intToString(d));
      vector<uint8_t> wall = Game::shuffledWall(rules, dealRand);
      HandSetup setup = Play::randomSetup(dealRand);
      double dealResult[4] = {0, 0, 0, 0};
      for(size_t si = 0; si < seatings.size(); si++) {
        const array<int, 4>& seatOf = seatings[si];
        Player* bySeat[4];
        int playerAt[4];
        for(int p = 0; p < 4; p++) {
          bySeat[seatOf[p]] = players[p].get();
          playerAt[seatOf[p]] = p;
        }
        Rand rand(seed + ":" + Global::intToString(d) + ":" + Global::intToString((int)si));
        game.start(setup, wall);
        Play::playOut(game, bySeat, rand);
        const HandResult& res = game.result();
        for(int s = 0; s < 4; s++) {
          PlayerStats& st = local[playerAt[s]];
          st.numHands++;
          dealResult[playerAt[s]] += res.points(s, rules.base);
          if(res.exhausted)
            st.numDrawn++;
          int calls = 0, kongs = 0;
          for(const Meld& m : game.seat(s).melds) {
            calls += m.isOpen() ? 1 : 0;
            kongs += m.isKong() ? 1 : 0;
          }
          st.numCalls += calls;
          st.numKongs += kongs;
        }
        for(size_t i = 0; i < res.winners.size(); i++) {
          PlayerStats& st = local[playerAt[res.winners[i]]];
          st.numWins++;
          st.numSelfDraws += res.selfDraw ? 1 : 0;
          st.winTai += res.tai[i].total;
        }
        if(res.loser >= 0 && !res.flowerWin)
          local[playerAt[res.loser]].numDealIns++;
      }
      for(int p = 0; p < 4; p++) {
        double mean = dealResult[p] / seatings.size();
        local[p].numDeals++;
        local[p].dealSum += mean;
        local[p].dealSumSq += mean * mean;
      }
      for(int i = 0; i < 4; i++)
        for(int j = i + 1; j < 4; j++) {
          double diff = (dealResult[i] - dealResult[j]) / seatings.size();
          localPair[i * 4 + j] += diff;
          localPairSq[i * 4 + j] += diff * diff;
        }
    }
    lock_guard<mutex> lock(totalsMutex);
    for(int p = 0; p < 4; p++)
      totals[p].add(local[p]);
    for(int i = 0; i < 16; i++) {
      pairSum[i] += localPair[i];
      pairSumSq[i] += localPairSq[i];
    }
  };

  vector<thread> threads;
  for(int t = 0; t < numThreads; t++)
    threads.emplace_back(worker);
  for(thread& t : threads)
    t.join();

  double secs = timer.getSeconds();
  cout << "Seed " << seed << ", " << numDeals << " deals x " << seatings.size() << " seatings, rules " << rulesSpec
       << ", " << secs << " s (" << (int)(numDeals * seatings.size() / max(secs, 1e-9)) << " hands/s)" << endl;
  cout << "Result: mean points per hand (in tai, base " << rules.base
       << "); +- is the standard error over deals. Rates are per hand." << endl;
  cout << Global::strprintf("%-2s %-10s %16s %7s %7s %7s %7s %7s %7s %7s", "#", "player", "result", "win", "tsumo",
                            "dealin", "draw", "wintai", "calls", "kongs")
       << endl;
  for(int p = 0; p < 4; p++) {
    const PlayerStats& st = totals[p];
    double n = (double)max<int64_t>(st.numDeals, 1);
    double mean = st.dealSum / n;
    double var = st.dealSumSq / n - mean * mean;
    double se = st.numDeals > 1 ? sqrt(max(var, 0.0) / (n - 1)) : 0.0;
    double h = (double)max<int64_t>(st.numHands, 1);
    cout << Global::strprintf("%-2d %-10s %8.3f +- %5.3f %7.4f %7.4f %7.4f %7.4f %7.2f %7.3f %7.4f", p, specs[p].c_str(),
                              mean, se, st.numWins / h, st.numSelfDraws / h, st.numDealIns / h, st.numDrawn / h,
                              st.numWins > 0 ? (double)st.winTai / st.numWins : 0.0, st.numCalls / h, st.numKongs / h)
         << endl;
  }
  // Paired by deal, a difference has a far smaller error than the two results apart.
  double n = (double)max(numDeals, 1);
  cout << "Differences, paired by deal:";
  for(int i = 0; i < 4; i++)
    for(int j = i + 1; j < 4; j++) {
      double mean = pairSum[i * 4 + j] / n, var = pairSumSq[i * 4 + j] / n - mean * mean;
      cout << Global::strprintf("  %d-%d %.3f +- %.3f", i, j, mean, numDeals > 1 ? sqrt(max(var, 0.0) / (n - 1)) : 0.0);
    }
  cout << endl;
  return 0;
}
