#!/bin/bash
# The self-play side of the run s3 (features version 3: 報聽), on one workstation with another playing half of every iteration;
# started by mahjong.sh. It goes on from s2's network 211 (scripts/migrate_v3_desk.sh) to teach the network when to
# declare 報聽 (on in every preset since 2026-11-13). As s2 at its end: 6 marks and 1.5 forks a hand (the forks give
# the critic the outcome of the action not played, here whether to declare). No matches in the loop (a short run;
# the networks are compared afterwards).
B=$HOME
cd $B/Mahjong-TW-v3 || exit 1
exec $B/mahjong-files/venv/bin/python python/loop1.py --role selfplay --run $B/mahjong_remote/share/s3 \
  --hands 100000 --threads 32 --slots 8 --marks 6 --forks 1.5 --eval-every 100000 --seed s3 --helper helper
