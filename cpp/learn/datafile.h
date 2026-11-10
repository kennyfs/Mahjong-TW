#ifndef LEARN_DATAFILE_H_
#define LEARN_DATAFILE_H_

#include "../learn/record.h"

#include <map>
#include <string>

// A file of hand records (.npz, read by python/mahjong/data.py):
//   hands    uint8  [B]      the records, one after another (cpp/learn/record.cpp)
//   offsets  int64  [N + 1]  where each begins
//   rules    float32 [R, P]  the rules of the file's hands, each set once (Rules::params() order)
//   marks    int32  [M, 2]   every decision marked for training: its hand and its mark in that hand
//   version  int32  [1]      Features::VERSION
namespace Learn {
  class DataFile {
   public:
    void add(const HandRecord& rec);
    int numHands() const { return (int)offsets_.size(); }
    int numMarks() const { return (int)(marks_.size() / 2); }
    // Writes the file (through a temporary name) and empties the buffer.
    void write(const std::string& path);

   private:
    std::vector<uint8_t> hands_;
    std::vector<int64_t> offsets_;
    std::vector<float> rules_;
    std::map<std::string, uint32_t> rulesIndex_;
    std::vector<int32_t> marks_;
  };
}

#endif  // LEARN_DATAFILE_H_
