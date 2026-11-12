<!--
  A chow's, a pung's or a win's moment, over the whole screen (Table.svelte starts it; the advanced settings can keep
  to the words alone). A dark veil; two black wedges slide in from opposite corners; across the middle a band laid
  askew, its white speed lines running; on it the word on its plaque (whose it is under it), and the tiles:

  - a chow or a pung: the caller's own tiles with a gap; the tile called lifts off the river where it lies (`src`),
    growing as it comes over the gap, and drops into it; then the meld flies to its place among the caller's melds
    (`dst`, kept out of sight there till now), shrinking or growing to their size on the way, as the rest fades: it
    lands where it will be;
  - a win: the winner's concealed tiles with a gap at their end, the winning tile coming from the river of the one
    who dealt in (a self-draw: lit, from above) and dropping into the gap; a light runs over the hand, and it all fades
    where it is (the hand's result comes next).

  Without the place to start from or to land at (another layout, a robbed kong) the tile comes from the side of the
  one who discarded it, and the meld goes off towards the caller's side, smaller, fading.

  It lasts `ms` (the words' time of the settings' pace). Nothing here takes a touch: a tap anywhere lets it go (the
  table's own listener).
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import type { Tile as T } from '../lib/tiles';
  import Tile from './Tile.svelte';

  export type Side = 'bottom' | 'right' | 'top' | 'left';
  export interface Fx {
    id: number;
    kind: 'call' | 'win';
    word: string;
    who: string;
    /** A second line under the name: who dealt in. */
    note: string;
    /** The caller's side, and the side the tile comes from (null: a self-draw, from above). */
    side: Side;
    from: Side | null;
    /** The caller's tiles around the gap, and where the gap is among them. */
    tiles: T[];
    gap: number;
    tile: T;
    ms: number;
    /** Selectors of the tile the called one lifts off (in a river) and of the meld it lands as (Melds' data-meld). */
    src: string | null;
    dst: string | null;
  }
  let { fx, w }: { fx: Fx; w: number } = $props();

  // Where the tile comes from, and where the meld goes: towards a side of the screen.
  const AWAY: Record<Side, [string, string]> = { bottom: ['0px', '38vh'], top: ['0px', '-38vh'], left: ['-42vw', '0px'], right: ['42vw', '0px'] };
  const from = $derived(fx.from ? AWAY[fx.from] : ['0px', '-30vh']);
  const to = $derived(AWAY[fx.side]);
  // A long hand (a win) in smaller tiles, so that it keeps on one line.
  const tw = $derived(Math.min(w, (Math.min(innerWidth, 900) - 48) / Math.max(4, fx.tiles.length + 1.4)));
  // Where the tile starts and where the meld lands, measured once both are on the screen: the moves (from the
  // untransformed places of the gap and the meld) and the scales that make the tiles as large as there.
  let row: HTMLElement;
  let slot: HTMLElement;
  let start = $state<{ x: number; y: number; k: number } | null>(null);
  let land = $state<{ x: number; y: number; k: number } | null>(null);
  const centre = (r: DOMRect) => [r.left + r.width / 2, r.top + r.height / 2];
  onMount(() => {
    const s = fx.src ? document.querySelector<HTMLElement>(fx.src) : null;
    if (s) {
      const r = s.getBoundingClientRect(), g = slot.getBoundingClientRect();
      const [a, b] = centre(r), [c, d] = centre(g);
      start = { x: a - c, y: b - d, k: s.offsetWidth / g.width };
    }
    // The meld's place, measured again every frame until the meld sets off: the table may still be settling (the
    // tiles called leaving the hand, the melds' sizes).
    if (!fx.dst) return;
    const t0 = performance.now();
    let frame = 0;
    const measure = () => {
      const m = document.querySelector<HTMLElement>(fx.dst!);
      if (m && row) {
        // The meld's own size (its entrance may be scaling it: the centre stays).
        const r = m.getBoundingClientRect(), g = row.getBoundingClientRect();
        const [a, b] = centre(r), [c, d] = centre(g);
        const k = m.offsetWidth / g.width;
        if (!land || Math.abs(land.x - (a - c)) > 0.5 || Math.abs(land.y - (b - d)) > 0.5 || Math.abs(land.k - k) > 0.005) land = { x: a - c, y: b - d, k };
      }
      if (performance.now() - t0 < fx.ms * 0.6) frame = requestAnimationFrame(measure);
    };
    measure();
    return () => cancelAnimationFrame(frame);
  });
  const vars = $derived([
    `--ms: ${fx.ms}ms`, `--tw: ${tw}px`,
    start ? `--fx: ${start.x}px; --fy: ${start.y}px; --fk: ${start.k}; --fo: 1; --fr: 0deg` : `--fx: ${from[0]}; --fy: ${from[1]}; --fk: 1; --fo: 0; --fr: -18deg`,
    land ? `--gx: ${land.x}px; --gy: ${land.y}px; --gk: ${land.k}; --go: 1` : `--gx: ${to[0]}; --gy: ${to[1]}; --gk: 0.4; --go: 0`,
  ].join('; '));
  const before = $derived(fx.tiles.slice(0, fx.gap));
  const after = $derived(fx.tiles.slice(fx.gap));
  // The speed lines: fixed at random once, each its own height, length, weight and pace.
  const LINES = Array.from({ length: 22 }, (_, i) => ({
    y: (i * 37) % 100, len: 18 + ((i * 53) % 40), weight: 1 + ((i * 7) % 3), op: 0.25 + ((i * 29) % 60) / 100, dur: 0.35 + ((i * 17) % 40) / 100,
    delay: -((i * 13) % 50) / 100,
  }));
</script>

<div class="fx {fx.kind}" style={vars} aria-hidden="true">
  <div class="veil"></div>
  <div class="band">
    {#each LINES as l, i (i)}
      <span class="streak" style="top: {l.y}%; width: {l.len}%; height: {l.weight}px; opacity: {l.op}; animation-duration: {l.dur}s; animation-delay: {l.delay}s"></span>
    {/each}
  </div>
  <div class="wedge tl"></div>
  <div class="wedge br"></div>
  <div class="stage">
    <div class="plaque">
      <!-- 搶碰, 搶胡 (a claim taken from the player): the 搶 in another color. -->
      <span class="word"><span class="face">{#if fx.word.startsWith('搶')}<span class="rob">搶</span>{fx.word.slice(1)}{:else}{fx.word}{/if}</span></span>
      {#if fx.who}<span class="who">{fx.who}</span>{/if}
      {#if fx.note}<span class="note">{fx.note}</span>{/if}
    </div>
    <div class="place" bind:this={row}>
      <div class="meld">
        {#each before as t, i (i)}<span class="own"><Tile tile={t} w={tw} /></span>{/each}
        <span class="slot" class:apart={fx.kind === 'win'} bind:this={slot}>
          <span class="called"><Tile tile={fx.tile} w={tw} glow={fx.kind === 'win'} /></span>
        </span>
        {#each after as t, i (i)}<span class="own"><Tile tile={t} w={tw} /></span>{/each}
        {#if fx.kind === 'win'}<span class="shine"></span>{/if}
      </div>
    </div>
  </div>
</div>

<style>
  .fx { position: fixed; inset: 0; z-index: 60; pointer-events: none; overflow: hidden; display: flex; align-items: center; justify-content: center;
    --edge: #f6c453; --face: #f6b93b; --ink: #3b2200; --back: #b9780f; --line: rgba(59, 34, 0, 0.42); --cut: 0.24em; }
  .fx.win { --edge: #ff8a73; --face: #d5382d; --ink: #fff6dc; --back: #8c1c15; --line: rgba(255, 214, 120, 0.9); }

  /* The veil and the frame come, stay and go with the moment: a call's go sooner, while its meld flies to its place. */
  .veil { position: absolute; inset: 0; background: radial-gradient(ellipse at 50% 50%, rgba(18, 30, 26, 0.55), rgba(4, 8, 7, 0.82));
    animation: veil var(--ms) linear both; }
  @keyframes veil { 0% { opacity: 0; } 8% { opacity: 1; } 84% { opacity: 1; } 100% { opacity: 0; } }
  .call .veil { animation-name: veil-call; }
  @keyframes veil-call { 0% { opacity: 0; } 8% { opacity: 1; } 64% { opacity: 1; } 92% { opacity: 0; } 100% { opacity: 0; } }

  /* Two black wedges from opposite corners, a fine line of the moment's color along their long edge. */
  .wedge { position: absolute; width: 62vw; height: 46vh; background: #050706; }
  .wedge::after { content: ''; position: absolute; inset: 0; background: var(--edge); }
  .wedge.tl { left: 0; top: 0; clip-path: polygon(0 0, 100% 0, 0 100%); animation: tl var(--ms) cubic-bezier(0.2, 0.8, 0.2, 1) both; }
  .wedge.tl::after { clip-path: polygon(calc(100% - 3px) 0, 100% 0, 0 100%, 0 calc(100% - 2px)); }
  .wedge.br { right: 0; bottom: 0; clip-path: polygon(100% 0, 100% 100%, 0 100%); animation: br var(--ms) cubic-bezier(0.2, 0.8, 0.2, 1) both; }
  .wedge.br::after { clip-path: polygon(100% 0, 100% 2px, 3px 100%, 0 100%); }
  @keyframes tl { 0% { transform: translate(-60%, -60%); } 12% { transform: none; } 84% { transform: none; opacity: 1; } 100% { transform: translate(-60%, -60%); opacity: 0; } }
  @keyframes br { 0% { transform: translate(60%, 60%); } 12% { transform: none; } 84% { transform: none; opacity: 1; } 100% { transform: translate(60%, 60%); opacity: 0; } }
  .call .wedge.tl { animation-name: tl-call; }
  .call .wedge.br { animation-name: br-call; }
  @keyframes tl-call { 0% { transform: translate(-60%, -60%); } 12% { transform: none; } 64% { transform: none; opacity: 1; } 90%, 100% { transform: translate(-60%, -60%); opacity: 0; } }
  @keyframes br-call { 0% { transform: translate(60%, 60%); } 12% { transform: none; } 64% { transform: none; opacity: 1; } 90%, 100% { transform: translate(60%, 60%); opacity: 0; } }

  /* The band across the middle, askew, and its speed lines running along it. */
  .band { position: absolute; left: -20vw; right: -20vw; top: 50%; height: clamp(180px, 46vh, 420px); translate: 0 -50%; rotate: -14deg;
    background: linear-gradient(180deg, transparent, rgba(255, 255, 255, 0.07) 18%, rgba(255, 255, 255, 0.1) 50%, rgba(255, 255, 255, 0.07) 82%, transparent);
    overflow: hidden; animation: band var(--ms) ease-out both; }
  @keyframes band { 0% { clip-path: inset(0 100% 0 0); } 14% { clip-path: inset(0 0 0 0); } 84% { opacity: 1; } 100% { opacity: 0; } }
  .call .band { animation-name: band-call; }
  @keyframes band-call { 0% { clip-path: inset(0 100% 0 0); } 14% { clip-path: inset(0 0 0 0); } 64% { opacity: 1; } 88%, 100% { opacity: 0; } }
  .streak { position: absolute; left: 0; border-radius: 2px; background: linear-gradient(90deg, transparent, #fff 35%, #fff 80%, transparent);
    animation-name: run; animation-timing-function: linear; animation-iteration-count: infinite; }
  @keyframes run { from { transform: translateX(-110%); } to { transform: translateX(380%); } }

  /* The word and the tiles, upright on the band. */
  .stage { position: relative; display: flex; flex-direction: column; align-items: center; gap: clamp(26px, 6vh, 48px); }
  .plaque { display: flex; flex-direction: column; align-items: center; font-size: clamp(3rem, 15vmin, 7.5rem);
    animation: plaque var(--ms) cubic-bezier(0.2, 0.9, 0.3, 1.25) both; }
  @keyframes plaque { 0% { transform: scale(1.6); opacity: 0; } 10% { transform: scale(1); opacity: 1; } 80% { opacity: 1; } 100% { opacity: 0; transform: scale(0.94); } }
  .call .plaque { animation-name: plaque-call; }
  @keyframes plaque-call { 0% { transform: scale(1.6); opacity: 0; } 10% { transform: scale(1); opacity: 1; } 62% { opacity: 1; transform: scale(1); }
    82%, 100% { opacity: 0; transform: scale(0.94); } }
  .word { position: relative; display: block; rotate: -4deg; padding-bottom: 0.09em; filter: drop-shadow(0 0.08em 0.12em rgba(0, 0, 0, 0.6)); }
  .word::before, .face { clip-path: polygon(var(--cut) 0, calc(100% - var(--cut)) 0, 100% var(--cut),
    100% calc(100% - var(--cut)), calc(100% - var(--cut)) 100%, var(--cut) 100%, 0 calc(100% - var(--cut)), 0 var(--cut)); }
  .word::before { content: ''; position: absolute; inset: 0.09em 0 0; background: var(--back); }
  .face { position: relative; display: block; height: 1.45em; line-height: 1.45em; padding: 0 0.38em; background: var(--face); color: var(--ink);
    font-weight: 900; letter-spacing: 0.04em; font-family: 'Noto Serif CJK TC', 'Noto Serif TC', 'Source Han Serif TC', 'Songti TC', 'PMingLiU', serif; }
  .who { margin-top: -0.12em; z-index: 1; padding: 0.05em 0.6em; border-radius: 0.3em; background: #10140f; color: #fff; font-size: 0.22em;
    font-weight: 700; box-shadow: 0 2px 6px rgba(0, 0, 0, 0.5); }
  .note { margin-top: 0.3em; padding: 0.1em 0.7em; border-radius: 999px; background: rgba(16, 20, 15, 0.92); color: #ffd2c8;
    font-size: 0.17em; font-weight: 700; }
  .face .rob { color: #a3150c; }
  .win .face .rob { color: #ffd56b; }

  /* The tiles. `.place` keeps the meld's untransformed place (what the moves are measured from). */
  .meld { position: relative; display: flex; align-items: flex-end; filter: drop-shadow(0 6px 10px rgba(0, 0, 0, 0.55)); }
  .own { display: block; animation: own var(--ms) ease-out both; }
  @keyframes own { 0% { transform: translateY(12px); opacity: 0; } 8%, 100% { transform: none; opacity: 1; } }
  /* A call's meld: still while the tile comes, then to its place, as large as the melds there. */
  .call .meld { animation: land var(--ms) both; }
  @keyframes land {
    0%, 62% { transform: none; opacity: 1; filter: drop-shadow(0 6px 10px rgba(0, 0, 0, 0.55)); animation-timing-function: cubic-bezier(0.5, 0, 0.2, 1); }
    90%, 100% { transform: translate(var(--gx), var(--gy)) scale(var(--gk)); opacity: var(--go); filter: drop-shadow(0 0 0 rgba(0, 0, 0, 0)); }
  }
  /* A win's hand: it stays, a light runs over it, and it fades with the rest. */
  .win .meld { animation: stay var(--ms) ease-in both; }
  @keyframes stay { 0%, 84% { transform: none; opacity: 1; } 100% { transform: scale(1.04); opacity: 0; } }
  .shine { position: absolute; inset: -6% -2%; pointer-events: none; mix-blend-mode: screen;
    background: linear-gradient(105deg, transparent 38%, rgba(255, 236, 170, 0.85) 50%, transparent 62%) no-repeat;
    background-size: 40% 100%; animation: shine var(--ms) linear both; }
  @keyframes shine { 0%, 50% { background-position: -60% 0; opacity: 0; } 52% { opacity: 1; } 76% { background-position: 160% 0; opacity: 1; } 78%, 100% { opacity: 0; } }

  .slot { position: relative; width: var(--tw); height: calc(var(--tw) * 1.33); flex: none; }
  .slot.apart { margin-left: calc(var(--tw) * 0.3); }
  /* The tile called: from where it lay (as large as there), up over the gap (a little larger), down into it. */
  .called { position: absolute; left: 0; bottom: 0; z-index: 1; animation: drop var(--ms) both; }
  @keyframes drop {
    0% { transform: translate(var(--fx), var(--fy)) rotate(var(--fr)) scale(var(--fk)); opacity: var(--fo); animation-timing-function: cubic-bezier(0.3, 0.6, 0.3, 1); }
    6% { opacity: 1; }
    30% { transform: translate(0, -55%) rotate(-4deg) scale(1.08); animation-timing-function: cubic-bezier(0.6, 0, 0.9, 0.5); }
    44% { transform: translate(0, 0); animation-timing-function: ease-out; }
    49% { transform: translate(0, -9%); animation-timing-function: ease-in; }
    54%, 100% { transform: none; opacity: 1; }
  }
</style>
