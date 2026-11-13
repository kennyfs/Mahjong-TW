#include "../tests/tests.h"

#include "../command/guiengine.h"
#include "../command/play.h"
#include "../players/analysis.h"
#include "../core/test.h"
#include "../game/shape.h"

#include <iostream>

using namespace std;

static Counts C(const string& s) { return Tiles::countsOf(Tiles::parse(s)); }
static int T(const string& s) {
  vector<int> t = Tiles::parse(s);
  testAssert(t.size() == 1);
  return t[0];
}

static void testTiles() {
  testAssert(Tiles::toString(Tiles::parse("3m 1m 2m 11z 9s 5f")) == "123m9s11z5f");
  testAssert(Tiles::name(0) == "1m" && Tiles::name(33) == "7z" && Tiles::name(41) == "8f");
  testAssert(Tiles::chineseName(T("5p")) == "五筒" && Tiles::chineseName(T("7z")) == "中");
  testAssert(Tiles::chineseName(T("7f")) == "菊");
  bool threw = false;
  try { Tiles::parse("8z"); } catch(const StringError&) { threw = true; }
  testAssert(threw);
  threw = false;
  try { Tiles::parse("12"); } catch(const StringError&) { threw = true; }
  testAssert(threw);
}

static void testShape() {
  // 17 tiles, five sets and a pair.
  testAssert(Shape::isComplete(C("123m456m789m234p55s777z"), 5));
  testAssert(!Shape::isComplete(C("123m456m789m234p56s777z"), 5));
  testAssert(Shape::isComplete(C("11122233344455566m"), 5));
  testAssert(Shape::isComplete(C("55z"), 0));
  testAssert(!Shape::isComplete(C("5z6z"), 0));
  // Waits.
  auto waitsOf = [](const string& s, int numSets) {
    uint64_t w = Shape::rawWaits(C(s), numSets);
    vector<int> out;
    for(int k = 0; k < 34; k++)
      if(w & ((uint64_t)1 << k))
        out.push_back(k);
    return Tiles::toString(out);
  };
  testAssert(waitsOf("123m456m789m234p5s777z", 5) == "5s");
  testAssert(waitsOf("123m456m789m234p45s77z", 5) == "36s");
  testAssert(waitsOf("1112345678999m", 4) == "123456789m");  // nine gates in 14 tiles (4 sets + pair)
  testAssert(waitsOf("3555m111p999p123s789s", 5) == "34m");
  // Readings: 111222333m reads as three pungs or three chows.
  vector<Shape::Reading> rs;
  Shape::readings(C("111222333m456p789s55z"), 5, rs);
  testAssert(rs.size() == 2);
  Shape::readings(C("11122233344455566m"), 5, rs);
  for(const Shape::Reading& r : rs)
    testAssert(r.n == 6);
  // Each multiset of groups once.
  for(size_t i = 0; i < rs.size(); i++)
    for(size_t j = i + 1; j < rs.size(); j++) {
      vector<pair<int, int>> a, b;
      for(int g = 0; g < 6; g++) {
        a.push_back({rs[i].groups[g].type, rs[i].groups[g].kind});
        b.push_back({rs[j].groups[g].type, rs[j].groups[g].kind});
      }
      sort(a.begin(), a.end());
      sort(b.begin(), b.end());
      testAssert(a != b);
    }
}

