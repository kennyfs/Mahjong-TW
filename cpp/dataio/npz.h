#ifndef DATAIO_NPZ_H_
#define DATAIO_NPZ_H_

#include "../core/global.h"

// Writes NumPy .npz files (a zip of .npy arrays), readable with numpy.load. The arrays are deflated when the build
// found zlib, stored otherwise.
class NpzWriter {
 public:
  void add(const std::string& name, const std::vector<int64_t>& shape, const std::vector<uint8_t>& data);
  void add(const std::string& name, const std::vector<int64_t>& shape, const std::vector<uint16_t>& data);
  void add(const std::string& name, const std::vector<int64_t>& shape, const std::vector<int32_t>& data);
  void add(const std::string& name, const std::vector<int64_t>& shape, const std::vector<int64_t>& data);
  void add(const std::string& name, const std::vector<int64_t>& shape, const std::vector<uint32_t>& data);
  void add(const std::string& name, const std::vector<int64_t>& shape, const std::vector<float>& data);

  // Writes to path + ".tmp", then renames, so readers never see a partial file. Throws IOError.
  void write(const std::string& path) const;

 private:
  struct Entry {
    std::string name;
    std::string bytes; // The whole .npy file.
  };
  std::vector<Entry> entries;

  void addRaw(const std::string& name, const char* descr, const std::vector<int64_t>& shape, const void* data,
              size_t numBytes);
};

namespace Npz {
  uint32_t crc32(const void* data, size_t len);
}

#endif // DATAIO_NPZ_H_
