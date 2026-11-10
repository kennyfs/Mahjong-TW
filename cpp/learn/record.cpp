#include "../learn/record.h"

#include "../core/global.h"

#include <cstring>

using namespace std;

namespace {
  template <typename T> void put(vector<uint8_t>& out, T v) {
    size_t n = out.size();
    out.resize(n + sizeof(T));
    memcpy(out.data() + n, &v, sizeof(T));
  }
  struct Reader {
    const uint8_t* p;
    const uint8_t* end;
    template <typename T> T get() {
      if(p + sizeof(T) > end)
        throw StringError("Truncated hand record");
      T v;
      memcpy(&v, p, sizeof(T));
      p += sizeof(T);
      return v;
    }
  };
}

vector<uint8_t> Learn::HandRecord::wallOf(const Rules& rules, uint64_t seed) {
  Rand rand(seed);
  return Game::shuffledWall(rules, rand);
}

// Layout: u32 rules index; u8 dealer | east << 2 | round wind << 4 | wall-from-seed << 6; u8 streak;
// u64 seed, or the wall's tiles; u8 x 4 player types; u16 n, n action bytes; u16 m, and per mark: u16 decision,
// u8 count, and per entry: u8 action, u8 flags (1 prob, 2 outcome), [u16 prob * 65535], [u8 winners, i8 loser, u8 x 4].
void Learn::HandRecord::serialize(vector<uint8_t>& out, uint32_t rulesIndex) const {
  put<uint32_t>(out, rulesIndex);
  int east = setup.eastSeat < 0 ? setup.dealer : setup.eastSeat;
  put<uint8_t>(out, (uint8_t)(setup.dealer | (east << 2) | (setup.roundWind << 4) | ((wallFromSeed ? 1 : 0) << 6)));
  put<uint8_t>(out, (uint8_t)min(255, setup.streak));
  if(wallFromSeed)
    put<uint64_t>(out, wallSeed);
  else
    out.insert(out.end(), wall.begin(), wall.end());
  for(int i = 0; i < 4; i++)
    put<uint8_t>(out, playerTypes[i]);
  put<uint16_t>(out, (uint16_t)actions.size());
  out.insert(out.end(), actions.begin(), actions.end());
  put<uint16_t>(out, (uint16_t)marks.size());
  for(const Mark& m : marks) {
    put<uint16_t>(out, m.decision);
    put<uint8_t>(out, (uint8_t)m.entries.size());
    for(const MarkEntry& e : m.entries) {
      put<uint8_t>(out, e.action);
      put<uint8_t>(out, (uint8_t)((e.hasProb ? 1 : 0) | (e.hasOutcome ? 2 : 0)));
      if(e.hasProb)
        put<uint16_t>(out, (uint16_t)(min(1.0f, max(0.0f, e.prob)) * 65535.0f + 0.5f));
      if(e.hasOutcome) {
        put<uint8_t>(out, e.outcome.winners);
        put<int8_t>(out, e.outcome.loser);
        for(int i = 0; i < 4; i++)
          put<uint8_t>(out, e.outcome.tai[i]);
      }
    }
  }
}

uint32_t Learn::HandRecord::rulesIndexOf(const uint8_t* data) {
  uint32_t v;
  memcpy(&v, data, sizeof(v));
  return v;
}

Learn::HandRecord Learn::HandRecord::parse(const uint8_t* data, size_t len, const Rules& rules) {
  Reader in{data, data + len};
  HandRecord rec;
  rec.rules = rules;
  in.get<uint32_t>();
  uint8_t bits = in.get<uint8_t>();
  rec.setup.dealer = bits & 3;
  rec.setup.eastSeat = (bits >> 2) & 3;
  rec.setup.roundWind = (bits >> 4) & 3;
  rec.wallFromSeed = (bits >> 6) & 1;
  rec.setup.streak = in.get<uint8_t>();
  if(rec.wallFromSeed) {
    rec.wallSeed = in.get<uint64_t>();
    rec.wall = wallOf(rules, rec.wallSeed);
  }
  else {
    size_t n = (size_t)rules.numTiles();
    if(in.p + n > in.end)
      throw StringError("Truncated hand record");
    rec.wall.assign(in.p, in.p + n);
    in.p += n;
  }
  for(int i = 0; i < 4; i++)
    rec.playerTypes[i] = in.get<uint8_t>();
  size_t numActions = in.get<uint16_t>();
  if(in.p + numActions > in.end)
    throw StringError("Truncated hand record");
  rec.actions.assign(in.p, in.p + numActions);
  in.p += numActions;
  size_t numMarks = in.get<uint16_t>();
  rec.marks.resize(numMarks);
  for(Mark& m : rec.marks) {
    m.decision = in.get<uint16_t>();
    m.entries.resize(in.get<uint8_t>());
    for(MarkEntry& e : m.entries) {
      e.action = in.get<uint8_t>();
      uint8_t flags = in.get<uint8_t>();
      e.hasProb = flags & 1;
      e.hasOutcome = flags & 2;
      if(e.hasProb)
        e.prob = in.get<uint16_t>() / 65535.0f;
      if(e.hasOutcome) {
        e.outcome.winners = in.get<uint8_t>();
        e.outcome.loser = in.get<int8_t>();
        for(int i = 0; i < 4; i++)
          e.outcome.tai[i] = in.get<uint8_t>();
      }
    }
  }
  return rec;
}

