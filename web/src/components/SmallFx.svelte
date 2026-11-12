<!--
  A small moment by a player (lib/moments.ts; Table.svelte starts it): a call (吃, 碰, 槓), who dealt in (another
  player), a win's words alone (a review's end, when the review keeps them small). It slides in from the left, slowing
  as it comes, and shows the word and the caller's tiles with a gap; the tile called lifts off the river where it lies
  (`src`) and drops into the gap; it stays; then it goes, and the meld flies to its place among the caller's melds
  (`dst`, kept out of sight there till now), shrinking or growing to their size: it lands where it will be. Without a
  place to land (who dealt in, a win's words) it all fades where it is.

  It stands over `anchor` (the caller's river; the seat of who dealt in), inside the table (`root`): a slanted dark
  panel with the word large at its foot. Nothing here takes a touch: a tap anywhere lets it go (the table's own
  listener).
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { later } from '../lib/clock';
  import type { Moment } from '../lib/moments';
  import Tile from './Tile.svelte';

  let { m, w, root }: { m: Moment; w: number; root: HTMLElement | undefined } = $props();

  let box: HTMLElement;
  let row = $state<HTMLElement>();
  let slot = $state<HTMLElement>();
  let called = $state<HTMLElement>();
  const parts = $derived(m.parts ?? { in: 0, drop: 0, hold: m.ms, out: 0 });
  const before = $derived(m.gap >= 0 ? m.tiles.slice(0, m.gap) : m.tiles);
  const after = $derived(m.gap >= 0 ? m.tiles.slice(m.gap) : []);
  const chars = $derived([...m.word].length);
  const centre = (r: DOMRect) => [r.left + r.width / 2, r.top + r.height / 2];
  const clamp = (x: number, a: number, b: number) => Math.max(a, Math.min(b, x));

  onMount(() => {
    const p = parts;
    // Where: centered over the anchor, inside the table.
    const T = (root ?? document.body).getBoundingClientRect();
    const a = m.anchor ? document.querySelector<HTMLElement>(m.anchor)?.getBoundingClientRect() : null;
    const [cx, cy] = a ? centre(a) : [T.left + T.width / 2, T.top + T.height / 2];
    const bw = box.offsetWidth, bh = box.offsetHeight;
    box.style.left = `${clamp(cx - bw / 2, T.left + 6, Math.max(T.left + 6, T.right - 6 - bw))}px`;
    box.style.top = `${clamp(cy - bh / 2, T.top + 6, Math.max(T.top + 6, T.bottom - 6 - bh))}px`;
    box.style.visibility = 'visible';

    const anims: Animation[] = [];
    const timers: (() => void)[] = [];
    // In: from the left by part of its width, slowing to its place.
    const shift = bw * 0.4;
    anims.push(box.animate([{ transform: `translateX(${-shift}px)`, opacity: 0 }, { transform: 'none', opacity: 1 }],
      { duration: p.in, easing: 'cubic-bezier(0.05, 0.7, 0.1, 1)', fill: 'backwards' }));
    // The tile called: from where it lay (as large as there), up over the gap, down into it, once in.
    if (slot && called && p.drop > 0) {
      const c = called, sl = slot;
      // (Where it lay, now: the river may change by then. Where it lands, then: the panel has come to rest.)
      const s = m.src ? document.querySelector<HTMLElement>(m.src)?.getBoundingClientRect() : null;
      timers.push(later(() => {
        const g = sl.getBoundingClientRect();
        const [gx, gy] = centre(g);
        let first: Keyframe;
        if (s) {
          const [sx, sy] = centre(s);
          first = { transform: `translate(${sx - gx}px, ${sy - gy}px) scale(${s.width / g.width})`, opacity: 1 };
        } else first = { transform: `translate(0, ${-g.height * 1.3}px)`, opacity: 0 };
        anims.push(c.animate([
          { ...first, offset: 0, easing: 'cubic-bezier(0.65, 0, 0.35, 1)' },
          { transform: `translate(0, ${-g.height * 0.55}px) rotate(-4deg) scale(1.08)`, opacity: 1, offset: 0.55, easing: 'cubic-bezier(0.65, 0, 0.35, 1)' },
          { transform: 'none', offset: 0.82, easing: 'ease-out' },
          { transform: `translate(0, ${-g.height * 0.08}px)`, offset: 0.9, easing: 'ease-in' },
          { transform: 'none', opacity: 1, offset: 1 },
        ], { duration: p.drop, fill: 'forwards' }));
      }, p.in));
      timers.push(later(() => {}, p.in + p.drop));
    } else if (called) called.style.opacity = '1';
    // Out: the meld to its place (measured now: the table has settled), the rest fading; or all of it fading.
    timers.push(later(() => {
      const d = m.dst ? document.querySelector<HTMLElement>(m.dst) : null;
      if (d && row) {
        const r = d.getBoundingClientRect(), g = row.getBoundingClientRect();
        const [a1, b1] = centre(r), [a2, b2] = centre(g);
        const k = d.offsetWidth / Math.max(1, row.offsetWidth);
        anims.push(row.animate([{ transform: 'none' }, { transform: `translate(${a1 - a2}px, ${b1 - b2}px) scale(${k})` }],
          { duration: p.out, easing: 'cubic-bezier(0.5, 0, 0.2, 1)', fill: 'forwards' }));
        for (const el of box.querySelectorAll<HTMLElement>('.deco'))
          anims.push(el.animate([{ opacity: 1 }, { opacity: 0 }], { duration: p.out * 0.6, easing: 'ease-in', fill: 'forwards' }));
      } else {
        anims.push(box.animate([{ opacity: 1, transform: 'none' }, { opacity: 0, transform: `translateX(${Math.min(bw * 0.06, 24)}px)` }],
          { duration: p.out, easing: 'ease-in', fill: 'forwards' }));
      }
    }, p.in + p.drop + p.hold));
    return () => {
      for (const t of timers) t();
      for (const x of anims) x.cancel();
    };
  });
</script>

<div class="sfx slant {m.kind}" class:mine={m.mine} bind:this={box} style="--tw: {w}px; --chars: {chars}" aria-hidden="true">
  <div class="panel deco"></div>
  <div class="word deco"><span class="face">{#if m.word.startsWith('搶')}<span class="rob">搶</span>{m.word.slice(1)}{:else}{m.word}{/if}</span></div>
  {#if m.who}<div class="who deco">{m.who}</div>{/if}
  {#if m.tiles.length || m.tile}
    <div class="row" bind:this={row}>
      {#each before as t, i (i)}<Tile tile={t} {w} />{/each}
      {#if m.gap >= 0}
        <span class="slot" bind:this={slot}><span class="called" bind:this={called}><Tile tile={m.tile} {w} /></span></span>
      {/if}
      {#each after as t, i (i)}<Tile tile={t} {w} />{/each}
    </div>
  {:else}<div class="row" bind:this={row}></div>{/if}
</div>

<style>
  .sfx { position: fixed; left: 0; top: 0; z-index: 30; pointer-events: none; visibility: hidden; display: flex; align-items: center;
    --face: #f6b93b; --ink: #3b2200; --back: #b9780f; --line: rgba(59, 34, 0, 0.42); --edge: #f6c453; --cut: 0.24em;
    --fs: calc(var(--tw) * 1.2); }
  .sfx.dealin { --face: #2e383e; --ink: #ffb3a7; --back: #12171a; --line: rgba(255, 179, 167, 0.45); --edge: #ff9d8c; }
  .sfx.small { --face: #d5382d; --ink: #fff6dc; --back: #8c1c15; --line: rgba(255, 214, 120, 0.9); --edge: #ff8a73; }
  .row { position: relative; z-index: 1; display: flex; align-items: flex-end; filter: drop-shadow(0 4px 6px rgba(0, 0, 0, 0.5)); }
  .slot { position: relative; display: block; width: var(--tw); height: calc(var(--tw) * 1.45); flex: none; }
  .called { position: absolute; left: 0; bottom: 0; z-index: 1; opacity: 0; }

  /* The word on its plaque: corners cut, a lip under it, a fine line inside its edge (the table's words). */
  .word { position: relative; z-index: 2; display: block; font-size: var(--fs); white-space: nowrap; padding-bottom: 0.09em;
    filter: drop-shadow(0 0.07em 0.09em rgba(0, 0, 0, 0.55)); }
  .word::before, .face { clip-path: polygon(var(--cut) 0, calc(100% - var(--cut)) 0, 100% var(--cut),
    100% calc(100% - var(--cut)), calc(100% - var(--cut)) 100%, var(--cut) 100%, 0 calc(100% - var(--cut)), 0 var(--cut)); }
  .word::before { content: ''; position: absolute; inset: 0.09em 0 0; background: var(--back); }
  .face { position: relative; display: block; height: 1.45em; line-height: 1.45em; padding: 0 0.34em; background: var(--face); color: var(--ink);
    font-weight: 900; letter-spacing: 0.04em; font-family: 'Noto Serif CJK TC', 'Noto Serif TC', 'Source Han Serif TC', 'Songti TC', 'PMingLiU', serif; }
  .face .rob { color: #a3150c; }
  .dealin .face .rob, .sfx.small .face .rob { color: #ffd56b; }
  .who { position: absolute; z-index: 3; padding: 0.15em 0.75em 0.2em; border-radius: 999px; background: #18221f; color: #fff6dc;
    border: 1.5px solid var(--edge); font-weight: 700; line-height: 1.1; white-space: nowrap; font-size: clamp(0.75rem, calc(var(--tw) * 0.32), 1rem); }

  /* Slanted: a dark panel leaning forward, a line of the moment's color along its leading edge, the word large over
     its lower left corner, the name on a tab over its top edge. */
  .slant { padding: calc(var(--tw) * 0.34) calc(var(--tw) * 0.75) calc(var(--tw) * 0.34)
    calc(var(--fs) * (var(--chars) * 1.04 + 0.68) * 0.78 + var(--tw) * 0.25); }
  .slant .panel { position: absolute; inset: 0; transform: skewX(-16deg); border-radius: 3px; overflow: hidden;
    background: linear-gradient(100deg, rgba(6, 9, 8, 0.94), rgba(22, 28, 25, 0.9) 55%, rgba(6, 9, 8, 0.94));
    box-shadow: 0 6px 18px rgba(0, 0, 0, 0.45), inset 0 0 0 1px rgba(255, 255, 255, 0.07); }
  .slant .panel::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 4px; background: var(--edge); }
  .slant .panel::after { content: ''; position: absolute; inset: 0;
    background: repeating-linear-gradient(-62deg, transparent 0 16px, rgba(255, 255, 255, 0.045) 16px 24px); }
  .slant .word { position: absolute; left: calc(var(--tw) * -0.3); bottom: calc(var(--tw) * -0.42); rotate: -6deg; }
  .slant .who { right: calc(var(--tw) * 0.5); top: calc(var(--tw) * -0.34); }
  /* (Without tiles the panel is low: the name over it, clear of the word.) */
  .slant.small .who { top: calc(var(--tw) * -1.05); }
</style>
