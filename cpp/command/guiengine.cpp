#include "../command/guiengine.h"

#include "../command/guianalysis.h"
#include "../core/global.h"
#include "../game/shape.h"
#include "../players/analysis.h"

#include <algorithm>

using namespace std;
using json = nlohmann::json;

// ---------------------------------------------------------------------------------------------------------------------
// Pieces

static const char* const ACTION_NAMES[Action::NUM_TYPES] = {"discard", "tsumo", "ankan", "kakan", "pass",
                                                            "ron",     "pon",   "kan",   "chi", "declare"};
static const char* const MELD_NAMES[5] = {"chi", "pon", "kan", "ankan", "kakan"};
static const char* const EVENT_NAMES[] = {"draw", "flower", "discard", "chi",  "pon",        "kan",
                                          "ankan", "kakan", "tsumo",   "ron",  "flower_win", "exhausted", "declare"};
static const char* const PHASE_NAMES[] = {"self", "react", "rob_kong", "over"};

static json tileJson(int t) { return t < 0 || t >= Tiles::NUM_ALL ? json(nullptr) : json(Tiles::name(t)); }

static int parseTile(const json& j) {
  if(!j.is_string())
    throw StringError("A tile must be a string like \"5m\"");
  vector<int> t = Tiles::parse(j.get<string>());
  if(t.size() != 1)
    throw StringError("Not one tile: " + j.get<string>());
  return t[0];
}

static string flowersString(uint8_t mask) {
  vector<int> f;
  for(int i = 0; i < Tiles::NUM_FLOWERS; i++)
    if(mask & (1 << i))
      f.push_back(Tiles::FLOWER_BASE + i);
  return Tiles::toString(f);
}

static uint8_t parseFlowers(const string& s) {
  uint8_t mask = 0;
  for(int t : Tiles::parse(s)) {
    if(!Tiles::isFlower(t))
      throw StringError("Not a flower: " + Tiles::name(t));
    mask |= (uint8_t)(1 << (t - Tiles::FLOWER_BASE));
  }
  return mask;
}

static int getInt(const json& j, const char* key, int def) {
  if(!j.contains(key) || j[key].is_null())
    return def;
  return j[key].get<int>();
}

static bool getBool(const json& j, const char* key) { return j.contains(key) && j[key].is_boolean() && j[key].get<bool>(); }

static string getString(const json& j, const char* key, const string& def) {
  if(!j.contains(key) || j[key].is_null())
    return def;
  return j[key].get<string>();
}

static unique_ptr<Rand> randOf(const json& req) {
  if(req.contains("seed") && !req["seed"].is_null()) {
    const json& s = req["seed"];
    return unique_ptr<Rand>(new Rand(s.is_string() ? s.get<string>() : s.dump()));
  }
  return unique_ptr<Rand>(new Rand());
}

static json setupJson(const HandSetup& s) {
  return json{{"dealer", s.dealer}, {"east", s.eastSeat}, {"round", s.roundWind}, {"streak", s.streak}};
}

static HandSetup parseSetup(const json& j) {
  HandSetup s;
  if(j.is_null())
    return s;
  s.dealer = getInt(j, "dealer", 0);
  s.eastSeat = getInt(j, "east", -1);
  s.roundWind = getInt(j, "round", 0);
  s.streak = getInt(j, "streak", 0);
  if(s.dealer < 0 || s.dealer > 3 || s.eastSeat < -1 || s.eastSeat > 3 || s.roundWind < 0 || s.roundWind > 3 ||
     s.streak < 0)
    throw StringError("Bad setup");
  return s;
}

static json meldJson(const Meld& m) {
  json j;
  j["type"] = MELD_NAMES[m.type];
  if(m.kind == Position::UNKNOWN) {
    j["tile"] = nullptr;
    return j;
  }
  j["tile"] = Tiles::name(m.kind);
  json tiles = json::array();
  for(int t : m.tiles())
    tiles.push_back(Tiles::name(t));
  j["tiles"] = tiles;
  if(m.type != Meld::ANKAN) {
    j["called"] = Tiles::name(m.called);
    j["from"] = m.from;
  }
  return j;
}

