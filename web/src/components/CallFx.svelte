<!--
  A moment over the whole screen (lib/moments.ts; Table.svelte starts it): what ends a hand (a win on a discard, a
  self-draw, a flower win, an exhaustive draw) and a declaration (天聽, 地聽). A dark veil; two black wedges slide in
  from opposite corners; across the middle a band laid askew, its white speed lines running; on it the word on its
  plaque (whose it is under it, and who dealt in), and the tiles:

  - a win: the winner's concealed tiles with a gap at their end, the winning tile coming from the river of the one
    who dealt in (a self-draw: lit, from above) and dropping into the gap; a light runs over the hand, and it all fades
    where it is (the hand's result comes next); a flower win: the winner's flowers, the last of them (七搶一: the one
    the other player held, from where it lies) coming into the gap;
  - a declaration: the declared hand (another's: its backs);
  - an exhaustive draw: the words alone, quieter (no lines, no wedges).

  The player's own win in gold, with rays and sparks; another's in steel blue, quieter (no lines, no wedges, a darker
  veil); a result the player neither gains nor loses by (another's win on another's discard, a draw: `m.neutral`)
  neutral: graphite, quiet. It lasts `ms`; a result stops where the light has run over the hand till the
  player's tap (Table.svelte), what turns and runs behind it going on. Nothing here takes a touch: a tap anywhere lets it go (the table's own listener).
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import type { Moment, Side } from '../lib/moments';
  import Tile from './Tile.svelte';

  let { m, w, ms }: { m: Moment; w: number; ms: number } = $props();

  // Where the tile comes from without a place to start from: a side of the screen (null: above).
  const AWAY: Record<Side, [string, string]> = { bottom: ['0px', '38vh'], top: ['0px', '-38vh'], left: ['-42vw', '0px'], right: ['42vw', '0px'] };
  const from = $derived(m.from ? AWAY[m.from] : ['0px', '-30vh']);
  // A long hand in smaller tiles on one line; tiles that would be smaller than MIN_TW: two lines.
  const MIN_TW = 34;
  const n = $derived(m.tiles.length + (m.gap >= 0 ? 1.4 : 0));
  let W = $state(0);
  const room = $derived(Math.min(W || innerWidth, 900) - 48);
  const two = $derived(room / Math.max(4, n) < MIN_TW && n > 4);
  const tw = $derived(Math.min(w, room / (two ? Math.ceil(n / 2) : Math.max(4, n))));
  let slot = $state<HTMLElement>();
  let start = $state<{ x: number; y: number; k: number } | null>(null);
  const centre = (r: DOMRect) => [r.left + r.width / 2, r.top + r.height / 2];
  onMount(() => {
    const s = m.src ? document.querySelector<HTMLElement>(m.src) : null;
    if (s && slot) {
      const r = s.getBoundingClientRect(), g = slot.getBoundingClientRect();
      const [a, b] = centre(r), [c, d] = centre(g);
      start = { x: a - c, y: b - d, k: r.width / g.width };
    }
  });
  const vars = $derived([
    `--ms: ${ms}ms`, `--tw: ${tw}px`,
    start ? `--fx: ${start.x}px; --fy: ${start.y}px; --fk: ${start.k}; --fo: 1; --fr: 0deg` : `--fx: ${from[0]}; --fy: ${from[1]}; --fk: 1; --fo: 0; --fr: -18deg`,
  ].join('; '));
  const before = $derived(m.gap >= 0 ? m.tiles.slice(0, m.gap) : m.tiles);
  const after = $derived(m.gap >= 0 ? m.tiles.slice(m.gap) : []);
  const win = $derived(m.kind === 'win');
  /** The player's own win, another's, a neutral result: their colors and their extras. */
  const tone = $derived(m.neutral ? 'neutral' : !win ? '' : m.mine ? 'mine' : 'theirs');
  const quiet = $derived(m.kind === 'drawn' || !!m.neutral || (win && !m.mine));
  const lively = $derived(win && m.mine);
  // The speed lines: fixed at random once, each its own height, length, weight and pace.
  const LINES = Array.from({ length: 22 }, (_, i) => ({
    y: (i * 37) % 100, len: 18 + ((i * 53) % 40), weight: 1 + ((i * 7) % 3), op: 0.25 + ((i * 29) % 60) / 100, dur: 0.35 + ((i * 17) % 40) / 100,
    delay: -((i * 13) % 50) / 100,
  }));
  // The sparks of the player's own win (lively): out from the hand as the tile lands.
  const SPARKS = Array.from({ length: 18 }, (_, i) => ({
    a: (i * 137.5) % 360, d: 22 + ((i * 41) % 26), s: 4 + ((i * 7) % 6), delay: ((i * 11) % 9) / 100,
  }));
</script>

<div class="fx {m.kind} {tone}" class:quiet class:lively style={vars} aria-hidden="true" bind:clientWidth={W}>
  <div class="veil"></div>
  {#if !quiet}
    <div class="band"><div class="lines">
      {#each LINES as l, i (i)}
        <span class="streak" style="top: {l.y}%; width: {l.len}%; height: {l.weight}px; opacity: {l.op}; --to: {10000 / l.len}%; animation-duration: {l.dur}s; animation-delay: {l.delay}s"></span>
      {/each}
    </div></div>
    <div class="wedge tl"></div>
    <div class="wedge br"></div>
  {:else}
    <div class="band calm"></div>
  {/if}
  {#if lively}<div class="rays"><div class="spin"></div></div>{/if}
  <div class="stage">
    <div class="plaque">
      <!-- 搶胡 (a claim taken from the player): the 搶 in another color. -->
      <span class="word"><span class="face">{#if m.word.startsWith('搶')}<span class="rob">搶</span>{m.word.slice(1)}{:else}{m.word}{/if}</span></span>
      {#if m.who}<span class="who">{m.who}</span>{/if}
      {#if m.note}<span class="note">{m.note}</span>{/if}
    </div>
    {#if m.tiles.length || m.gap >= 0}
      <div class="place">
        <div class="meld" class:two style={two ? `max-width: ${Math.ceil(n / 2) * tw + 2}px` : ''}>
          {#each before as t, i (i)}<span class="own"><Tile tile={t} w={tw} /></span>{/each}
          {#if m.gap >= 0}
            <span class="slot" class:apart={m.tiles.length > 0} bind:this={slot}>
              <span class="called"><Tile tile={m.tile} w={tw} glow={win} /></span>
            </span>
          {/if}
          {#each after as t, i (i)}<span class="own"><Tile tile={t} w={tw} /></span>{/each}
          {#if win || m.kind === 'declare'}<span class="shine"></span>{/if}
          {#if lively}
            <span class="sparks">{#each SPARKS as k, i (i)}<span class="spark" style="--a: {k.a}deg; --d: {k.d}vmin; --s: {k.s}px; animation-delay: calc(var(--ms) * {0.5 + k.delay * 0.7})"></span>{/each}</span>
          {/if}
        </div>
      </div>
    {/if}
  </div>
</div>

<style>
  .fx { position: fixed; inset: 0; z-index: 60; pointer-events: none; overflow: hidden; display: flex; align-items: center; justify-content: center;
    --edge: #ff8a73; --face: #d5382d; --ink: #fff6dc; --back: #8c1c15; --line: rgba(255, 214, 120, 0.9); --cut: 0.24em;
    --veil-in: rgba(18, 30, 26, 0.55); --veil-out: rgba(4, 8, 7, 0.82); --glint: rgba(255, 236, 170, 0.85); }
  /* The player's own win in gold, another's in steel blue; a declaration in jade; a draw in slate (neutral, below). */
  .fx.mine { --edge: #ffd36b; --face: #f7c948; --ink: #3b2200; --back: #a8740c; --line: rgba(59, 34, 0, 0.42);
    --veil-in: rgba(48, 34, 8, 0.5); --veil-out: rgba(10, 7, 2, 0.82); }
  .fx.theirs { --edge: #8fb3d9; --face: #3f5e7d; --ink: #eef5ff; --back: #22374d; --line: rgba(200, 225, 255, 0.5);
    --veil-in: rgba(14, 22, 34, 0.6); --veil-out: rgba(3, 6, 10, 0.86); --glint: rgba(210, 230, 255, 0.6); }
  .fx.declare { --edge: #7fe0c8; --face: #1f8a70; --ink: #effff9; --back: #0f4f40; --line: rgba(220, 255, 244, 0.5);
    --glint: rgba(200, 255, 236, 0.7); }
  .fx.drawn { --edge: #a9bcc6; --face: #5b7684; --ink: #fff; --back: #384b55; --line: rgba(255, 255, 255, 0.45);
    --veil-in: rgba(20, 26, 30, 0.5); --veil-out: rgba(6, 9, 11, 0.8); }
  .fx.quiet.theirs { --veil-in: rgba(8, 12, 18, 0.7); --veil-out: rgba(2, 4, 7, 0.9); }
  /* Neutral (nothing gained or lost by the player): graphite, the light a plain white. */
  .fx.neutral { --edge: #c5ccd1; --face: #4d565c; --ink: #f4f6f7; --back: #2b3236; --line: rgba(255, 255, 255, 0.4);
    --veil-in: rgba(22, 25, 27, 0.6); --veil-out: rgba(6, 7, 8, 0.86); --glint: rgba(240, 244, 248, 0.6); }
  .fx.neutral .note { color: #e3e7ea; }

  /* The veil and the frame come, stay and go with the moment. */
  .veil { position: absolute; inset: 0; background: radial-gradient(ellipse at 50% 50%, var(--veil-in), var(--veil-out));
    animation: veil var(--ms) linear both; }
  @keyframes veil { 0% { opacity: 0; } 8% { opacity: 1; } 84% { opacity: 1; } 100% { opacity: 0; } }

  /* Two black wedges from opposite corners, a fine line of the moment's color along their long edge. */
  .wedge { position: absolute; width: 62vw; height: 46vh; background: #050706; }
  .wedge::after { content: ''; position: absolute; inset: 0; background: var(--edge); }
  .wedge.tl { left: 0; top: 0; clip-path: polygon(0 0, 100% 0, 0 100%); animation: tl var(--ms) cubic-bezier(0.2, 0.8, 0.2, 1) both; }
  .wedge.tl::after { clip-path: polygon(calc(100% - 3px) 0, 100% 0, 0 100%, 0 calc(100% - 2px)); }
  .wedge.br { right: 0; bottom: 0; clip-path: polygon(100% 0, 100% 100%, 0 100%); animation: br var(--ms) cubic-bezier(0.2, 0.8, 0.2, 1) both; }
  .wedge.br::after { clip-path: polygon(100% 0, 100% 2px, 3px 100%, 0 100%); }
  @keyframes tl { 0% { transform: translate(-60%, -60%); } 12% { transform: none; } 84% { transform: none; opacity: 1; } 100% { transform: translate(-60%, -60%); opacity: 0; } }
  @keyframes br { 0% { transform: translate(60%, 60%); } 12% { transform: none; } 84% { transform: none; opacity: 1; } 100% { transform: translate(60%, 60%); opacity: 0; } }

  /* The band across the middle, askew, long enough to reach past the screen's corners, there from the start (it comes in
     as the veil does), and its speed lines running along it, each over its whole length. A quiet one: level, still. */
  .band { position: absolute; left: -50vmax; right: -50vmax; top: 50%; height: clamp(180px, 46vh, 420px); translate: 0 -50%; rotate: -14deg;
    background: linear-gradient(180deg, transparent, rgba(255, 255, 255, 0.07) 18%, rgba(255, 255, 255, 0.1) 50%, rgba(255, 255, 255, 0.07) 82%, transparent);
    overflow: hidden; animation: band var(--ms) ease-out both; }
  .band.calm { rotate: 0deg; background: linear-gradient(180deg, transparent, rgba(255, 255, 255, 0.05) 25%, rgba(255, 255, 255, 0.07) 50%, rgba(255, 255, 255, 0.05) 75%, transparent); }
  @keyframes band { 0% { opacity: 0; } 8% { opacity: 1; } 84% { opacity: 1; } 100% { opacity: 0; } }
  /* The lines run over the part of the band on the screen (as wide as the screen, turned, and a little more). */
  .lines { position: absolute; top: 0; bottom: 0; left: 50%; width: calc(110vw + 0.5 * clamp(180px, 46vh, 420px)); translate: -50% 0; }
  .streak { position: absolute; left: 0; border-radius: 2px; background: linear-gradient(90deg, transparent, #fff 35%, #fff 80%, transparent);
    animation-name: run; animation-timing-function: linear; animation-iteration-count: infinite; }
  @keyframes run { from { transform: translateX(-100%); } to { transform: translateX(var(--to)); } }

  /* The player's own win (lively): rays of gold turning slowly behind the plaque. */
  .rays { position: absolute; left: 50%; top: 50%; width: 150vmax; height: 150vmax; translate: -50% -50%;
    mask-image: radial-gradient(circle, #000 0, #000 8%, transparent 42%); -webkit-mask-image: radial-gradient(circle, #000 0, #000 8%, transparent 42%);
    animation: rays var(--ms) linear both; }
  @keyframes rays { 0% { opacity: 0; } 10% { opacity: 1; } 84% { opacity: 1; } 100% { opacity: 0; } }
  /* (Turning on its own: it keeps turning while the result waits for the tap.) */
  .spin { position: absolute; inset: 0; background: repeating-conic-gradient(from 0deg, rgba(255, 214, 110, 0.2) 0deg 5deg, transparent 5deg 15deg);
    animation: spin 36s linear infinite; }
  @keyframes spin { to { rotate: 360deg; } }

  /* The word and the tiles, upright on the band. */
  .stage { position: relative; display: flex; flex-direction: column; align-items: center; gap: clamp(26px, 6vh, 48px); }
  .plaque { display: flex; flex-direction: column; align-items: center; font-size: clamp(3rem, 15vmin, 7.5rem);
    animation: plaque var(--ms) cubic-bezier(0.2, 0.9, 0.3, 1.25) both; }
  @keyframes plaque { 0% { transform: scale(1.6); opacity: 0; } 10% { transform: scale(1); opacity: 1; } 80% { opacity: 1; } 100% { opacity: 0; transform: scale(0.94); } }
  .quiet .plaque { animation-name: plaque-quiet; animation-timing-function: ease-out; }
  @keyframes plaque-quiet { 0% { transform: translateY(-0.12em); opacity: 0; } 14% { transform: none; opacity: 1; } 80% { opacity: 1; } 100% { opacity: 0; } }
  .word { position: relative; display: block; rotate: -4deg; padding-bottom: 0.09em; filter: drop-shadow(0 0.08em 0.12em rgba(0, 0, 0, 0.6)); }
  .quiet .word { rotate: 0deg; }
  .word::before, .face { clip-path: polygon(var(--cut) 0, calc(100% - var(--cut)) 0, 100% var(--cut),
    100% calc(100% - var(--cut)), calc(100% - var(--cut)) 100%, var(--cut) 100%, 0 calc(100% - var(--cut)), 0 var(--cut)); }
  .word::before { content: ''; position: absolute; inset: 0.09em 0 0; background: var(--back); }
  .face { position: relative; display: block; height: 1.45em; line-height: 1.45em; padding: 0 0.38em; background: var(--face); color: var(--ink);
    font-weight: 900; letter-spacing: 0.04em; font-family: 'Noto Serif CJK TC', 'Noto Serif TC', 'Source Han Serif TC', 'Songti TC', 'PMingLiU', serif; }
  .who { margin-top: -0.12em; z-index: 1; padding: 0.05em 0.6em; border-radius: 0.3em; background: #10140f; color: #fff; font-size: 0.22em;
    font-weight: 700; box-shadow: 0 2px 6px rgba(0, 0, 0, 0.5); }
  .note { margin-top: 0.3em; padding: 0.1em 0.7em; border-radius: 999px; background: rgba(16, 20, 15, 0.92); color: #ffd2c8;
    font-size: 0.17em; font-weight: 700; }
  .face .rob { color: #ffd56b; }
  .mine .face .rob { color: #a3150c; }

  /* The tiles. */
  .meld { position: relative; display: flex; align-items: flex-end; filter: drop-shadow(0 6px 10px rgba(0, 0, 0, 0.55));
    animation: stay var(--ms) ease-in both; }
  .meld.two { flex-wrap: wrap; justify-content: center; row-gap: calc(var(--tw) * 0.18); }
  @keyframes stay { 0%, 84% { transform: none; opacity: 1; } 100% { transform: scale(1.04); opacity: 0; } }
  .own { display: block; animation: own var(--ms) ease-out both; }
  @keyframes own { 0% { transform: translateY(12px); opacity: 0; } 8%, 100% { transform: none; opacity: 1; } }
  .shine { position: absolute; inset: -6% -2%; pointer-events: none; mix-blend-mode: screen;
    background: linear-gradient(105deg, transparent 38%, var(--glint) 50%, transparent 62%) no-repeat;
    background-size: 40% 100%; animation: shine var(--ms) linear both; }
  @keyframes shine { 0%, 50% { background-position: -60% 0; opacity: 0; } 52% { opacity: 1; } 76% { background-position: 160% 0; opacity: 1; } 78%, 100% { opacity: 0; } }
  .declare .shine { animation-name: shine-early; }
  @keyframes shine-early { 0%, 24% { background-position: -60% 0; opacity: 0; } 26% { opacity: 1; } 52% { background-position: 160% 0; opacity: 1; } 54%, 100% { opacity: 0; } }

  .slot { position: relative; width: var(--tw); height: calc(var(--tw) * 1.45); flex: none; }
  .slot.apart { margin-left: calc(var(--tw) * 0.3); }
  /* The tile: from where it lay (as large as there), up over the gap (a little larger), down into it. */
  .called { position: absolute; left: 0; bottom: 0; z-index: 1; animation: drop var(--ms) both; }
  @keyframes drop {
    0% { transform: translate(var(--fx), var(--fy)) rotate(var(--fr)) scale(var(--fk)); opacity: var(--fo); animation-timing-function: cubic-bezier(0.65, 0, 0.35, 1); }
    6% { opacity: 1; }
    30% { transform: translate(0, -55%) rotate(-4deg) scale(1.08); animation-timing-function: cubic-bezier(0.65, 0, 0.35, 1); }
    44% { transform: translate(0, 0); animation-timing-function: ease-out; }
    49% { transform: translate(0, -9%); animation-timing-function: ease-in; }
    54%, 100% { transform: none; opacity: 1; }
  }

  /* Sparks from the player's own hand as the tile lands. */
  .sparks { position: absolute; left: 50%; top: 50%; width: 0; height: 0; }
  .spark { position: absolute; left: 0; top: 0; width: var(--s); height: var(--s); border-radius: 50%; opacity: 0;
    background: radial-gradient(circle, #fff7d6, #ffd36b 55%, rgba(255, 180, 60, 0) 70%);
    animation: spark calc(var(--ms) * 0.22) cubic-bezier(0.1, 0.7, 0.3, 1) both; }
  @keyframes spark { 0% { opacity: 1; transform: rotate(var(--a)) translateX(0) scale(1.2); }
    100% { opacity: 0; transform: rotate(var(--a)) translateX(var(--d)) scale(0.4); } }
</style>
