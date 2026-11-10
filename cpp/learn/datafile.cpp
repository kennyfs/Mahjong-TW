#include "../learn/datafile.h"

#include "../dataio/npz.h"
#include "../learn/features.h"

using namespace std;

void Learn::DataFile::add(const HandRecord& rec) {
  string key = rec.rules.toString();
  auto it = rulesIndex_.find(key);
  uint32_t index;
  if(it != rulesIndex_.end()) {
    index = it->second;
  }
  else {
    index = (uint32_t)rulesIndex_.size();
    rulesIndex_[key] = index;
    vector<float> v = rulesToVector(rec.rules);
    rules_.insert(rules_.end(), v.begin(), v.end());
  }
  int hand = numHands();
  offsets_.push_back((int64_t)hands_.size());
  rec.serialize(hands_, index);
  for(size_t m = 0; m < rec.marks.size(); m++) {
    marks_.push_back(hand);
    marks_.push_back((int32_t)m);
  }
}

void Learn::DataFile::write(const string& path) {
  const int64_t numParams = (int64_t)Rules::params().size();
  vector<int64_t> offsets = offsets_;
  offsets.push_back((int64_t)hands_.size());
  NpzWriter npz;
  npz.add("hands", {(int64_t)hands_.size()}, hands_);
  npz.add("offsets", {(int64_t)offsets.size()}, offsets);
  npz.add("rules", {(int64_t)rules_.size() / numParams, numParams}, rules_);
  npz.add("marks", {(int64_t)marks_.size() / 2, 2}, marks_);
  npz.add("version", {1}, vector<int32_t>{Features::VERSION});
  npz.write(path);
  hands_.clear();
  offsets_.clear();
  rules_.clear();
  rulesIndex_.clear();
  marks_.clear();
}
