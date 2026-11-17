#include "../command/guianalysis.h"

#include "../command/guiengine.h"
#include "../core/global.h"
#include "../game/shape.h"
#include "../game/tai.h"
#include "../learn/features.h"
#include "../learn/outcome.h"
#include "../players/analysis.h"

#include <algorithm>
#include <cmath>

using namespace std;
using json = nlohmann::json;

static double r4(double x) {
  return std::round(x * 1e4) / 1e4;
}

GuiAnalysis::Batch::Batch(shared_ptr<const Learn::NetModel> model) : model_(model) {}

int GuiAnalysis::Batch::add(const Game& game, int seat) {
  Item item;
  item.seat = seat;
  item.claimTile = game.claimTile();
  game.legalActions(seat, item.legal);
  if(item.legal.empty())
    throw StringError("Seat " + Global::intToString(seat) + " has nothing to decide");
  for(const Analysis::ActionInfo& info : Analysis::efficiency(game, seat)) {
    json waits = json::array();
    for(const Analysis::Wait& w : info.waits)
      waits.push_back(json{{"tile", Tiles::name(w.tile)}, {"left", w.left}, {"ron_tai", w.ronTai},
                           {"tsumo_tai", w.tsumoTai}});
    item.eff.push_back(json{{"action", GuiJson::action(info.action, game.claimTile())}, {"shanten", info.shanten},
                            {"ukeire", info.ukeire}, {"waits", waits}, {"win_tai", info.winTai}});
  }
  if(model_) {
    for(const Action& a : item.legal)
      item.index.push_back(Learn::actionIndex(game, a));
    const size_t ks = (size_t)Features::NUM_KINDS * Features::KIND_OBS, ss = (size_t)4 * Features::SEAT_OBS,
                 gs = (size_t)Features::globalObsSize();
    const size_t n = items_.size();
    kind_.resize((n + 1) * ks);
    seat_.resize((n + 1) * ss);
    global_.resize((n + 1) * gs);
    uint8_t mask[Learn::NUM_ACTIONS];
    Features::observation(game, seat, kind_.data() + n * ks, seat_.data() + n * ss, global_.data() + n * gs, mask);
    Learn::eventCoefficients(game.rules(), game.setup(), seat, item.coefP, item.coefT);
  }
  items_.push_back(std::move(item));
  return (int)items_.size() - 1;
}

void GuiAnalysis::Batch::run() {
  if(!model_ || evaluated_ >= (int)items_.size())
    return;
  const size_t ks = (size_t)Features::NUM_KINDS * Features::KIND_OBS, ss = (size_t)4 * Features::SEAT_OBS,
               gs = (size_t)Features::globalObsSize();
  const int chunk = 32;
  vector<Learn::NetOutput> outs((size_t)chunk);
  for(int from = evaluated_; from < (int)items_.size(); from += chunk) {
    const int n = min(chunk, (int)items_.size() - from);
    model_->evaluate(n, kind_.data() + from * ks, seat_.data() + from * ss, global_.data() + from * gs, outs.data());
    for(int i = 0; i < n; i++) {
      Item& item = items_[from + i];
      item.out.reset(new Learn::NetOutput(outs[i]));
      float q[Learn::NUM_ACTIONS];
      item.out->compose(item.coefP, item.coefT, q);
      float top = -1e30f;
      for(int idx : item.index)
        top = max(top, item.out->policy[idx]);
      double sum = 0.0;
      for(int idx : item.index)
        sum += exp((double)(item.out->policy[idx] - top));
      for(int idx : item.index) {
        item.q.push_back(q[idx]);
        item.policy.push_back((float)(exp((double)(item.out->policy[idx] - top)) / sum));
      }
    }
  }
  evaluated_ = (int)items_.size();
}

int GuiAnalysis::Batch::best(int i) const {
  const Item& item = items_[i];
  int best = 0;
  for(int a = 1; a < (int)item.q.size(); a++)
    if(item.q[a] > item.q[best])
      best = a;
  return best;
}

int GuiAnalysis::Batch::indexOf(int i, const Action& a) const {
  const Item& item = items_[i];
  for(int k = 0; k < (int)item.legal.size(); k++)
    if(item.legal[k] == a)
      return k;
  return -1;
}

