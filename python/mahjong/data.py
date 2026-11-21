"""Hand records written by the C++ engine, and the batches the trainers read.

A data file (.npz, cpp/learn/datafile.h) holds hands as compact records (rules, setup, wall, actions and the
decisions marked for training: about 0.6 KB a hand):

    hands    uint8  [bytes]   the records, one after another
    offsets  int64  [N + 1]   where each begins
    rules    float32 [R, P]   the rules of the file's hands, each set once
    marks    int32  [M, 2]    every marked decision: its hand and its mark in that hand
    version  int32  [1]       Features::VERSION

Like KataGo's packed rows, hands stay in this form in memory, and a batch is expanded when it is drawn: the C++
library (libmahjong_data.so, cpp/learn/learn_c.cpp) replays each hand to the marked decision and writes the inputs
of both networks, what is known about every action, and the labels of the heads (cpp/learn/expand.h). All sizes come
from the library, so the Python side never repeats the feature layout.
"""

import ctypes
import glob
import os
import queue
import threading

import numpy as np
import torch

DEFAULT_LIB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "cpp", "build",
                           "libmahjong_data.so")
NUM_KINDS = 34
NUM_SEATS = 4
SIZE_NAMES = ("version", "actions", "kind_obs", "seat_obs", "global_obs", "kind_full", "seat_full", "global_full",
              "events", "info", "future", "tai_items", "rule_params", "rule_features", "go_rules", "go_actions",
              "ga_size")


class _MjBatch(ctypes.Structure):
    _fields_ = [(name, ctypes.c_void_p) for name in (
        "kind", "seat", "global_", "kind_full", "seat_full", "global_full", "legal", "played", "mu", "known",
        "events", "tai", "coef", "info", "opp_hand", "danger", "danger_tai", "opp_shanten", "next_chow", "win_tile",
        "tai_items", "future", "final_hand", "next_discard", "greedy")]


