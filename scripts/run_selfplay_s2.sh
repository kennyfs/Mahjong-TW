#!/bin/bash
# The self-play side of the run s2 (features version 2), on one workstation with another playing half of every iteration; started by
# mahjong.sh. Forks are a tenth of the evaluations (0.2 a hand; 1.5 a hand were 45%). With both machines the hands
# come faster than the GPU trains on them, so 8 decisions a hand are marked, not 12. Matches every 20 networks (they
# take the self-play machine about ten minutes).
B=$HOME
cd $B/Mahjong-TW-v2 || exit 1
exec $B/mahjong-files/venv/bin/python python/loop1.py --role selfplay --run $B/mahjong_remote/share/s2 \
  --hands 100000 --threads 32 --slots 8 --marks 8 --forks 0.2 --eval-every 20 --eval-deals 4000 --seed s2 --helper helper
