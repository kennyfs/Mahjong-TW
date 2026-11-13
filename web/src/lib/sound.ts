// The table's sounds (public/sounds: scripts/make_sounds.py): the shuffle, a packet of
// tiles dealt, a tile discarded (a few takes, one at random each time), a call's, a kong's, a flower; at a hand's end,
// as the hands are shown: the player's own win, another's (neither gaining nor losing by it), another's they pay for,
// a draw; and with the result's moment, a jingle: the player's success, failure, or neither; a declared ready hand
// (天聽, 地聽, 報聽): surprise. Tiles as they sound (a second set, made from scratch, was dropped
// on 2026-11-13). Web Audio: the files are fetched and decoded once, after the first tap or key (a browser lets a page
// make sound only after one); a sound asked for before they are in is not played late. The level: off, soft, full.

import { settings } from './settings.svelte';

export type SoundName = 'shuffle' | 'deal' | 'discard' | 'call' | 'kong' | 'win' | 'other' | 'lose' | 'drawn' | 'flower'
  | 'success' | 'failure' | 'neutral' | 'declare';

/** Each sound's files (takes of the same, one picked at random) and its own loudness at the full level. */
const FILES: Record<SoundName, { files: string[]; gain: number }> = {
  shuffle: { files: ['shuffle'], gain: 0.8 },
  deal: { files: ['deal-1', 'deal-2'], gain: 0.55 },
  discard: { files: ['discard-1', 'discard-2', 'discard-3'], gain: 0.45 },
  call: { files: ['call'], gain: 0.75 },
  kong: { files: ['kong'], gain: 0.75 },
  win: { files: ['win'], gain: 0.7 },
  other: { files: ['other'], gain: 0.7 },
  lose: { files: ['lose'], gain: 0.7 },
  drawn: { files: ['drawn'], gain: 0.6 },
  flower: { files: ['flower'], gain: 0.5 },
  success: { files: ['success'], gain: 0.3 },
  failure: { files: ['failure'], gain: 0.3 },
  neutral: { files: ['neutral'], gain: 0.22 },
  declare: { files: ['declare'], gain: 0.45 },
};
const LEVEL = { off: 0, soft: 0.4, full: 1 };

let ctx: AudioContext | null = null;
const buffers = new Map<string, AudioBuffer>();
let loading: Promise<void> | null = null;

function base(): string {
  const b = (import.meta as { env?: { BASE_URL?: string } }).env?.BASE_URL ?? '/';
  return b.endsWith('/') ? b : b + '/';
}

function load(): Promise<void> {
  if (loading || !ctx) return loading ?? Promise.resolve();
  const c = ctx;
  const names = [...new Set(Object.values(FILES).flatMap((f) => f.files))];
  const p = Promise.all(names.map(async (n) => {
    try {
      const r = await fetch(`${base()}sounds/${n}.mp3`);
      buffers.set(n, await c.decodeAudioData(await r.arrayBuffer()));
    } catch {
      // (A sound that does not load is not played.)
    }
  })).then(() => undefined);
  loading = p;
  return p;
}

/** The first tap or key: sound may start (and the files load). */
function unlock() {
  if (typeof window === 'undefined') return;
  const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctor) return;
  if (!ctx) ctx = new Ctor();
  if (ctx.state === 'suspended') void ctx.resume();
  void load();
}
if (typeof window !== 'undefined') {
  window.addEventListener('pointerdown', unlock, true);
  window.addEventListener('keydown', unlock, true);
}

/** Plays a sound, `delay` ms from now (a discard's when it lands, a meld's tile as it lands in its gap). Nothing when
 *  the settings have them off, before the first tap, or while the page is hidden. Gives what calls it off if it has
 *  not started yet (its moment let go before then). */
export function playSound(name: SoundName, delay = 0): () => void {
  const none = () => {};
  const level = LEVEL[settings.sound];
  if (!level || !ctx || ctx.state !== 'running' || document.hidden) return none;
  const f = FILES[name];
  const buf = buffers.get(f.files[Math.floor(Math.random() * f.files.length)]);
  if (!buf) {
    void load();
    return none;
  }
  const src = ctx.createBufferSource();
  src.buffer = buf;
  // A little life in the repeated ones: the pitch a shade up or down.
  if (name === 'discard' || name === 'deal') src.playbackRate.value = 0.96 + Math.random() * 0.08;
  const g = ctx.createGain();
  g.gain.value = f.gain * level;
  src.connect(g).connect(ctx.destination);
  const at = ctx.currentTime + Math.max(0, delay) / 1000;
  src.start(at);
  const c = ctx;
  return () => {
    if (c.currentTime < at) src.stop();
  };
}
