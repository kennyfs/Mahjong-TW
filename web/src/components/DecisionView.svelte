<!--
  A decision as a list, in place of the table (a branch's, on a phone or a window without room beside the table): the
  deciding seat's tiles over the list of every action (DecisionPanel). The player may
  play here: a tap chooses a tile (its row is lit) and a second discards it; the choices' buttons are under the hand.
  While there is nothing to show (the computer players playing) the tiles are the player's own and the list keeps
  its shape (PanelSkeleton). On a wide window the tiles at the left, the list at the right.

  `view`: the deciding seat's view (or the player's, meanwhile); `heading`: whose turn it is, in a few words.
  `canPlay`: the player's decision, now (`view` is theirs). `waitShown`: the tiles a ready hand waits with have blue
  faces (as on the table, Table.svelte).
-->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import { DRAWN_GAP } from '../lib/layout';
  import { tileMarks, type ChoiceMark, type TileMark } from '../lib/review';
  import { flowersOf, meldTiles } from '../lib/seats';
  import { groupIndices, parseTiles, sortTiles, windName, without, type Tile as T } from '../lib/tiles';
  import type { Action, Analysis, View } from '../lib/types';
  import ClaimBar from './ClaimBar.svelte';
  import DecisionPanel from './DecisionPanel.svelte';
  import MyHand from './MyHand.svelte';
  import PanelSkeleton from './PanelSkeleton.svelte';
  import Tile from './Tile.svelte';

  let { view, heading, analysis, played = -1, unit = undefined, names, who = '', canPlay = false, onact, choices = null,
    selected = $bindable(null), wide = false, hw, waitShown = false }: {
    view: View | null; heading: string; analysis: Analysis | null; played?: number; unit?: number; names: string[];
    who?: string; canPlay?: boolean; onact: (a: Action) => void; choices?: ((a: Action) => ChoiceMark | null) | null;
    selected?: number | null; wide?: boolean; hw: number; waitShown?: boolean;
  } = $props();

  const NO_MARKS = new Map<T, TileMark>();
  // The tiles as wide as `hw`, or less where the hand's column is narrower than the window says (a side rail, halves):
  // eight a row and the drawn one after a gap.
  let colW = $state(0);
  const w = $derived(colW ? Math.min(hw, (colW - 4) / (8 + DRAWN_GAP + 1)) : hw);
  const hand = $derived.by(() => {
    if (!view) return null;
    const pos = view.position;
    const all = parseTiles(pos.hand);
    const drawn = view.phase === 'self' && pos.drawn ? pos.drawn : null;
    const p = pos.players[pos.me];
    const where = `${t('round_wind', windName(pos.setup.round))}・${t('wall_left', pos.wall_left)}`;
    return { tiles: sortTiles(drawn ? without(all, [drawn]) : all), drawn, melds: p.melds, flowers: flowersOf(p), claim: pos.claim, where };
  });
  const discardable = $derived(new Set(canPlay && view ? view.legal.filter((a) => a.type === 'discard').map((a) => a.tile as T) : []));
  const claims = $derived(canPlay && view ? view.legal.filter((a) => a.type !== 'discard') : []);
  /** The tile chosen, its row lit in the list. */
  const picked = $derived(hand && selected !== null ? (selected < hand.tiles.length ? hand.tiles[selected] : hand.drawn) : null);
  const waitAt = $derived.by(() => {
    if (!waitShown || !view || !hand) return null;
    if (view.phase === 'self' && view.current === view.position.me) {
      const i = selected ?? (hand.drawn ? hand.tiles.length : null);
      const x = i === null ? null : i < hand.tiles.length ? hand.tiles[i] : hand.drawn;
      const g = x ? view.ready_tiles?.[x] : null;
      return g ? groupIndices(hand.tiles, hand.drawn, parseTiles(g), i) : null;
    }
    return view.wait_tiles ? groupIndices(hand.tiles, hand.drawn, parseTiles(view.wait_tiles)) : null;
  });
  const pickedIndex = $derived(analysis && picked ? analysis.actions.findIndex((f) => f.action.type === 'discard' && f.action.tile === picked) : -1);
</script>

<div class="list-view" class:halves={wide}>
  <div class="seat-hand" bind:clientWidth={colW}>
    <div class="head body-medium">
      <span class="dim">{[hand?.where, heading].filter(Boolean).join('・')}</span>
    </div>
    {#if hand}
      <MyHand tiles={hand.tiles} drawn={hand.drawn} {w} perRow={8} bind:selected tagH={17}
        marks={analysis ? tileMarks(analysis, played, unit) : NO_MARKS} canDiscard={(x) => discardable.has(x)}
        ondiscard={(x) => onact({ type: 'discard', tile: x })} turn={discardable.size > 0} pick={!claims.length || discardable.size > 0} {waitAt} />
      {#if hand.claim?.tile}
        <div class="claimed body-medium"><span>{names[hand.claim.seat]} {t('act_discard')}</span><Tile tile={hand.claim.tile} {w} mark /></div>
      {/if}
      {#if claims.length}<ClaimBar actions={claims} {w} {onact} claimed={hand.claim?.tile ?? null} marks={choices} />{/if}
      {#if hand.melds.length || hand.flowers.length}
        <div class="melds">
          {#each hand.melds as m, i (i)}<span class="meld">{#each meldTiles(m) as x}<Tile tile={x} w={w * 0.75} />{/each}</span>{/each}
          {#each hand.flowers as f (f)}<Tile tile={f} w={w * 0.6} />{/each}
        </div>
      {/if}
    {/if}
  </div>
  <div class="list-scroll">
    {#if analysis}
      <DecisionPanel {analysis} {played} {unit} w={Math.max(30, hw * 0.8)} {names} picked={pickedIndex} {who} />
    {:else}
      <PanelSkeleton w={Math.max(30, hw * 0.8)} />
    {/if}
  </div>
</div>

<style>
  .list-view { flex: 1; min-height: 0; overflow: auto; display: flex; flex-direction: column; gap: 8px; padding: 8px 12px 16px; }
  .list-view.halves { flex-direction: row; overflow: hidden; gap: 12px; }
  .seat-hand { display: flex; flex-direction: column; gap: 8px; }
  .halves .seat-hand { flex: 0 0 46%; min-width: 0; overflow: auto; }
  .halves .list-scroll { flex: 1; min-width: 0; overflow: auto; }
  .list-view:not(.halves) .list-scroll { max-width: 760px; }
  .head { display: flex; align-items: center; gap: 8px; min-height: 40px; }
  .dim { color: var(--on-surface-variant); }
  .seat-hand :global(.bar) { justify-content: flex-start; }
  .claimed { display: flex; align-items: center; gap: 8px; }
  .melds { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: flex-end; }
  .meld { display: inline-flex; }
</style>
