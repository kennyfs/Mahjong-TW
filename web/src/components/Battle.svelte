<!--
  Battles online (lib/battle.svelte.ts): the entry (first only a new room or joining one; then, for a new room, the
  name, the server and which kind: a table or a same-tiles contest; to join, the name, the server and the room's
  PIN), the room's lobby (its PIN and link, the players, the host's settings), the table while a hand is played (the
  player's own seat, the time left to decide; no hint or analysis), each hand's result with the standings, and the
  final standings with the player's hands (their review and analysis come from the records).
-->
<script lang="ts">
  import { fxDone, fxOn, resultTap } from '../lib/fx.svelte';
  import { onDestroy, onMount } from 'svelte';
  import {
    battle, HAND_CHOICES, LENGTHS, NAME_LENGTH, nameOf, rankOf, standings, TIME_LIMITS, type HandOutcome, type Length,
    type PlayerView, type RoomSettings,
  } from '../lib/battle.svelte';
  import { t } from '../lib/i18n.svelte';
  import { nav } from '../lib/nav.svelte';
  import { records } from '../lib/records.svelte';
  import { FLOWER_PRESETS, flowerLabel, TAI_PRESETS, taiLabel } from '../lib/rules';
  import { settings } from '../lib/settings.svelte';
  import { windName } from '../lib/tiles';
  import { localTime, signed, ui } from '../lib/ui.svelte';
  import TapOn from './TapOn.svelte';
  import Dialog from './Dialog.svelte';
  import HandResult from './HandResult.svelte';
  import Icon from './Icon.svelte';
  import Table from './Table.svelte';
  import TableSkeleton from './TableSkeleton.svelte';
  import TitleBar from './TitleBar.svelte';
  import Toggle from './Toggle.svelte';

  let { onreview }: { onreview: (id: string) => void } = $props();

  let editServer = $state(false);
  /** The entry's step: its first page, a new room (which kind), or joining one (the PIN). Back goes to the first. */
  let step = $state<'home' | 'create' | 'join'>('home');
  let confirmLeave = $state(false);
  let copied = $state(false);
  let hideResult = $state(false);

  const room = $derived(battle.room);
  const s = $derived(room?.settings ?? null);
  const table = $derived(battle.table);
  const handOver = $derived(!!table?.view.result);
  /** A hand's deal shows at its start, as in practice (not on coming back to a hand under way). */
  const dealOf = (tb: NonNullable<typeof table>) => (tb.view.events.some((e) => e.type === 'discard') ? null : `${battle.battleId}/${tb.hand}`);
  const page = $derived.by(() => {
    if (!room) return 'entry';
    if (room.phase === 'LOBBY' && !battle.finished) return 'lobby';
    if (battle.over && (battle.lastSeen || !handOver)) return 'over';
    return 'table';
  });
  // A new hand: its table, not the last one's result.
  $effect(() => { if (!handOver) hideResult = false; });
  /** A hand's end: the table as it ended (every hand shown, the words of the win) for a moment before the result; a
   *  tap goes on at once. Not for a result come back to. */
  let held = $state(false);
  let inPlay = false;
  $effect(() => {
    if (!handOver) {
      inPlay = true;
      held = false;
      return;
    }
    if (!inPlay) return;
    inPlay = false;
    held = true;
    // The result's moment was tapped on (Table.svelte): the result at once; else (words alone) till a tap.
    let alive = true;
    void fxDone().then(() => { if (alive && resultTap.tapped) held = false; });
    return () => { alive = false; };
  });

  // A shared link (#/battle/123456): its PIN, ready to join.
  onMount(() => {
    const sub = nav.sub;
    if (sub && /^\d{6}$/.test(sub)) {
      battle.pin = sub;
      step = 'join';
      nav.replace('BATTLE');
    }
  });
  // Back (the browser's, a phone's gesture) from a step of the entry: its first page.
  $effect(() => { nav.guard(page === 'entry' && step !== 'home' ? () => (step = 'home') : null); });
  onDestroy(() => nav.guard(null));
  // Out of a room: the entry's first page again.
  $effect(() => { if (page !== 'entry') step = 'home'; });

  // The clock: the time left to decide, and the countdown to the next hand.
  let now = $state(Date.now());
  $effect(() => {
    const id = setInterval(() => (now = Date.now()), 250);
    return () => clearInterval(id);
  });
  const left = $derived(table?.deadline ? Math.max(0, table.deadline - now) : null);
  const nextIn = $derived(room?.phase === 'REVIEW' && room.remainingMs != null ? Math.max(0, room.remainingMs - (now - battle.roomAt)) : null);

  const people = $derived(room ? room.players.filter((p) => !p.bot) : []);
  const handLabel = $derived(room && s ? (s.mode === 'CONTEST' ? t('battle_hand_of', room.hand + 1, s.hands) : t('hand_n', room.hand + 1)) : '');
  const rulesText = $derived(s ? t('rules_chip', t(taiLabel(s.rules.tai)), t(flowerLabel(s.rules.flowers))) +
    (Object.keys(s.rules.overrides).length ? '・' + t('rules_custom') : '') : '');
  const lengthLabel = (l: Length) => t(`length_${l.toLowerCase()}`);
  /** A short window (a phone held sideways): the lobby's start in its title bar, the standings beside a result. */
  const short = $derived(ui.h < 560);

  function change(p: Partial<RoomSettings>) { if (s) battle.changeSettings({ ...s, ...p }); }

  function notes(p: PlayerView): string {
    const n: string[] = [];
    if (room && p.id === room.host) n.push(t('battle_host'));
    if (p.left) n.push(t('battle_left'));
    else if (!p.connected) n.push(t('battle_away'));
    return n.join('・');
  }

  async function copyLink() {
    if (!room) return;
    const link = `${location.origin}${location.pathname}#/battle/${room.pin}`;
    try {
      await navigator.clipboard.writeText(link);
      copied = true;
      setTimeout(() => (copied = false), 2000);
    } catch { /* no clipboard (an insecure page): the PIN is there to read */ }
  }

  /** A closed hand's points for a player: their seat's at their table. */
  function pointsOf(o: HandOutcome | undefined, id: number): number | null {
    if (!o) return null;
    const tables = o.players.length ? o.tables.filter((_, i) => o.players[i] === id) : o.tables;
    for (const tp of tables) {
      const seat = tp.seats.indexOf(id);
      if (seat >= 0) return tp.points[seat];
    }
    return null;
  }
  const lastOutcome = $derived(room ? battle.outcomes.find((o) => o.index === room.hand) : undefined);
  const done = $derived(people.filter((p) => p.done || p.left).length);
  const readyCount = $derived(people.filter((p) => p.ready || p.left || !p.connected).length);
  const myHands = $derived(battle.battleId ? records.ofGroup(battle.battleId) : []);
</script>

{#snippet clock()}
  {#if left !== null}
    <span class="clock" class:bad={left < 5000}><Icon name="timer" size={18} />{t('battle_your_time', Math.ceil(left / 1000))}</span>
  {/if}
{/snippet}

{#snippet standingRows(withHand: boolean)}
  {#if room}
    <div class="standings">
      {#each standings(room) as p (p.id)}
        {@const x = withHand ? pointsOf(lastOutcome, p.id) : null}
        <div class="stand" class:me={p.id === battle.me}>
          <span class="rank title-medium">{rankOf(room, p)}</span>
          <span class="who"><span class="body-large ellipsis">{nameOf(p)}</span>
            {#if notes(p)}<span class="body-small dim">{notes(p)}</span>{/if}</span>
          {#if withHand}
            <span class="num body-large" class:good={(x ?? 0) > 0} class:bad={(x ?? 0) < 0}>{x === null ? '' : signed(x)}</span>
          {/if}
          <span class="num title-medium" class:good={p.total > 0} class:bad={p.total < 0}>{signed(p.total)}</span>
        </div>
      {/each}
    </div>
  {/if}
{/snippet}

{#snippet nameAndServer()}
  <label class="field">
    <span class="body-small dim">{t('battle_name')}</span>
    <input class="body-large" maxlength={NAME_LENGTH} placeholder={battle.defaultName} bind:value={battle.name} />
  </label>
  {#if editServer}
    <label class="field">
      <span class="body-small dim">{t('battle_server')}</span>
      <input class="body-large" placeholder={t('battle_server_hint')} bind:value={battle.server} onblur={() => (battle.server = battle.server.trim())} />
    </label>
  {:else}
    <div class="server">
      <span class="body-medium dim grow">{t('battle_server_is', battle.server.trim() || t('battle_server_here'))}</span>
      <button class="btn text" onclick={() => (editServer = true)}>{t('change')}</button>
    </div>
  {/if}
{/snippet}

{#if page === 'entry'}
  <div class="page">
    {#if step === 'home'}
      <TitleBar title={t('battle_title')} />
    {:else}
      <TitleBar title={t(step === 'create' ? 'battle_create' : 'battle_join_room')} onback={() => (step = 'home')} />
    {/if}
    <div class="progress">{#if battle.busy}<span class="bar"></span>{/if}</div>
    <div class="scroll">
      <div class="entry">
        {#if step === 'home'}
          <button class="tile fill" onclick={() => (step = 'create')}>
            <span class="headline-small">{t('battle_create')}</span><span class="body-medium info">{t('battle_create_info')}</span>
          </button>
          <button class="tile tonal" onclick={() => (step = 'join')}>
            <span class="headline-small">{t('battle_join_room')}</span><span class="body-medium info">{t('battle_join_room_info')}</span>
          </button>
          <p class="body-medium dim note">{t('battle_no_help')}</p>
        {:else if step === 'create'}
          {@render nameAndServer()}
          <div class="title-medium mode-title">{t('battle_mode_title')}</div>
          <button class="tile fill" disabled={battle.busy} onclick={() => battle.create('TABLE')}>
            <span class="headline-small">{t('battle_new_table')}</span><span class="body-medium info">{t('battle_table_info')}</span>
          </button>
          <button class="tile fill" disabled={battle.busy} onclick={() => battle.create('CONTEST')}>
            <span class="headline-small">{t('battle_new_contest')}</span><span class="body-medium info">{t('battle_contest_info')}</span>
          </button>
        {:else}
          {@render nameAndServer()}
          <form class="pin-row" onsubmit={(e) => { e.preventDefault(); battle.join(); }}>
            <label class="field pin">
              <span class="body-small dim">{t('battle_pin')}</span>
              <input class="headline-small" inputmode="numeric" autocomplete="off" maxlength={6} value={battle.pin}
                oninput={(e) => (battle.pin = e.currentTarget.value.replace(/\D/g, '').slice(0, 6))} />
            </label>
            <button class="btn filled big" type="submit" disabled={battle.busy || battle.pin.length !== 6}>{t('battle_join')}</button>
          </form>
        {/if}
      </div>
    </div>
  </div>
{:else if page === 'lobby' && room && s}
  <div class="page">
    <TitleBar title={s.mode === 'CONTEST' ? t('battle_new_contest') : t('battle_new_table')}>
      <button class="btn outlined" onclick={() => battle.leave()}>{t('battle_leave')}</button>
      {#if short}
        {#if battle.isHost}<button class="btn filled" onclick={() => battle.startGame()}>{t('battle_start')}</button>
        {:else}<span class="body-medium dim">{t('battle_waiting_host')}</span>{/if}
      {/if}
    </TitleBar>
    <div class="scroll">
      <div class="lobby" class:wide={ui.w >= 760}>
        <div class="col">
          <div class="code fill">
            <span class="body-large sub">{t('battle_room_number')}</span>
            <span class="pin-big">{room.pin.slice(0, 3)} {room.pin.slice(3)}</span>
            <span class="body-medium sub">{t('battle_room_number_info')}</span>
            <button class="btn tonal" onclick={copyLink}><Icon name={copied ? 'check' : 'copy'} size={18} />{copied ? t('battle_link_copied') : t('battle_copy_link')}</button>
          </div>
          <h2 class="section">{t('battle_players', people.length)}</h2>
          {#each people as p (p.id)}
            <div class="player" class:me={p.id === battle.me}>
              <span class="avatar">{[...p.name][0] ?? '?'}</span>
              <span class="body-large ellipsis grow">{p.name}{p.id === battle.me ? `（${t('you')}）` : ''}</span>
              <span class="body-medium dim">{notes(p)}</span>
            </div>
          {/each}
        </div>
        <div class="col">
          <h2 class="section">{t('battle_settings')}</h2>
          {#if battle.isHost}
            {#if s.mode === 'TABLE'}
              <div class="setting"><span class="body-large">{t('battle_length')}</span>
                <Toggle options={LENGTHS.map((l) => [l, lengthLabel(l)] as [Length, string])} selected={s.length} onselect={(v) => change({ length: v })} /></div>
            {:else}
              <div class="setting"><span class="body-large">{t('battle_hands')}</span>
                <Toggle options={HAND_CHOICES.map((n) => [n, String(n)] as [number, string])} selected={s.hands} onselect={(v) => change({ hands: v })} /></div>
            {/if}
            <div class="setting"><span class="body-large">{t('battle_time_seconds')}</span>
              <Toggle options={TIME_LIMITS.map((n) => [n, String(n)] as [number, string])} selected={s.timeLimit} onselect={(v) => change({ timeLimit: v })} />
              <span class="body-small dim">{t('battle_time_info')}</span></div>
            <div class="setting"><span class="body-large">{t('battle_rules')}</span>
              <Toggle options={TAI_PRESETS.map((p) => [p, t(taiLabel(p))] as [string, string])} selected={s.rules.tai}
                onselect={(v) => change({ rules: { ...s.rules, tai: v } })} />
              <Toggle options={FLOWER_PRESETS.map((p) => [p, t(flowerLabel(p))] as [string, string])} selected={s.rules.flowers}
                onselect={(v) => change({ rules: { ...s.rules, flowers: v } })} />
              <div class="rules-line">
                <span class="body-medium dim grow">{rulesText}</span>
                <button class="btn text" onclick={() => change({ rules: $state.snapshot(settings.rules) })}>{t('battle_rules_page')}</button>
              </div>
            </div>
          {:else}
            <div class="setting row"><span class="body-large grow">{s.mode === 'TABLE' ? t('battle_length') : t('battle_hands')}</span>
              <span class="body-large dim">{s.mode === 'TABLE' ? lengthLabel(s.length) : t('battle_hands_n', s.hands)}</span></div>
            <div class="setting row"><span class="body-large grow">{t('battle_time')}</span><span class="body-large dim">{t('battle_time_n', s.timeLimit)}</span></div>
            <div class="setting row"><span class="body-large grow">{t('battle_rules')}</span><span class="body-large dim">{rulesText}</span></div>
          {/if}
        </div>
      </div>
    </div>
    <div class="bottom" hidden={short}>
      {#if battle.isHost}
        <button class="btn filled big start" onclick={() => battle.startGame()}>{t('battle_start')}</button>
      {:else}
        <span class="body-large dim">{t('battle_waiting_host')}</span>
      {/if}
    </div>
  </div>
{:else if page === 'table' && room && s}
  {@const names = table ? battle.namesAt(table.seats) : []}
  <div class="play">
    {#if !ui.wide}
      <header class="topbar">
        <span class="title-medium nowrap">{table ? t('round_wind', windName(table.view.position.setup.round)) : ''} {handLabel}</span>
        <span class="grow"></span>
        {@render clock()}
        <button class="btn text" onclick={() => (confirmLeave = true)}>{t('battle_leave')}</button>
      </header>
    {/if}
    <div class="table-wrap">
      {#if table}
        <Table view={table.view} me={table.seat} {names} scores={battle.totalsAt(table.seats)} away={battle.awayAt(table.seats)}
          revealed={handOver ? (table.view.hands ?? null) : null} interactive={table.view.my_turn && !handOver}
          onact={(a) => battle.act(a)} handLabel={ui.wide ? handLabel : ''} maxHand={ui.desk ? 72 : 64} keys={ui.desk}
          deal={dealOf(table)} hints={false}>
          {#snippet status()}{#if ui.wide}{@render clock()}{/if}{/snippet}
          {#snippet corner()}
            {#if ui.wide}
              <span class="mini-text">{rulesText}</span>
              <button class="mini" onclick={() => (confirmLeave = true)}>{t('battle_leave')}</button>
            {/if}
          {/snippet}
          {#snippet aside()}
            <div class="panel">
              <div class="p-head">
                <div class="title-large">{t('round_wind', windName(table.view.position.setup.round))} {handLabel}</div>
                <div class="body-medium p-dim">{t('wall_left', table.view.position.wall_left)}・{t('battle_room_number')} {room.pin}</div>
                <div class="body-medium p-dim">{rulesText}・{t('battle_time_n', s.timeLimit)}</div>
              </div>
              <div class="p-card">
                <div class="label-large p-dim">{t('battle_standings')}</div>
                {#each standings(room) as p (p.id)}
                  <div class="p-score" class:me={p.id === battle.me}>
                    <span class="p-name">{nameOf(p)}</span>
                    {#if notes(p)}<span class="p-note">{notes(p)}</span>{/if}
                    <span class="p-pts" class:good={p.total > 0} class:bad={p.total < 0}>{signed(p.total)}</span>
                  </div>
                {/each}
              </div>
              <div class="p-buttons">
                <button class="btn tonal" onclick={() => (confirmLeave = true)}>{t('battle_leave')}</button>
              </div>
              <div class="p-card keys">
                <div class="label-large p-dim">{t('keys_title')}</div>
                <div>{t('key_choose')}</div>
                <div>{t('key_discard')}</div>
                <div>{t('key_claims')}</div>
              </div>
            </div>
          {/snippet}
          {#snippet overlay()}
            {#if handOver && (held || fxOn.on)}
              <TapOn onnext={() => (held = false)} />
            {:else if handOver && !hideResult}
              <!-- A short window (a phone held sideways): the standings beside the result, which keeps its buttons in view. -->
              {@const side = short && room.phase === 'REVIEW' && people.length > 1}
              <div class="sheet">
                <div class="sheet-col" class:side>
                  <HandResult final={table.view} {names} me={table.seat} compact={short}
                    w={side ? Math.min(30, Math.max(22, (ui.w - 330) / 18.5)) : ui.wide ? Math.min(40, Math.max(26, ui.w / 27)) : Math.min(36, Math.max(26, ui.w / 13))}>
                    {#snippet actions()}
                      <button class="btn text" onclick={() => (hideResult = true)}>{t('result_hands')}</button>
                      {#if room.phase === 'PLAYING'}
                        <span class="body-medium wait">{t('battle_waiting_others', done, people.length)}</span>
                      {:else if battle.over}
                        <button class="btn filled" onclick={() => (battle.lastSeen = true)}>{t('see_totals')}</button>
                      {:else if battle.mine?.ready}
                        <span class="body-medium wait">{t('battle_ready_waiting', readyCount, people.length)}</span>
                      {:else}
                        <button class="btn filled" onclick={() => battle.ready()}>{t('battle_ready')}</button>
                      {/if}
                    {/snippet}
                  </HandResult>
                  {#if room.phase === 'REVIEW' && people.length > 1}
                    <div class="mini-standings">
                      <div class="row-head body-small dim">
                        <span class="grow">{t('battle_standings')}</span><span class="num">{t('battle_this_hand')}</span><span class="num">{t('battle_total')}</span>
                      </div>
                      {@render standingRows(true)}
                      {#if nextIn !== null}<div class="body-small dim">{t('battle_next_in', Math.ceil(nextIn / 1000))}</div>{/if}
                    </div>
                  {/if}
                </div>
              </div>
            {:else if handOver && hideResult}
              <div class="peek"><button class="btn filled" onclick={() => (hideResult = false)}>{t('back')}</button></div>
            {/if}
          {/snippet}
        </Table>
      {:else}
        <TableSkeleton />
      {/if}
      {#if battle.reconnecting}
        <div class="reconnect"><span class="spinner"></span>{t('battle_reconnecting')}</div>
      {/if}
    </div>
  </div>
{:else if page === 'over' && room}
  <div class="page">
    <TitleBar title={t('battle_over')}>
      <button class="btn filled" onclick={() => battle.dismiss()}>{t('battle_done')}</button>
    </TitleBar>
    <div class="scroll">
      <div class="summary">
        {#if battle.mine}<p class="headline-small">{t('battle_place', rankOf(room, battle.mine))}</p>{/if}
        <div class="row-head body-small dim"><span class="grow">{t('battle_standings')}</span><span class="num">{t('battle_total')}</span></div>
        {@render standingRows(false)}
        {#if myHands.length}
          <h2 class="section">{t('battle_your_hands')}</h2>
          {#each myHands as h (h.id)}
            {@const x = h.points[h.me]}
            <button class="hand-line" onclick={() => onreview(h.id)}>
              <span class="grow">
                <span class="body-large">{t('record_hand_of', h.index ?? 0)}</span>
                <span class="body-small dim">{localTime(h.time)}</span>
              </span>
              <span class="num title-medium" class:good={x > 0} class:bad={x < 0}>{signed(x)}</span>
              <Icon name="chevron" />
            </button>
          {/each}
        {/if}
      </div>
    </div>
  </div>
{/if}

{#if confirmLeave}
  <Dialog onclose={() => (confirmLeave = false)}>
    <p class="body-large" style="margin:0">{t('battle_leave_confirm')}</p>
    {#snippet actions()}
      <button class="btn text" onclick={() => (confirmLeave = false)}>{t('cancel')}</button>
      <button class="btn text" onclick={() => { confirmLeave = false; battle.leave(); }}>{t('battle_leave')}</button>
    {/snippet}
  </Dialog>
{/if}

<style>
  .page, .play { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .scroll { flex: 1; min-height: 0; overflow: auto; }
  .grow { flex: 1; min-width: 0; }
  .progress { height: 4px; flex: none; overflow: hidden; position: relative; }
  .bar { position: absolute; inset: 0 auto 0 0; width: 40%; background: var(--primary); animation: slide 1.1s ease-in-out infinite; }
  @keyframes slide { from { left: -40%; } to { left: 100%; } }

  /* The entry */
  .entry { display: flex; flex-direction: column; gap: 14px; padding: 4px 16px 16px; max-width: 640px; margin: 0 auto; }
  .mode-title { margin-top: 6px; }
  .tonal { background: var(--secondary-container); color: var(--on-secondary-container); }
  .col { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
  .field { display: flex; flex-direction: column; gap: 2px; padding: 6px 14px 8px; border: 1px solid var(--outline); border-radius: 6px; }
  .field:focus-within { border: 2px solid var(--primary); padding: 5px 13px 7px; }
  .field input { border: none; outline: none; background: none; color: var(--on-surface); padding: 2px 0; font: inherit; width: 100%; min-width: 0; }
  .server { display: flex; align-items: center; gap: 8px; }
  .note { margin: 4px 0 0; }
  .tile { display: flex; flex-direction: column; gap: 4px; text-align: left; padding: 14px 16px; border-radius: 16px; font: inherit; }
  button.tile { border: none; cursor: pointer; }
  button.tile:disabled { opacity: 0.6; cursor: default; }
  button.tile:not(:disabled):hover { filter: brightness(1.08); }
  .fill { background: var(--felt); color: var(--on-felt); }
  /* The minimal colors' felt is the page: a container, so that it still looks like a button. */
  :global(:root.minimal) .fill { background: var(--secondary-container); color: var(--on-secondary-container); }
  .tile .info { opacity: 0.88; }
  .pin-row { display: flex; align-items: center; gap: 10px; }
  .pin { flex: 1; max-width: 220px; }
  .pin input { letter-spacing: 6px; }

  /* The lobby */
  .lobby { display: flex; flex-direction: column; padding: 0 16px 8px; max-width: 640px; margin: 0 auto; }
  .lobby.wide { flex-direction: row; gap: 24px; max-width: 1100px; }
  .lobby.wide .col { flex: 1; }
  .code { display: flex; flex-direction: column; align-items: center; gap: 4px; text-align: center; padding: 14px 18px; border-radius: 22px; }
  .code .sub { opacity: 0.85; }
  .code .btn { margin-top: 6px; }
  .pin-big { font-size: 2.25rem; line-height: 1.25; font-weight: 700; letter-spacing: 4px; }
  .section { font-size: 0.875rem; font-weight: 500; color: var(--primary); margin: 16px 0 4px; }
  .player { display: flex; align-items: center; gap: 12px; min-height: 56px; }
  .avatar { flex: none; width: 40px; height: 40px; border-radius: 50%; display: flex; align-items: center; justify-content: center;
    background: var(--primary-container); color: var(--on-primary-container); font-weight: 700; }
  .setting { display: flex; flex-direction: column; gap: 8px; padding: 8px 0 12px; }
  .setting.row { flex-direction: row; align-items: center; min-height: 48px; padding: 0; }
  .rules-line { display: flex; align-items: center; gap: 8px; }
  .bottom { flex: none; padding: 12px 16px 16px; display: flex; justify-content: center; }
  .start { width: 100%; max-width: 640px; }

  /* The table */
  .topbar { flex: none; display: flex; align-items: center; gap: 8px; padding: 4px 8px 4px 16px; min-height: 48px; }
  .table-wrap { flex: 1; min-height: 0; position: relative; }
  .clock { display: inline-flex; align-items: center; gap: 4px; padding: 2px 10px; border-radius: 14px; font-weight: 700;
    background: var(--secondary-container); color: var(--on-secondary-container); white-space: nowrap; }
  .clock.bad { background: var(--error-container); color: var(--on-error-container); }
  .mini { border: 1px solid var(--on-felt-dim); background: transparent; color: var(--on-felt); border-radius: 12px; padding: 1px 8px;
    font-size: 0.75rem; cursor: pointer; }
  .mini-text { font-size: 0.75rem; color: var(--on-felt-dim); }
  .sheet { position: absolute; inset: 0; z-index: 5; display: flex; align-items: center; justify-content: center; padding: 12px;
    background: rgba(0, 0, 0, 0.35); overflow: auto; }
  .sheet-col { display: flex; flex-direction: column; gap: 10px; max-width: min(760px, 100%); max-height: 100%; }
  .sheet-col.side { flex-direction: row; align-items: flex-start; max-width: min(1000px, 100%); }
  .sheet-col.side .mini-standings { flex: none; width: 270px; max-height: 100%; overflow: auto; }
  .bottom[hidden] { display: none; }
  .sheet :global(.result) { max-width: 100%; }
  .wait { color: var(--on-surface-variant); align-self: center; }
  .mini-standings { display: flex; flex-direction: column; gap: 2px; padding: 10px 14px; border-radius: 20px; background: var(--surface-container-high);
    color: var(--on-surface); }
  .peek { position: absolute; right: 12px; top: 12px; z-index: 5; }
  .reconnect { position: absolute; left: 50%; top: 12px; transform: translateX(-50%); z-index: 6; display: flex; align-items: center; gap: 8px;
    padding: 8px 14px; border-radius: 18px; background: var(--surface-container-highest); color: var(--on-surface); }
  .panel { display: flex; flex-direction: column; gap: 14px; padding: 16px 16px 16px 0; color: var(--on-felt); }
  .p-head { display: flex; flex-direction: column; gap: 2px; }
  .p-dim { color: var(--on-felt-dim); }
  .p-card { display: flex; flex-direction: column; gap: 6px; padding: 12px 14px; border-radius: 16px; background: rgba(0, 0, 0, 0.18); }
  .p-score { display: flex; align-items: center; gap: 8px; font-size: 1rem; }
  .p-score.me .p-name { font-weight: 700; }
  .p-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .p-note { font-size: 0.8rem; color: var(--on-felt-dim); flex: none; }
  .p-pts { margin-left: auto; font-weight: 700; flex: none; }
  .p-pts.good { color: var(--felt-good); }
  .p-pts.bad { color: var(--felt-bad); }
  .p-buttons { display: flex; flex-wrap: wrap; gap: 8px; }
  .keys { font-size: 0.9rem; line-height: 1.5; }
  .spinner { width: 20px; height: 20px; border-radius: 50%; border: 3px solid transparent; border-top-color: var(--primary); animation: spin 0.9s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }

  /* The standings */
  .standings { display: flex; flex-direction: column; gap: 2px; }
  .stand { display: flex; align-items: center; gap: 10px; min-height: 44px; padding: 0 10px; border-radius: 12px; }
  .stand.me, .player.me { background: var(--secondary-container); border-radius: 12px; }
  .player.me { padding: 0 8px; margin: 0 -8px; }
  .rank { width: 1.6em; text-align: center; flex: none; }
  .who { flex: 1; min-width: 0; display: flex; flex-direction: column; }
  .num { flex: none; min-width: 4em; text-align: right; white-space: nowrap; }
  .row-head { display: flex; align-items: center; gap: 10px; padding: 0 10px; }
  .summary { padding: 0 16px 16px; max-width: 760px; margin: 0 auto; display: flex; flex-direction: column; gap: 4px; }
  .summary p { margin: 4px 0 8px; }
  .hand-line { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border: none; border-bottom: 1px solid var(--outline-variant);
    background: none; color: inherit; font: inherit; text-align: left; cursor: pointer; width: 100%; }
  .hand-line .grow { display: flex; flex-direction: column; }
  .hand-line:hover { background: var(--surface-container); }
</style>
