#!/bin/bash
# The self-play side of the mahjong runs on this machine (the self-play machine).
#
#   mahjong.sh start [RUN]    start the run's self-play loop, detached; does nothing when it already runs
#   mahjong.sh stop [RUN]     stop it (without RUN: every run here)
#   mahjong.sh status [RUN]   the cycle, how far the self-play is, the last log lines of both sides
#
# RUN defaults to the name in bin/current_run. A run RUN has its script bin/run_selfplay_RUN.sh and its directory
# mahjong_remote/share/RUN. Stopping finds processes by that directory's full path in their command lines (the loop
# and the engine it started), so nothing else on this machine is touched.
B=$HOME; BIN=$B/mahjong-files/bin; S=$B/mahjong_remote/share
cmd=$1
run=${2:-$(cat $BIN/current_run 2>/dev/null)}

descendants() { local c; for c in $(pgrep -P "$1"); do echo "$c"; descendants "$c"; done; }

pids_of() {
  {
    pgrep -f "^[^ ]*python[^ ]* [^ ]*loop1\.py --role selfplay --run $S/$1( |\$)"
    pgrep -f "^bash $BIN/run_selfplay_$1\.sh"
    pgrep -f "cpp/build/mahjong (selfplay|match) .*$S/$1/"
  } | sort -u
}

runs() { for f in $BIN/run_selfplay_*.sh; do [ -f "$f" ] && basename "$f" .sh | sed 's/^run_selfplay_//'; done; }

start_run() {
  [ -f $BIN/run_selfplay_$1.sh ] || { echo "$1: there is no $BIN/run_selfplay_$1.sh"; return 1; }
  if [ -n "$(pids_of $1)" ]; then echo "$1: already running"; return 0; fi
  setsid nohup bash $BIN/run_selfplay_$1.sh >> $B/mahjong-files/logs/loop1_$1.out 2>&1 < /dev/null &
  sleep 2
  if [ -n "$(pids_of $1)" ]; then echo "$1: started (log: $S/$1/loop.log)"; else echo "$1: did not start; see $B/mahjong-files/logs/loop1_$1.out"; return 1; fi
}

stop_run() {
  local pids all n i
  pids=$(pids_of $1)
  if [ -z "$pids" ]; then echo "$1: not running"; return 0; fi
  all=$(for p in $pids; do echo $p; descendants $p; done | sort -u)
  n=$(echo "$all" | wc -l)
  kill -TERM $all 2>/dev/null
  for i in $(seq 1 30); do [ -z "$(pids_of $1)" ] && break; sleep 0.5; done
  if [ -n "$(pids_of $1)" ]; then kill -KILL $(pids_of $1) $all 2>/dev/null; sleep 1; fi
  if [ -z "$(pids_of $1)" ]; then echo "$1: stopped ($n processes)"; else echo "$1: still running: $(pids_of $1 | tr '\n' ' ')"; return 1; fi
}

# The helper machine of a run (--helper HOST in its script), when this is not that machine.
helper_of() {
  local h=$(sed -n 's/.*--helper \([A-Za-z0-9_.-]*\).*/\1/p' $BIN/run_selfplay_$1.sh 2>/dev/null | head -n 1)
  [ -n "$h" ] && [ "${h%%.*}" != "$(hostname -s)" ] && echo "$h"
}

age() {
  local t now s
  t=$(date -d "$(date +%Y)-${1:0:5} ${1:6:8}" +%s 2>/dev/null) || return
  now=$(date +%s); s=$((now - t))
  if [ $s -lt 120 ]; then echo "${s} s ago"; elif [ $s -lt 7200 ]; then echo "$((s / 60)) min ago"; else echo "$((s / 3600)) h ago"; fi
}

