<!-- A player's name plate: their seat wind (lit for the dealer, with the streak), name, score, and whose turn it is.
     Beside the wind, the plate's words flow onto a second line where one has no room for them all: the name stays
     whole (cut only where it alone is too long), the dealer's 莊連N, the count and the score go under it, the count
     and the score together. `note` (a review, the hints): a few words in place of the count, how likely the player is
     ready (level 0 to 3); it comes last, and its parts (between ・) go to lines of their own where its line has no room
     for it. An opponent's note (`opp`, the hints) is a button: a tap shows its figures in a small window by the plate
     (how likely it is ready and one away, the discards likeliest to deal in to it); where its words have no room, its
     shorter ones (聽 40% for 聽 40%・可能聽五筒). `room`: words the note keeps the width of, shown or not (the player's own standing: the plate keeps its
     size as the note comes and goes). The app's SeatBadge (TableView.kt). -->
<script lang="ts">
  import { autoUpdate, computePosition, flip, offset, shift } from '@floating-ui/dom';
  import { t } from '../lib/i18n.svelte';
  import { dangerLevel, dangerTiles, pct, type SeatNote } from '../lib/review';
  import { shorten } from '../lib/shorten';
  import { windName } from '../lib/tiles';
  import { signed } from '../lib/ui.svelte';
  import Tile from './Tile.svelte';
  let { wind, name, score = null, dealer = false, streak = 0, turn = false, count = null, compact = false, away = false,
    note = null, room = '' }: {
    wind: number; name: string; score?: number | null; dealer?: boolean; streak?: number; turn?: boolean; count?: number | null;
    compact?: boolean; away?: boolean; note?: SeatNote | null; room?: string;
  } = $props();
  const uid = $props.id();
  const id = `opp-${uid}`;
  let open = $state(false);
  let button = $state<HTMLButtonElement>();
  let card = $state<HTMLElement>();
  // The card under the note (over it where there is more room above), inside the window.
  $effect(() => {
    if (!open || !button || !card) return;
    const [b, c] = [button, card];
    return autoUpdate(b, c, () => {
      computePosition(b, c, { strategy: 'fixed', placement: 'bottom', middleware: [offset(6), flip({ padding: 8 }), shift({ padding: 8 })] })
        .then(({ x, y }) => { c.style.left = `${x}px`; c.style.top = `${y}px`; });
    });
  });
</script>

