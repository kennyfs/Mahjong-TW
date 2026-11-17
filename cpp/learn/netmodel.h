#ifndef LEARN_NETMODEL_H_
#define LEARN_NETMODEL_H_

#include "../learn/features.h"
#include "../learn/outcome.h"

#include <memory>
#include <string>

// The actor network as exported by python/mahjong/model.py (export_onnx), run with ONNX Runtime. Without ONNX
// Runtime (cmake -DONNXRUNTIME_ROOT=...), loading a model throws.
namespace Learn {
  // Tai::NUM_ITEMS, the tai items of game/tai.h in their order (checked in netmodel.cpp).
  constexpr int NUM_TAI_ITEMS = 41;

  // What the network says about one decision.
  struct NetOutput {
    float policy[NUM_ACTIONS];              // logits; only those of legal actions mean anything
    float events[NUM_ACTIONS][NUM_EVENTS];  // per action, the chance of each of my ten outcome events
    float tai[NUM_ACTIONS][NUM_EVENTS];     // and its winner's tai, given that it happens
    float ready[3][4];                      // per opponent (1, 2, 3 places after me): ready, 1, 2, 3 or more away
    float danger[3][Features::NUM_KINDS];     // the chance that it wins on a discard of this kind now
    float dangerTai[3][Features::NUM_KINDS];  // and with how many tai
    // The deciding seat's own, from a network that has them (NetModel::hasMe; exported from 2026-11-17): the chance
    // of being ready (or having won) within its next 1, 2, 4, 8 draws, and of each tai item being in its win.
    float readyWithin[4];
    float taiItems[NUM_TAI_ITEMS];

    // The expected result of each action, by the rules' coefficients (eventCoefficients).
    void compose(const float coefP[NUM_EVENTS], const float coefT[NUM_EVENTS], float q[NUM_ACTIONS]) const;
  };

  class NetModel {
   public:
    // Whether this build can run models.
    static bool available();
    // Loads a model, once per path: threads share the session.
    static std::shared_ptr<const NetModel> load(const std::string& path);

    explicit NetModel(const std::string& path);
    ~NetModel();
    NetModel(const NetModel&) = delete;
    NetModel& operator=(const NetModel&) = delete;

    const std::string& path() const { return path_; }
    // Whether the file has the outputs ready_within and tai_items (NetOutput::readyWithin, taiItems).
    bool hasMe() const { return hasMe_; }
    // n decisions in one batch. kind: [n * 34 * KIND_OBS], seat: [n * 4 * SEAT_OBS], global: [n * globalObsSize()],
    // as Features::observation writes them.
    void evaluate(int n, const float* kind, const float* seat, const float* global, NetOutput* out) const;

   private:
    struct Impl;
    std::string path_;
    Impl* impl_;
    bool hasMe_ = false;
  };
}

#endif  // LEARN_NETMODEL_H_
