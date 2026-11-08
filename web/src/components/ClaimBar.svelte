<!--
  The player's choices other than discarding: win (胡, 自摸), kongs, pung, each way of a chow (its three tiles), and
  pass. The player's own legal actions, not advice. A computer shows each one's key.
-->
<script lang="ts">
  import type { Action } from '../lib/types';
  import { t } from '../lib/i18n.svelte';
  import Tile from './Tile.svelte';
  let { actions, w, onact, keys = false }: { actions: Action[]; w: number; onact: (a: Action) => void; keys?: boolean } = $props();

  const ORDER = ['ron', 'tsumo', 'kan', 'ankan', 'kakan', 'pon', 'chi', 'pass'];
  const LABEL: Record<string, string> = {
    ron: 'act_ron', tsumo: 'act_tsumo', kan: 'act_kan', ankan: 'act_ankan', kakan: 'act_kakan', pon: 'act_pon', chi: 'act_chi', pass: 'act_pass',
  };
  /** The keys of a computer (Table.svelte): H wins, G kongs, P pungs, C chows (1 2 3 its ways), Space passes. */
  const KEY: Record<string, string> = { ron: 'H', tsumo: 'H', kan: 'G', ankan: 'G', kakan: 'G', pon: 'P', chi: 'C', pass: 'Space' };
  const shown = $derived(
    actions.filter((a) => a.type !== 'discard').sort((a, b) => ORDER.indexOf(a.type) - ORDER.indexOf(b.type)),
  );
  const chis = $derived(shown.filter((a) => a.type === 'chi'));
</script>

{#if shown.length}
  <div class="bar" role="group">
    {#each shown as a, i (a.type + (a.tile ?? '') + i)}
      <button class="claim {a.type}" onclick={() => onact(a)}>
        <span class="word">{t(LABEL[a.type])}</span>
        {#if a.type === 'chi' && a.tiles}
          <span class="tiles">{#each a.tiles as x}<Tile tile={x} w={w * 0.62} />{/each}</span>
        {:else if a.tile && a.type !== 'ron'}
          <span class="tiles"><Tile tile={a.tile} w={w * 0.62} /></span>
        {/if}
        {#if keys}<kbd>{a.type === 'chi' && chis.length > 1 ? `${chis.indexOf(a) + 1}` : KEY[a.type]}</kbd>{/if}
      </button>
    {/each}
  </div>
{/if}

<style>
  .bar { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; align-items: center; pointer-events: auto; }
  .claim { display: inline-flex; align-items: center; gap: 6px; min-height: 48px; padding: 4px 14px; border-radius: 24px; border: none;
    cursor: pointer; font-weight: 700; font-size: 1.125rem; background: var(--action); color: var(--on-action);
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.35); }
  .claim.ron, .claim.tsumo { background: #d84315; color: #fff; font-size: 1.25rem; }
  .claim.pass { background: var(--surface-container-highest); color: var(--on-surface); font-weight: 500; }
  .tiles { display: inline-flex; gap: 1px; }
  kbd { font: inherit; font-size: 0.7rem; font-weight: 500; padding: 0 4px; border-radius: 4px; border: 1px solid currentColor; opacity: 0.7; }
</style>
