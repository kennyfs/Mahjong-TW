#!/usr/bin/env python3
"""The stage 1 loop (docs/plan.md 3.4): self-play on this machine, training on a machine with a GPU.

    python loop1.py --run RUN --remote HOST:DIR [--ssh-config FILE] --hands 300000 --threads 32

Two things go on side by side, so that neither machine waits for the other:

- Self-play: iteration i plays --hands hands with the newest network there is (RUN/models/actor_K.onnx, the largest
  K) into RUN/selfplay/iter_i. In a share of the hands some seats are played by `greedy` or by earlier networks.
- Training: each finished iteration, in order, is copied to the remote machine and trained on once
  (python/train1.py there, from the checkpoints of the iteration before); the exported network comes back as
  RUN/models/actor_i.onnx.

Every --eval-every iterations the newest network plays duplicate matches against `greedy` and the first network.

To start: RUN/models/actor_0.onnx here, and DIR/ckpt/actor_0.pt and DIR/ckpt/critic_0.pt on the remote machine
(stage 0's networks). The remote machine needs this repository at DIR/Mahjong-TW with the data library built
(cpp/build/libmahjong_data.so) and Python at --remote-python. Stopping and starting again continues where it was.
"""

import argparse
import glob
import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time


def log(run, text):
    line = f"{time.strftime('%m-%d %H:%M:%S')} {text}"
    print(line, flush=True)
    with open(os.path.join(run, "loop.log"), "a") as f:
        f.write(line + "\n")


def newest_model(run):
    best = -1
    for path in glob.glob(os.path.join(run, "models", "actor_*.onnx")):
        m = re.fullmatch(r"actor_(\d+)\.onnx", os.path.basename(path))
        if m:
            best = max(best, int(m.group(1)))
    return best


