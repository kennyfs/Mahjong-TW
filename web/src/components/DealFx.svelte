<!--
  A hand's start (Table.svelte, practice): the hands' tiles (16 each, the dealer's 17th) shuffled face down in the
  middle of the table, then dealt all at once as they lie, no walls built (the user's, 2026-11-13: a real wall would
  not fit the screen, and in a game on a screen it adds little): each flies from where the shuffle left it to its
  player's seat, all together (a little apart, as hands would push them), and the players' tiles are there as they
  land (`onpacket`: a seat's all at once). Its times: deal_shuffle, deal_pause (the pile still a moment, the
  shuffle's sound heard out), deal_fly (lib/timing.ts). Sounds: the shuffle, and
  one knock of tiles as they come (not one a packet: a run of them sounded like a machine gun). `targets`: each seat's
  place the tiles land at (in the table's px, by seat).
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { later } from '../lib/clock';
  import { ms } from '../lib/timing';
  import { playSound } from '../lib/sound';
  import Tile, { TILE_RATIO } from './Tile.svelte';

  let { center, tw, dealer, targets, onpacket, ondone }: {
    /** The middle of the table and the room there (the rivers' box), in the table's px. */
    center: { x: number; y: number; w: number; h: number };
    /** A tile's width here (a river tile's). */
    tw: number;
    dealer: number;
    targets: { x: number; y: number }[];
    onpacket: (seat: number, n: number) => void;
    ondone: () => void;
  } = $props();

  /** The hands' tiles: 16 each and the dealer's first draw. */
  const N = 16 * 4 + 1;
  /** How far apart the tiles leave (ms, at most): together, not as one block. */
  const SPREAD = 90;
  /** A shuffled tile's time from one spot to the next (ms, about): its pace, whatever deal_shuffle is (as many spots
   *  as fill it). The app's DealFx.kt the same. */
  const LEG_MS = 470;
  const size = $derived(Math.max(14, Math.min(tw * 0.9, Math.sqrt((center.w * center.h * 0.5) / N / TILE_RATIO))));
  let els: HTMLElement[] = $state([]);
  let gone = $state(false);

  const rand = (a: number, b: number) => a + Math.random() * (b - a);
  const tf = (x: number, y: number, r: number, s = 1) =>
    `translate(${x - size / 2}px, ${y - (size * TILE_RATIO) / 2}px) rotate(${r}deg) scale(${s})`;

  let timers: (() => void)[] = [];
  let anims: Animation[] = [];

  onMount(() => {
    const shuffleMs = ms('deal_shuffle');
    // A pause after the shuffle: its sound ends, and the deal's knock is heard apart from it.
    const pause = ms('deal_pause');
    const fly = ms('deal_fly');
    const rx = Math.min(center.w, center.h * 1.6) * 0.32;
    const ry = Math.min(center.h, center.w) * 0.3;
    const point = () => {
      const a = rand(0, Math.PI * 2);
      const d = Math.sqrt(Math.random());
      return { x: center.x + Math.cos(a) * rx * d, y: center.y + Math.sin(a) * ry * d, r: rand(-180, 180) };
    };
    // Whose each tile is: the dealer's 17, the others' 16, at random among the pile.
    const owner = [...Array(N).keys()].map((k) => (k === N - 1 ? dealer : k % 4));
    for (let i = owner.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [owner[i], owner[j]] = [owner[j], owner[i]];
    }
    // 1. Shuffled: each tile wanders among a few spots, turning (hands swirling them on the felt).
    const ends: { x: number; y: number; r: number }[] = [];
    const legs = Math.max(1, Math.round(shuffleMs / LEG_MS));
    els.forEach((el, k) => {
      const ps = Array.from({ length: legs + 1 }, point);
      ends[k] = ps[legs];
      anims.push(el.animate(ps.map((p, i) => ({ transform: tf(p.x, p.y, p.r), opacity: i === 0 ? 0 : 1 })),
        { duration: shuffleMs, easing: 'ease-in-out', fill: 'forwards' }));
    });
    playSound('shuffle');
    // 2. Dealt: every tile at once from where it lies to its player, a little apart, fading into their seat.
    timers.push(later(() => {
      els.forEach((el, k) => {
        const p = ends[k];
        const to = targets[owner[k]];
        if (!to) return;
        const jx = rand(-0.6, 0.6) * size;
        const jy = rand(-0.3, 0.3) * size;
        anims.push(el.animate([{ transform: tf(p.x, p.y, p.r), opacity: 1 },
          { transform: tf(to.x + jx, to.y + jy, 0, 0.9), opacity: 0.85, offset: 0.85 },
          { transform: tf(to.x + jx, to.y + jy, 0, 0.8), opacity: 0 }],
          { duration: fly, delay: rand(0, SPREAD), easing: 'cubic-bezier(0.45, 0, 0.3, 1)', fill: 'forwards' }));
      });
      // They come together: one knock, as they land, and the players' tiles there.
      timers.push(later(() => playSound('deal'), fly * 0.8 + SPREAD / 2));
      timers.push(later(() => {
        gone = true;
        for (let seat = 0; seat < 4; seat++) onpacket(seat, owner.filter((o) => o === seat).length);
        ondone();
      }, fly + SPREAD));
    }, shuffleMs + pause));
    return () => {
      for (const t of timers.splice(0)) t();
      for (const a of anims.splice(0)) a.cancel();
    };
  });
</script>

<div class="deal" class:gone aria-hidden="true">
  {#each { length: N } as _, k}
    <div class="t" bind:this={els[k]} style="width: {size}px; height: {size * TILE_RATIO}px"><Tile w={size} /></div>
  {/each}
</div>

<style>
  .deal { position: absolute; inset: 0; pointer-events: none; z-index: 6; overflow: hidden; }
  .deal.gone { visibility: hidden; }
  .t { position: absolute; left: 0; top: 0; opacity: 0; will-change: transform; filter: drop-shadow(0 1px 1.5px rgba(0, 0, 0, 0.35)); }
</style>
