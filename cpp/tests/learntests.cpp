#include "../tests/tests.h"

#include "../command/play.h"
#include "../core/test.h"
#include "../game/shape.h"
#include "../learn/expand.h"
#include "../learn/sampling.h"

#include <cmath>
#include <iostream>

using namespace std;

namespace {
  // The arrays of one expanded sample.
  struct Sample {
    vector<float> kind, seat, global, kindFull, seatFull, globalFull, mu, coef, info;
    vector<uint8_t> legal, known, tai, oppHand, danger, dangerTai, oppShanten, nextChow, taiItems, future, finalHand;
    vector<uint16_t> events;
    vector<int8_t> winTile, nextDiscard;
    int32_t played = -1;
    int32_t greedy = -1;
    Learn::Row row;

    Sample()
      : kind(34 * Features::KIND_OBS), seat(4 * Features::SEAT_OBS), global(Features::globalObsSize()),
        kindFull(34 * Features::KIND_FULL), seatFull(4 * Features::SEAT_FULL), globalFull(Features::GLOBAL_FULL),
        mu(Learn::NUM_ACTIONS), coef(2 * Learn::NUM_EVENTS), info(Learn::INFO_SIZE), legal(Learn::NUM_ACTIONS),
        known(Learn::NUM_ACTIONS), tai(Learn::NUM_ACTIONS * 4), oppHand(3 * 34), danger(3 * 34), dangerTai(3 * 34),
        oppShanten(3), nextChow(34), taiItems(Tai::NUM_ITEMS), future(Learn::FUTURE_SIZE), finalHand(34),
        events(Learn::NUM_ACTIONS), winTile(4), nextDiscard(3) {
      row.kind = kind.data();
      row.seat = seat.data();
      row.global = global.data();
      row.kindFull = kindFull.data();
      row.seatFull = seatFull.data();
      row.globalFull = globalFull.data();
      row.legal = legal.data();
      row.played = &played;
      row.mu = mu.data();
      row.known = known.data();
      row.events = events.data();
      row.tai = tai.data();
      row.coef = coef.data();
      row.info = info.data();
      row.oppHand = oppHand.data();
      row.danger = danger.data();
      row.dangerTai = dangerTai.data();
      row.oppShanten = oppShanten.data();
      row.nextChow = nextChow.data();
      row.winTile = winTile.data();
      row.taiItems = taiItems.data();
      row.future = future.data();
      row.finalHand = finalHand.data();
      row.nextDiscard = nextDiscard.data();
      row.greedy = &greedy;
    }
    Sample(const Sample&) = delete;

    // The result of an action from its events, as the network composes it.
    double composed(int action) const {
      double v = 0.0;
      for(int e = 0; e < Learn::NUM_EVENTS; e++) {
        if(!((events[action] >> Learn::EVENT_BIT[e]) & 1))
          continue;
        v += coef[e] + tai[action * 4 + Learn::EVENT_WINNER[e]] * coef[Learn::NUM_EVENTS + e];
      }
      return v;
    }
  };

  bool near(double a, double b) { return fabs(a - b) < 1e-4; }
}