class Remote:
    def __init__(self, spec, ssh_config):
        self.host, self.dir = spec.split(":", 1)
        self.ssh = ["ssh"] + (["-F", ssh_config] if ssh_config else []) + ["-o", "ControlMaster=no",
                                                                           "-o", "ControlPath=none"]

    def run(self, command, timeout=120):
        """Runs a short command there; (exit code, output), or (None, "") when the link fails."""
        try:
            r = subprocess.run(self.ssh + [self.host, command], capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            return None, ""
        return (None if r.returncode == 255 else r.returncode), r.stdout

    def rsync(self, src, dst, limit):
        cmd = ["rsync", "-az", "--partial", "-e", " ".join(shlex.quote(x) for x in self.ssh)]
        if limit:
            cmd.append(f"--bwlimit={limit}")
        return subprocess.run(cmd + [src, dst]).returncode == 0

    def until(self, what, action, run):
        """Repeats an action over the link until it succeeds."""
        wait = 10
        while not action():
            log(run, f"{what} failed; again in {wait} s")
            time.sleep(wait)
            wait = min(600, wait * 2)


def selfplay(args, i, stop):
    run = args.run
    out = os.path.join(run, "selfplay", f"iter_{i}")
    if os.path.isdir(out):
        return
    k = newest_model(run)
    tmp = out + ".part"
    subprocess.run(["rm", "-rf", tmp])
    models = os.path.join(run, "models")

    def command(hands, seed, directory, progress):
        cmd = [args.engine, "selfplay", "-m", os.path.join(models, f"actor_{k}.onnx"), "-n", str(hands),
               "-t", str(args.threads), "--slots", str(args.slots), "--marks", str(args.marks), "--forks",
               str(args.forks), "--opponent-rate", str(args.opponent_rate), "-s", seed, "-o", directory,
               "--opponent", "greedy"]
        if progress:
            cmd += ["--progress", progress]
        # Earlier networks: the first one, and those of about 5 and 20 iterations before.
        for j in sorted({0, max(0, k - 5), max(0, k - 20)}):
            if j < k:
                cmd += ["--opponent", "net:model=" + os.path.join(models, f"actor_{j}.onnx")]
        return cmd

    # A helper machine (the same paths, the same engine there) plays half of the hands: the run's networks are
    # copied to it, it plays into a directory of its own, and its files are fetched. Without it (unreachable, or its
    # half fails) the iteration is what was played here.
    hands, helper, helper_dir = args.hands, None, out + ".helper"
    if args.helper:
        ssh = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20", args.helper]
        ready = subprocess.run(ssh + [f"mkdir -p {shlex.quote(models)} && rm -rf {shlex.quote(helper_dir)}"]).returncode == 0
        ready = ready and subprocess.run(["rsync", "-a", models + "/", f"{args.helper}:{models}/"]).returncode == 0
        if ready:
            share = args.hands // 2
            line = " ".join(shlex.quote(x) for x in command(share, f"{args.seed}_{i}h", helper_dir, None))
            helper = subprocess.Popen(ssh + [line], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            hands = args.hands - share
        else:
            log(run, f"the helper {args.helper} cannot be reached: self-play {i} is played here alone")
    log(run, f"self-play {i} with network {k}" + (f" (half of it on {args.helper})" if helper else ""))
    with open(os.path.join(run, "logs", f"selfplay_{i}.log"), "w") as f:
        code = subprocess.run(command(hands, f"{args.seed}_{i}", tmp, os.path.join(run, "selfplay_progress.txt")),
                              stdout=f, stderr=subprocess.STDOUT).returncode
    if code != 0:
        if helper:
            helper.kill()
        raise RuntimeError(f"self-play {i} failed: see logs/selfplay_{i}.log")
    with open(os.path.join(run, "logs", f"selfplay_{i}.log")) as f:
        text = f.read().strip().replace("\n", " | ")
    if helper:
        theirs = helper.communicate()[0]
        got = helper.returncode == 0 and subprocess.run(
            ["rsync", "-a", f"{args.helper}:{helper_dir}/", tmp + "/"]).returncode == 0
        subprocess.run(ssh + [f"rm -rf {shlex.quote(helper_dir)}"])
        with open(os.path.join(run, "logs", f"selfplay_{i}.log"), "a") as f:
            f.write(f"\n== {args.helper}\n{theirs}")
        if got:
            text += f" || {args.helper}: " + theirs.strip().replace("\n", " | ")
        else:
            log(run, f"the half of self-play {i} on {args.helper} is missing: the iteration has {hands} hands")
    os.rename(tmp, out)
    log(run, f"self-play {i} done: " + text)


def evaluate(args, k):
    run = args.run
    done = os.path.join(run, "logs", f"eval_{k}.log")
    if os.path.exists(done) or k <= 0:
        return
    new = "net:model=" + os.path.join(run, "models", f"actor_{k}.onnx")
    first = "net:model=" + os.path.join(run, "models", "actor_0.onnx")
    results = []
    log(run, f"matches of network {k}")
    with open(done + ".part", "w") as f:
        for name, players in (("1 vs 3 greedy", [new, "greedy", "greedy", "greedy"]),
                              ("3 vs 1 greedy", [new, new, new, "greedy"]),
                              ("1 vs 3 first", [new, first, first, first]),
                              ("3 vs 1 first", [new, new, new, first])):
            for rules in args.eval_rules:
                cmd = [args.engine, "match", "-n", str(args.eval_deals), "-t", str(args.threads), "--rules", rules,
                       "-s", f"eval_{rules}"]
                for p in players:
                    cmd += ["-p", p]
                text = subprocess.run(cmd, capture_output=True, text=True).stdout
                f.write(f"== {name}, {rules}\n{text}\n")
                rows = [line.split() for line in text.splitlines() if re.match(r"\d\s", line)]
                if len(rows) == 4:
                    # The newest network's seats against the opponent's: mean result per hand of each side.
                    mine = [float(r[2]) for r in rows if r[1] == new]
                    theirs = [float(r[2]) for r in rows if r[1] != new]
                    se = max(float(r[4]) for r in rows)
                    results.append({"iter": k, "match": name, "rules": rules, "new": sum(mine) / len(mine),
                                    "other": sum(theirs) / len(theirs), "se": se})
    os.rename(done + ".part", done)
    with open(os.path.join(run, "eval.jsonl"), "a") as f:
        for r in results:
            f.write(json.dumps(r) + "\n")
    log(run, f"network {k}: " + "; ".join(f"{r['match']} {r['rules']} {r['new']:+.3f}" for r in results))


def selfplay_loop(args, stop):
    try:
        i = 1
        while os.path.isdir(os.path.join(args.run, "selfplay", f"iter_{i}")):
            i += 1
        while not stop.is_set() and i <= args.iterations:
            k = newest_model(args.run)
            if args.eval_every and k > 0 and k % args.eval_every == 0:
                evaluate(args, k)
            # Self-play runs at most --ahead iterations ahead of the newest network.
            if i - k > args.ahead:
                time.sleep(10)
                continue
            selfplay(args, i, stop)
            i += 1
    except Exception as e:
        log(args.run, f"self-play loop stopped: {e}")
        stop.set()


def train_loop(args, remote, stop):
    run = args.run
    try:
        while not stop.is_set():
            i = newest_model(run) + 1
            if i > args.iterations:
                return
            data = os.path.join(run, "selfplay", f"iter_{i}")
            if not os.path.isdir(data):
                time.sleep(5)
                continue
            rdir = remote.dir
            remote.until(f"copying iteration {i}", lambda: remote.rsync(
                data, f"{remote.host}:{rdir}/selfplay/", args.bwlimit), run)
            name = f"train_{i}"
            command = (
                f"cd {rdir}/Mahjong-TW && {args.remote_python} python/train1.py --data {rdir}/selfplay/iter_{i} "
                f"--actor-init {rdir}/ckpt/actor_{i - 1}.pt --critic-init {rdir}/ckpt/critic_{i - 1}.pt "
                f"--actor-out {rdir}/ckpt/actor_{i}.pt --critic-out {rdir}/ckpt/critic_{i}.pt "
                f"--export {rdir}/models/actor_{i}.onnx --log-file {rdir}/train.jsonl --amp {args.train_args}")
            # Started detached (its own session; the pause lets it get there before this shell ends), so that a
            # broken link does not stop it; its exit code lands in a file. Not started twice: the pattern finds the
            # trainer itself (a command line that begins with the Python), not the shells that carry its text.
            running = f"^[^ ]*python[^ ]* python/train1.py .*ckpt/actor_{i}.pt"
            launch = (
                f"mkdir -p {rdir}/status {rdir}/logs {rdir}/models {rdir}/ckpt; cd {rdir}; "
                f"if [ ! -e status/{name}.code ] && ! pgrep -f {shlex.quote(running)} > /dev/null; then "
                f"setsid nohup bash -c {shlex.quote(command + f' > {rdir}/logs/{name}.log 2>&1; echo $? > {rdir}/status/{name}.code')} "
                f"> /dev/null 2>&1 < /dev/null & sleep 1; fi; echo ok")
            remote.until(f"starting training {i}", lambda: remote.run(launch)[0] == 0, run)
            log(run, f"training {i} started")
            code = None
            while code is None and not stop.is_set():
                time.sleep(20)
                rc, out = remote.run(f"cat {rdir}/status/{name}.code 2>/dev/null")
                if rc == 0 and out.strip():
                    code = int(out.strip())
            if code != 0:
                raise RuntimeError(f"training {i} failed on the remote machine: see {rdir}/logs/{name}.log")
            os.makedirs(os.path.join(run, "models"), exist_ok=True)
            tmp = os.path.join(run, "models", f"actor_{i}.onnx.part")
            remote.until(f"fetching network {i}", lambda: remote.rsync(
                f"{remote.host}:{rdir}/models/actor_{i}.onnx", tmp, 0), run)
            remote.until("fetching the training log", lambda: remote.rsync(
                f"{remote.host}:{rdir}/train.jsonl", os.path.join(run, "train.jsonl"), 0), run)
            os.rename(tmp, os.path.join(run, "models", f"actor_{i}.onnx"))
            held = ""
            try:
                with open(os.path.join(run, "train.jsonl")) as f:
                    last = [json.loads(line) for line in f if '"held"' in line][-1]
                keys = ("kl", "entropy", "improve", "left", "c_q_mse", "q_mse", "critic_explains", "fork_explains")
                held = " ".join(f"{k} {last[k]:.4g}" for k in keys if k in last)
            except (OSError, IndexError, ValueError):
                pass
            log(run, f"network {i} is here. {held}")
    except Exception as e:
        log(run, f"training loop stopped: {e}")
        stop.set()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--run", required=True, help="The run's directory here")
    ap.add_argument("--role", default="both", choices=("both", "selfplay"),
                    help="selfplay: only play, into RUN; the training machine fetches the hands and sends the "
                         "networks itself (python/train_loop.py there)")
    ap.add_argument("--remote", help="HOST:DIR of the training machine (--role both)")
    ap.add_argument("--ssh-config", help="ssh configuration file that knows HOST")
    ap.add_argument("--remote-python", default="python3")
    ap.add_argument("--engine", default=os.path.join(here, "..", "cpp", "build", "mahjong"))
    ap.add_argument("--iterations", type=int, default=1000000, help="Stop after this iteration")
    ap.add_argument("--hands", type=int, default=100000, help="Hands per iteration")
    ap.add_argument("--threads", type=int, default=32)
    ap.add_argument("--slots", type=int, default=8,
                    help="Hands a thread plays at once; with 32 threads, small batches are faster (caches)")
    ap.add_argument("--marks", type=int, default=8)
    ap.add_argument("--forks", type=float, default=1.5)
    ap.add_argument("--opponent-rate", type=float, default=0.2)
    ap.add_argument("--helper", help="A second machine (ssh HOST, with this run's paths and the engine at the same "
                                     "place) that plays half of every iteration's hands")
    ap.add_argument("--ahead", type=int, default=2, help="Iterations self-play may be ahead of the newest network")
    ap.add_argument("--bwlimit", type=int, default=0, help="Limit of the copies to the remote machine, KiB/s")
    ap.add_argument("--train-args", default="", help="More arguments for train1.py")
    ap.add_argument("--eval-every", type=int, default=5)
    ap.add_argument("--eval-deals", type=int, default=1000)
    ap.add_argument("--eval-rules", nargs="+", default=["simple1,none", "simple2,see"])
    ap.add_argument("--seed", default="s1")
    args = ap.parse_args()

    for sub in ("models", "selfplay", "logs"):
        os.makedirs(os.path.join(args.run, sub), exist_ok=True)
    if newest_model(args.run) < 0:
        raise SystemExit(f"Put the first network at {args.run}/models/actor_0.onnx")
    stop = threading.Event()
    threads = [threading.Thread(target=selfplay_loop, args=(args, stop))]
    if args.role == "both":
        if not args.remote:
            raise SystemExit("--role both needs --remote")
        threads.append(threading.Thread(target=train_loop, args=(args, Remote(args.remote, args.ssh_config), stop)))
    for t in threads:
        t.start()
    try:
        for t in threads:
            while t.is_alive():
                t.join(timeout=1.0)
    except KeyboardInterrupt:
        stop.set()
        log(args.run, "stopping after the running steps")
    return 1 if stop.is_set() else 0


if __name__ == "__main__":
    sys.exit(main())
