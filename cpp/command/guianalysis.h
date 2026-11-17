#ifndef COMMAND_GUIANALYSIS_H_
#define COMMAND_GUIANALYSIS_H_

#include "../game/game.h"
#include "../learn/netmodel.h"

#include <nlohmann/json.hpp>

#include <memory>
#include <vector>

// The analysis of decisions for the GUIs (docs/analysis.md): the tile efficiency of every legal action, and with a
// network its figures from the deciding seat's view. Decisions are collected, evaluated in one batch and then read.
//
// ANALYSIS  {"source": "efficiency"|"network", "actions": [FIGURES], and with a network:
//            "best": index of the action with the best expected result, "value": that result,
//            "policy_value": the policy's mean result,
//            "opponents": [{"seat": s, "ready": [P ready, 1, 2, 3 or more tiles away],
//                           "danger": [34: P it wins on a discard of the kind now], "danger_tai": [34]} x3],
//            and with a network that has them (exported from 2026-11-17), the deciding seat's own:
//            "me": {"ready_within": [P ready (or won) within its next 1, 2, 4, 8 draws],
//                   "tai_items": {PARAM: P the item is in its win (and it wins)}: the items from 1%, by docs/rules.md's
//                   parameter names}}
//           The opponents are the seats 1, 2, 3 places after the deciding one; the kinds in the order 1m-9m, 1p-9p,
//           1s-9s, 1z-7z.
// FIGURES   {"action": ACTION, "shanten": n (-1 a win), "ukeire": n, "waits": [...], "win_tai": n}, and with a network:
//            "q": the expected result of the hand in points, "policy": P the network plays it,
//            "win": P of winning the hand, "tsumo": P by self-draw, "deal_in": P of dealing in, "others_tsumo": P that
//            another seat self-draws, "tai": the mean tai of my win, "parts": [3] q's shares from my wins, from
//            dealing in and from the others' self-draws,
//            and for a discard: "danger": P somebody wins on it now, "danger_each": [3], "danger_tai": [3].
namespace GuiAnalysis {
  class Batch {
   public:
    // Without a model: tile efficiency only.
    explicit Batch(std::shared_ptr<const Learn::NetModel> model);
    // Adds the decision of `seat` in `game` as it is now (the seat must have something to decide). Returns its index.
    int add(const Game& game, int seat);
    // Asks the network about everything added since the last run.
    void run();
    int size() const { return (int)items_.size(); }

    const std::vector<Action>& legal(int i) const { return items_[i].legal; }
    // The legal actions' expected results (after run, with a model), and the best one's index.
    const std::vector<float>& q(int i) const { return items_[i].q; }
    int best(int i) const;
    // The index of an action among the decision's legal ones, -1 when it is not one of them.
    int indexOf(int i, const Action& a) const;
    nlohmann::json analysis(int i) const;
    // One action's FIGURES.
    nlohmann::json figures(int i, int at) const;
    bool hasNetwork() const { return (bool)model_; }

   private:
    struct Item {
      int seat = 0;
      int claimTile = -1;
      std::vector<Action> legal;
      std::vector<int> index;           // the network's action index of each
      std::vector<nlohmann::json> eff;  // the efficiency figures of each
      float coefP[Learn::NUM_EVENTS], coefT[Learn::NUM_EVENTS];
      std::vector<float> q, policy;
      std::unique_ptr<Learn::NetOutput> out;
    };
    std::shared_ptr<const Learn::NetModel> model_;
    std::vector<Item> items_;
    std::vector<float> kind_, seat_, global_;
    int evaluated_ = 0;
  };

  // What `seat` cannot see of the others, for the review of a finished hand:
  // [{"seat": s, "shanten": n (0 ready), "waits": [{"tile", "tai"}]} x3], the seats 1, 2, 3 places after it.
  nlohmann::json truth(const Game& game, int seat);
}

#endif  // COMMAND_GUIANALYSIS_H_
