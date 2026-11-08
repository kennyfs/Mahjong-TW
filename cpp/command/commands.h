#ifndef COMMAND_COMMANDS_H_
#define COMMAND_COMMANDS_H_

#include <string>
#include <vector>

namespace Commands {
  int runTests(std::vector<std::string> args);
  int match(std::vector<std::string> args);
  int show(std::vector<std::string> args);
  int tai(std::vector<std::string> args);
  int benchmark(std::vector<std::string> args);
}

#endif // COMMAND_COMMANDS_H_