status_run() {
  local r=$1 R=$S/$1 pids newest line played h
  pids=$(pgrep -f "^[^ ]*python[^ ]* [^ ]*loop1\.py --role selfplay --run $R( |\$)" | tr '\n' ' ')
  if [ -n "$pids" ]; then echo "run $r: RUNNING (pid $pids)"; else echo "run $r: NOT RUNNING"; fi
  newest=$(ls $R/models 2>/dev/null | sed -n 's/^actor_\([0-9]*\)\.onnx$/\1/p' | sort -n | tail -n 1)
  played=$(ls $R/selfplay 2>/dev/null | sed -n 's/^iter_\([0-9]*\)$/\1/p' | sort -n | tail -n 1)
  echo "cycle: newest network ${newest:-none}; self-play finished through iteration ${played:-none}"
  line=$(tail -n 1 $R/loop.log 2>/dev/null)
  [ -n "$line" ] && echo "last log: ${line:0:110}  ($(age "$line"))"
  if [ -z "$pids" ]; then
    echo "now: nothing (stopped)"
  elif pgrep -f "cpp/build/mahjong selfplay .*$R/" > /dev/null && [ -f $R/selfplay_progress.txt ]; then
    read seed done total secs < $R/selfplay_progress.txt
    awk -v sd=$seed -v d=$done -v t=$total -v s=$secs 'BEGIN { sub(/^.*_/, "", sd); p = t > 0 ? 100 * d / t : 0; eta = d > 0 ? s * (t - d) / d : -1;
      printf "now: SELF-PLAY iteration %s: %.0f%% (%d of %d hands, %d s so far", sd, p, d, t, s; if (eta >= 0) printf ", about %d s left", eta; print ")" }'
    h=$(helper_of $r); [ -n "$h" ] && echo "     (the hands played here; $h plays as many at the same time)"
  elif pgrep -f "cpp/build/mahjong selfplay .*$R/" > /dev/null; then
    echo "now: SELF-PLAY of iteration $((played + 1)) (this run's engine writes no progress file)"
  elif pgrep -f "cpp/build/mahjong match .*$R/" > /dev/null; then
    local m=$(grep -c '^==' $R/logs/eval_$newest.log.part 2>/dev/null)
    echo "now: MATCHES of network $newest (${m:-0} of 8 started)"
  else
    echo "now: WAITING for network $((newest + 1)) from the training machine (self-play may be 2 iterations ahead)"
  fi
  line=$(tail -n 1 $R/train_loop.log 2>/dev/null)
  [ -n "$line" ] && echo "training side, as last sent here: $line  ($(age "$line"))"
  echo "recent log:"
  tail -n 4 $R/loop.log 2>/dev/null | cut -c1-150 | sed 's/^/  /'
  if [ -f $R/eval.jsonl ]; then
    echo "last matches (the new network's mean result per hand):"
    python3 - $R/eval.jsonl <<'P'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1])]
if rows:
    last = rows[-1]["iter"]
    print("  network %d: " % last + "; ".join("%s %s %+.3f" % (r["match"], r["rules"], r["new"]) for r in rows if r["iter"] == last))
P
  fi
}

case "$cmd" in
start)
  [ -n "$run" ] || { echo "Which run? (none named, and no $BIN/current_run)"; exit 1; }
  start_run $run ;;
stop)
  # The helper's engine goes too (it would only finish the half it is playing).
  for r in ${2:-$(runs)}; do
    stop_run $r
    h=$(helper_of $r)
    [ -n "$h" ] && ssh -o BatchMode=yes -o ConnectTimeout=10 $h "bash $BIN/mahjong.sh stop $r" 2>&1 | sed "s/^/$h: /"
  done ;;
status)
  [ -n "$run" ] || { echo "Which run? (none named, and no $BIN/current_run)"; exit 1; }
  status_run $run
  for r in $(runs); do [ "$r" != "$run" ] && [ -n "$(pids_of $r)" ] && echo "(also running: $r)"; done
  true ;;
*)
  echo "usage: $0 start|stop|status [RUN]   (runs here: $(runs | tr '\n' ' '))" ;;
esac
