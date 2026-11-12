// The game's waits (a computer player's pace, a moment and its parts, words, a hand's end): timers that can be called
// off. (The moments' tuning tool, on the branch fx-dev, runs them on a clock it can pause.)

/** `fn` in `ms`; the returned function calls it off. */
export function later(fn: () => void, ms: number): () => void {
  const id = setTimeout(fn, ms);
  return () => clearTimeout(id);
}

/** A wait of `ms`. */
export const wait = (ms: number) => new Promise<void>((go) => setTimeout(go, ms));

/** The time, ms. */
export const now = () => performance.now();