json GuiAnalysis::Batch::figures(int i, int at) const {
  const Item& item = items_[i];
  json f = item.eff[at];
  if(!item.out)
    return f;
  const Learn::NetOutput& o = *item.out;
  const int idx = item.index[at];
  const float* ev = o.events[idx];
  const float* tai = o.tai[idx];
  double parts[3] = {0, 0, 0}, group[3] = {0, 0, 0}, winTai = 0;
  for(int e = 0; e < Learn::NUM_EVENTS; e++) {
    const int g = e < 4 ? 0 : (e < 7 ? 1 : 2);
    parts[g] += ev[e] * (item.coefP[e] + tai[e] * item.coefT[e]);
    group[g] += ev[e];
    if(e < 4)
      winTai += ev[e] * tai[e];
  }
  f["q"] = r4(item.q[at]);
  f["policy"] = r4(item.policy[at]);
  f["win"] = r4(group[0]);
  f["tsumo"] = r4(ev[0]);
  f["deal_in"] = r4(group[1]);
  f["others_tsumo"] = r4(group[2]);
  f["tai"] = r4(group[0] > 1e-6 ? winTai / group[0] : 0.0);
  f["parts"] = json::array({r4(parts[0]), r4(parts[1]), r4(parts[2])});
  if(item.legal[at].type == Action::DISCARD) {
    const int k = item.legal[at].kind;
    double safe = 1.0;
    json each = json::array(), eachTai = json::array();
    for(int r = 0; r < 3; r++) {
      safe *= 1.0 - o.danger[r][k];
      each.push_back(r4(o.danger[r][k]));
      eachTai.push_back(r4(o.dangerTai[r][k]));
    }
    f["danger"] = r4(1.0 - safe);
    f["danger_each"] = each;
    f["danger_tai"] = eachTai;
  }
  return f;
}

json GuiAnalysis::Batch::analysis(int i) const {
  const Item& item = items_[i];
  json actions = json::array();
  for(int a = 0; a < (int)item.legal.size(); a++)
    actions.push_back(figures(i, a));
  json j{{"source", item.out ? "network" : "efficiency"}, {"actions", actions}};
  if(!item.out)
    return j;
  const int b = best(i);
  double mean = 0.0;
  for(size_t a = 0; a < item.q.size(); a++)
    mean += item.policy[a] * item.q[a];
  j["best"] = b;
  j["value"] = r4(item.q[b]);
  j["policy_value"] = r4(mean);
  json opponents = json::array();
  for(int r = 0; r < 3; r++) {
    json ready = json::array(), danger = json::array(), dangerTai = json::array();
    for(int c = 0; c < 4; c++)
      ready.push_back(r4(item.out->ready[r][c]));
    for(int k = 0; k < Features::NUM_KINDS; k++) {
      danger.push_back(r4(item.out->danger[r][k]));
      dangerTai.push_back(r4(item.out->dangerTai[r][k]));
    }
    opponents.push_back(json{{"seat", (item.seat + r + 1) % 4}, {"ready", ready}, {"danger", danger},
                             {"danger_tai", dangerTai}});
  }
  j["opponents"] = opponents;
  if(model_->hasMe()) {
    json within = json::array();
    for(int c = 0; c < 4; c++)
      within.push_back(r4(item.out->readyWithin[c]));
    // The items likely enough to say anything (1% and up), by their parameters' names (docs/rules.md).
    json items = json::object();
    for(int t = 0; t < Learn::NUM_TAI_ITEMS; t++)
      if(item.out->taiItems[t] >= 0.01f)
        items[Tai::PARAM_NAMES[t]] = r4(item.out->taiItems[t]);
    j["me"] = json{{"ready_within", within}, {"tai_items", items}};
  }
  return j;
}

json GuiAnalysis::truth(const Game& game, int seat) {
  json out = json::array();
  for(int r = 1; r <= 3; r++) {
    const int p = (seat + r) % 4;
    const SeatState& sp = game.seat(p);
    const int shanten = Shape::shanten(sp.hand, 5 - (int)sp.melds.size());
    json waits = json::array();
    // As the danger head's labels (cpp/learn/expand.cpp): the kinds it would win on now.
    if(shanten == 0 && !sp.missedWin) {
      const uint64_t mask = Tai::waits(sp.hand, sp.melds);
      for(int k = 0; k < Tiles::NUM_KINDS; k++) {
        if(!(mask & ((uint64_t)1 << k)))
          continue;
        const int tai = Tai::compute(game.rules(), game.winInfo(p, k, false, false)).total;
        if(tai >= game.rules().minTai)
          waits.push_back(json{{"tile", Tiles::name(k)}, {"tai", tai}});
      }
    }
    out.push_back(json{{"seat", p}, {"shanten", max(shanten, 0)}, {"missed", sp.missedWin}, {"waits", waits}});
  }
  return out;
}
