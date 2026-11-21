<!--
  The AI coach's page (docs/coach.md; the app's CoachScreen), opened from the records: which hands (one set of rules,
  the latest 50, 100 or 200), which service and model, a question if any; the digest made and its size and price
  shown before anything is sent; the answer with its ids as links to the moments of the review; more questions after
  it; the last few answers kept. What it shows lives in lib/coach (a review opened from a link, and Back, find it so).
-->
<script lang="ts">
  import { onDestroy, onMount, tick } from 'svelte';
  import { coach, firstMessage, type CoachSession, type Prepared, type RulesGroup } from '../lib/coach/coach.svelte';
  import { coachKeys } from '../lib/coach/keys.svelte';
  import {
    answerJson, EFFORTS, effortOf, modelOf, OUTPUT_GUESS, PROVIDERS, tokens, type CoachModel, type Effort, type ErrorKind, type Provider,
  } from '../lib/coach/providers';
  import { t } from '../lib/i18n.svelte';
  import { records } from '../lib/records.svelte';
  import { flowerLabel, taiLabel } from '../lib/rules';
  import { settings } from '../lib/settings.svelte';
  import { localTime } from '../lib/ui.svelte';
  import CoachKeys from './CoachKeys.svelte';
  import Dialog from './Dialog.svelte';
  import Icon from './Icon.svelte';
  import Menu from './Menu.svelte';
  import TitleBar from './TitleBar.svelte';
  import Toggle from './Toggle.svelte';

  let { onback, onreview }: { onback: () => void; onreview: (id: string, step: number) => void } = $props();

  const COUNTS = [50, 100, 200];
  const zh = () => t('coach_lang') === 'zh-TW';

  let scroller = $state<HTMLElement>();
  onMount(async () => {
    void coach.loadGroups();
    await tick();
    if (scroller) scroller.scrollTop = coach.scroll;
  });
  onDestroy(() => { coach.scroll = scroller?.scrollTop ?? 0; });

  function rulesName(g: RulesGroup): string {
    const base = t(taiLabel(g.tai)) + t('sep') + t(flowerLabel(g.flowers));
    return g.changed === 0 ? base : t('coach_rules_changed', base, g.changed);
  }
  const groupName = (g: RulesGroup) => rulesName(g) + t('sep') + t('coach_hands_n', g.hands.length);
  const groups = $derived(coach.groups);
  const group = $derived(groups?.find((g) => g.key === coach.groupKey) ?? groups?.[0] ?? null);
  const ready = $derived(PROVIDERS.filter((p) => coachKeys.has(p.id)));
  let providerId = $state(settings.coachProvider);
  const provider = $derived(ready.find((p) => p.id === providerId) ?? ready[0] ?? null);
  const modelFor = (p: Provider) => modelOf(p, settings.coachModels[p.id]);
  const effortFor = (p: Provider) => effortOf(settings.coachEffort[p.id]);
  const nameOf = (p: Provider) => (p.id === 'custom' ? t('coach_custom_short') : p.label);
  const shown = $derived(coach.sessions.find((s) => s.id === coach.shownId) ?? coach.sessions[0] ?? null);
  const phase = $derived(coach.phase);
  const busy = $derived(phase.kind === 'gathering' || phase.kind === 'asking');

  function setCount(n: number) { settings.coachHands = n; settings.persist(); }
  function prepare() { if (group) void coach.prepare(group, settings.coachHands, rulesName(group), coach.question); }

  // Sending: the notice of what goes where, once per provider.
  let tell = $state<{ prepared: Prepared; to: Provider } | null>(null);
  function send(prepared: Prepared, to: Provider) {
    if (!coachKeys.told(to.id)) { tell = { prepared, to }; return; }
    settings.coachProvider = to.id;
    settings.persist();
    void coach.send(prepared, to.id, modelFor(to), effortFor(to), settings.level);
  }

  // The model and its thinking, chosen in a dialog.
  let choosing = $state<{ p: Provider; m: CoachModel; e: Effort } | null>(null);
  function chooseModel(p: Provider) { choosing = { p, m: modelFor(p) ?? p.models[0], e: effortFor(p) }; }
  function saveModel() {
    const c = choosing!;
    settings.coachModels = { ...settings.coachModels, [c.p.id]: c.m.id };
    settings.coachEffort = { ...settings.coachEffort, [c.p.id]: c.e };
    settings.persist();
    choosing = null;
  }
  const effortName = (e: Effort) => t({ low: 'coach_effort_low', medium: 'coach_effort_medium', high: 'coach_effort_high' }[e]);
  const efforts: [Effort, string][] = $derived(EFFORTS.map((e) => [e, effortName(e)]));
  const price = (x: number) => (x >= 1 ? x.toFixed(0) : x.toFixed(2));

  let keysOpen = $state(false);
  let preview = $state<string | null>(null);
  let confirmDelete = $state<string | null>(null);
  let more = $state('');

  function estimate(p: Prepared): string {
    const n = Math.floor((tokens(firstMessage(p, zh())) + 1500) * (provider?.tokenScale ?? 1));
    const m = provider ? modelFor(provider) : undefined;
    if (!m || m.inPrice <= 0 || !provider) return t('coach_estimate_tokens', Math.floor(n / 1000));
    const cost = (n * m.inPrice + OUTPUT_GUESS[effortFor(provider)] * m.outPrice) / 1e6;
    // (A cent or more to the cent; less, as Haiku's, to a tenth of one.)
    return t('coach_estimate', Math.floor(n / 1000), cost.toFixed(cost < 0.01 ? 3 : 2));
  }

  const ERRORS: Record<ErrorKind, string> = {
    KEY: 'coach_error_key', QUOTA: 'coach_error_quota', REFUSED: 'coach_error_refused', TRUNCATED: 'coach_error_truncated',
    NETWORK: 'coach_error_network_web', SERVER: 'coach_error_server', BAD_ANSWER: 'coach_error_bad',
  };

  function sessionLine(s: CoachSession): string {
    const effort = s.effort ? t('coach_session_effort', effortName(effortOf(s.effort))) : '';
    return localTime(s.time) + t('sep') + s.model + effort + t('sep') + s.rules + t('sep') + t('coach_hands_n', s.hands.length);
  }

  /** Text with its ids ([H9-73], H9-73) as links to the moments, where the hand and the step are there. */
  const REF = /H(\d{1,3})-(\d{1,4})/g;
  type Part = { text: string; id?: string; step?: number };
  function linked(s: CoachSession, text: string): Part[] {
    const out: Part[] = [];
    let at = 0;
    for (const m of text.matchAll(REF)) {
      out.push({ text: text.slice(at, m.index) });
      const rec = records.get(s.hands[Number(m[1]) - 1] ?? '');
      const step = Number(m[2]);
      out.push(rec && step <= rec.game.actions.length ? { text: m[0], id: rec.id, step } : { text: m[0] });
      at = m.index! + m[0].length;
    }
    out.push({ text: text.slice(at) });
    return out;
  }
  const str = (o: Record<string, unknown>, k: string) => (typeof o[k] === 'string' ? (o[k] as string).trim() : '');
  const list = (x: unknown) => (Array.isArray(x) ? x : []);