<div class="badge-row" {@attach shorten} class:turn class:compact class:away class:noted={!!note || !!room}>
  <span class="wind" class:dealer title={dealer ? t('dealer_mark') : undefined}>{windName(wind)}</span>
  <span class="lines">
    <span class="name ellipsis">{name}</span>
    {#if dealer}<span class="deal">{t('dealer_mark')}{#if streak > 0}<span class="streak">{t('streak_n', streak)}</span>{/if}</span>{/if}
    {#if room}
      <span class="note ellipsis l{note?.level ?? 0}" class:empty={!note}><span class="stack"><span>{note?.text ?? ''}</span><span class="room" aria-hidden="true">{room}</span></span></span>
    {:else if note?.opp}
      <button bind:this={button} class="note tap l{note.level}" popovertarget={id} aria-haspopup="dialog" aria-expanded={open}
        aria-label={note.text}><span data-full={note.text} data-short={note.short ?? note.text}></span></button>
    {:else if note}<span class="note l{note.level}">{#each note.text.split('・') as part, i (i)}{#if i}・<wbr />{/if}<span class="part">{part}</span>{/each}</span>{/if}
    {#if (!note && count !== null) || score !== null}
      <span class="tail">
        {#if !note && count !== null}<span class="count">{t('hand_cards', count)}</span>{/if}
        {#if score !== null}<span class="score" class:pos={score > 0} class:neg={score < 0}>{signed(score)}</span>{/if}
      </span>
    {/if}
  </span>
  {#if note?.opp}
    {@const o = note.opp}
    <div bind:this={card} {id} popover="auto" class="opp-card" onbeforetoggle={(e) => (open = e.newState === 'open')}>
      <div class="title-small">{name}</div>
      <div class="body-medium">{t('opp_ready', pct(o.ready[0]))}・{t('opp_one_away', pct(o.ready[1] ?? 0))}</div>
      <div class="body-small dim">{t('opp_detail_danger')}</div>
      <div class="tiles body-small">
        {#each dangerTiles(o) as d (d.tile)}
          <span class="danger"><Tile tile={d.tile} w={30} danger={dangerLevel(d.p)} />{pct(d.p)}</span>
        {:else}<span class="dim">{t('opp_no_danger')}</span>{/each}
      </div>
      <div class="body-small dim">{t('opp_detail_source')}</div>
    </div>
  {/if}
</div>

<style>
  .badge-row { display: flex; align-items: center; gap: 6px; min-width: 0; padding: 2px 8px 2px 2px; border-radius: 16px;
    background: rgba(0, 0, 0, 0.16); color: var(--on-felt); font-size: 1em; line-height: 1.2; max-width: 100%;
    border: 2px solid transparent; }
  .badge-row.turn { border-color: var(--highlight); background: rgba(0, 0, 0, 0.28); }
  .badge-row.compact { font-size: 0.86em; gap: 4px; padding-right: 6px; }
  .badge-row.away { opacity: 0.6; }
  /* The words beside the wind: one line where they fit, else the name first and the rest under it. */
  .lines { display: flex; flex-wrap: wrap; align-items: center; column-gap: 6px; row-gap: 0; min-width: 0; }
  .compact .lines { column-gap: 4px; }
  .noted .lines { row-gap: 2px; }
  .name { flex: 0 1 auto; font-weight: 500; }
  /* The count and the score keep together (the score alone on a second line read as a stray figure). */
  .tail { display: inline-flex; align-items: center; gap: 6px; flex: none; }
  .wind { flex: none; width: 1.7em; height: 1.7em; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center;
    background: var(--on-felt); color: var(--felt); font-weight: 700; }
  .wind.dealer { background: var(--highlight); color: #3a2a00; }
  .deal { flex: none; color: var(--highlight); font-weight: 700; }
  .streak { margin-left: 2px; }
  .count { flex: none; color: var(--on-felt-dim); }
  .note { order: 1; flex: 0 1 auto; max-width: 100%; padding: 0 6px; border-radius: 8px; font-weight: 700; background: rgba(0, 0, 0, 0.3); color: var(--on-felt); }
  /* A narrow plate: the note's parts (聽 40%・可能聽五筒) go to lines of their own rather than cut short. */
  .note:not(.ellipsis) { white-space: normal; }
  .part { white-space: nowrap; }
  .note.empty { background: none; }
  .stack { display: inline-grid; justify-items: center; }
  .stack > * { grid-area: 1 / 1; }
  .room { visibility: hidden; }
  .note.l1 { background: var(--danger-1); color: var(--on-danger); }
  .note.l2 { background: var(--danger-2); color: var(--on-danger); }
  .note.l3 { background: var(--danger-3); color: #fff; }
  button.note { border: none; font: inherit; font-weight: 700; line-height: inherit; cursor: pointer; white-space: nowrap; overflow: hidden;
    text-overflow: ellipsis; }
  button.note:focus-visible { outline: 2px solid var(--highlight); outline-offset: 1px; }
  /* An opponent's figures (a tap on its note): over the page, closed by a tap outside it or Escape. */
  .opp-card { position: fixed; inset: auto; margin: 0; border: none; padding: 12px 14px; width: max-content; max-width: min(320px, calc(100vw - 16px));
    color: var(--on-surface); background: var(--surface-container-high); border-radius: 12px; box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
    display: none; flex-direction: column; gap: 6px; line-height: 1.35; }
  .opp-card:popover-open { display: flex; }
  .tiles { display: flex; flex-wrap: wrap; gap: 6px 10px; }
  .danger { display: inline-flex; align-items: center; gap: 4px; font-variant-numeric: tabular-nums; }
  .score { flex: none; font-weight: 700; }
  .score.pos { color: var(--felt-good); }
  .score.neg { color: var(--felt-bad); }
</style>