static Meld parseMeld(const json& j) {
  string type = j.at("type").get<string>();
  Meld m;
  int t = -1;
  for(int i = 0; i < 5; i++)
    if(type == MELD_NAMES[i])
      t = i;
  if(t < 0)
    throw StringError("Unknown meld type: " + type);
  m.type = (Meld::Type)t;
  if(!j.contains("tile") || j["tile"].is_null()) {
    if(m.type != Meld::ANKAN)
      throw StringError("Only a concealed kong may have no tile");
    m.kind = m.called = Position::UNKNOWN;
    m.from = -1;
    return m;
  }
  int k = parseTile(j["tile"]);
  if(Tiles::isFlower(k) || (m.type == Meld::CHI && (Tiles::isHonor(k) || k % 9 > 6)))
    throw StringError("Bad meld tile: " + Tiles::name(k));
  m.kind = (uint8_t)k;
  m.called = j.contains("called") && !j["called"].is_null() ? (uint8_t)parseTile(j["called"]) : (uint8_t)k;
  if(m.type == Meld::CHI && (m.called < m.kind || m.called > m.kind + 2))
    throw StringError("A chow's called tile must be one of its tiles");
  m.from = (int8_t)getInt(j, "from", -1);
  return m;
}

static json discardJson(const Discard& d) {
  return json{{"tile", Tiles::name(d.kind)}, {"drawn", d.tsumogiri}, {"called", d.called}};
}

static Discard parseDiscard(const json& j) {
  Discard d;
  if(j.is_string()) {
    d.kind = (uint8_t)parseTile(j);
    d.tsumogiri = d.called = false;
    return d;
  }
  d.kind = (uint8_t)parseTile(j.at("tile"));
  d.tsumogiri = getBool(j, "drawn");
  d.called = getBool(j, "called");
  return d;
}

static json eventJson(const Event& e, int viewer, bool reveal) {
  json j;
  j["seat"] = e.seat;
  j["type"] = EVENT_NAMES[e.type];
  bool hidden = !reveal && e.seat != viewer && (e.type == Event::DRAW || e.type == Event::ANKAN);
  if(e.type == Event::EXHAUSTED || e.type == Event::FLOWER_WIN || e.type == Event::DECLARE || hidden)
    j["tile"] = nullptr;
  else
    j["tile"] = Tiles::name(e.tile);
  if(e.type == Event::DRAW)
    j["replacement"] = e.extra != 0;
  if(e.type == Event::DISCARD)
    j["drawn"] = e.extra != 0;
  if(e.type == Event::DECLARE)
    j["declared"] = e.extra == 1 ? "tianting" : "diting";
  if(e.type == Event::CHI)
    j["tiles"] = json::array({Tiles::name(e.extra), Tiles::name(e.extra + 1), Tiles::name(e.extra + 2)});
  return j;
}

static json taiJson(const Tai::Result& r) {
  json items = json::array();
  for(auto& it : r.items)
    items.push_back(json{{"item", Tai::PARAM_NAMES[it.first]}, {"name", Tai::NAMES[it.first]}, {"tai", it.second}});
  return json{{"total", r.total}, {"items", items}};
}

static json resultJson(const HandResult& r, double base) {
  json j;
  j["exhausted"] = r.exhausted;
  json winners = json::array();
  for(size_t i = 0; i < r.winners.size(); i++) {
    json w = taiJson(r.tai[i]);
    w["seat"] = r.winners[i];
    w["tai"] = r.tai[i].total;
    w.erase("total");
    winners.push_back(w);
  }
  j["winners"] = winners;
  j["loser"] = r.loser;
  j["win_tile"] = r.flowerWin ? json(nullptr) : tileJson(r.winTile);
  j["self_draw"] = r.selfDraw;
  j["flower_win"] = r.flowerWin;
  j["dealer_tai"] = r.dealerTai;
  json points = json::array(), bd = json::array(), td = json::array();
  for(int s = 0; s < 4; s++) {
    points.push_back(r.points(s, base));
    bd.push_back(r.baseDelta[s]);
    td.push_back(r.taiDelta[s]);
  }
  j["points"] = points;
  j["base_delta"] = bd;
  j["tai_delta"] = td;
  j["next_dealer"] = r.nextDealer;
  j["next_streak"] = r.nextStreak;
  return j;
}

// ---------------------------------------------------------------------------------------------------------------------
// GuiJson

json GuiJson::action(const Action& a, int claimTile) {
  json j;
  j["type"] = ACTION_NAMES[a.type];
  switch(a.type) {
    case Action::TSUMO:
    case Action::PASS:
    case Action::DECLARE:
      break;
    case Action::RON:
    case Action::PON:
    case Action::MINKAN:
      j["tile"] = tileJson(claimTile);
      break;
    case Action::CHI:
      j["tile"] = Tiles::name(a.kind);
      j["tiles"] = json::array({Tiles::name(a.kind), Tiles::name(a.kind + 1), Tiles::name(a.kind + 2)});
      break;
    default:
      j["tile"] = Tiles::name(a.kind);
  }
  return j;
}