// Shanten against its inductive characterization: -1 exactly when complete; for 3n+1 tiles, 0 exactly when some
// tile completes the hand and otherwise one draw and discard can lower it by exactly one and never more; for 3n+2
// tiles, the best discard's.
static void testShanten() {
  Rand rand("shanten");
  testAssert(Shape::shanten(C("123m456m789m234p55s777z"), 5) == -1);
  testAssert(Shape::shanten(C("123m456m789m234p5s777z"), 5) == 0);
  testAssert(Shape::shanten(C("147m258p369s1234z"), 5) >= 6);
  for(int iter = 0; iter < 300; iter++) {
    int numSets = 1 + (int)rand.nextUInt(5);
    // Hands from a small part of the wall make close shapes more likely.
    vector<int> pool;
    int suitMask = 1 + (int)rand.nextUInt(15);
    for(int k = 0; k < 34; k++)
      if(suitMask & (1 << Tiles::suitOf(k)))
        for(int i = 0; i < 4; i++)
          pool.push_back(k);
    int n = 3 * numSets + 1;
    if((int)pool.size() < n + 1)
      continue;
    for(int i = (int)pool.size() - 1; i > 0; i--)
      swap(pool[i], pool[rand.nextUInt(i + 1)]);
    Counts c{};
    for(int i = 0; i < n; i++)
      c[pool[i]]++;
    int s = Shape::shanten(c, numSets);
    uint64_t w = Shape::rawWaits(c, numSets);
    testAssert((s == 0) == (w != 0));
    int bestAfter = 100, worstAfter = -100;
    for(int j = 0; j < 34; j++) {
      if(c[j] >= 4)
        continue;  // shanten takes at most four of a kind
      c[j]++;
      int s2 = Shape::shanten(c, numSets);
      testAssert((s2 == -1) == Shape::isComplete(c, numSets));
      int best = 100;
      for(int k = 0; k < 34; k++) {
        if(c[k] == 0)
          continue;
        c[k]--;
        int s3 = Shape::shanten(c, numSets);
        c[k]++;
        best = min(best, s3);
        bestAfter = min(bestAfter, s3);
      }
      if(s2 >= 0)
        testAssert(s2 == best);
      worstAfter = max(worstAfter, best);
      c[j]--;
    }
    if(s >= 1)
      testAssert(bestAfter == s - 1);
    testAssert(bestAfter >= s - 1);
  }
}

static Tai::WinInfo win(const string& hand, const string& winTile, bool selfDraw, vector<Meld> melds = {}) {
  Tai::WinInfo w;
  w.concealed = C(hand);
  w.winTile = T(winTile);
  w.selfDraw = selfDraw;
  w.melds = melds;
  return w;
}

static Meld meld(Meld::Type t, const string& tile) {
  Meld m;
  m.type = t;
  m.kind = m.called = (uint8_t)T(tile);
  m.from = 1;
  if(t == Meld::ANKAN)
    m.from = -1;
  return m;
}

