#include "../command/commands.h"

#include "../command/guiengine.h"

#include <iostream>

using namespace std;

int Commands::gui(vector<string>) {
  GuiEngine engine;
  string line;
  while(getline(cin, line)) {
    if(line.empty())
      continue;
    cout << engine.handleLine(line) << endl;
  }
  return 0;
}