class Library:
    """libmahjong_data.so: the sizes of this build, and the expansion of samples."""

    def __init__(self, path=DEFAULT_LIB, threads=4):
        self.lib = ctypes.CDLL(os.path.abspath(path))
        sizes = (ctypes.c_int32 * len(SIZE_NAMES))()
        self.lib.mj_sizes(sizes)
        self.sizes = dict(zip(SIZE_NAMES, (int(x) for x in sizes)))
        s = self.sizes
        bits = (ctypes.c_int32 * s["events"])()
        winners = (ctypes.c_int32 * s["events"])()
        self.lib.mj_event_tables(bits, winners)
        self.event_bits = [int(x) for x in bits]
        self.event_winners = [int(x) for x in winners]
        self.lib.mj_expand.restype = ctypes.c_int
        self.lib.mj_expand.argtypes = [
            ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int64, ctypes.c_void_p,
            ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(_MjBatch), ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
        if hasattr(self.lib, "mj_full_reshuffled"):
            self.lib.mj_full_reshuffled.restype = ctypes.c_int
            self.lib.mj_full_reshuffled.argtypes = [
                ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int64, ctypes.c_void_p,
                ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_uint64, ctypes.c_void_p, ctypes.c_void_p,
                ctypes.c_void_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
        self.threads = threads
        a = s["actions"]
        # Name, dtype and row shape of every array of a batch, in the order of MjBatch.
        self.layout = (
            ("kind", np.float32, (NUM_KINDS, s["kind_obs"])),
            ("seat", np.float32, (NUM_SEATS, s["seat_obs"])),
            ("global", np.float32, (s["global_obs"],)),
            ("kind_full", np.float32, (NUM_KINDS, s["kind_full"])),
            ("seat_full", np.float32, (NUM_SEATS, s["seat_full"])),
            ("global_full", np.float32, (s["global_full"],)),
            ("legal", np.uint8, (a,)),
            ("played", np.int32, ()),
            ("mu", np.float32, (a,)),
            ("known", np.uint8, (a,)),
            ("events", np.uint16, (a,)),
            ("tai", np.uint8, (a, 4)),
            ("coef", np.float32, (2, s["events"])),
            ("info", np.float32, (s["info"],)),
            ("opp_hand", np.uint8, (3, NUM_KINDS)),
            ("danger", np.uint8, (3, NUM_KINDS)),
            ("danger_tai", np.uint8, (3, NUM_KINDS)),
            ("opp_shanten", np.uint8, (3,)),
            ("next_chow", np.uint8, (NUM_KINDS,)),
            ("win_tile", np.int8, (4,)),
            ("tai_items", np.uint8, (s["tai_items"],)),
            ("future", np.uint8, (s["future"],)),
            ("final_hand", np.uint8, (NUM_KINDS,)),
            ("next_discard", np.int8, (3,)),
            ("greedy", np.int32, ()),
        )

    def expand(self, hands, offsets, rules, hand_idx, mark_idx, sym_seeds=None):
        """Samples (hand_idx[i], mark_idx[i]) of a set of hands -> a dict of numpy arrays [n, ...]."""
        n = len(hand_idx)
        hand_idx = np.ascontiguousarray(hand_idx, np.int32)
        mark_idx = np.ascontiguousarray(mark_idx, np.int32)
        if sym_seeds is not None:
            sym_seeds = np.ascontiguousarray(sym_seeds, np.uint64)
        out = {name: np.empty((n,) + shape, dtype) for name, dtype, shape in self.layout}
        batch = _MjBatch(*(out[name].ctypes.data for name, _, _ in self.layout))
        error = ctypes.create_string_buffer(256)
        code = self.lib.mj_expand(
            hands.ctypes.data, offsets.ctypes.data, rules.ctypes.data, rules.shape[1], n, hand_idx.ctypes.data,
            mark_idx.ctypes.data, sym_seeds.ctypes.data if sym_seeds is not None else None, ctypes.byref(batch),
            self.threads, error, len(error))
        if code != 0:
            raise ValueError("mj_expand: " + error.value.decode(errors="replace"))
        return out


def full_reshuffled(library, hands, offsets, rules, hand_idx, mark_idx, sym_seeds, k, seed):
    """The critic's extra inputs of the same samples as Library.expand (same symmetries), k times with the undrawn
    tiles reshuffled -> kind_full_k [n, k, 34, KIND_FULL], seat_full_k [n, k, 4, SEAT_FULL], global_full_k."""
    s = library.sizes
    n = len(hand_idx)
    hand_idx = np.ascontiguousarray(hand_idx, np.int32)
    mark_idx = np.ascontiguousarray(mark_idx, np.int32)
    if sym_seeds is not None:
        sym_seeds = np.ascontiguousarray(sym_seeds, np.uint64)
    out = {"kind_full_k": np.empty((n, k, NUM_KINDS, s["kind_full"]), np.float32),
           "seat_full_k": np.empty((n, k, NUM_SEATS, s["seat_full"]), np.float32),
           "global_full_k": np.empty((n, k, s["global_full"]), np.float32)}
    error = ctypes.create_string_buffer(256)
    code = library.lib.mj_full_reshuffled(
        hands.ctypes.data, offsets.ctypes.data, rules.ctypes.data, rules.shape[1], n, hand_idx.ctypes.data,
        mark_idx.ctypes.data, sym_seeds.ctypes.data if sym_seeds is not None else None, k, seed,
        out["kind_full_k"].ctypes.data, out["seat_full_k"].ctypes.data, out["global_full_k"].ctypes.data,
        library.threads, error, len(error))
    if code != 0:
        raise ValueError("mj_full_reshuffled: " + error.value.decode(errors="replace"))
    return out


class Hands:
    """The hands of some data files, as one set: records, offsets, rules and marks."""

    def __init__(self, paths, version=None):
        hands, offsets, rules, marks = [], [], [], []
        num_bytes = num_hands = num_rules = 0
        for path in paths:
            with np.load(path) as d:
                if version is not None and int(d["version"][0]) != version:
                    raise ValueError(f"{path}: features version {int(d['version'][0])}, the library has {version}")
                h = d["hands"].astype(np.uint8, copy=True)
                o = d["offsets"].astype(np.int64)
                r = d["rules"].astype(np.float32)
                m = d["marks"].astype(np.int32).reshape(-1, 2)
            # A record begins with the index of its rules in its file (u32, little endian): moved to the joint table.
            starts = o[:-1]
            index = sum(h[starts + i].astype(np.int64) << (8 * i) for i in range(4)) + num_rules
            for i in range(4):
                h[starts + i] = (index >> (8 * i)) & 0xFF
            hands.append(h)
            offsets.append(o[:-1] + num_bytes)
            rules.append(r)
            marks.append(m + np.array([num_hands, 0], np.int32))
            num_bytes += len(h)
            num_hands += len(o) - 1
            num_rules += len(r)
        self.hands = np.concatenate(hands) if hands else np.zeros(0, np.uint8)
        self.offsets = np.concatenate(offsets + [np.array([num_bytes], np.int64)])
        self.rules = np.ascontiguousarray(np.concatenate(rules)) if rules else np.zeros((0, 1), np.float32)
        self.marks = np.concatenate(marks) if marks else np.zeros((0, 2), np.int32)
        self.num_hands = num_hands


def list_files(dirs):
    """All .npz files under the given directories (or the files themselves), by name."""
    files = []
    for d in dirs:
        if os.path.isfile(d):
            files.append(d)
        else:
            files.extend(glob.glob(os.path.join(d, "**", "*.npz"), recursive=True))
    return sorted(files)


def held_out(marks, every=100):
    """One hand in `every` is kept from training (by its number in its group of files)."""
    return marks[:, 0] % every == every - 1


def to_torch(batch, device):
    return {k: torch.from_numpy(v).to(device, non_blocking=True) for k, v in batch.items()}


def batches(library, files, batch_size, rng=None, part="train", group=16, symmetries=True, passes=1,
            drop_last=True, shuffle=True, wall_k=0):
    """Batches of the marked decisions of the given files, each read once per pass.

    With wall_k, each batch also holds the critic's extra inputs wall_k times with the undrawn tiles reshuffled
    (full_reshuffled).

    Files are loaded a group at a time (a few hundred MB of records at most) and their marks shuffled together.
    `part` picks "train" (99 hands in 100), "held" (the others) or "all".
    """
    rng = rng if rng is not None else np.random.default_rng(0)
    for _ in range(passes):
        order = rng.permutation(len(files)) if shuffle else np.arange(len(files))
        for g in range(0, len(order), group):
            hands = Hands([files[i] for i in order[g:g + group]], library.sizes["version"])
            marks = hands.marks
            if part != "all":
                out = held_out(marks)
                marks = marks[out if part == "held" else ~out]
            if shuffle:
                marks = marks[rng.permutation(len(marks))]
            for i in range(0, len(marks), batch_size):
                m = marks[i:i + batch_size]
                if len(m) < batch_size and drop_last:
                    break
                seeds = rng.integers(1, 2 ** 63, size=len(m), dtype=np.uint64) if symmetries else None
                batch = library.expand(hands.hands, hands.offsets, hands.rules, m[:, 0], m[:, 1], seeds)
                if wall_k:
                    batch.update(full_reshuffled(library, hands.hands, hands.offsets, hands.rules, m[:, 0], m[:, 1],
                                                 seeds, wall_k, int(rng.integers(1, 2 ** 63))))
                yield batch


class Stream:
    """`batches`, expanded ahead of the trainer by a thread."""

    def __init__(self, library, files, batch_size, seed=0, prefetch=4, **kwargs):
        self.source = batches(library, list(files), batch_size, np.random.default_rng(seed), **kwargs)
        self.queue = queue.Queue(maxsize=prefetch)
        self.error = None
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _put(self, item):
        while not self.stopped.is_set():
            try:
                self.queue.put(item, timeout=0.2)
                return
            except queue.Full:
                pass

    def _run(self):
        try:
            for batch in self.source:
                if self.stopped.is_set():
                    return
                self._put(batch)
        except Exception as e:  # Handed to the reader.
            self.error = e
        self._put(None)

    def close(self):
        self.stopped.set()

    def __iter__(self):
        while True:
            batch = self.queue.get()
            if batch is None:
                if self.error is not None:
                    raise self.error
                return
            yield batch


def count_marks(files):
    total = 0
    for f in files:
        with np.load(f) as d:
            total += d["marks"].reshape(-1, 2).shape[0]
    return total