static void testTai() {
  Rules simple = Rules::parse("simple2,none");
  testAssert(Rules().toString() == Rules::parse("simple1,none").toString());  // the default
  Rules standard = Rules::parse("standard,none");
  Rules masters = Rules::parse("masters,none");
  ostringstream out;

  // Pure suit, concealed, self-drawn; 78m waited on 6m and 9m.
  Tai::WinInfo a = win("123456789m234678m55m", "9m", true);
  out << Tai::compute(simple, a).toString() << endl;
  out << Tai::compute(Rules::parse("simple1"), a).toString() << endl;
  out << Tai::compute(standard, a).toString() << endl;
  a.lastTile = true;
  a.replacement = true;
  out << Tai::compute(standard, a).toString() << endl;
  // A pair wait on the red dragon.
  Tai::WinInfo b = win("123m456p999p789s234s77z", "7z", false);
  out << Tai::compute(simple, b).toString() << endl;
  b.flowers = 0x21;  // 1f 6f
  out << Tai::compute(Rules::parse("simple2,see"), b).toString() << endl;
  out << Tai::compute(Rules::parse("simple2,seat"), b).toString() << endl;  // east: 1f counts, 6f does not
  b.flowers = 0x1F;  // 1f-4f, 5f
  out << Tai::compute(Rules::parse("simple2,seat"), b).toString() << endl;  // 1f, 5f and the seasons
  // simple2 counts every pung of a wind and of a dragon (not the winds of the round and the seat, nor 小三元).
  Tai::WinInfo honors = win("111z555z666z123m456p77z", "7z", false);
  out << Tai::compute(simple, honors).toString() << endl;
  // All chows: two-sided wait, discard, no honors.
  Tai::WinInfo c = win("123m456m99m789p234s567s", "2s", false);
  out << Tai::compute(standard, c).toString() << endl;
  c.selfDraw = true;
  out << Tai::compute(standard, c).toString() << endl;
  // All pungs with a pung: pair wait, four concealed pungs; then a pung wait completed by a discard.
  vector<Meld> pon5p = {meld(Meld::PON, "5p")};
  out << Tai::compute(standard, win("111m222p999s777z33s", "3s", false, pon5p)).toString() << endl;
  out << Tai::compute(standard, win("111m222p999s777z33s", "9s", false, pon5p)).toString() << endl;
  // Big dragons.
  out << Tai::compute(standard, win("555666777z123m44p789s", "4p", false)).toString() << endl;
  // All melded: standard 2, masters 2 + single wait.
  vector<Meld> five = {meld(Meld::PON, "5p"), meld(Meld::PON, "7z"), meld(Meld::PON, "1z"), meld(Meld::PON, "9s"),
                       meld(Meld::PON, "2m")};
  out << Tai::compute(standard, win("99m", "9m", false, five)).toString() << endl;
  out << Tai::compute(masters, win("99m", "9m", false, five)).toString() << endl;
  out << Tai::compute(masters, win("99m", "9m", true, five)).toString() << endl;
  // Little winds, seat east, round south, self-drawn.
  Tai::WinInfo d = win("111222333z44z123456m", "6m", true);
  d.roundWind = 1;
  out << Tai::compute(standard, d).toString() << endl;
  // Single wait: 3555m waits on 3m and 4m. Strict: none; loose: a pair wait on 3m.
  out << Tai::compute(simple, win("33555m111p999p123s789s", "3m", false)).toString() << endl;
  Rules loose = simple;
  loose.dutingLoose = 1;
  out << Tai::compute(loose, win("33555m111p999p123s789s", "3m", false)).toString() << endl;
  // A concealed kong keeps the hand concealed; heavenly hand replaces concealed and self-draw.
  Tai::WinInfo e = win("123m456m789m234s55m", "5m", true, {meld(Meld::ANKAN, "9p")});
  out << Tai::compute(standard, e).toString() << endl;
  e.heavenly = true;
  out << Tai::compute(standard, e).toString() << endl;

  TestCommon::expect("tai", out, R"(
門清1 自摸1 不求人1 清一色1 = 4
自摸1 = 1
門清1 自摸1 不求人1 清一色8 = 11
門清1 自摸1 不求人1 清一色8 海底撈月1 = 12
門清1 獨聽1 = 2
門清1 獨聽1 花牌2 = 4
門清1 獨聽1 花牌1 = 3
門清1 獨聽1 花牌2 花槓2 = 6
門清1 獨聽1 見風見台1 三元牌2 = 5
門清1 平胡2 = 3
門清1 自摸1 不求人1 = 3
獨聽1 三元牌1 碰碰胡4 四暗刻5 = 11
三元牌1 碰碰胡4 三暗刻2 = 7
門清1 獨聽1 大三元8 三暗刻2 = 12
全求人2 圈風1 門風1 三元牌1 碰碰胡4 = 9
全求人2 獨聽1 圈風1 門風1 三元牌1 碰碰胡4 = 10
自摸1 半求1 獨聽1 圈風1 門風1 三元牌1 碰碰胡4 = 10
門清1 自摸1 不求人1 圈風1 門風1 小四喜8 三暗刻2 混一色4 = 19
門清1 = 1
門清1 獨聽1 = 2
門清1 自摸1 不求人1 獨聽1 = 4
天胡24 獨聽1 = 25
)");
}

// Tiles in hands, melds, uncalled discards, flowers and the wall add up to the full set.
static void checkConservation(const Game& g) {
  int counts[Tiles::NUM_ALL] = {0};
  for(int s = 0; s < 4; s++) {
    const SeatState& st = g.seat(s);
    for(int k = 0; k < 34; k++)
      counts[k] += st.hand[k];
    for(const Meld& m : st.melds)
      for(int t : m.tiles())
        counts[t]++;
    for(const Discard& d : st.discards)
      if(!d.called)
        counts[d.kind]++;
    for(int f = 0; f < 8; f++)
      if(st.flowers & (1 << f))
        counts[Tiles::FLOWER_BASE + f]++;
  }
  // The undrawn wall: everything not accounted for must be exactly what is left.
  int total = 0;
  for(int t = 0; t < Tiles::NUM_ALL; t++) {
    testAssert(counts[t] <= (Tiles::isFlower(t) ? 1 : 4));
    total += counts[t];
  }
  testAssert(total <= g.rules().numTiles());
}

