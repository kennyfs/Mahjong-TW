<!--
  A hand's start (Table.svelte, practice): the tiles shuffled face down in the middle of the table, built into four
  walls around it, and dealt: four at a time to each player in turn from the dealer, four rounds, then the dealer's
  one more; each packet flies from the wall to the player's seat (Table.svelte counts their tiles up as it lands:
  `onpacket`), and what is left of the wall shrinks away into the middle (the wall's count is on the table). Its times:
  deal_shuffle, deal_build, deal_packet (one packet after another), deal_fly (a packet's flight), lib/timing.ts.
  Sounds: the shuffle, a packet landing (lib/sound.ts). `root`: the table (positions are its own); `targets`: each
  seat's place a packet lands at (in the table's px, by seat).
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

  /** The tiles shown (each stands for a few of the 144): ten a wall. */
  const PER_WALL = 10;
  const N = PER_WALL * 4;
  // A wall's tiles lie end to end; the walls' square fits the middle.
  const size = $derived(Math.min(tw, (Math.min(center.w, center.h) * 0.78) / (PER_WALL + TILE_RATIO)));
  let els: HTMLElement[] = $state([]);
  let gone = $state<boolean[]>(Array(N).fill(false));

  const rand = (a: number, b: number) => a + Math.random() * (b - a);
  /** Where a wall's tile lies: its center and turn (the side walls' tiles across). */
  function wallSlot(k: number): { x: number; y: number; r: number } {
    const wall = Math.floor(k / PER_WALL);
    const i = k % PER_WALL;
    const half = ((PER_WALL + TILE_RATIO) * size) / 2;
    const along = (i - (PER_WALL - 1) / 2) * size;
    const edge = half - (size * TILE_RATIO) / 2;
    // Bottom, right, top, left: the deal goes round them in that order.
    if (wall === 0) return { x: center.x + along, y: center.y + edge, r: 0 };
    if (wall === 1) return { x: center.x + edge, y: center.y - along, r: 90 };
    if (wall === 2) return { x: center.x - along, y: center.y - edge, r: 180 };
    return { x: center.x - edge, y: center.y + along, r: 270 };
  }
  const tf = (x: number, y: number, r: number, s = 1) =>
    `translate(${x - size / 2}px, ${y - (size * TILE_RATIO) / 2}px) rotate(${r}deg) scale(${s})`;

  let timers: (() => void)[] = [];
  let anims: Animation[] = [];

  onMount(() => {
    const shuffleMs = ms('deal_shuffle');
    const buildMs = ms('deal_build');
    const step = ms('deal_packet');
    const fly = ms('deal_fly');
    const rx = Math.min(center.w, center.h * 1.6) * 0.3;
    const ry = Math.min(center.h, center.w) * 0.28;
    const point = () => {
      const a = rand(0, Math.PI * 2);
      const d = Math.sqrt(Math.random());
      return { x: center.x + Math.cos(a) * rx * d, y: center.y + Math.sin(a) * ry * d, r: rand(-180, 180) };
    };
    // 1. Shuffled: each tile wanders among a few spots, turning (hands swirling them on the felt).
    const ends: { x: number; y: number; r: number }[] = [];
    els.forEach((el, k) => {
      const ps = [point(), point(), point(), point()];
      ends[k] = ps[3];
      anims.push(el.animate(ps.map((p, i) => ({ transform: tf(p.x, p.y, p.r), opacity: i === 0 ? 0 : 1 })),
        { duration: shuffleMs, easing: 'ease-in-out', fill: 'forwards' }));
    });
    playSound('shuffle');
    // 2. Built into the walls.
    timers.push(later(() => {
      els.forEach((el, k) => {
        const p = ends[k];
        const w = wallSlot(k);
        anims.push(el.animate([{ transform: tf(p.x, p.y, p.r), opacity: 1 }, { transform: tf(w.x, w.y, w.r), opacity: 1 }],
          { duration: buildMs, delay: (k % PER_WALL) * (buildMs * 0.03), easing: 'cubic-bezier(0.2, 0.7, 0.3, 1)', fill: 'forwards' }));
      });
      timers.push(later(() => playSound('deal'), buildMs * 0.8));
    }, shuffleMs));
    // 3. Dealt: four rounds of four, from the dealer round the table, then the dealer's one more. Each packet leaves
    // the wall where the last one left it.
    const start = shuffleMs + buildMs * 1.27;
    const packets: [number, number][] = [];
    for (let r = 0; r < 4; r++) for (let i = 0; i < 4; i++) packets.push([(dealer + i) % 4, 4]);
    packets.push([dealer, 1]);
    let next = Math.floor(rand(0, 4)) * PER_WALL + 2;
    packets.forEach(([seat, n], j) => {
      timers.push(later(() => {
        const k = next++ % N;
        const el = els[k];
        const w = wallSlot(k);
        const to = targets[seat];
        if (!el || !to) return;
        anims.push(el.animate([{ transform: tf(w.x, w.y, w.r), opacity: 1 }, { transform: tf(to.x, to.y, 0, n > 1 ? 1.25 : 1), opacity: 0.2 }],
          { duration: fly, easing: 'cubic-bezier(0.45, 0, 0.3, 1)', fill: 'forwards' }));
        timers.push(later(() => {
          gone[k] = true;
          onpacket(seat, n);
          playSound('deal');
        }, fly));
      }, start + j * step));
    });
    // 4. The rest of the wall shrinks into the middle; done.
    const end = start + (packets.length - 1) * step + fly;
    timers.push(later(() => {
      els.forEach((el, k) => {
        if (gone[k]) return;
        const w = wallSlot(k);
        anims.push(el.animate([{ transform: tf(w.x, w.y, w.r), opacity: 1 }, { transform: tf(center.x, center.y, w.r, 0.3), opacity: 0 }],
          { duration: 280, easing: 'ease-in', fill: 'forwards' }));
      });
    }, end));
    timers.push(later(ondone, end + 280));
    return () => {
      for (const t of timers.splice(0)) t();
      for (const a of anims.splice(0)) a.cancel();
    };
  });
</script>

<div class="deal" aria-hidden="true">
  {#each { length: N } as _, k}
    <div class="t" class:gone={gone[k]} bind:this={els[k]} style="width: {size}px; height: {size * TILE_RATIO}px"><Tile w={size} /></div>
  {/each}
</div>

<style>
  .deal { position: absolute; inset: 0; pointer-events: none; z-index: 6; overflow: hidden; }
  .t { position: absolute; left: 0; top: 0; opacity: 0; will-change: transform; filter: drop-shadow(0 1px 1.5px rgba(0, 0, 0, 0.35)); }
  .t.gone { visibility: hidden; }
</style>