vector<float> Learn::rulesToVector(const Rules& rules) {
  vector<float> v;
  for(const Rules::Param& p : Rules::params())
    v.push_back(p.intField ? (float)(rules.*p.intField) : (float)(rules.*p.doubleField));
  return v;
}

Rules Learn::rulesFromVector(const float* values, int count) {
  const vector<Rules::Param>& params = Rules::params();
  if(count != (int)params.size())
    throw StringError("Rules table has " + Global::intToString(count) + " parameters, this build " +
                      Global::intToString((int)params.size()));
  Rules r;
  for(int i = 0; i < count; i++) {
    if(params[i].intField)
      r.*params[i].intField = (int)values[i];
    else
      r.*params[i].doubleField = values[i];
  }
  return r;
}

// ---------------------------------------------------------------------------------------------------------------------

Learn::Symmetry Learn::Symmetry::random(uint64_t seed) {
  Rand rand(seed);
  Symmetry s;
  for(int i = 2; i > 0; i--) {
    swap(s.suit[i], s.suit[rand.nextUInt((uint32_t)(i + 1))]);
    swap(s.dragon[i], s.dragon[rand.nextUInt((uint32_t)(i + 1))]);
  }
  for(int i = 0; i < 3; i++)
    s.flip[i] = rand.nextUInt(2) == 1;
  return s;
}

bool Learn::Symmetry::identity() const {
  for(int i = 0; i < 3; i++)
    if(suit[i] != i || flip[i] || dragon[i] != i)
      return false;
  return true;
}

int Learn::Symmetry::tile(int t) const {
  if(t < 27) {
    int s = t / 9, n = t % 9;
    return suit[s] * 9 + (flip[s] ? 8 - n : n);
  }
  if(t >= Tiles::WHITE && t < Tiles::NUM_KINDS)
    return Tiles::WHITE + dragon[t - Tiles::WHITE];
  return t;
}

int Learn::Symmetry::action(int index, int claimTile) const {
  if(index < A_KONG)
    return A_DISCARD + tile(index - A_DISCARD);
  if(index < A_TSUMO)
    return A_KONG + tile(index - A_KONG);
  if(index >= A_CHI && claimTile >= 0 && claimTile < 27) {
    // The claimed tile is already transformed: find the suit it came from.
    int to = claimTile / 9;
    for(int s = 0; s < 3; s++)
      if(suit[s] == to && flip[s])
        return A_CHI + 2 - (index - A_CHI);
  }
  return index;
}

Learn::Replay::Replay(const HandRecord& rec, const Symmetry& sym) : rec_(rec), sym_(sym), game_(rec.rules) {
  if(sym.identity()) {
    game_.start(rec.setup, rec.wall);
  }
  else {
    vector<uint8_t> wall(rec.wall.size());
    for(size_t i = 0; i < wall.size(); i++)
      wall[i] = (uint8_t)sym.tile(rec.wall[i]);
    game_.start(rec.setup, wall);
  }
}

int Learn::Replay::seat() const {
  vector<int> pending = game_.pending();
  if(pending.empty())
    throw StringError("A record goes on after its hand is over");
  return pending[0];
}

int Learn::Replay::actionIndex() const { return mapped(rec_.actions[next_]); }

void Learn::Replay::step() {
  int s = seat();
  game_.act(s, Learn::actionOf(game_, s, actionIndex()));
  next_++;
}
