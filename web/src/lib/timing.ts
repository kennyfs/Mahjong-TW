// The pace of the game: how long the computer players wait before they play, the words over a player stay, and the
// moments of calls, wins and the rest last. One file holds it for the app and the page alike
// (android/app/src/main/res/raw/timing.json, copied here by scripts/strings.mjs):
//
// - `ms`: each time at the normal pace, in ms;
// - `speed`: the slow and the fast pace as factors of the normal one (the manual pace is the normal one), so that a
//   time changed once changes at every pace; `fixed`: the times that do not follow the pace (a notice's: reading);
// - `review_end`: a review's moments of a hand's end (small, by the player) as a factor of their time;
// - `style`: the looks picked with the moments' tuning tool (branch fx-dev, ?tune), the only ones here and in the app.

import base from '../generated/timing.json';
import { settings, type Speed } from './settings.svelte';

export type TimingKey = keyof typeof base.ms;
const T = base as { speed: { slow: number; fast: number }; fixed: string[]; ms: Record<TimingKey, number>; review_end: number };

/** The pace's factor (the manual pace: the normal one). */
export function factor(speed: Speed = settings.speed): number {
  return speed === 'slow' ? T.speed.slow : speed === 'fast' ? T.speed.fast : 1;
}

/** A time at the settings' pace (or `speed`'s), ms. */
export function ms(key: TimingKey, speed?: Speed): number {
  const v = T.ms[key];
  return T.fixed.includes(key) ? v : Math.round(v * factor(speed));
}

/** A review's moments of a hand's end: this factor of their time. */
export const REVIEW_END = T.review_end;