static void testGames() {
  for(const char* spec : {"simple1,none", "simple2,see", "standard,see", "masters,seat", "standard,seat,multi_ron=1,keep=0"}) {
    Rules rules = Rules::parse(spec);
    Rand rand(string("games:") + spec);
    unique_ptr<Player> owned[4];
    Player* players[4];
    for(int s = 0; s < 4; s++) {
      owned[s] = makePlayer(s % 2 == 0 ? "greedy" : "random");
      players[s] = owned[s].get();
    }
    Game game(rules);
    int wins = 0, draws = 0;
    for(int h = 0; h < 400; h++) {
      HandSetup setup = Play::randomSetup(rand);
      setup.dealer = (int)rand.nextUInt(4);
      vector<uint8_t> wall = Game::shuffledWall(rules, rand);
      uint64_t seed = rand.nextUInt64();
      game.start(setup, wall);
      Rand r1(seed);
      Play::playOut(game, players, r1, [&](const Game& g, int s, const Action&) {
        checkConservation(g);
        // The deciding seat holds 3n+2 tiles in SELF and 3n+1 otherwise.
        const SeatState& st = g.seat(s);
        int n = Tiles::total(st.hand) + 3 * (int)st.melds.size();
        testAssert(n == (g.phase() == Game::SELF ? 17 : 16));
      });
      const HandResult& res = game.result();
      int baseSum = 0, taiSum = 0;
      for(int s = 0; s < 4; s++) {
        baseSum += res.baseDelta[s];
        taiSum += res.taiDelta[s];
      }
      testAssert(baseSum == 0 && taiSum == 0);
      // Payments follow section 7.
      if(!res.exhausted) {
        int expectTai[4] = {0, 0, 0, 0};
        for(size_t i = 0; i < res.winners.size(); i++) {
          int w = res.winners[i];
          for(int p = 0; p < 4; p++) {
            if(p == w || (res.loser >= 0 && p != res.loser))
              continue;
            int amt = res.tai[i].total + ((w == setup.dealer || p == setup.dealer) ? res.dealerTai : 0);
            expectTai[w] += amt;
            expectTai[p] -= amt;
          }
        }
        for(int s = 0; s < 4; s++)
          testAssert(expectTai[s] == res.taiDelta[s]);
        wins++;
      }
      else {
        draws++;
        testAssert(res.nextDealer == setup.dealer && res.nextStreak == setup.streak + rules.drawStreak);
      }
      // Replaying the same wall and seed gives the same hand.
      vector<Event> events = game.events();
      game.start(setup, wall);
      Rand r2(seed);
      Play::playOut(game, players, r2);
      testAssert(game.events().size() == events.size());
      for(size_t i = 0; i < events.size(); i++)
        testAssert(game.events()[i].toString() == events[i].toString());
    }
    testAssert(wins > 50 && draws > 0);
  }
}


