// The words over the players (Table.svelte: a call, a win, a flower): how long they stay, and waiting for them. The
// computer players of a practice game wait for a call's words before they go on, so that it can be followed; a tap
// anywhere (or a key) lets the words go at once, and whoever waited goes on.

import { later, now } from './clock';
import { ms } from './timing';

/** A call's, a declaration's or a win's words, ms, by the pace of the settings (lib/timing.ts; the manual pace
 *  keeps them until the next tap: this is how long they stay where nothing waits for the player). Where a moment shows
 *  the same (Moments.svelte), its own time is waited for as the words' are. */
export const callMs = () => ms('words_call');
/** A flower's (there are many, and nothing is decided): brief, and nothing waits for them. */
export const flowerMs = () => ms('words_flower');

let until = 0;
let waiting: ((waited: boolean) => void)[] = [];
let timer: (() => void) | undefined;

function release() {
  timer?.();
  timer = undefined;
  until = 0;
  for (const go of waiting.splice(0)) go(true);
}

/** Words went up for `ms`, to be waited for. */
export function calloutShown(ms: number) {
  until = Math.max(until, now() + ms);
  if (waiting.length) {
    timer?.();
    timer = later(release, until - now());
  }
}

/** The words were let go (a tap anywhere, a key). */
export function calloutsSkipped() {
  release();
}

/** Resolves when no words that are waited for are up: at once (false) when there are none, else (true) at their end. */
export function calloutsDone(): Promise<boolean> {
  const left = until - now();
  if (left <= 0) return Promise.resolve(false);
  return new Promise((go) => {
    waiting.push(go);
    timer?.();
    timer = later(release, left);
  });
}