Action GuiJson::parseAction(const json& j) {
  string type = j.at("type").get<string>();
  for(int i = 0; i < Action::NUM_TYPES; i++) {
    if(type != ACTION_NAMES[i])
      continue;
    Action a((Action::Type)i);
    bool needsTile = i == Action::DISCARD || i == Action::ANKAN || i == Action::KAKAN || i == Action::CHI;
    if(needsTile)
      a.kind = (uint8_t)parseTile(j.at("tile"));
    return a;
  }
  throw StringError("Unknown action type: " + type);
}

json GuiJson::position(const Position& p) {
  json j;
  j["setup"] = setupJson(p.setup);
  j["me"] = p.me;
  j["hand"] = Tiles::toString(p.hand);
  j["drawn"] = tileJson(p.drawn);
  j["after_call"] = p.afterCall;
  if(p.declaring)
    j["declaring"] = true;
  j["missed"] = p.missedWin;
  if(p.claimSeat >= 0)
    j["claim"] = json{{"seat", p.claimSeat}, {"tile", tileJson(p.claimTile)}, {"rob_kong", p.robKong}};
  else
    j["claim"] = nullptr;
  json players = json::array();
  for(int s = 0; s < 4; s++) {
    const Position::Player& pl = p.players[s];
    json melds = json::array(), discards = json::array();
    for(const Meld& m : pl.melds)
      melds.push_back(meldJson(m));
    for(const Discard& d : pl.discards)
      discards.push_back(discardJson(d));
    json player{{"melds", melds}, {"flowers", flowersString(pl.flowers)}, {"discards", discards}};
    if(pl.declared)
      player["declared"] = pl.declared == 1 ? "tianting" : "diting";
    players.push_back(player);
  }
  j["players"] = players;
  j["wall_left"] = p.wallLeft;
  return j;
}

Position GuiJson::parsePosition(const json& j) {
  Position p;
  p.setup = parseSetup(j.contains("setup") ? j["setup"] : json(nullptr));
  p.me = getInt(j, "me", 0);
  p.hand = Tiles::countsOf(Tiles::parse(getString(j, "hand", "")));
  p.drawn = j.contains("drawn") && !j["drawn"].is_null() ? parseTile(j["drawn"]) : -1;
  if(p.drawn >= 0 && p.hand[p.drawn] == 0)
    throw StringError("The drawn tile must be in the hand");
  p.afterCall = getBool(j, "after_call");
  p.declaring = getBool(j, "declaring");
  p.missedWin = getBool(j, "missed");
  if(j.contains("claim") && !j["claim"].is_null()) {
    const json& c = j["claim"];
    p.claimSeat = c.at("seat").get<int>();
    p.claimTile = c.contains("tile") && !c["tile"].is_null() ? parseTile(c["tile"]) : -1;
    p.robKong = getBool(c, "rob_kong");
  }
  if(j.contains("players")) {
    const json& ps = j["players"];
    if(!ps.is_array() || ps.size() != 4)
      throw StringError("players must list four seats");
    for(int s = 0; s < 4; s++) {
      Position::Player& pl = p.players[s];
      if(ps[s].contains("melds"))
        for(const json& m : ps[s]["melds"])
          pl.melds.push_back(parseMeld(m));
      pl.flowers = parseFlowers(getString(ps[s], "flowers", ""));
      const string declared = getString(ps[s], "declared", "");
      pl.declared = declared == "tianting" ? 1 : declared == "diting" ? 2 : 0;
      if(ps[s].contains("discards"))
        for(const json& d : ps[s]["discards"])
          pl.discards.push_back(parseDiscard(d));
    }
  }
  p.wallLeft = getInt(j, "wall_left", -1);
  return p;
}

json GuiJson::record(const Record& r) {
  string wall;
  for(uint8_t t : r.wall)
    wall += Tiles::name(t);
  json actions = json::array();
  for(auto& sa : r.actions) {
    json a = json::array({sa.first, ACTION_NAMES[sa.second.type]});
    if(sa.second.type == Action::DISCARD || sa.second.type == Action::ANKAN || sa.second.type == Action::KAKAN ||
       sa.second.type == Action::CHI)
      a.push_back(Tiles::name(sa.second.kind));
    actions.push_back(a);
  }
  return json{{"rules", r.rules}, {"setup", setupJson(r.setup)}, {"wall", wall}, {"actions", actions}};
}