// Declared ready hands (docs/rules.md 6): a wall dealt by hand. The dealer (S0) is ready after a discard (天聽), S1's
// dealt tiles are ready (天聽), S2 becomes ready with its first discard (地聽); S3 declares nothing.
static void testDeclare() {
  const char* hands[4] = {"123456789m123p4599s1z", "234567789p23478s55z", "333444666777z56p8m9p",
                          "2255m3388p115566s22z"};
  auto buildWall = [&](const Rules& rules) {
    vector<vector<int>> h;
    for(int s = 0; s < 4; s++)
      h.push_back(Tiles::parse(hands[s]));
    testAssert(h[0].size() == 17 && h[1].size() == 16 && h[2].size() == 16 && h[3].size() == 16);
    vector<uint8_t> wall;
    Counts used{};
    auto put = [&](int t) {
      wall.push_back((uint8_t)t);
      used[t]++;
    };
    for(int round = 0; round < 4; round++)
      for(int s = 0; s < 4; s++)
        for(int j = 0; j < 4; j++)
          put(h[s][round * 4 + j]);
    put(h[0][16]);
    // The draws: S1 2z, S2 8m, S3 9m, S0 7z, S1 6s.
    for(int t : Tiles::parse("2z8m9m7z6s"))
      put(t);
    for(int k = 0; k < Tiles::NUM_KINDS; k++)
      for(int n = used[k]; n < 4; n++)
        wall.push_back((uint8_t)k);
    testAssert((int)wall.size() == rules.numTiles());
    return wall;
  };
  auto has = [](const vector<Action>& legal, Action::Type t) {
    for(const Action& a : legal)
      if(a.type == t)
        return true;
    return false;
  };
  auto passAll = [](Game& g) {
    while(g.phase() == Game::REACT && !g.pending().empty())
      g.act(g.pending()[0], Action(Action::PASS));
  };
  const int z = Tiles::parse("1z")[0];
  vector<Action> legal;

  Rules rules = Rules::parse("standard,none");
  testAssert(rules.tianting == 8 && rules.diting == 4 && Rules::parse("masters").tianting == 8);
  testAssert(Rules::parse("simple1").tianting == 0 && Rules::parse("simple2").diting == 0);
  Game g(rules);
  HandSetup setup;
  g.start(setup, buildWall(rules));
  // The dealer declares; its discard must leave the hand ready.
  testAssert(g.declareType(0) == 1);
  g.legalActions(0, legal);
  testAssert(has(legal, Action::DECLARE));
  g.act(0, Action(Action::DECLARE));
  testAssert(g.seat(0).declared == 1 && g.seat(0).declaring && g.pending() == vector<int>{0});
  testAssert(g.positionOf(0).declaring);
  g.legalActions(0, legal);
  testAssert(legal.size() == 1 && legal[0] == Action(Action::DISCARD, z));
  g.act(0, legal[0]);
  testAssert(!g.seat(0).declaring);
  passAll(g);
  // S1: ready as dealt.
  testAssert(g.current() == 1 && g.declareType(1) == 1);
  g.act(1, Action(Action::DECLARE));
  g.legalActions(1, legal);
  testAssert(legal.size() == 1 && legal[0] == Action(Action::DISCARD, z + 1));
  g.act(1, legal[0]);
  passAll(g);
  // S2: ready only after its first draw and discard.
  testAssert(g.current() == 2 && g.declareType(2) == 2);
  g.act(2, Action(Action::DECLARE));
  g.legalActions(2, legal);
  testAssert(legal.size() == 1 && legal[0] == Action(Action::DISCARD, Tiles::parse("9p")[0]));
  g.act(2, legal[0]);
  passAll(g);
  testAssert(g.positionOf(3).players[2].declared == 2 && g.positionOf(3).players[1].declared == 1);
  // S3 cannot be ready; its 9m is not for the dealer, whose hand is locked (no chow).
  testAssert(g.current() == 3 && g.declareType(3) == 0);
  g.legalActions(3, legal);
  testAssert(!has(legal, Action::DECLARE));
  g.act(3, Action(Action::DISCARD, Tiles::parse("9m")[0]));
  // The dealer draws 7z: nothing but to discard it. S2 holds three of them and cannot call either.
  testAssert(g.phase() == Game::SELF && g.current() == 0);
  g.legalActions(0, legal);
  testAssert(legal.size() == 1 && legal[0] == Action(Action::DISCARD, z + 6));
  g.act(0, legal[0]);
  testAssert(g.phase() == Game::SELF && g.current() == 1);
  // S1 draws its 6s.
  g.legalActions(1, legal);
  testAssert(legal.size() == 2 && has(legal, Action::TSUMO) && has(legal, Action::DISCARD));
  g.act(1, Action(Action::TSUMO));
  ostringstream out;
  out << g.result().toString(rules.base) << endl;

  // With the items off nobody can declare.
  for(const char* spec : {"simple2,none,baoting=0", "standard,none,tianting=0,baoting=0"}) {
    Rules r = Rules::parse(spec);
    Game g2(r);
    g2.start(setup, buildWall(r));
    testAssert(g2.declareType(0) == 0);
    g2.legalActions(0, legal);
    testAssert(!has(legal, Action::DECLARE));
  }

  // 報聽: where 天聽 and 地聽 are not played, the same declarations are 報聽, a win after one adds its tai.
  {
    Rules r = Rules::parse("simple2,none,baoting=1");
    testAssert(Rules::parse("simple1").baoting == 0 && Rules::parse("standard").baoting == 0);
    Game g3(r);
    g3.start(setup, buildWall(r));
    testAssert(g3.declareType(0) == 3);
    g3.act(0, Action(Action::DECLARE));
    g3.act(0, Action(Action::DISCARD, z));
    passAll(g3);
    g3.act(1, Action(Action::DECLARE));
    testAssert(g3.seat(1).declared == 3);
    g3.act(1, Action(Action::DISCARD, z + 1));
    passAll(g3);
    testAssert(g3.declareType(2) == 3 && g3.declareType(3) == 0);
    g3.act(2, Action(Action::DISCARD, Tiles::parse("9p")[0]));
    passAll(g3);
    g3.act(3, Action(Action::DISCARD, Tiles::parse("9m")[0]));
    passAll(g3);
    g3.act(0, Action(Action::DISCARD, z + 6));
    passAll(g3);
    g3.act(1, Action(Action::TSUMO));
    const Tai::Result& res = g3.result().tai.at(0);
    bool baoting = false;
    for(const auto& item : res.items)
      baoting = baoting || (item.first == Tai::BAOTING && item.second == 1);
    testAssert(baoting);
  }
  // Later than the first turn: 報聽 also with 天聽 and 地聽 played; the hand is locked after it.
  {
    Rules r = Rules::parse("standard,none,baoting=2");
    Game g4(r);
    g4.start(setup, buildWall(r));
    testAssert(g4.declareType(0) == 1);
    g4.act(0, Action(Action::DISCARD, z));
    passAll(g4);
    g4.act(1, Action(Action::DISCARD, z + 1));
    passAll(g4);
    g4.act(2, Action(Action::DISCARD, Tiles::parse("9p")[0]));
    passAll(g4);
    g4.act(3, Action(Action::DISCARD, Tiles::parse("9m")[0]));
    passAll(g4);
    // The dealer drew 7z and is still ready: now 報聽.
    testAssert(g4.current() == 0 && g4.declareType(0) == 3);
    g4.act(0, Action(Action::DECLARE));
    testAssert(g4.declareType(0) == 0);
    g4.act(0, Action(Action::DISCARD, z + 6));
    testAssert(g4.seat(0).declared == 3 && g4.positionOf(1).players[0].declared == 3);
  }
  // 報聽 needs a concealed hand; a declared hand may make a concealed kong of the tile drawn when its waits stay.
  {
    Rules r = Rules::parse("simple2,none,baoting=1");
    Rand rand("declare");
    auto load = [&](const char* hand, uint8_t declared, bool pung) {
      Position p;
      p.me = 0;
      p.hand = Tiles::countsOf(Tiles::parse(hand));
      p.drawn = Tiles::parse("1m")[0];
      p.players[0].declared = declared;
      for(int q = 0; q < 4; q++)
        p.players[q].discards.push_back(Discard{(uint8_t)Tiles::parse("9p")[0], false, false});
      if(pung) {
        Meld m;
        m.type = Meld::PON;
        m.kind = m.called = (uint8_t)Tiles::parse("7z")[0];
        m.from = 3;
        p.players[0].melds.push_back(m);
      }
      Game g5(r);
      g5.loadPosition(p, rand);
      return g5;
    };
    // 1111m 234p 567p 789s 45s 11z: waiting on 3s 6s with the kong or without.
    Game a = load("1111m234567p78945s11z", 3, false);
    a.legalActions(0, legal);
    testAssert(has(legal, Action::ANKAN) && has(legal, Action::DISCARD) && legal.size() == 2);
    // 1111m 2m 345p 678p 999s 111z: 2m 3m; with the kong only 2m.
    Game b = load("11112m345678p999s111z", 3, false);
    b.legalActions(0, legal);
    testAssert(!has(legal, Action::ANKAN) && legal.size() == 1);
    // Not declared: ready, so it may declare 報聽; with a pung it may not.
    Game c = load("1111m234567p78945s11z", 0, false);
    testAssert(c.declareType(0) == 3);
    Game d = load("1111m234p78945s11z", 0, true);
    testAssert(d.declareType(0) == 0);
  }
  cout << out.str();
  cout << "declare ok" << endl;
}