void Tests::runLearn() {
  static const char* const PLAYERS[] = {"random", "greedy", "greedy:eps=0.25", "greedy:calls=more",
                                        "greedy:calls=none"};
  Rand rand("learn tests 3");
  int64_t numDecisions = 0, numMarks = 0, numDanger = 0, numSafe = 0, numSymmetric = 0, numMulti = 0;
  bool sawRob = false, sawChi = false, sawKong = false, sawFlowers = false;
  Sample a, b;
  vector<Action> legal;
  for(int h = 0; h < 400; h++) {
    Learn::HandRecord rec;
    rec.rules = Learn::sampleRules("mix", rand);
    rec.setup = Learn::sampleSetup(rand);
    rec.wallFromSeed = h % 2 == 0;
    rec.wallSeed = rand.nextUInt64();
    rec.wall = Learn::HandRecord::wallOf(rec.rules, rec.wallSeed);
    unique_ptr<Player> owned[4];
    Player* players[4];
    for(int s = 0; s < 4; s++) {
      owned[s] = makePlayer(PLAYERS[rand.nextUInt(5)]);
      players[s] = owned[s].get();
      rec.playerTypes[s] = (uint8_t)rand.nextUInt(3);
    }
    sawFlowers = sawFlowers || rec.rules.hasFlowers();

    // The action indices are one to one with the legal actions.
    Game game(rec.rules);
    game.start(rec.setup, rec.wall);
    Play::playOut(game, players, rand, [&](const Game& g, int s, const Action& act) {
      g.legalActions(s, legal);
      bool used[Learn::NUM_ACTIONS] = {false};
      for(const Action& x : legal) {
        int index = Learn::actionIndex(g, x);
        testAssert(index >= 0 && index < Learn::NUM_ACTIONS && !used[index]);
        used[index] = true;
        testAssert(Learn::actionOf(g, s, index) == x);
      }
      sawRob = sawRob || g.phase() == Game::ROB_KONG;
      sawChi = sawChi || act.type == Action::CHI;
      sawKong = sawKong || act.type == Action::ANKAN || act.type == Action::KAKAN || act.type == Action::MINKAN;
      if(legal.size() >= 2 && rand.nextDouble() < 0.1) {
        Learn::Mark mark;
        mark.decision = (uint16_t)rec.actions.size();
        for(const Action& x : legal) {
          Learn::MarkEntry e;
          e.action = (uint8_t)Learn::actionIndex(g, x);
          e.hasProb = true;
          e.prob = 1.0f / legal.size();
          mark.entries.push_back(e);
        }
        rec.marks.push_back(mark);
      }
      rec.actions.push_back((uint8_t)Learn::actionIndex(g, act));
      numDecisions++;
    });

    // The outcome keeps what the result says, and the events compose into the same points.
    const HandResult& res = game.result();
    const Learn::Outcome outcome = Learn::Outcome::of(res);
    numMulti += res.winners.size() > 1 ? 1 : 0;
    for(int s = 0; s < 4; s++) {
      testAssert(near(outcome.points(s, rec.rules, rec.setup), res.points(s, rec.rules.base)));
      float coefP[Learn::NUM_EVENTS], coefT[Learn::NUM_EVENTS];
      Learn::eventCoefficients(rec.rules, rec.setup, s, coefP, coefT);
      double v = 0.0;
      uint16_t events = outcome.events(s);
      for(int e = 0; e < Learn::NUM_EVENTS; e++)
        if((events >> Learn::EVENT_BIT[e]) & 1)
          v += coefP[e] + outcome.tai[(s + Learn::EVENT_WINNER[e]) % 4] * coefT[e];
      testAssert(near(v, res.points(s, rec.rules.base)));
    }

    // A record survives its bytes, and replays to the same end.
    vector<uint8_t> bytes;
    rec.serialize(bytes, 7);
    testAssert(Learn::HandRecord::rulesIndexOf(bytes.data()) == 7);
    Learn::HandRecord back = Learn::HandRecord::parse(bytes.data(), bytes.size(), rec.rules);
    testAssert(back.wall == rec.wall && back.actions == rec.actions && back.marks.size() == rec.marks.size());
    testAssert(back.setup.dealer == rec.setup.dealer && back.setup.eastSeat == rec.setup.eastSeat &&
               back.setup.roundWind == rec.setup.roundWind && back.setup.streak == rec.setup.streak);
    for(size_t m = 0; m < rec.marks.size(); m++) {
      testAssert(back.marks[m].decision == rec.marks[m].decision);
      testAssert(back.marks[m].entries.size() == rec.marks[m].entries.size());
      for(size_t i = 0; i < rec.marks[m].entries.size(); i++) {
        testAssert(back.marks[m].entries[i].action == rec.marks[m].entries[i].action);
        testAssert(near(back.marks[m].entries[i].prob, rec.marks[m].entries[i].prob));
      }
    }
    Learn::Replay replay(back);
    while(!replay.done())
      replay.step();
    testAssert(replay.game().phase() == Game::OVER);
    for(int s = 0; s < 4; s++)
      testAssert(near(replay.game().result().points(s, rec.rules.base), res.points(s, rec.rules.base)));

    // Samples.
    for(size_t m = 0; m < back.marks.size(); m++) {
      numMarks++;
      Learn::expand(back, (int)m, Learn::Symmetry(), a.row);
      const int me = (int)a.info[7];
      testAssert(a.legal[a.played] == 1 && a.known[a.played] == 1);
      testAssert(a.greedy >= 0 && a.greedy < Learn::NUM_ACTIONS && a.legal[a.greedy] == 1);
      testAssert(near(a.composed(a.played), a.info[2]) && near(a.info[2], res.points(me, rec.rules.base)));
      int numLegal = 0;
      for(int i = 0; i < Learn::NUM_ACTIONS; i++) {
        numLegal += a.legal[i];
        testAssert(a.legal[i] ? near(a.mu[i], 1.0 / back.marks[m].entries.size()) : a.mu[i] == 0.0f);
      }
      testAssert(numLegal == (int)a.info[4] && numLegal == (int)back.marks[m].entries.size());

      // The danger labels say exactly who would win on each of my discards, and no kind marked safe is dangerous.
      Learn::Replay at(back);
      while(at.index() < back.marks[m].decision)
        at.step();
      const Game& g = at.game();
      testAssert(at.seat() == me);
      for(int r = 1; r <= 3; r++) {
        const int p = (me + r) % 4;
        for(int k = 0; k < 34; k++) {
          testAssert(a.oppHand[(r - 1) * 34 + k] == g.seat(p).hand[k]);
          if(a.kind[k * Features::KIND_OBS + Features::KO_SAFE + r - 1] > 0) {
            numSafe++;
            testAssert(a.danger[(r - 1) * 34 + k] == 0);
          }
        }
      }
      if(g.phase() == Game::SELF) {
        for(int k = 0; k < 34; k++) {
          if(!a.legal[Learn::A_DISCARD + k])
            continue;
          Game after = g;
          after.act(me, Action(Action::DISCARD, k));
          for(int r = 1; r <= 3; r++) {
            const int p = (me + r) % 4;
            bool canRon = false;
            if(after.phase() == Game::REACT) {
              after.legalActions(p, legal);
              for(const Action& x : legal)
                canRon = canRon || x.type == Action::RON;
            }
            testAssert(canRon == (a.danger[(r - 1) * 34 + k] == 1));
            numDanger += canRon ? 1 : 0;
          }
        }
      }

      // Seen through a symmetry, the same sample with its kinds and actions moved.
      const Learn::Symmetry sym = Learn::Symmetry::random(rand.nextUInt64() | 1);
      Learn::expand(back, (int)m, sym, b.row);
      numSymmetric++;
      testAssert(a.seat == b.seat && a.seatFull == b.seatFull && a.info == b.info);
      {
        // A reversed suit also exchanges the chows that take the claimed tile as their lowest and highest.
        bool claimFlipped = false;
        for(int k = 0; k < 27; k++)
          if(a.kind[k * Features::KIND_OBS + Features::KO_CLAIM] > 0 && sym.flip[k / 9])
            claimFlipped = true;
        const int chi = Features::GO_ACTIONS + (Learn::A_CHI - Learn::A_TSUMO) * Features::GA_SIZE;
        for(int i = 0; i < (int)a.global.size(); i++) {
          int i2 = i;
          if(claimFlipped && i >= chi && i < chi + Features::GA_SIZE)
            i2 = i + 2 * Features::GA_SIZE;
          else if(claimFlipped && i >= chi + 2 * Features::GA_SIZE && i < chi + 3 * Features::GA_SIZE)
            i2 = i - 2 * Features::GA_SIZE;
          testAssert(a.global[i] == b.global[i2]);
        }
      }
      testAssert(a.coef == b.coef && a.future == b.future && a.oppShanten == b.oppShanten);
      int claim = -1;
      for(int k = 0; k < 34; k++) {
        const int k2 = sym.tile(k);
        const bool flipped = k < 27 && sym.flip[k / 9];
        if(b.kind[k2 * Features::KIND_OBS + Features::KO_CLAIM] > 0)
          claim = k2;
        for(int j = 0; j < Features::KIND_OBS; j++) {
          int j2 = j;
          // A reversed suit exchanges the lowest and the highest tile of a chow.
          if(flipped && j >= Features::KO_MELD && j < Features::KO_DISC && ((j - Features::KO_MELD) % 6) % 2 == 0 &&
             (j - Features::KO_MELD) % 6 <= 2)
            j2 = j + 2 - 2 * ((j - Features::KO_MELD) % 6);
          testAssert(a.kind[k * Features::KIND_OBS + j] == b.kind[k2 * Features::KIND_OBS + j2]);
        }
        for(int j = 0; j < Features::KIND_FULL; j++)
          testAssert(a.kindFull[k * Features::KIND_FULL + j] == b.kindFull[k2 * Features::KIND_FULL + j]);
        testAssert(a.finalHand[k] == b.finalHand[k2] && a.nextChow[k] == b.nextChow[k2]);
        for(int r = 0; r < 3; r++) {
          testAssert(a.oppHand[r * 34 + k] == b.oppHand[r * 34 + k2] && a.danger[r * 34 + k] == b.danger[r * 34 + k2]);
          testAssert(a.dangerTai[r * 34 + k] == b.dangerTai[r * 34 + k2]);
        }
      }
      for(int i = 0; i < Learn::NUM_ACTIONS; i++) {
        const int i2 = sym.action(i, claim);
        testAssert(a.legal[i] == b.legal[i2] && a.mu[i] == b.mu[i2] && a.known[i] == b.known[i2]);
        testAssert(a.events[i] == b.events[i2]);
      }
      testAssert(b.played == sym.action(a.played, claim));
      for(int r = 0; r < 3; r++)
        testAssert((a.nextDiscard[r] < 0 ? -1 : sym.tile(a.nextDiscard[r])) == b.nextDiscard[r]);
    }
  }
  testAssert(numMarks > 1000 && numDanger > 20 && numSafe > 1000 && numSymmetric > 1000);
  testAssert(sawChi && sawKong && sawFlowers && numMulti > 0);

  // A kong to rob is rare in play: a position set up by hand.
  if(!sawRob) {
    Game g(Rules::parse("simple2,none"));
    Position p;
    p.me = 1;
    p.hand = Tiles::countsOf(Tiles::parse("123456789m123p46s77z"));
    p.claimSeat = 0;
    p.claimTile = Tiles::parse("5s")[0];
    p.robKong = true;
    Meld pung;
    pung.type = Meld::PON;
    pung.kind = pung.called = (uint8_t)p.claimTile;
    pung.from = 2;
    p.players[0].melds.push_back(pung);
    p.wallLeft = 40;
    Rand r("rob");
    g.loadPosition(p, r);
    testAssert(g.phase() == Game::ROB_KONG);
    const uint8_t types[4] = {0, 0, 0, 0};
    Features::observation(g, 1, a.row.kind, a.row.seat, a.row.global, a.row.legal);
    Features::fullState(g, 1, types, a.row.kindFull, a.row.seatFull, a.row.globalFull);
    int numLegal = 0;
    for(int i = 0; i < Learn::NUM_ACTIONS; i++)
      numLegal += a.legal[i];
    testAssert(numLegal == 2 && a.legal[Learn::A_RON] && a.legal[Learn::A_PASS]);
    testAssert(a.global[Features::GO_PHASE + 3] == 1.0f && a.global[Features::GO_SHANTEN + 1] == 1.0f);
    const float* ron = a.global.data() + Features::GO_ACTIONS + (Learn::A_RON - Learn::A_TSUMO) * Features::GA_SIZE;
    // Concealed (1), a single wait (1) and robbing the kong (not counted in simple2): 2 tai.
    testAssert(ron[Features::GA_LEGAL] == 1.0f && near(ron[Features::GA_READY + 1], 2.0 / 8.0));
    testAssert(a.kind[p.claimTile * Features::KIND_OBS + Features::KO_CLAIM] == 1.0f);
    testAssert(a.kind[p.claimTile * Features::KIND_OBS + Features::KO_DRAW + 1] == 1.0f);
  }
  // Decisions on one discard are simultaneous: what a seat observes there does not depend on the answers the seats
  // before it have already given (batched self-play asks for all of them at once).
  {
    int groups = 0;
    Rand rand("learn tests: groups");
    unique_ptr<Player> eager = makePlayer("greedy:calls=more");
    const int kindSize = 34 * Features::KIND_OBS, seatSize = 4 * Features::SEAT_OBS, globalSize = Features::globalObsSize();
    vector<Action> legal;
    for(int h = 0; h < 300; h++) {
      Rules rules = Learn::sampleRules("mix", rand);
      HandSetup setup = Learn::sampleSetup(rand);
      Game game(rules);
      game.start(setup, Game::shuffledWall(rules, rand));
      while(game.phase() != Game::OVER) {
        const vector<int> pending = game.pending();
        vector<vector<float>> before;
        vector<vector<uint8_t>> masks;
        vector<Action> actions;
        for(int s : pending) {
          vector<float> obs(kindSize + seatSize + globalSize);
          vector<uint8_t> mask(Learn::NUM_ACTIONS);
          Features::observation(game, s, obs.data(), obs.data() + kindSize, obs.data() + kindSize + seatSize, mask.data());
          before.push_back(obs);
          masks.push_back(mask);
          game.legalActions(s, legal);
          actions.push_back(eager->decide(game, s, legal, rand));
        }
        groups += pending.size() > 1 ? 1 : 0;
        for(size_t j = 0; j < pending.size(); j++) {
          vector<float> obs(kindSize + seatSize + globalSize);
          vector<uint8_t> mask(Learn::NUM_ACTIONS);
          Features::observation(game, pending[j], obs.data(), obs.data() + kindSize, obs.data() + kindSize + seatSize,
                                mask.data());
          testAssert(obs == before[j] && mask == masks[j]);
          testAssert(game.pending()[0] == pending[j]);
          game.act(pending[j], actions[j]);
        }
      }
    }
    testAssert(groups > 30);
  }

  cout << "learn ok (" << numDecisions << " decisions, " << numMarks << " samples, " << numDanger
       << " dangerous discards)" << endl;
}
