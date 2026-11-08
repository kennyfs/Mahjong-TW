#include "command/commands.h"
#include "core/global.h"

#include <iostream>

using namespace std;

static void printHelp(const string& program) {
  cout << "Usage: " << program << " SUBCOMMAND [ARGS]" << endl;
  cout << endl;
  cout << "Subcommands:" << endl;
  cout << "  runtests   Run the unit tests." << endl;
  cout << "  match      Play duplicate deals between four players and report their results." << endl;
  cout << "  show       Play one hand and print it." << endl;
  cout << "  tai        Score a winning hand." << endl;
  cout << "  benchmark  Time the building blocks." << endl;
  cout << "  gui        The GUIs' engine: JSON requests on stdin, answers on stdout (cpp/command/guiengine.h)." << endl;
  cout << endl;
  cout << "Run '" << program << " SUBCOMMAND -h' for a subcommand's arguments." << endl;
}

int main(int argc, const char* argv[]) {
  vector<string> args(argv, argv + argc);
  if(args.size() < 2 || args[1] == "-h" || args[1] == "--help" || args[1] == "help") {
    printHelp(args[0]);
    return args.size() < 2 ? 1 : 0;
  }
  string sub = args[1];
  // TCLAP sees "program subcommand" as the program name.
  vector<string> subArgs;
  subArgs.push_back(args[0] + " " + sub);
  subArgs.insert(subArgs.end(), args.begin() + 2, args.end());
  try {
    if(sub == "runtests")
      return Commands::runTests(subArgs);
    if(sub == "match")
      return Commands::match(subArgs);
    if(sub == "show")
      return Commands::show(subArgs);
    if(sub == "tai")
      return Commands::tai(subArgs);
    if(sub == "benchmark")
      return Commands::benchmark(subArgs);
    if(sub == "gui")
      return Commands::gui(subArgs);
  }
  catch(const StringError& e) {
    cerr << "Error: " << e.what() << endl;
    return 1;
  }
  cerr << "Unknown subcommand: " << sub << endl;
  printHelp(args[0]);
  return 1;
}
