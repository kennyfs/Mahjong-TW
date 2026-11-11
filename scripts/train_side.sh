#!/bin/bash
# The training side of the mahjong runs on this machine (installed as $HOME/mahjong_runs/mahjong.sh).
#
#   mahjong.sh start [RUN]    start the run's training loop, detached; does nothing when it already runs
#   mahjong.sh stop [RUN]     stop it (without RUN: every run here)
#   mahjong.sh status [RUN]   what it is doing: the cycle, how far the training or the self-play is, the last log lines
#
# RUN defaults to the name in $HOME/mahjong_runs/current_run. A run RUN has its script $HOME/mahjong_runs/run_RUN.sh and
# its directory $HOME/mahjong_runs/RUN. Stopping finds processes by this directory's full path in their command lines
# (the loop, its trainer, and what they started), so no other Python or training job on this machine is touched.
D=$HOME/mahjong_runs
REMOTE=selfplay
KEY=$HOME/.ssh/mahjong_sync
cmd=$1
run=${2:-$(cat $D/current_run 2>/dev/null)}

descendants() { local c; for c in $(pgrep -P "$1"); do echo "$c"; descendants "$c"; done; }

# The processes of a run: its loop (or the script that becomes it) and its trainer.
pids_of() {
  {
    pgrep -f "^[^ ]*python[^ ]* [^ ]*python/train_loop\.py --run $D/$1( |\$)"
    pgrep -f "^[^ ]*python[^ ]* [^ ]*python/train1\.py --data $D/$1/"
    pgrep -f "^bash $D/run_$1\.sh"
  } | sort -u
}

runs() { for f in $D/run_*.sh; do [ -f "$f" ] && basename "$f" .sh | sed 's/^run_//'; done; }