// The GUI engine: records replay to the same hand; a seat's view hides what it cannot see; a position taken from a
// game and loaded back gives the seat the same choices and figures.
static void testGuiEngine() {
  using json = nlohmann::json;
  GuiEngine engine;
  Rand rand("gui");
  for(int iter = 0; iter < 30; iter++) {
    string spec = iter % 3 == 0 ? (iter % 2 ? "simple1,none" : "simple2,see") : iter % 3 == 1 ? "standard,see" : "masters,seat";
    json g = engine.handle(json{{"cmd", "new"}, {"rules", spec}, {"seed", "g" + Global::intToString(iter)},
                                {"setup", {{"dealer", iter % 4}, {"round", iter % 4}, {"streak", iter % 3}}}})["game"];
    int person = iter % 4;
    json players = json::array();
    for(int s = 0; s < 4; s++)
      players.push_back(s == person ? json(nullptr) : json(s % 2 ? "random" : "greedy"));
    for(int step = 0; step < 300; step++) {
      g = engine.handle(json{{"cmd", "advance"}, {"game", g}, {"players", players}, {"seed", step}})["game"];
      json v = engine.handle(json{{"cmd", "view"}, {"game", g}, {"seat", person}});
      if(v["phase"] == "over")
        break;
      testAssert(v["my_turn"].get<bool>());
      // Others' draws are hidden.
      for(const json& e : v["events"])
        if(e["type"] == "draw" && e["seat"] != person)
          testAssert(e["tile"].is_null());
      testAssert(!v.contains("hands"));
      // The same choices from the position alone.
      GuiJson::Record rec = GuiJson::parseRecord(g);
      Game game(Rules::parse(rec.rules));
      GuiJson::replay(rec, game);
      Position pos = GuiJson::parsePosition(v["position"]);
      Game loaded(Rules::parse(rec.rules));
      loaded.loadPosition(pos, rand);
      vector<Action> a1, a2;
      game.legalActions(person, a1);
      loaded.legalActions(person, a2);
      testAssert(a1.size() == a2.size());
      for(size_t i = 0; i < a1.size(); i++)
        testAssert(a1[i] == a2[i]);
      vector<Analysis::ActionInfo> f1 = Analysis::efficiency(game, person), f2 = Analysis::efficiency(loaded, person);
      testAssert(f1.size() == f2.size());
      for(size_t i = 0; i < f1.size(); i++)
        testAssert(f1[i].shanten == f2[i].shanten && f1[i].ukeire == f2[i].ukeire &&
                   f1[i].waits.size() == f2[i].waits.size() && f1[i].winTai == f2[i].winTai);
      // The game analyzed with a hand in place of the seat's: with the seat's own, the game's own figures.
      if(step % 8 == 0) {
        json own{{"cmd", "analyze"}, {"game", g}, {"seat", person}, {"hand", v["position"]["hand"]}, {"seed", "hand"}};
        if(!v["position"]["drawn"].is_null())
          own["drawn"] = v["position"]["drawn"];
        testAssert(engine.handle(own) == engine.handle(json{{"cmd", "analyze"}, {"game", g}, {"seat", person}}));
      }
      // The person plays like greedy.
      json act = engine.handle(json{{"cmd", "bot"}, {"game", g}, {"seat", person}, {"player", "greedy"}})["action"];
      g = engine.handle(json{{"cmd", "act"}, {"game", g}, {"seat", person}, {"action", act}})["game"];
    }
    json v = engine.handle(json{{"cmd", "view"}, {"game", g}, {"seat", person}});
    testAssert(v["phase"] == "over" && v["hands"].size() == 4);
    double sum = 0;
    for(const json& p : v["result"]["points"])
      sum += p.get<double>();
    testAssert(sum == 0);
    // Which actions took effect: every one on a seat's own turn, no pass, and of the claims on a discard the one
    // made; a claim that was not made names the one that was. The wins taken are the winners', the calls taken the
    // melds on the table.
    {
      json st = engine.handle(json{{"cmd", "steps"}, {"game", g}});
      const json& acts = g["actions"];
      testAssert(st["taken"].size() == acts.size() && st["over"].size() == acts.size());
      vector<int> winners;
      int calls = 0, lastTaken = -1, firstWin = -1;
      for(size_t k = 0; k < acts.size(); k++) {
        const string type = acts[k][1].get<string>();
        const bool taken = st["taken"][k].get<bool>();
        const int over = st["over"][k].get<int>();
        if(type == "pass")
          testAssert(!taken && over == -1);
        if(type == "discard" || type == "tsumo" || type == "ankan" || type == "kakan")
          testAssert(taken);
        if(over >= 0) {
          const string by = acts[over][1].get<string>();
          testAssert(!taken && st["taken"][over].get<bool>() && (by == "ron" || by == "pon" || by == "kan"));
          testAssert(type == "chi" || by == "ron");
        }
        else if(type != "pass")
          testAssert(taken);
        if(!taken)
          continue;
        lastTaken = (int)k;
        if(type == "ron" || type == "tsumo") {
          winners.push_back(acts[k][0].get<int>());
          if(firstWin < 0)
            firstWin = (int)k;
        }
        if(type == "chi" || type == "pon" || type == "kan")
          calls++;
      }
      const json& res = v["result"];
      testAssert(st["last"] == (firstWin >= 0 ? firstWin : lastTaken));
      if(!res["flower_win"].get<bool>()) {
        vector<int> seats;
        for(const json& w : res["winners"])
          seats.push_back(w["seat"].get<int>());
        sort(seats.begin(), seats.end());
        sort(winners.begin(), winners.end());
        testAssert(seats == winners);
      }
      int melds = 0;
      for(const json& p : v["position"]["players"])
        for(const json& m : p["melds"])
          if(m["type"] != "ankan")
            melds++;
      testAssert(calls == melds);
    }
    // The record survives a round trip through text.
    GuiJson::Record rec = GuiJson::parseRecord(json::parse(g.dump()));
    testAssert(GuiJson::record(rec) == g);
    // The next hand.
    json n = engine.handle(json{{"cmd", "next"}, {"game", g}, {"start_dealer", 0}, {"seed", "n"}});
    testAssert(n["game"]["setup"]["dealer"] == v["result"]["next_dealer"]);
  }
  // One decision point at a time (as a room paces the bots), and who must decide: the same hand as all at once.
  {
    json players = json::array({"greedy", "greedy", "greedy", "greedy"});
    json g0 = engine.handle(json{{"cmd", "new"}, {"rules", "standard,see"}, {"seed", "steps"}})["game"];
    json all = engine.handle(json{{"cmd", "advance"}, {"game", g0}, {"players", players}})["game"];
    json g = g0;
    for(int step = 0; step < 1000; step++) {
      json p = engine.handle(json{{"cmd", "pending"}, {"game", g}});
      testAssert(p["actions"] == g["actions"].size());
      if(p["phase"] == "over") {
        testAssert(p["pending"].empty());
        break;
      }
      testAssert(!p["pending"].empty());
      for(const json& s : p["pending"]) {
        json v = engine.handle(json{{"cmd", "view"}, {"game", g}, {"seat", s}});
        testAssert(v["my_turn"].get<bool>() && v["event_count"] == p["event_count"]);
      }
      size_t before = g["actions"].size();
      g = engine.handle(json{{"cmd", "advance"}, {"game", g}, {"players", players}, {"steps", 1}})["game"];
      testAssert(g["actions"].size() == before + p["pending"].size());
    }
    testAssert(g == all);
  }
  // A view held at the last discard: nothing after it (the next draw) until the room shows it. Where someone may
  // claim the discard, the held view is the plain one.
  {
    json players = json::array({"greedy", "greedy", "greedy", "greedy"});
    json g = engine.handle(json{{"cmd", "new"}, {"rules", "simple1,none"}, {"seed", "hold"}})["game"];
    int plain = 0, claimable = 0;
    for(int step = 0; step < 1000; step++) {
      json p = engine.handle(json{{"cmd", "pending"}, {"game", g}});
      if(p["phase"] == "over")
        break;
      g = engine.handle(json{{"cmd", "advance"}, {"game", g}, {"players", players}, {"steps", 1}})["game"];
      const json& last = g["actions"].back();
      if(last[1] != "discard")
        continue;
      int seat = last[0];
      for(int s = 0; s < 4; s++) {
        json v = engine.handle(json{{"cmd", "view"}, {"game", g}, {"seat", s}});
        json h = engine.handle(json{{"cmd", "view"}, {"game", g}, {"seat", s}, {"hold", true}});
        const json& lastEvent = h["events"].back();
        testAssert(lastEvent["type"] == "discard" && lastEvent["seat"] == seat);
        if(v["phase"] == "react") {
          testAssert(h == v);
          claimable++;
        } else if(v["phase"] == "self") {
          testAssert(h["phase"] == "react" && !h["my_turn"].get<bool>() && h["legal"].empty());
          testAssert(h["event_count"].get<int>() < v["event_count"].get<int>());
          plain++;
        }
      }
    }
    testAssert(plain > 0 && claimable > 0);
  }
  // Errors are answers, not crashes.
  testAssert(json::parse(engine.handleLine("{\"cmd\": \"nope\"}")).contains("error"));
  testAssert(json::parse(engine.handleLine("not json")).contains("error"));
}

void Tests::runAll() {
  testTiles();
  cout << "tiles ok" << endl;
  testShape();
  cout << "shape ok" << endl;
  testShanten();
  cout << "shanten ok" << endl;
  testTai();
  cout << "tai ok" << endl;
  testGames();
  testDeclare();
  cout << "games ok" << endl;
  testGuiEngine();
  cout << "gui engine ok" << endl;
  runLearn();
  cout << "All tests passed" << endl;
}
