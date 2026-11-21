<!--
  A finished hand: who won and how, the winner's hand (the winning tile apart), its tai items by name, the payments
  (under each, how it comes about: the bases and the tai, the dealer's among them), and the next dealer. The tai's
  total says what every payer pays in tai: the dealer's tai is in it when every payment has it. `final` is the hand's view with everything shown (reveal). `rev`: the network's review of the
  player's decisions, in a line (the expected result at the first decision, what the decisions lost, the rest as luck).
-->
<script lang="ts">
  import type { Snippet } from 'svelte';
  import { t } from '../lib/i18n.svelte';
  import type { ReviewSummary } from '../lib/records.svelte';
  import { mistakesOf, mistakesText, pts } from '../lib/review';
  import { meldTiles, flowersOf } from '../lib/seats';
  import { settings } from '../lib/settings.svelte';
  import { parseTiles, sortTiles, taiName, without } from '../lib/tiles';
  import type { View } from '../lib/types';
  import { signed } from '../lib/ui.svelte';
  import Tile from './Tile.svelte';
  let { final, names, me, w = 30, actions, compact = false, rev = null, next = true, rules }: {
    final: View; names: string[]; me: number; w?: number; actions?: Snippet; compact?: boolean; rev?: ReviewSummary | null;
    /** The rules in words (a review's: the hand may be old). */
    rules?: string;
    /** The line on the next hand's dealer (not for a review's branch: there is no next hand). */
    next?: boolean;
  } = $props();
  const mistakes = $derived(rev ? mistakesOf(rev, settings.level) : 0);

  const r = $derived(final.result!);
  const title = $derived.by(() => {
    if (r.exhausted) return t('result_exhausted');
    const w0 = r.winners[0];
    if (!w0) return t('result_exhausted');
    if (r.flower_win) return t('result_flower_win', names[w0.seat]);
    if (r.self_draw) return t('result_tsumo', names[w0.seat]);
    return r.winners.map((x) => t('result_ron', names[x.seat])).join(t('sep_list'));
  });
  const sub = $derived(!r.exhausted && !r.self_draw && r.loser >= 0 ? t('result_ron_from', names[r.loser]) : '');
  function winnerTiles(seat: number) {
    const all = sortTiles(parseTiles(final.hands?.[seat] ?? ''));
    const win = r.win_tile && !r.flower_win ? r.win_tile : null;
    return { rest: win ? without(all, [win]) : all, win };
  }
  // Payments involving the dealer carry the dealer's tai: every payment of a win by the dealer or on the dealer's
  // discard (the total says them all, then); of another's self-drawn win only the dealer's own (said after the total).
  const dealer = $derived(final.position.setup.dealer);
  const dealerWord = $derived(final.position.setup.streak > 0 ? t('result_dealer_streak_tai', r.dealer_tai, final.position.setup.streak) : t('result_dealer_tai', r.dealer_tai));
  const dealerIn = (seat: number) => r.dealer_tai > 0 && !r.exhausted && (seat === dealer || (!r.self_draw && r.loser === dealer));
  const dealerPays = (seat: number) => r.dealer_tai > 0 && !r.exhausted && r.self_draw && seat !== dealer && r.points[dealer] < 0;
  /** How a seat's points come about, "底 2＋1 台" for −3: the bases, and the tai (with the dealer's where they count). */
  function parts(seat: number): string {
    const bases = r.base_delta?.[seat] ?? 0;
    if (!bases) return '';
    const tai = r.tai_delta?.[seat] ?? 0;
    const base = Math.abs(r.points[seat] - tai);
    return t('result_parts', Number.isInteger(base) ? base.toFixed(0) : base.toFixed(1), String(Math.abs(tai)));
  }
</script>

<div class="result" class:compact>
  <div class="head">
    <div class="title headline-small">{title}</div>
    {#if sub}<div class="sub title-medium">{sub}</div>{/if}
    {#if rules}<div class="rules body-small">{rules}</div>{/if}
  </div>

  {#each r.winners as wn (wn.seat)}
    {@const wt = winnerTiles(wn.seat)}
    {@const p = final.position.players[wn.seat]}
    <div class="hand-line">
      {#each p.melds as m}<span class="group">{#each meldTiles(m) as x}<Tile tile={x} {w} />{/each}</span>{/each}
      <span class="group rest">{#each wt.rest as x}<Tile tile={x} {w} />{/each}</span>
      {#if wt.win}<span class="group win"><Tile tile={wt.win} {w} glow /></span>{/if}
      {#if flowersOf(p).length}<span class="group">{#each flowersOf(p) as f}<Tile tile={f} w={w * 0.8} />{/each}</span>{/if}
    </div>
    <div class="items">
      {#each wn.items as it (it.item)}
        <div class="item"><span>{taiName(it.item, it.name)}</span><span class="tai">{t('tai_unit', it.tai)}</span></div>
      {/each}
      {#if dealerIn(wn.seat)}<div class="item dealer"><span>{dealerWord}</span></div>{/if}
      <div class="item total"><span>{t('result_tai_total', wn.tai + (dealerIn(wn.seat) ? r.dealer_tai : 0))}</span></div>
      {#if dealerPays(wn.seat)}<div class="item dealer more"><span>{t('result_dealer_pays', dealerWord)}</span></div>{/if}
    </div>
  {/each}

  <div class="pays">
    {#each [0, 1, 2, 3] as seat}
      <div class="pay" class:me={seat === me}>
        <span class="ellipsis">{names[seat]}</span>
        <span class="pts" class:good={r.points[seat] > 0} class:bad={r.points[seat] < 0}>{signed(r.points[seat])}</span>
        {#if parts(seat)}<span class="parts">{parts(seat)}</span>{/if}
      </div>
    {/each}
  </div>
  {#if next}
    <div class="next body-medium dim">
      {r.next_streak > 0 ? t('result_next_streak', names[r.next_dealer], r.next_streak) : t('result_next_dealer', names[r.next_dealer])}
    </div>
  {/if}
  {#if rev}
    <div class="rev body-medium">
      <span>{t('review_line', pts(rev.first), pts(-rev.loss), pts(rev.luck))}</span>
      <span class="count" class:some={mistakes > 0}>{mistakesText(rev.grades, settings.level)}</span>
    </div>
  {/if}
  {#if actions}<div class="actions">{@render actions()}</div>{/if}
</div>

<style>
  .result { display: flex; flex-direction: column; gap: 10px; padding: 16px; border-radius: 20px; background: var(--surface-container-high);
    color: var(--on-surface); box-shadow: 0 6px 24px rgba(0, 0, 0, 0.4); max-height: 100%; overflow: auto; }
  .result.compact { padding: 12px; gap: 8px; }
  .head { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
  .title { color: var(--primary); }
  .rules { margin-left: auto; color: var(--on-surface-variant); }
  .hand-line { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 6px 0; }
  .group { display: inline-flex; margin-right: 8px; }
  /* The concealed tiles keep together: on a line of their own where they do not fit after the melds. */
  .group.rest { flex-wrap: wrap; row-gap: 6px; max-width: 100%; }
  .group.win { margin-left: 8px; }
  .items { display: flex; flex-wrap: wrap; gap: 6px; }
  .item { display: inline-flex; gap: 6px; padding: 3px 10px; border-radius: 10px; background: var(--secondary-container);
    color: var(--on-secondary-container); font-weight: 500; }
  .item .tai { font-weight: 700; }
  .item.total { background: var(--primary); color: var(--on-primary); font-weight: 700; }
  .item.dealer { background: var(--tertiary-container); color: var(--on-tertiary-container); }
  .pays { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 6px; }
  .pay { display: flex; flex-direction: column; align-items: center; padding: 4px; border-radius: 10px; background: var(--surface-container); }
  .pay.me { outline: 2px solid var(--primary); }
  .pts { font-weight: 700; font-size: 1.125rem; }
  /* (Two lines where a quarter of the card is too narrow: "Base 2 + 1 tai" on a phone, two-digit tai.) */
  .parts { font-size: 0.75rem; line-height: 1.2; color: var(--on-surface-variant); text-align: center; max-width: 100%; }
  .rev { display: flex; flex-wrap: wrap; gap: 2px 12px; padding: 6px 10px; border-radius: 10px; background: var(--surface-container);
    font-variant-numeric: tabular-nums; }
  .count { font-weight: 700; color: var(--good); }
  .count.some { color: var(--bad); }
  /* The buttons stay in sight where the card is taller than its room and scrolls (a phone held sideways). */
  .actions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; position: sticky; bottom: -16px; z-index: 1;
    margin: -4px -16px -16px; padding: 8px 16px 16px; background: var(--surface-container-high); border-radius: 0 0 20px 20px; }
  .result.compact .actions { bottom: -12px; margin: -4px -12px -12px; padding: 8px 12px 12px; }
</style>
