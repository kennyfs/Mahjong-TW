#!/bin/bash
# The self-play side of the run s2 (features version 2), on one workstation with another playing half of every iteration; started by
# mahjong.sh. Forks are a tenth of the evaluations (0.17 a hand: a fork costs about 31 evaluations and a hand without one 47; 1.5 a hand were 45%). With both machines the hands
# come faster than the training machine reads them (about 3,700 samples a second), so 6 decisions a hand are marked,
# not 12: an iteration's 600,000 samples take it about as long as the next iteration takes to play. Matches every 20
# networks (they take the self-play machine about ten minutes).
B=$HOME
cd $B/Mahjong-TW-v2 || exit 1
exec $B/mahjong-files/venv/bin/python python/loop1.py --role selfplay --run $B/mahjong_remote/share/s2 \
  --hands 100000 --threads 32 --slots 8 --marks 6 --forks 0.17 --eval-every 20 --eval-deals 4000 --seed s2 --helper helper
