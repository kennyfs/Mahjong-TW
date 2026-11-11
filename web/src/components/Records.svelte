<!--
  The records: every finished hand, newest first, in three tabs (practice, table battles, same-tiles contests); a
  line's tap opens the hand's review (replay, and each decision's analysis). Favorites stay when the rest are cleared.
  Battles are listed as groups of their hands. Over the list, what the network's reviews of these hands add up to:
  the points lost a hand, the mistakes a hand at the settings' level, and which kind of decision lost them.
-->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import { records, type BattleGroup, type HandRecord, type RecordKind } from '../lib/records.svelte';
  import * as engine from '../lib/engine';
  import { keepSummary, KINDS_OF_LOSS, levelOrWorse, mistakesOf, pts } from '../lib/review';
  import { flowerLabel, parseChoice, taiLabel } from '../lib/rules';
  import { settings } from '../lib/settings.svelte';
  import { localTime, signed } from '../lib/ui.svelte';
  import Dialog from './Dialog.svelte';
  import Icon from './Icon.svelte';
  import TitleBar from './TitleBar.svelte';
  import Toggle from './Toggle.svelte';

  let { onreview }: { onreview: (id: string) => void } = $props();
  let tab = $state<RecordKind>('practice');
  let favOnly = $state(false);
  let confirmClear = $state(false);
  let openBattle = $state<string | null>(null);

  const hands = $derived(records.hands.filter((h) => h.kind === tab && (!favOnly || h.fav)).slice().reverse());
  const battles = $derived(records.battles.filter((b) => b.kind === tab && (!favOnly || b.fav || records.ofGroup(b.id).some((h) => h.fav))).slice().reverse());
  const stats = $derived.by(() => {
    const list = records.hands.filter((h) => h.kind === tab);
    const wins = list.filter((h) => h.winners.includes(h.me)).length;
    const dealtIn = list.filter((h) => h.loser === h.me).length;
    const total = list.reduce((a, h) => a + h.points[h.me], 0);
    return t('records_stats', list.length, wins, dealtIn, signed(total));
  });

  // The reviews of this tab's hands, added up; the hands not reviewed by this network can be, one after another.
  const reviewed = $derived(records.hands.filter((h) => h.kind === tab && h.rev?.m === engine.MODEL_ID));
  const pending = $derived(records.hands.filter((h) => h.kind === tab && h.rev?.m !== engine.MODEL_ID));
  const review = $derived.by(() => {
    const k = reviewed.length;
    if (!k) return null;
    const loss = reviewed.reduce((a, h) => a + h.rev!.loss, 0);
    const kinds = KINDS_OF_LOSS.map((_, i) => reviewed.reduce((a, h) => a + (h.rev!.kinds[i] ?? 0), 0));
    return {
      hands: k, loss: loss / k, mistakes: reviewed.reduce((a, h) => a + mistakesOf(h.rev!, settings.level), 0) / k,
      kinds: kinds.map((x, i) => ({ name: t('stats_kind_' + KINDS_OF_LOSS[i]), share: loss > 0 ? x / loss : 0, each: x / k })),
    };
  });
  let progress = $state<{ done: number; of: number } | null>(null);
  async function reviewAll() {
    const list = pending.slice().reverse();
    progress = { done: 0, of: list.length };
    for (const h of list) {
      if (!progress) break;
      await keepSummary(h);
      progress = { done: progress.done + 1, of: list.length };
    }
    progress = null;
  }

  function summary(h: HandRecord): string {
    if (h.exhausted || !h.winners.length) return t('record_draw');
    if (h.winners.includes(h.me)) return h.selfDraw ? t('record_tsumo_you', h.tai) : t('record_win_you', h.tai);
    if (h.loser === h.me) return t('record_dealt_in');
    return t('record_win_other', h.names[h.winners[0]]);
  }
  function rulesOf(spec: string): string {
    const [tai, flowers] = spec.split(',');
    const c = parseChoice({ tai, flowers });
    return t('rules_chip', t(taiLabel(c.tai)), t(flowerLabel(c.flowers))) + (spec.split(',').length > 2 ? '・' + t('rules_custom') : '');
  }
  const tabs: [RecordKind, string][] = [['practice', t('records_practice')], ['table', t('records_table')], ['contest', t('records_contest')]];
</script>

{#snippet line(h: HandRecord)}
  {@const me = h.points[h.me]}
  <div class="line">
    <button class="main" onclick={() => onreview(h.id)}>
      <span class="what">
        <span class="body-large" class:good={h.winners.includes(h.me)} class:bad={h.loser === h.me}>{summary(h)}</span>
        <span class="body-small dim">{localTime(h.time)}・{rulesOf(h.game.rules)}{h.index ? '・' + t('record_hand_of', h.index) : ''}{h.rev ? '・' + t('record_loss', pts(h.rev.loss, false)) : ''}</span>
      </span>
      <span class="pts" class:good={me > 0} class:bad={me < 0}>{signed(me)}</span>
    </button>
    <button class="btn icon text" aria-label={t('favorite')} aria-pressed={!!h.fav} onclick={() => records.setFav(h.id, !h.fav)}>
      <Icon name={h.fav ? 'star' : 'starBorder'} />
    </button>
  </div>
{/snippet}

{#snippet battle(b: BattleGroup)}
  {@const list = records.ofGroup(b.id)}
  <div class="battle">
    <div class="line">
      <button class="main" aria-expanded={openBattle === b.id} onclick={() => (openBattle = openBattle === b.id ? null : b.id)}>
        <span class="what">
          <span class="body-large">{b.names.join('・')}</span>
          <span class="body-small dim">{localTime(b.time)}・{t(b.via === 'nearby' ? 'records_kind_nearby' : 'records_kind_online')}・{t('records_battle_hands', b.hands)}{b.left ? '・' + t('records_battle_left') : ''}</span>
        </span>
        <span class="pts" class:good={b.totals[b.me] > 0} class:bad={b.totals[b.me] < 0}>{signed(b.totals[b.me] ?? 0)}</span>
      </button>
      <button class="btn icon text" aria-label={t('favorite')} aria-pressed={!!b.fav} onclick={() => records.setBattleFav(b.id, !b.fav)}>
        <Icon name={b.fav ? 'star' : 'starBorder'} />
      </button>
    </div>
    {#if openBattle === b.id}
      <div class="hands">{#each list as h (h.id)}{@render line(h)}{/each}</div>
    {/if}
  </div>
{/snippet}

<div class="page">
  <TitleBar title={t('records_title')} max={760}>
    <button class="btn text" disabled={!records.hands.some((h) => !h.fav)} onclick={() => (confirmClear = true)}>{t('records_clear')}</button>
  </TitleBar>
  <div class="scroll"><div class="readable">
    <div class="head">
      <Toggle options={tabs} selected={tab} onselect={(v) => (tab = v)} />
      <div class="sub">
        <span class="body-medium dim">{stats}</span>
        <button class="chip" class:on={favOnly} aria-pressed={favOnly} onclick={() => (favOnly = !favOnly)}>
          <Icon name={favOnly ? 'star' : 'starBorder'} size={18} />{t('records_favorites_only')}
        </button>
      </div>
    </div>
    {#if review || pending.length}
      <div class="stats">
        <div class="stats-head">
          <span class="label-large">{t('stats_title')}</span>
          <span class="body-small dim">{t('stats_reviewed', review?.hands ?? 0)}</span>
          {#if progress}
            <button class="btn text" onclick={() => (progress = null)}>{t('stats_running', progress.done, progress.of)}</button>
          {:else if pending.length}
            <button class="btn text" onclick={reviewAll}>{t('stats_run', pending.length)}</button>
          {/if}
        </div>
        {#if review}
          <div class="stats-line body-medium">
            <span><b>{t('stats_loss', pts(review.loss, false))}</b> <span class="dim">{t('stats_net_note')}</span></span>
            <span>{t('stats_per_hand', t('review_mistakes', levelOrWorse(settings.level), review.mistakes.toFixed(2)))}</span>
          </div>
          <div class="kinds body-small">
            <span class="dim">{t('stats_kinds')}</span>
            {#each review.kinds as k (k.name)}
              <span class="kind"><span class="kind-bar"><i style="width: {Math.round(k.share * 100)}%"></i></span>{k.name} {pts(k.each, false)}</span>
            {/each}
          </div>
        {/if}
      </div>
    {/if}
    {#if tab === 'practice'}
      {#each hands as h (h.id)}{@render line(h)}{:else}<p class="empty body-large dim">{t('records_empty')}</p>{/each}
    {:else}
      {#each battles as b (b.id)}{@render battle(b)}{:else}<p class="empty body-large dim">{t('records_empty')}</p>{/each}
    {/if}
  </div></div>
</div>

{#if confirmClear}
  <Dialog onclose={() => (confirmClear = false)}>
    <p class="body-large" style="margin:0">{t('records_clear_confirm')}</p>
    {#snippet actions()}
      <button class="btn text" onclick={() => (confirmClear = false)}>{t('cancel')}</button>
      <button class="btn text" onclick={() => { confirmClear = false; records.clear(); }}>{t('delete')}</button>
    {/snippet}
  </Dialog>
{/if}

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .scroll { flex: 1; overflow: auto; padding-bottom: 24px; }
  .readable { max-width: 760px; margin: 0 auto; }
  .head { display: flex; flex-direction: column; gap: 8px; padding: 4px 16px 8px; }
  .sub { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
  .chip.on { background: var(--secondary-container); color: var(--on-secondary-container); border-color: transparent; }
  .line { display: flex; align-items: center; gap: 4px; padding-right: 8px; border-bottom: 1px solid var(--outline-variant, var(--surface-container-highest)); }
  .main { flex: 1; min-width: 0; display: flex; align-items: center; gap: 12px; padding: 10px 8px 10px 16px; border: none; background: none;
    color: inherit; text-align: left; cursor: pointer; font: inherit; }
  .main:hover { background: var(--surface-container); }
  .what { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
  .pts { font-weight: 700; font-size: 1.125rem; flex: none; }
  .hands { padding-left: 16px; background: var(--surface-container-low, var(--surface)); }
  .empty { padding: 32px 16px; text-align: center; }
  .stats { margin: 0 12px 8px; padding: 8px 12px; border-radius: 14px; background: var(--surface-container); display: flex; flex-direction: column; gap: 4px; }
  .stats-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; min-height: 40px; }
  .stats-head .btn { margin-left: auto; }
  .stats-line { display: flex; flex-wrap: wrap; gap: 2px 16px; font-variant-numeric: tabular-nums; }
  .kinds { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 14px; font-variant-numeric: tabular-nums; }
  .kind { display: inline-flex; align-items: center; gap: 6px; }
  .kind-bar { width: 48px; height: 8px; border-radius: 4px; background: var(--surface-container-highest); overflow: hidden; display: inline-block; }
  .kind-bar i { display: block; height: 100%; background: var(--primary); }
</style>
