<!-- A player's name plate: their seat wind (lit for the dealer, with the streak), name, score, and whose turn it is.
     `note` (a review, the hints): a few words in place of the count, how likely the player is ready (level 0 to 3); it
     comes last, alone goes to a second line where the plate has no room for it, and its parts (between ・) to lines
     of their own where that line has none. `count` on a wide table (a low one):
     the count and the score go to a second line where the side column is too narrow for one. `room`: words the note
     keeps the width of, shown or not (the player's own standing: the plate keeps its size as the note comes and goes). -->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import { windName } from '../lib/tiles';
  import { signed } from '../lib/ui.svelte';
  let { wind, name, score = null, dealer = false, streak = 0, turn = false, count = null, compact = false, away = false,
    note = null, room = '' }: {
    wind: number; name: string; score?: number | null; dealer?: boolean; streak?: number; turn?: boolean; count?: number | null;
    compact?: boolean; away?: boolean; note?: { text: string; level: number } | null; room?: string;
  } = $props();
</script>

<div class="badge-row" class:turn class:compact class:away class:noted={!!note || !!room} class:counted={count !== null && !compact}>
  <span class="wind" class:dealer title={dealer ? t('dealer_mark') : undefined}>{windName(wind)}</span>
  <span class="name ellipsis">{name}</span>
  {#if dealer}<span class="deal">{t('dealer_mark')}{#if streak > 0}<span class="streak">{t('streak_n', streak)}</span>{/if}</span>{/if}
  {#if room}
    <span class="note ellipsis l{note?.level ?? 0}" class:empty={!note}><span class="stack"><span>{note?.text ?? ''}</span><span class="room" aria-hidden="true">{room}</span></span></span>
  {:else if note}<span class="note l{note.level}">{#each note.text.split('・') as part, i (i)}{#if i}・<wbr />{/if}<span class="part">{part}</span>{/each}</span>{/if}
  <span class="tail">
    {#if !note && count !== null}<span class="count">{t('hand_cards', count)}</span>{/if}
    {#if score !== null}<span class="score" class:pos={score > 0} class:neg={score < 0}>{signed(score)}</span>{/if}
  </span>
</div>

<style>
  .badge-row { display: flex; align-items: center; gap: 6px; min-width: 0; padding: 2px 8px 2px 2px; border-radius: 16px;
    background: rgba(0, 0, 0, 0.16); color: var(--on-felt); font-size: 1em; line-height: 1.2; max-width: 100%;
    border: 2px solid transparent; }
  .badge-row.turn { border-color: var(--highlight); background: rgba(0, 0, 0, 0.28); }
  .badge-row.compact { font-size: 0.86em; gap: 4px; padding-right: 6px; }
  .badge-row.away { opacity: 0.6; }
  .badge-row.noted { flex-wrap: wrap; row-gap: 2px; }
  .badge-row.noted .name { flex: 0 1 auto; }
  .badge-row.noted .score { margin-left: 0; }
  /* A low wide table's side columns are narrow: the count and the score go under the name rather than cut it short. */
  .badge-row.counted { flex-wrap: wrap; row-gap: 0; }
  .badge-row.counted .name { flex: 0 1 auto; }
  .badge-row.counted .score { margin-left: 0; }
  /* The count and the score keep together (the score alone on a second line read as a stray figure). */
  .tail { display: contents; }
  .badge-row.counted .tail { display: inline-flex; align-items: center; gap: 6px; flex: none; }
  .wind { flex: none; width: 1.7em; height: 1.7em; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center;
    background: var(--on-felt); color: var(--felt); font-weight: 700; }
  .wind.dealer { background: var(--highlight); color: #3a2a00; }
  .name { font-weight: 500; }
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
  .score { flex: none; font-weight: 700; margin-left: auto; }
  .score.pos { color: var(--felt-good); }
  .score.neg { color: var(--felt-bad); }
</style>
