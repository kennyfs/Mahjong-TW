#include "../command/commands.h"

#include "../tests/tests.h"

using namespace std;

int Commands::runTests(vector<string>) {
  Tests::runAll();
  return 0;
}