GuiJson::Record GuiJson::parseRecord(const json& j) {
  Record r;
  r.rules = getString(j, "rules", "simple1,none");
  r.setup = parseSetup(j.contains("setup") ? j["setup"] : json(nullptr));
  string wall = j.at("wall").get<string>();
  for(int t : Tiles::parse(wall))
    r.wall.push_back((uint8_t)t);
  if(j.contains("actions"))
    for(const json& a : j["actions"]) {
      if(!a.is_array() || a.size() < 2)
        throw StringError("An action is [seat, type] or [seat, type, tile]");
      json obj{{"type", a[1]}};
      if(a.size() > 2)
        obj["tile"] = a[2];
      r.actions.push_back({a[0].get<int>(), parseAction(obj)});
    }
  return r;
}

// The kind an action carries in Game: claims take the claimed tile.
static Action normalized(const Game& game, Action a) {
  if(a.type == Action::RON || a.type == Action::PON || a.type == Action::MINKAN)
    a.kind = (uint8_t)game.claimTile();
  if(a.type == Action::TSUMO || a.type == Action::PASS || a.type == Action::DECLARE)
    a.kind = 0;
  return a;
}

void GuiJson::replay(const Record& r, Game& game, bool holdLast) {
  game.start(r.setup, r.wall);
  for(size_t i = 0; i < r.actions.size(); i++) {
    int s = r.actions[i].first;
    if(s < 0 || s > 3)
      throw StringError("Bad seat in action " + Global::intToString((int)i));
    if(holdLast && i + 1 == r.actions.size())
      game.hold(true);
    game.act(s, normalized(game, r.actions[i].second));
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// The engine

Player* GuiEngine::player(const string& spec) {
  auto it = players.find(spec);
  if(it != players.end())
    return it->second.get();
  unique_ptr<Player> p = makePlayer(spec);
  Player* raw = p.get();
  players[spec] = std::move(p);
  return raw;
}

static int seatOf(const json& req) {
  int s = req.at("seat").get<int>();
  if(s < 0 || s > 3)
    throw StringError("seat must be 0-3");
  return s;
}

static json viewOf(const Game& game, int seat, int since, bool reveal) {
  json v;
  v["rules"] = game.rules().toString();
  Position p = game.positionOf(seat);
  if(reveal)
    for(int q = 0; q < 4; q++)
      p.players[q].melds = game.seat(q).melds;
  v["position"] = GuiJson::position(p);
  v["phase"] = PHASE_NAMES[game.phase()];
  v["current"] = game.current();
  json winds = json::array();
  for(int s = 0; s < 4; s++)
    winds.push_back(game.seatWind(s));
  v["seat_winds"] = winds;
  vector<int> pending = game.pending();
  bool myTurn = find(pending.begin(), pending.end(), seat) != pending.end();
  v["my_turn"] = myTurn;
  json legal = json::array();
  if(myTurn) {
    vector<Action> acts;
    game.legalActions(seat, acts);
    for(const Action& a : acts)
      legal.push_back(GuiJson::action(a, game.claimTile()));
  }
  v["legal"] = legal;
  json events = json::array();
  const vector<Event>& evs = game.events();
  for(size_t i = max(0, since); i < evs.size(); i++)
    events.push_back(eventJson(evs[i], seat, reveal));
  v["events"] = events;
  v["event_count"] = (int)evs.size();
  bool over = game.phase() == Game::OVER;
  v["result"] = over ? resultJson(game.result(), game.rules().base) : json(nullptr);
  if(over || reveal) {
    json hands = json::array();
    for(int s = 0; s < 4; s++)
      hands.push_back(Tiles::toString(game.seat(s).hand));
    v["hands"] = hands;
  }
  return v;
}

// The network a request names ("model": a file), or none.
static shared_ptr<const Learn::NetModel> modelOf(const json& req) {
  string path = getString(req, "model", "");
  if(path.empty())
    return nullptr;
  return Learn::NetModel::load(path);
}

static json analysisJson(const Game& game, int seat, shared_ptr<const Learn::NetModel> model) {
  GuiAnalysis::Batch batch(model);
  batch.add(game, seat);
  batch.run();
  return batch.analysis(0);
}

// A game's position for a seat with another hand ("hand": TILES, and on the seat's own turn "drawn": the tile drawn of
// it), loaded into `out`: what the seat sees of the table stays the game's, and so do the game's events, which the
// network reads (how long ago a seat called, which kinds are safe against whom). A position given by itself has only
// the plain history of its discards (Game::plainHistory), so its figures differ from the game's once there are calls.
// False: the request names no hand.
static bool withOtherHand(const json& req, const Game& game, int seat, Rand& rand, Game& out) {
  if(!req.contains("hand") || req["hand"].is_null())
    return false;
  Position p = game.positionOf(seat);
  p.hand = Tiles::countsOf(Tiles::parse(req["hand"].get<string>()));
  p.drawn = -1;
  if(p.claimSeat < 0 && !p.afterCall && req.contains("drawn") && !req["drawn"].is_null()) {
    p.drawn = parseTile(req["drawn"]);
    if(p.hand[p.drawn] == 0)
      throw StringError("The drawn tile must be in the hand");
  }
  out.loadPosition(p, rand, game.events());
  return true;
}

// The tiles of each kind `seat` has not seen.
static Counts unseenBy(const Game& game, int seat) {
  Counts visible = game.visibleCounts(seat);
  Counts left{};
  for(int k = 0; k < Tiles::NUM_KINDS; k++)
    left[k] = (uint8_t)max(0, 4 - (int)visible[k]);
  return left;
}

// What the seat would do after each tile it may draw next, and on each discard of the others it could claim:
// its position with the hand as it rests between turns (without the tile just drawn), tried with every kind it has
// not seen. {"draws": [{"tile", "left", "action", "value", "figures": FIGURES of the best action,
// "next": the second best action or null, "next_loss"}], "claims": [{"seat", "tile", "left", "best": index,
// "options": [FIGURES]}] (only the discards that leave a choice)}.
static json whatIf(const Game& game, int me, shared_ptr<const Learn::NetModel> model, Rand& rand) {
  if(!model)
    throw StringError("whatif needs a model");
  Position base = game.positionOf(me);
  vector<Event> history = game.events();
  Counts rest = base.hand;
  if(base.claimSeat < 0) {
    if(base.drawn < 0)
      throw StringError("The hand has no tile drawn to take the place of");
    rest[base.drawn]--;
  }
  Counts left = unseenBy(game, me);
  if(base.claimSeat < 0)
    left[base.drawn]++;
  base.claimSeat = base.claimTile = -1;
  base.robKong = base.afterCall = false;
  base.drawn = -1;
  base.hand = rest;

  GuiAnalysis::Batch batch(model);
  struct Entry {
    int item, seat, tile;
  };
  vector<Entry> draws, claims;
  for(int k = 0; k < Tiles::NUM_KINDS; k++) {
    if(left[k] == 0)
      continue;
    {
      Position p = base;
      p.hand[k]++;
      p.drawn = k;
      Game g(game.rules());
      g.loadPosition(p, rand, history);
      draws.push_back({batch.add(g, me), me, k});
    }
    for(int r = 1; r <= 3; r++) {
      const int c = (me + r) % 4;
      Position p = base;
      p.claimSeat = c;
      p.claimTile = k;
      Discard d;
      d.kind = (uint8_t)k;
      d.tsumogiri = d.called = false;
      p.players[c].discards.push_back(d);
      vector<Event> h = history;
      Event e;
      e.type = Event::DISCARD;
      e.seat = (int8_t)c;
      e.tile = (uint8_t)k;
      e.extra = 0;
      h.push_back(e);
      Game g(game.rules());
      g.loadPosition(p, rand, h);
      vector<Action> legal;
      if(g.phase() == Game::REACT)
        g.legalActions(me, legal);
      if(legal.size() >= 2)
        claims.push_back({batch.add(g, me), c, k});
    }
  }
  batch.run();
  json jd = json::array(), jc = json::array();
  for(const Entry& e : draws) {
    const vector<float>& q = batch.q(e.item);
    const int best = batch.best(e.item);
    int next = -1;
    for(int a = 0; a < (int)q.size(); a++)
      if(a != best && (next < 0 || q[a] > q[next]))
        next = a;
    json row{{"tile", Tiles::name(e.tile)}, {"left", left[e.tile]}, {"value", q[best]},
             {"figures", batch.figures(e.item, best)}};
    row["action"] = row["figures"]["action"];
    row["next"] = next >= 0 ? batch.figures(e.item, next)["action"] : json(nullptr);
    row["next_loss"] = next >= 0 ? q[best] - q[next] : 0.0f;
    jd.push_back(row);
  }
  for(const Entry& e : claims) {
    json options = json::array();
    for(int a = 0; a < (int)batch.legal(e.item).size(); a++)
      options.push_back(batch.figures(e.item, a));
    jc.push_back(json{{"seat", e.seat}, {"tile", Tiles::name(e.tile)}, {"left", left[e.tile]},
                      {"best", batch.best(e.item)}, {"options", options}});
  }
  return json{{"draws", jd}, {"claims", jc}};
}

// Which of a record's actions took effect, for a review to show: everything on a seat's own turn; of the answers to a
// discard (or an added kong) the claim that was made, which is the win before a pung or a kong before a chow (every
// win with multi_ron, else the first winner in turn order). A pass never does. {"taken": [bool per action],
// "over": [per action: the index of the claim that took its place, or -1], "last": the action the hand's play ended
// with (the first win taken; without a win the last action taken; -1 without any)}.
static json stepsOf(const GuiJson::Record& rec) {
  Game game(Rules::parse(rec.rules));
  game.start(rec.setup, rec.wall);
  const int n = (int)rec.actions.size();
  vector<char> taken(n, 0);
  vector<int> over(n, -1);
  vector<int> answers;  // to the tile on the table
  for(int i = 0; i < n; i++) {
    const int s = rec.actions[i].first;
    if(s < 0 || s > 3)
      throw StringError("Bad seat in action " + Global::intToString(i));
    const bool answer = game.phase() == Game::REACT || game.phase() == Game::ROB_KONG;
    const size_t before = game.events().size();
    game.act(s, normalized(game, rec.actions[i].second));
    if(!answer) {
      taken[i] = 1;
      continue;
    }
    answers.push_back(i);
    const vector<Event>& events = game.events();
    if(events.size() == before)
      continue;  // others have yet to answer
    int first = -1;
    for(size_t e = before; e < events.size(); e++) {
      const Event::Type type = events[e].type;
      if(type != Event::RON && type != Event::CHI && type != Event::PON && type != Event::MINKAN)
        continue;
      for(int a : answers)
        if(rec.actions[a].first == events[e].seat && rec.actions[a].second.type != Action::PASS) {
          taken[a] = 1;
          if(first < 0)
            first = a;
        }
    }
    for(int a : answers)
      if(!taken[a] && rec.actions[a].second.type != Action::PASS)
        over[a] = first;
    answers.clear();
  }
  int last = -1;
  for(int i = 0; i < n; i++) {
    if(!taken[i])
      continue;
    const Action::Type type = rec.actions[i].second.type;
    if(type == Action::RON || type == Action::TSUMO) {
      last = i;
      break;
    }
    last = i;
  }
  json jt = json::array(), jo = json::array();
  for(int i = 0; i < n; i++) {
    jt.push_back(taken[i] != 0);
    jo.push_back(over[i]);
  }
  return json{{"taken", jt}, {"over", jo}, {"last", last}};
}

json GuiEngine::handle(const json& req) {
  string cmd = req.at("cmd").get<string>();

  if(cmd == "rules") {
    Rules rules = Rules::parse(getString(req, "rules", "simple1,none"));
    json values = json::object();
    for(const Rules::Param& p : Rules::params())
      values[p.name] = rules.get(p.name);
    json taiPresets = json::array(), flowerPresets = json::array(), items = json::array();
    for(int i = 0; Rules::TAI_PRESETS[i]; i++)
      taiPresets.push_back(Rules::TAI_PRESETS[i]);
    for(int i = 0; Rules::FLOWER_PRESETS[i]; i++)
      flowerPresets.push_back(Rules::FLOWER_PRESETS[i]);
    for(int i = 0; i < Tai::NUM_ITEMS; i++)
      items.push_back(json{{"param", Tai::PARAM_NAMES[i]}, {"name", Tai::NAMES[i]}});
    return json{{"spec", rules.toString()}, {"values", values}, {"tai_presets", taiPresets},
                {"flower_presets", flowerPresets}, {"items", items}};
  }

  if(cmd == "tai") {
    Rules rules = Rules::parse(getString(req, "rules", "simple1,none"));
    Tai::WinInfo w;
    w.concealed = Tiles::countsOf(Tiles::parse(req.at("hand").get<string>()));
    if(req.contains("melds"))
      for(const json& m : req["melds"]) {
        Meld meld = parseMeld(m);
        if(meld.kind == Position::UNKNOWN)
          throw StringError("A meld needs its tile");
        w.melds.push_back(meld);
      }
    w.winTile = parseTile(req.at("win"));
    w.flowers = parseFlowers(getString(req, "flowers", ""));
    w.selfDraw = getBool(req, "tsumo");
    w.robbedKong = getBool(req, "robbed");
    w.lastTile = getBool(req, "last");
    w.replacement = getBool(req, "replacement");
    w.heavenly = getBool(req, "heavenly");
    const string declared = getString(req, "declared", "");
    w.declared = declared == "tianting" ? 1 : declared == "diting" ? 2 : 0;
    w.earthly = getBool(req, "earthly");
    w.human = getBool(req, "human");
    w.seatWind = getInt(req, "seat_wind", 0);
    w.roundWind = getInt(req, "round_wind", 0);
    int numSets = 5 - (int)w.melds.size();
    if(numSets < 0 || Tiles::total(w.concealed) != 3 * numSets + 2)
      throw StringError("The hand needs " + Global::intToString(3 * numSets + 2) + " tiles with these melds");
    if(w.concealed[w.winTile] == 0)
      throw StringError("The winning tile must be in the hand");
    if(!Shape::isComplete(w.concealed, numSets))
      throw StringError("Not a winning hand");
    Counts before = w.concealed;
    before[w.winTile]--;
    uint64_t waits = Tai::waits(before, w.melds);
    vector<int> wt;
    for(int k = 0; k < Tiles::NUM_KINDS; k++)
      if(waits & ((uint64_t)1 << k))
        wt.push_back(k);
    json out = taiJson(Tai::compute(rules, w));
    out["waits"] = Tiles::toString(wt);
    return out;
  }

  if(cmd == "new") {
    string spec = getString(req, "rules", "simple1,none");
    Rules rules = Rules::parse(spec);
    GuiJson::Record r;
    r.rules = spec;
    r.setup = parseSetup(req.contains("setup") ? req["setup"] : json(nullptr));
    unique_ptr<Rand> rand = randOf(req);
    r.wall = Game::shuffledWall(rules, *rand);
    return json{{"game", GuiJson::record(r)}};
  }

  if(cmd == "model") {
    // Whether this build runs networks, and whether the named one loads.
    json out{{"available", Learn::NetModel::available()}};
    if(req.contains("model") && Learn::NetModel::available()) {
      modelOf(req);
      out["features"] = Features::VERSION;
    }
    return out;
  }

  if((cmd == "analyze" || cmd == "whatif") && req.contains("position")) {
    Game game(Rules::parse(getString(req, "rules", "simple1,none")));
    Position p = GuiJson::parsePosition(req["position"]);
    unique_ptr<Rand> rand = randOf(req);
    game.loadPosition(p, *rand, Game::plainHistory(p));
    if(cmd == "whatif")
      return whatIf(game, p.me, modelOf(req), *rand);
    return analysisJson(game, p.me, modelOf(req));
  }

  if(cmd == "steps")
    return stepsOf(GuiJson::parseRecord(req.at("game")));

  if(cmd == "review") {
    // Every decision of a seat in a finished hand that had a choice, as the seat saw it, with what it could not see.
    GuiJson::Record rec = GuiJson::parseRecord(req.at("game"));
    const int seat = seatOf(req);
    Game game(Rules::parse(rec.rules));
    game.start(rec.setup, rec.wall);
    GuiAnalysis::Batch batch(modelOf(req));
    struct Decision {
      int step, item, played;
      json truth;
    };
    vector<Decision> decisions;
    vector<Action> legal;
    for(size_t i = 0; i < rec.actions.size(); i++) {
      const int s = rec.actions[i].first;
      if(s < 0 || s > 3)
        throw StringError("Bad seat in action " + Global::intToString((int)i));
      const Action a = normalized(game, rec.actions[i].second);
      if(s == seat) {
        game.legalActions(s, legal);
        if(legal.size() >= 2) {
          const int item = batch.add(game, s);
          decisions.push_back({(int)i, item, batch.indexOf(item, a), GuiAnalysis::truth(game, s)});
        }
      }
      game.act(s, a);
    }
    if(game.phase() != Game::OVER)
      throw StringError("The hand is not over");
    batch.run();
    json list = json::array();
    double loss = 0.0;
    for(const Decision& d : decisions) {
      json j{{"step", d.step}, {"played", d.played}, {"analysis", batch.analysis(d.item)}, {"truth", d.truth}};
      if(batch.hasNetwork() && d.played >= 0) {
        const vector<float>& q = batch.q(d.item);
        const double l = q[batch.best(d.item)] - q[d.played];
        j["loss"] = l;
        loss += l;
      }
      list.push_back(j);
    }
    json out{{"seat", seat}, {"decisions", list}, {"result", game.result().points(seat, game.rules().base)}};
    if(batch.hasNetwork()) {
      // The result is the value at the first decision, less what the decisions lost, plus the rest: luck.
      const double first = decisions.empty() ? 0.0 : (double)batch.q(decisions[0].item)[batch.best(decisions[0].item)];
      out["first_value"] = first;
      out["loss"] = loss;
      out["luck"] = out["result"].get<double>() - first + loss;
      // A typical payment under the hand's rules (python/mahjong/losses.py, payment_unit), to grade losses by.
      float coefP[Learn::NUM_EVENTS], coefT[Learn::NUM_EVENTS];
      Learn::eventCoefficients(game.rules(), game.setup(), seat, coefP, coefT);
      out["unit"] = (coefP[1] + coefP[2] + coefP[3]) / 3.0 + 2.0;
    }
    return out;
  }

  // The rest take a game.
  GuiJson::Record rec = GuiJson::parseRecord(req.at("game"));
  Game game(Rules::parse(rec.rules));
  // "hold": the view stops at a last discard (or added kong) nobody may claim, before the next draw.
  bool hold = cmd == "view" && getBool(req, "hold");
  GuiJson::replay(rec, game, hold);

  if(cmd == "view")
    return viewOf(game, seatOf(req), getInt(req, "since", 0), getBool(req, "reveal"));

  if(cmd == "act") {
    int s = seatOf(req);
    Action a = normalized(game, GuiJson::parseAction(req.at("action")));
    game.act(s, a);
    rec.actions.push_back({s, a});
    return json{{"game", GuiJson::record(rec)}};
  }

  if(cmd == "bot") {
    int s = seatOf(req);
    vector<Action> legal;
    game.legalActions(s, legal);
    if(legal.empty())
      throw StringError("Seat " + Global::intToString(s) + " has nothing to decide");
    unique_ptr<Rand> rand = randOf(req);
    Action a = player(req.at("player").get<string>())->decide(game, s, legal, *rand);
    return json{{"action", GuiJson::action(a, game.claimTile())}};
  }

  if(cmd == "advance") {
    const json& ps = req.at("players");
    if(!ps.is_array() || ps.size() != 4)
      throw StringError("players must list four seats (a spec, or null for a person)");
    Player* bots[4];
    for(int s = 0; s < 4; s++)
      bots[s] = ps[s].is_null() ? nullptr : player(ps[s].get<string>());
    unique_ptr<Rand> rand = randOf(req);
    vector<Action> legal;
    // "steps": at most this many decision points (each: every bot that must decide then); by default until a person
    // must decide or the hand is over.
    int steps = getInt(req, "steps", 10000);
    for(int guard = 0; guard < steps && game.phase() != Game::OVER; guard++) {
      vector<int> pending = game.pending();
      bool waitingForPerson = false;
      vector<pair<int, Action>> decisions;
      for(int s : pending) {
        if(!bots[s]) {
          waitingForPerson = true;
          continue;
        }
        game.legalActions(s, legal);
        decisions.push_back({s, bots[s]->decide(game, s, legal, *rand)});
      }
      for(auto& d : decisions) {
        game.act(d.first, d.second);
        rec.actions.push_back(d);
      }
      if(waitingForPerson)
        break;
    }
    return json{{"game", GuiJson::record(rec)}};
  }

  if(cmd == "next") {
    if(game.phase() != Game::OVER)
      throw StringError("The hand is not over");
    int startDealer = getInt(req, "start_dealer", 0);
    const HandResult& res = game.result();
    GuiJson::Record r;
    r.rules = rec.rules;
    r.setup.dealer = res.nextDealer;
    r.setup.streak = res.nextStreak;
    r.setup.eastSeat = -1;
    r.setup.roundWind = rec.setup.roundWind;
    bool gameOver = false;
    if(res.nextDealer != rec.setup.dealer && res.nextDealer == startDealer) {
      r.setup.roundWind++;
      if(r.setup.roundWind >= 4) {
        r.setup.roundWind = 0;
        gameOver = true;
      }
    }
    unique_ptr<Rand> rand = randOf(req);
    r.wall = Game::shuffledWall(game.rules(), *rand);
    return json{{"game", GuiJson::record(r)}, {"game_over", gameOver}};
  }

  if(cmd == "analyze" || cmd == "whatif") {
    const int seat = seatOf(req);
    unique_ptr<Rand> rand = randOf(req);
    Game other(game.rules());
    const Game& g = withOtherHand(req, game, seat, *rand, other) ? other : game;
    if(cmd == "whatif")
      return whatIf(g, seat, modelOf(req), *rand);
    return analysisJson(g, seat, modelOf(req));
  }

  if(cmd == "pending") {
    json seats = json::array();
    for(int s : game.pending())
      seats.push_back(s);
    return json{{"phase", PHASE_NAMES[game.phase()]}, {"current", game.current()}, {"pending", seats},
                {"actions", (int)rec.actions.size()}, {"event_count", (int)game.events().size()}};
  }

  throw StringError("Unknown cmd: " + cmd);
}

string GuiEngine::handleLine(const string& line) {
  try {
    return handle(json::parse(line)).dump();
  }
  catch(const StringError& e) {
    return json{{"error", e.what()}}.dump();
  }
  catch(const json::exception& e) {
    return json{{"error", string("JSON: ") + e.what()}}.dump();
  }
  catch(const std::exception& e) {
    return json{{"error", e.what()}}.dump();
  }
}
