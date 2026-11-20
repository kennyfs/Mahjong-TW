<!--
  The player's choices other than discarding: win (胡, 自摸), declaring a ready hand (聽), kongs, pung, each way of a chow (its three tiles), and
  pass. The player's own legal actions, not advice. A computer shows each one's key. The tiles are large (`w` is the
  hand's tile), and `claimed`, the discard (or added tile) being claimed, is lit in each choice. `marks` (with the
  analysis shown: a branch, a practice hand with its hints): on each choice what it costs against the best, and a ring
  on the one made there (a step gone back to).
-->
<script lang="ts">
  import type { ChoiceMark } from '../lib/review';
  import type { Action } from '../lib/types';
  import { t } from '../lib/i18n.svelte';
  import Tile from './Tile.svelte';
  let { actions, w, onact, keys = false, claimed = null, marks = null }: {
    actions: Action[]; w: number; onact: (a: Action) => void; keys?: boolean; claimed?: string | null;
    marks?: ((a: Action) => ChoiceMark | null) | null;
  } = $props();
  const tw = $derived(w * 0.8);

  const ORDER = ['ron', 'tsumo', 'declare', 'kan', 'ankan', 'kakan', 'pon', 'chi', 'pass'];
  const LABEL: Record<string, string> = {
    ron: 'act_ron', tsumo: 'act_tsumo', kan: 'act_kan', ankan: 'act_ankan', kakan: 'act_kakan', pon: 'act_pon', chi: 'act_chi', pass: 'act_pass',
    declare: 'act_declare',
  };
  /** The keys of a computer (Table.svelte): H wins, G kongs, P pungs, C chows (1 2 3 its ways), Space passes. */
  const KEY: Record<string, string> = { ron: 'H', tsumo: 'H', kan: 'G', ankan: 'G', kakan: 'G', pon: 'P', chi: 'C', pass: 'Space', declare: 'T' };
  const shown = $derived(
    actions.filter((a) => a.type !== 'discard').sort((a, b) => ORDER.indexOf(a.type) - ORDER.indexOf(b.type)),
  );
  const chis = $derived(shown.filter((a) => a.type === 'chi'));
</script>

{#if shown.length}
  <div class="bar" role="group">
    {#each shown as a, i (a.type + (a.tile ?? '') + i)}
      {@const m = marks?.(a) ?? null}
      <button class="claim {a.type}" class:played={m?.played} onclick={() => onact(a)}>
        <span class="word">{t(LABEL[a.type])}</span>
        {#if a.type === 'chi' && a.tiles}
          {@const at = claimed ? a.tiles.indexOf(claimed) : -1}
          <span class="tiles">{#each a.tiles as x, k}<Tile tile={x} w={tw} glow={k === at} />{/each}</span>
        {:else if a.tile && a.type !== 'ron'}
          <span class="tiles"><Tile tile={a.tile} w={tw} glow={a.tile === claimed} /></span>
        {/if}
        {#if m}<span class="cost {m.tone}">{m.tag}</span>{/if}
        {#if keys}<kbd>{a.type === 'chi' && chis.length > 1 ? `${chis.indexOf(a) + 1}` : KEY[a.type]}</kbd>{/if}
      </button>
    {/each}
  </div>
{/if}

<style>
  .bar { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; align-items: center; pointer-events: auto; }
  .claim { display: inline-flex; align-items: center; gap: 6px; min-height: 48px; padding: 5px 14px; border-radius: 24px; border: none;
    cursor: pointer; font-weight: 700; font-size: 1.125rem; background: var(--action); color: var(--on-action);
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.35); }
  /* A win, larger than the rest and ringed in gold: not to be missed or tapped past. */
  .claim.ron, .claim.tsumo { min-height: 76px; padding: 6px 30px; border-radius: 38px; font-size: 2rem; letter-spacing: 0.08em; color: #fff;
    background: linear-gradient(180deg, #ff7043, #d84315); box-shadow: 0 0 0 3px #ffd36b, 0 4px 14px rgba(0, 0, 0, 0.5); }
  .claim.pass { background: var(--surface-container-highest); color: var(--on-surface); font-weight: 500; }
  .tiles { display: inline-flex; gap: 1px; }
  .claim.played { outline: 3px solid var(--highlight); outline-offset: 1px; }
  /* What the choice costs: as the tags over the hand's tiles (MyHand.svelte), in the grade's color. */
  .cost { padding: 1px 8px; border-radius: 8px; font-size: 0.8125rem; font-weight: 700; background: rgba(0, 0, 0, 0.62); color: #fff;
    font-variant-numeric: tabular-nums; }
  .cost.best { background: var(--found); color: #fff; }
  .cost.g1 { background: var(--danger-1); color: var(--on-danger); }
  .cost.g2 { background: var(--danger-2); color: var(--on-danger); }
  .cost.g3 { background: var(--danger-3); color: #fff; }
  kbd { font: inherit; font-size: 0.7rem; font-weight: 500; padding: 0 4px; border-radius: 4px; border: 1px solid currentColor; opacity: 0.7; }
</style>
