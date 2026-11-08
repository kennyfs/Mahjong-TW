<!-- A player's name plate: their seat wind (lit for the dealer, with the streak), name, score, and whose turn it is. -->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import { windName } from '../lib/tiles';
  import { signed } from '../lib/ui.svelte';
  let { wind, name, score = null, dealer = false, streak = 0, turn = false, count = null, compact = false, away = false }: {
    wind: number; name: string; score?: number | null; dealer?: boolean; streak?: number; turn?: boolean; count?: number | null;
    compact?: boolean; away?: boolean;
  } = $props();
</script>

<div class="badge-row" class:turn class:compact class:away>
  <span class="wind" class:dealer title={dealer ? t('dealer_mark') : undefined}>{windName(wind)}</span>
  <span class="name ellipsis">{name}</span>
  {#if dealer}<span class="deal">{t('dealer_mark')}{#if streak > 0}<span class="streak">{t('streak_n', streak)}</span>{/if}</span>{/if}
  {#if count !== null}<span class="count">{t('hand_cards', count)}</span>{/if}
  {#if score !== null}<span class="score" class:pos={score > 0} class:neg={score < 0}>{signed(score)}</span>{/if}
</div>

<style>
  .badge-row { display: flex; align-items: center; gap: 6px; min-width: 0; padding: 2px 8px 2px 2px; border-radius: 16px;
    background: rgba(0, 0, 0, 0.16); color: var(--on-felt); font-size: 1em; line-height: 1.2; max-width: 100%;
    border: 2px solid transparent; }
  .badge-row.turn { border-color: var(--highlight); background: rgba(0, 0, 0, 0.28); }
  .badge-row.compact { font-size: 0.86em; gap: 4px; padding-right: 6px; }
  .badge-row.away { opacity: 0.6; }
  .wind { flex: none; width: 1.7em; height: 1.7em; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center;
    background: var(--on-felt); color: var(--felt); font-weight: 700; }
  .wind.dealer { background: var(--highlight); color: #3a2a00; }
  .name { font-weight: 500; }
  .deal { flex: none; color: var(--highlight); font-weight: 700; }
  .streak { margin-left: 2px; }
  .count { flex: none; color: var(--on-felt-dim); }
  .score { flex: none; font-weight: 700; margin-left: auto; }
  .score.pos { color: var(--felt-good); }
  .score.neg { color: var(--felt-bad); }
</style>
