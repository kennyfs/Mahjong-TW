// The words over the players (Table.svelte: a call, a win, a flower): how long they stay, and waiting for them. The
// computer players of a practice game wait for a call's words before they go on, so that it can be followed; a tap
// anywhere (or a key) lets the words go at once, and whoever waited goes on.

/** A call's, a declaration's or a win's words, ms. */
export const CALL_MS = 2000;
/** A flower's (there are many, and nothing is decided): brief, and nothing waits for them. */
export const FLOWER_MS = 900;

let until = 0;
let waiting: (() => void)[] = [];
let timer: ReturnType<typeof setTimeout> | undefined;

function release() {
  clearTimeout(timer);
  timer = undefined;
  until = 0;
  for (const go of waiting.splice(0)) go();
}

/** Words went up for `ms`, to be waited for. */
export function calloutShown(ms: number) {
  until = Math.max(until, performance.now() + ms);
  if (waiting.length) {
    clearTimeout(timer);
    timer = setTimeout(release, until - performance.now());
  }
}

/** The words were let go (a tap anywhere, a key). */
export function calloutsSkipped() {
  release();
}

/** Resolves when no words that are waited for are up: at once when there are none. */
export function calloutsDone(): Promise<void> {
  const left = until - performance.now();
  if (left <= 0) return Promise.resolve();
  return new Promise((go) => {
    waiting.push(go);
    clearTimeout(timer);
    timer = setTimeout(release, left);
  });
}