start_run() {
  [ -f $D/run_$1.sh ] || { echo "$1: there is no $D/run_$1.sh"; return 1; }
  if [ -n "$(pids_of $1)" ]; then echo "$1: already running"; return 0; fi
  [ -f $KEY ] || { echo "No key at $KEY"; return 1; }
  mkdir -p $D/logs
  setsid nohup bash $D/run_$1.sh >> $D/logs/train_loop_$1.out 2>&1 < /dev/null &
  sleep 2
  if [ -n "$(pids_of $1)" ]; then echo "$1: started (log: $D/$1/train_loop.log)"; else echo "$1: did not start; see $D/logs/train_loop_$1.out"; return 1; fi
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

# "MM-DD HH:MM:SS ..." -> how long ago
age() {
  local t now
  t=$(date -d "$(date +%Y)-${1:0:5} ${1:6:8}" +%s 2>/dev/null) || return
  now=$(date +%s)
  local s=$((now - t))
  if [ $s -lt 120 ]; then echo "${s} s ago"; elif [ $s -lt 7200 ]; then echo "$((s / 60)) min ago"; else echo "$((s / 3600)) h ago"; fi
}

status_run() {
  local r=$1 R=$D/$1 pids newest last line
  pids=$(pids_of $r | tr '\n' ' ')
  if [ -n "$pids" ]; then echo "run $r: RUNNING (pid $pids)"; else echo "run $r: NOT RUNNING"; fi
  newest=$(ls $R/models 2>/dev/null | sed -n 's/^actor_\([0-9]*\)\.onnx$/\1/p' | sort -n | tail -n 1)
  echo "cycle: newest network ${newest:-none}"
  last=$(tail -n 1 $R/train_loop.log 2>/dev/null)
  [ -n "$last" ] && echo "last log: $last  ($(age "$last"))"
  # What it is doing now.
  local part=$(ls -d $R/selfplay/iter_*.part 2>/dev/null | head -n 1)
  if [ -n "$(pgrep -f "^[^ ]*python[^ ]* [^ ]*python/train1\.py --data $R/")" ]; then
    if [ -f $R/train_progress.txt ]; then
      read done total secs < $R/train_progress.txt
      awk -v d=$done -v t=$total -v s=$secs -v i=$((newest + 1)) 'BEGIN { p = t > 0 ? 100 * d / t : 0; eta = d > 0 ? s * (t - d) / d : -1;
        printf "now: TRAINING iteration %d: %.0f%% (step %d of %d, %d s so far", i, p, d, t, s; if (eta >= 0) printf ", about %d s left", eta; print ")" }'
    else
      echo "now: TRAINING iteration $((newest + 1)) (starting)"
    fi
  elif [ -n "$part" ] && [ -n "$pids" ]; then
    local have want
    have=$(du -sb $part 2>/dev/null | cut -f1)
    want=$(timeout 20 rsync --list-only -r -e "ssh -i $KEY -o BatchMode=yes -o ConnectTimeout=8" $REMOTE:$r/selfplay/$(basename $part .part)/ 2>/dev/null | awk '{ gsub(",", "", $2); s += $2 } END { print s + 0 }')
    awk -v h=${have:-0} -v w=${want:-0} -v n=$(basename $part .part) 'BEGIN { printf "now: FETCHING %s: ", n; if (w > 0) printf "%.0f%% (%.1f of %.1f MB)\n", 100 * h / w, h / 1e6, w / 1e6; else printf "%.1f MB so far\n", h / 1e6 }'
  else
    [ -n "$pids" ] && echo "now: WAITING for iteration $((newest + 1)) from the self-play machine" || echo "now: nothing (stopped)"
  fi
  # The self-play side, as far as it can be seen from here.
  mkdir -p $R/remote
  rm -f $R/remote/selfplay_progress.txt
  if timeout 25 rsync -q -e "ssh -i $KEY -o BatchMode=yes -o ConnectTimeout=8" $REMOTE:$r/loop.log $R/remote/ 2>/dev/null; then
    timeout 15 rsync -q -e "ssh -i $KEY -o BatchMode=yes -o ConnectTimeout=8" $REMOTE:$r/selfplay_progress.txt $R/remote/ 2>/dev/null
    line=$(tail -n 1 $R/remote/loop.log 2>/dev/null)
    echo "self-play ($REMOTE): ${line:0:110}  ($(age "$line"))"
    if echo "$line" | grep -q "self-play [0-9]* with network" && [ -f $R/remote/selfplay_progress.txt ]; then
      read seed done total secs < $R/remote/selfplay_progress.txt
      awk -v sd=$seed -v d=$done -v t=$total -v s=$secs 'BEGIN { sub(/^.*_/, "", sd); p = t > 0 ? 100 * d / t : 0; eta = d > 0 ? s * (t - d) / d : -1;
        printf "self-play: iteration %s: %.0f%% (%d of %d hands, %d s so far", sd, p, d, t, s; if (eta >= 0) printf ", about %d s left", eta; print ")" }'
    elif echo "$line" | grep -q "self-play [0-9]* with network"; then
      echo "self-play: an iteration is under way (this run's engine writes no progress file)"
    elif echo "$line" | grep -q "matches of network"; then
      echo "self-play: playing the matches of a network (no self-play meanwhile)"
    else
      echo "self-play: idle (waiting for a network, or stopped)"
    fi
  else
    echo "self-play ($REMOTE): cannot be reached from here now"
  fi
  echo "recent log:"
  tail -n 5 $R/train_loop.log 2>/dev/null | sed 's/^/  /'
  if [ -f $R/train.jsonl ]; then
    echo "last training result (held-out):"
    grep '"held"' $R/train.jsonl | tail -n 1 | python3 -c '
import json, sys
for l in sys.stdin:
    r = json.loads(l)
    keys = ("kl", "entropy", "improve", "critic_explains", "fork_slope", "c_q_mse")
    print("  " + ", ".join("%s %.4g" % (k, r[k]) for k in keys if k in r))' 2>/dev/null
  fi
  nvidia-smi --query-gpu=utilization.gpu,memory.used,temperature.gpu --format=csv,noheader 2>/dev/null | sed 's/^/GPU: /'
}

case "$cmd" in
start)
  [ -n "$run" ] || { echo "Which run? (none named, and no $D/current_run)"; exit 1; }
  start_run $run ;;
stop)
  if [ -n "$2" ]; then stop_run $2; else for r in $(runs); do stop_run $r; done; fi ;;
status)
  [ -n "$run" ] || { echo "Which run? (none named, and no $D/current_run)"; exit 1; }
  status_run $run
  for r in $(runs); do [ "$r" != "$run" ] && [ -n "$(pids_of $r)" ] && echo "(also running: $r)"; done
  true ;;
*)
  echo "usage: $0 start|stop|status [RUN]   (runs here: $(runs | tr '\n' ' '))" ;;
esac
