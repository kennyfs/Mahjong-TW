#include "../players/players.h"

#include "../core/global.h"
#include "../learn/netmodel.h"

#include <cmath>

using namespace std;

namespace {
  // net:model=FILE[,mode=q|policy|sample][,temp=T]
  //   q       the action with the best composed result (the default);
  //   policy  the action the policy head likes best;
  //   sample  an action drawn from the policy at temperature T (default 1).
  class NetPlayer : public Player {
   public:
    enum Mode { MODE_Q, MODE_POLICY, MODE_SAMPLE };

    NetPlayer(shared_ptr<const Learn::NetModel> model, Mode mode, double temp, const string& spec)
      : model_(model),
        mode_(mode),
        temp_(temp),
        spec_(spec),
        kind_((size_t)Features::NUM_KINDS * Features::KIND_OBS),
        seat_((size_t)Features::NUM_SEATS * Features::SEAT_OBS),
        global_((size_t)Features::globalObsSize()),
        out_(new Learn::NetOutput()) {}
    string name() const override { return spec_; }

    Action decide(const Game& game, int seat, const vector<Action>& legal, Rand& rand) override {
      if(legal.size() == 1)
        return legal[0];
      uint8_t mask[Learn::NUM_ACTIONS];
      Features::observation(game, seat, kind_.data(), seat_.data(), global_.data(), mask);
      model_->evaluate(1, kind_.data(), seat_.data(), global_.data(), out_.get());
      float score[Learn::NUM_ACTIONS];
      if(mode_ == MODE_Q) {
        float coefP[Learn::NUM_EVENTS], coefT[Learn::NUM_EVENTS];
        Learn::eventCoefficients(game.rules(), game.setup(), seat, coefP, coefT);
        out_->compose(coefP, coefT, score);
      }
      else {
        for(int a = 0; a < Learn::NUM_ACTIONS; a++)
          score[a] = out_->policy[a];
      }
      int best = -1;
      if(mode_ == MODE_SAMPLE) {
        float top = -1e30f;
        for(int a = 0; a < Learn::NUM_ACTIONS; a++)
          if(mask[a])
            top = max(top, score[a]);
        double weight[Learn::NUM_ACTIONS];
        double sum = 0.0;
        for(int a = 0; a < Learn::NUM_ACTIONS; a++) {
          weight[a] = mask[a] ? exp((double)(score[a] - top) / temp_) : 0.0;
          sum += weight[a];
        }
        double u = rand.nextDouble() * sum;
        for(int a = 0; a < Learn::NUM_ACTIONS; a++) {
          if(!mask[a])
            continue;
          best = a;
          u -= weight[a];
          if(u < 0.0)
            break;
        }
      }
      else {
        for(int a = 0; a < Learn::NUM_ACTIONS; a++)
          if(mask[a] && (best < 0 || score[a] > score[best]))
            best = a;
      }
      if(best < 0)
        throw StringError("The net player has no legal action");
      return Learn::actionOf(game, seat, best);
    }

   private:
    shared_ptr<const Learn::NetModel> model_;
    Mode mode_;
    double temp_;
    string spec_;
    vector<float> kind_, seat_, global_;
    unique_ptr<Learn::NetOutput> out_;
  };
}

unique_ptr<Player> makeNetPlayer(const string& spec) {
  string path;
  NetPlayer::Mode mode = NetPlayer::MODE_Q;
  double temp = 1.0;
  for(const string& tok : Global::split(spec.substr(4), ',')) {
    size_t eq = tok.find('=');
    string key = eq == string::npos ? tok : tok.substr(0, eq), value = eq == string::npos ? "" : tok.substr(eq + 1);
    if(key == "model" && !value.empty()) {
      path = value;
      continue;
    }
    if(key == "mode" && (value == "q" || value == "policy" || value == "sample")) {
      mode = value == "q" ? NetPlayer::MODE_Q : value == "policy" ? NetPlayer::MODE_POLICY : NetPlayer::MODE_SAMPLE;
      continue;
    }
    if(key == "temp" && Global::tryStringToDouble(value, temp) && temp > 0.0)
      continue;
    throw StringError("Bad option for the net player: " + tok);
  }
  if(path.empty())
    throw StringError("The net player needs model=FILE");
  return unique_ptr<Player>(new NetPlayer(Learn::NetModel::load(path), mode, temp, spec));
}
