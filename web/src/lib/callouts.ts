// The words over the players (Table.svelte: a call, a win, a flower): how long they stay, and waiting for them. The
// computer players of a practice game wait for a call's words before they go on, so that it can be followed; a tap
// anywhere (or a key) lets the words go at once, and whoever waited goes on.
// A practice hand's deal is waited for the same way (dealArm, dealReady): until the table has dealt it.

import { later, now } from './clock';
import { skipSoundSync } from './sound';
import { ms } from './timing';

/** A call's, a declaration's or a win's words, ms, by the pace of the settings (lib/timing.ts; the manual pace
 *  keeps them until the next tap: this is how long they stay where nothing waits for the player). Where a moment shows
 *  the same (Moments.svelte), its own time is waited for as the words' are. */
export const callMs = () => ms('words_call');
/** A flower's (there are many, and nothing is decided): brief, and nothing waits for them. */
export const flowerMs = () => ms('words_flower');

// A practice hand's deal (DealFx), as the computer players wait for it: from the hand's start (practice.svelte.ts)
// until the table has shuffled and dealt it and the player's hand has turned face up, or a tap let it go. No limit in
// time: a hand begun while the table is not on the screen (the settings changed) waits for the player to come back to
// it. The app's DealClock (Callouts.kt) the same.
let pendingDeal: string | null = null;
let dealWaiters: (() => void)[] = [];

/** A hand begun, to be dealt on the table. */
export function dealArm(id: string) {
  pendingDeal = id;
}

/** The table has dealt `id` (or will not: shown already, no animations). */
export function dealComplete(id: string) {
  if (pendingDeal === id) dealReset();
}

/** Nothing waited for: a tap let the deal go, or another game began. */
export function dealReset() {
  pendingDeal = null;
  for (const go of dealWaiters.splice(0)) go();
}

/** Resolves once no deal is waited for. */
export function dealReady(): Promise<void> {
  if (pendingDeal === null) return Promise.resolve();
  return new Promise((go) => { dealWaiters.push(go); });
}

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
  dealReset();
  skipSoundSync();
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