</script>

{#snippet text(s: CoachSession, words: string, cls = 'body-large')}
  <p class={cls}>{#each linked(s, words) as part, i (i)}{#if part.id}<button class="ref" onclick={() => onreview(part.id!, part.step!)}>{part.text}</button>{:else}{part.text}{/if}{/each}</p>
{/snippet}

{#snippet answer(s: CoachSession)}
  {@const raw = s.turns[1]?.text ?? ''}
  {@const o = answerJson(raw)}
  {#if !o}
    {@render text(s, raw)}
  {:else}
    {#if str(o, 'summary')}{@render text(s, str(o, 'summary'))}{/if}
    {#each list(o.habits) as h, i (i)}
      {@const habit = (h ?? {}) as Record<string, unknown>}
      <div class="card habit">
        <p class="title-medium strong">{i + 1}. {str(habit, 'title')}</p>
        {@render text(s, str(habit, 'body'))}
        {#if str(habit, 'rule')}<p class="body-large try">{t('coach_try', str(habit, 'rule'))}</p>{/if}
        {#if list(habit.refs).length}{@render text(s, list(habit.refs).filter((x) => typeof x === 'string').join('  '), 'body-medium')}{/if}
      </div>
    {/each}
    {#if str(o, 'strengths')}
      <p class="title-small head">{t('coach_strengths')}</p>
      {@render text(s, str(o, 'strengths'))}
    {/if}
    {#if str(o, 'practice')}
      <p class="title-small head">{t('coach_practice')}</p>
      {@render text(s, str(o, 'practice'))}
    {/if}
  {/if}
{/snippet}

<div class="page">
  <TitleBar title={t('coach_title')} {onback} max={760} />
  <div class="scroll" bind:this={scroller}><div class="readable">
    <section class="new">
      <p class="body-medium dim">{t('coach_intro')}</p>
      {#if !ready.length}
        <p class="body-large">{t('coach_need_key')}</p>
        <div><button class="btn filled" onclick={() => (keysOpen = true)}>{t('coach_set_keys')}</button></div>
      {:else}
        <div class="row">
          <span class="label body-large">{t('coach_rules')}</span>
          {#if groups === null}<span>…</span>
          {:else if !group}<span class="dim">{t('coach_no_records')}</span>
          {:else}
            <Menu label={groupName(group)} options={groups.map((g) => [g.key, groupName(g)] as [string, string])} selected={group.key}
              onselect={(k) => (coach.groupKey = k)} />
          {/if}
        </div>
        <div class="row">
          <span class="label body-large">{t('coach_latest')}</span>
          <div class="grow"><Toggle options={COUNTS.map((n) => [n, String(n)] as [number, string])} selected={settings.coachHands} onselect={setCount} /></div>
        </div>
        {#if group && group.hands.length < 20}<p class="body-small error">{t('coach_few')}</p>{/if}
        <div class="row">
          <span class="label body-large">{t('coach_provider')}</span>
          <div class="grow"><Toggle options={ready.map((p) => [p.id, nameOf(p)] as [string, string])} selected={provider!.id} onselect={(id) => (providerId = id)} /></div>
        </div>
        <!-- Its model and thinking, for those who want another (the default: the provider's, medium). -->
        <div class="row">
          <span class="label body-large">{t('coach_model')}</span>
          {#if provider!.id === 'custom'}
            <span class="grow body-medium">{coachKeys.custom.model}</span>
          {:else}
            <span class="grow body-medium">{t('coach_model_line', modelFor(provider!)?.label ?? '', effortName(effortFor(provider!)))}</span>
            <button class="btn text" onclick={() => chooseModel(provider!)}>{t('coach_change')}</button>
          {/if}
        </div>
        <label class="field"><span class="body-small dim">{t('coach_question')}</span>
          <textarea class="body-large" rows="2" maxlength="500" placeholder={t('coach_question_hint')} bind:value={coach.question}></textarea></label>
        <div class="actions">
          <button class="btn filled" disabled={!group || busy} onclick={prepare}>{t('coach_prepare')}</button>
          <button class="btn text" onclick={() => (keysOpen = true)}>{t('coach_set_keys')}</button>
        </div>
      {/if}
    </section>

    <section class="phase">
      {#if phase.kind === 'gathering' || phase.kind === 'asking'}
        <div class="progress-box" role="status">
          <div class="line">
            <span class="body-medium grow">{phase.kind === 'gathering' ? t('coach_gathering', phase.done, phase.total)
              : t('coach_asking', nameOf(PROVIDERS.find((p) => p.id === phase.provider)!))}</span>
            <button class="btn text" onclick={() => coach.cancel()}>{t('cancel')}</button>
          </div>
          <div class="progress">
            {#if phase.kind === 'gathering'}<span class="fill" style:width="{(phase.done / Math.max(1, phase.total)) * 100}%"></span>
            {:else}<span class="bar"></span>{/if}
          </div>
        </div>
      {:else if phase.kind === 'ready'}
        {@const p = phase.prepared}
        <div class="card">
          <p class="title-small">{t('coach_ready', p.hands.length, p.rulesName)}</p>
          {#if p.skipped > 0}<p class="body-small dim">{t('coach_skipped', p.skipped)}</p>{/if}
          <p class="body-medium">{estimate(p)}</p>
          <div class="actions">
            {#if provider}<button class="btn filled" onclick={() => send(p, provider!)}><Icon name="send" size={18} />{t('coach_send_to', nameOf(provider))}</button>{/if}
            <button class="btn outlined" onclick={() => (preview = firstMessage(p, zh()))}>{t('coach_preview')}</button>
            <button class="btn text" onclick={() => coach.dismiss()}>{t('cancel')}</button>
          </div>
        </div>
      {:else if phase.kind === 'failed'}
        <div class="card failed" role="alert">
          <p class="body-large">{t(phase.error ? ERRORS[phase.error] : 'coach_error')}</p>
          {#if phase.detail.trim()}<p class="body-small detail">{phase.detail}</p>{/if}
          <div><button class="btn text" onclick={() => coach.dismiss()}>{t('close')}</button></div>
        </div>
      {/if}
    </section>

    {#if shown}
      {@const s = shown}
      <section class="session">
        <div class="line">
          <span class="body-small dim grow">{sessionLine(s)}</span>
          <button class="btn icon text" aria-label={t('delete')} onclick={() => (confirmDelete = s.id)}><Icon name="delete" /></button>
        </div>
        {@render answer(s)}
        <!-- Questions after it, and their answers. -->
        {#each s.turns.slice(2) as turn, i (i)}
          {#if turn.user}<p class="body-medium asked">{turn.text}</p>
          {:else}{@render text(s, turn.text)}{/if}
        {/each}
        <div class="line">
          <label class="field grow"><span class="body-small dim">{t('coach_more')}</span>
            <textarea class="body-large" rows="1" maxlength="500" disabled={busy} bind:value={more}></textarea></label>
          <button class="btn icon text" aria-label={t('coach_send')} disabled={busy || !more.trim()}
            onclick={() => { void coach.followUp(s, more); more = ''; }}><Icon name="send" /></button>
        </div>
        <p class="body-small dim">{s.cost !== null ? t('coach_used_cost', s.input, s.output, s.cost.toFixed(3)) : t('coach_used', s.input, s.output)}</p>
      </section>
    {/if}

    {#if coach.sessions.some((s) => s.id !== shown?.id)}
      <section class="earlier">
        <p class="title-small head">{t('coach_earlier')}</p>
        {#each coach.sessions.filter((s) => s.id !== shown?.id) as s (s.id)}
          <button class="btn text earlier-row" onclick={() => (coach.shownId = s.id)}>{sessionLine(s)}</button>
        {/each}
      </section>
    {/if}
  </div></div>
</div>

{#if keysOpen}
  <Dialog title={t('coach_keys_title')} onclose={() => (keysOpen = false)}>
    <p class="body-small dim note">{t('coach_keys_note_web')}</p>
    <div class="keys"><CoachKeys /></div>
    {#snippet actions()}<button class="btn text" onclick={() => (keysOpen = false)}>{t('close')}</button>{/snippet}
  </Dialog>
{/if}

{#if choosing}
  {@const c = choosing}
  <Dialog title={t('coach_model_title')} onclose={() => (choosing = null)}>
    <div class="choose">
      <p class="body-medium dim">{t('coach_model_help')}</p>
      <div role="radiogroup" class="models">
        {#each c.p.models as m (m.id)}
          <button role="radio" aria-checked={m.id === c.m.id} class="model" class:on={m.id === c.m.id} onclick={() => (choosing = { ...c, m })}>
            <span class="grow"><span class="body-large">{m.label}</span><br /><span class="body-small dim">{t('coach_model_price', price(m.inPrice), price(m.outPrice))}</span></span>
            <span class="radio" aria-hidden="true"></span>
          </button>
        {/each}
      </div>
      <p class="title-small">{t('coach_effort')}</p>
      <Toggle options={efforts} selected={c.e} onselect={(e) => (choosing = { ...c, e })} />
      <p class="body-small dim">{t('coach_effort_help')}</p>
    </div>
    {#snippet actions()}
      <button class="btn text" onclick={() => (choosing = null)}>{t('cancel')}</button>
      <button class="btn text" onclick={saveModel}>{t('ok')}</button>
    {/snippet}
  </Dialog>
{/if}

{#if preview !== null}
  <Dialog title={t('coach_preview_title')} wide onclose={() => (preview = null)}>
    <pre class="preview">{preview}</pre>
    {#snippet actions()}<button class="btn text" onclick={() => (preview = null)}>{t('close')}</button>{/snippet}
  </Dialog>
{/if}

{#if tell}
  {@const x = tell}
  <Dialog title={t('coach_tell_title', nameOf(x.to))} onclose={() => (tell = null)}>
    <p class="body-large note">{t('coach_tell', x.prepared.hands.length, nameOf(x.to))}</p>
    {#if x.to.id === 'gemini'}<p class="body-medium error note">{t('coach_gemini_free')}</p>{/if}
    {#if x.to.id === 'custom'}<p class="body-medium note">{t('coach_tell_custom', coachKeys.custom.url)}</p>{/if}
    {#snippet actions()}
      <button class="btn text" onclick={() => (tell = null)}>{t('cancel')}</button>
      <!-- (Read before it is closed: x is gone with it.) -->
      <button class="btn text" onclick={() => { const { prepared, to } = x; tell = null; coachKeys.setTold(to.id); send(prepared, to); }}>{t('coach_send')}</button>
    {/snippet}
  </Dialog>
{/if}

{#if confirmDelete}
  {@const id = confirmDelete}
  <Dialog title={t('coach_delete_confirm')} onclose={() => (confirmDelete = null)}>
    <span></span>
    {#snippet actions()}
      <button class="btn text" onclick={() => (confirmDelete = null)}>{t('cancel')}</button>
      <button class="btn text error" onclick={() => { coach.delete(id); coach.shownId = null; confirmDelete = null; }}>{t('delete')}</button>
    {/snippet}
  </Dialog>
{/if}

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .scroll { flex: 1; overflow: auto; padding-bottom: 24px; }
  .readable { max-width: 760px; margin: 0 auto; }
  section { padding: 8px 16px; display: flex; flex-direction: column; gap: 10px; }
  section:empty { display: none; }
  p { margin: 0; line-height: 1.55; white-space: pre-line; }
  .row, .line, .actions { display: flex; align-items: center; gap: 8px; }
  .actions { flex-wrap: wrap; }
  .label { flex: 0 0 72px; }
  .grow { flex: 1; min-width: 0; }
  .error { color: var(--error); }
  .strong { font-weight: 700; }
  .head { color: var(--primary); }
  .card { background: var(--surface-container); border-radius: 14px; padding: 10px 14px; display: flex; flex-direction: column; gap: 6px; }
  .card.failed { background: var(--error-container); color: var(--on-error-container); }
  .detail { user-select: text; overflow-wrap: anywhere; }
  .try { color: var(--primary); font-weight: 500; }
  .asked { background: var(--secondary-container); color: var(--on-secondary-container); border-radius: 12px; padding: 10px; }
  .ref { display: inline; border: none; background: none; padding: 0; font: inherit; color: var(--primary); text-decoration: underline; cursor: pointer; }
  .field { display: flex; flex-direction: column; gap: 2px; padding: 6px 14px 8px; border: 1px solid var(--outline); border-radius: 6px; }
  .field:focus-within { border: 2px solid var(--primary); padding: 5px 13px 7px; }
  .field textarea { border: none; outline: none; background: none; color: var(--on-surface); padding: 2px 0; font: inherit; width: 100%; min-width: 0; resize: vertical; }
  .progress-box { display: flex; flex-direction: column; gap: 6px; padding: 8px 0; }
  .progress { height: 4px; border-radius: 2px; background: var(--surface-container-highest); overflow: hidden; position: relative; }
  .fill { position: absolute; inset: 0 auto 0 0; background: var(--primary); transition: width 0.2s; }
  .bar { position: absolute; inset: 0 auto 0 0; width: 40%; background: var(--primary); animation: slide 1.1s ease-in-out infinite; }
  @keyframes slide { from { left: -40%; } to { left: 100%; } }
  @media (prefers-reduced-motion: reduce) { .bar { animation: none; } }
  .earlier-row { justify-content: flex-start; text-align: left; height: auto; padding: 8px 12px; }
  .note { margin-bottom: 8px; }
  /* The rows lined up with the note: the dialog's content scrolls, so a negative margin would be cut off. */
  .keys :global(.row) { padding-inline: 0; }
  .choose { display: flex; flex-direction: column; gap: 8px; }
  .models { display: flex; flex-direction: column; gap: 4px; }
  .model { display: flex; align-items: center; gap: 12px; padding: 8px 12px; border: none; border-radius: 16px; background: none; color: inherit;
    font: inherit; text-align: left; cursor: pointer; }
  .model.on { background: var(--secondary-container); }
  .radio { width: 18px; height: 18px; border-radius: 50%; border: 2px solid var(--on-surface-variant); flex: none; box-sizing: border-box; }
  .model.on .radio { border-color: var(--primary); background: radial-gradient(var(--primary) 0 4px, transparent 5px); }
  .preview { margin: 0; max-height: 60vh; overflow: auto; font-size: 12px; white-space: pre-wrap; user-select: text; }
</style>
