<!--
  The rules: a tai preset and a flower preset, chosen apart (簡單/標準/大師; 無花/見花見台/正花, in that order), the
  base (底), and every parameter in a list, the changed ones marked, each with what it means behind an i button (the
  strings' help_<parameter>, in a dialog). No button changes the page's height: the mark and the reset are always in
  their place, shown or not. The parameters' names and values come from the engine ({"cmd":"rules"}), never from a
  table here. Practice deals a new game under new rules when the page is left.
-->
<script lang="ts">
  import { onDestroy } from 'svelte';
  import { has, t } from '../lib/i18n.svelte';
  import { practice } from '../lib/practice.svelte';
  import {
    FLAG_PARAMS, FLOWER_PARAMS, FLOWER_PRESETS, flowerLabel, info, PLAY_PARAMS, ruleSpec, TAI_PRESETS, taiLabel, withValue,
    type RuleChoice,
  } from '../lib/rules';
  import { settings } from '../lib/settings.svelte';
  import type { RulesInfo } from '../lib/types';
  import { ui } from '../lib/ui.svelte';
  import Dialog from './Dialog.svelte';
  import Icon from './Icon.svelte';
  import TitleBar from './TitleBar.svelte';

  let { onback = null }: { onback?: (() => void) | null } = $props();

  /** The chosen rules' values, and the presets' (what a changed value is told from). */
  let full = $state<RulesInfo | null>(null);
  let presets = $state<RulesInfo | null>(null);
  let advanced = $state(false);
  $effect(() => {
    const c = settings.rules;
    const spec = ruleSpec(c);
    void info(spec).then((i) => { if (ruleSpec(settings.rules) === spec) full = i; }, (e) => (full = null, console.error(e)));
    void info(`${c.tai},${c.flowers}`).then((i) => (presets = i));
  });
  onDestroy(() => { void practice.settingsChanged(); });

  function choose(c: RuleChoice) {
    settings.rules = c;
    settings.persist();
  }
  async function set(param: string, value: number) {
    choose(await withValue(settings.rules, param, value));
  }

  // Each related flag right after its item; the parameters in three groups.
  const AFTER: Record<string, string> = { quanqiu_duting: 'quanqiu', dasixi_winds: 'dasixi' };
  const names = $derived(new Map((full?.items ?? []).map((i) => [i.param, i.name])));
  const groups = $derived.by(() => {
    if (!full) return [];
    const values = full.values;
    const tai: string[] = [];
    for (const it of full.items) {
      if (FLOWER_PARAMS.includes(it.param)) continue;
      tai.push(it.param);
      for (const [flag, after] of Object.entries(AFTER)) if (after === it.param && flag in values) tai.push(flag);
    }
    const listed = new Set([...tai, ...FLOWER_PARAMS, ...PLAY_PARAMS]);
    for (const p of Object.keys(values)) if (!listed.has(p)) tai.push(p);
    return [
      { title: 'rules_group_play', params: PLAY_PARAMS.filter((p) => p in values) },
      // The flowers' mode itself is the cards' choice above, not a number here.
      { title: 'rules_group_flowers', params: FLOWER_PARAMS.filter((p) => p in values && p !== 'flowers') },
      { title: 'rules_group_tai', params: tai },
    ];
  });
  const label = (p: string) => names.get(p) ?? t('param_' + p);
  /** The parameter whose explanation is open (in a dialog, so that the page keeps its height). */
  let helped = $state<string | null>(null);
  const changed = (p: string) => !!presets && !!full && presets.values[p] !== full.values[p];
  const MIN: Record<string, number> = { base: 1 };
  const MAX: Record<string, number> = { flowers: 2, keep: 64 };
  const base = $derived(full?.values.base ?? 1);
  const anyChanged = $derived(Object.keys(settings.rules.overrides).length > 0);
</script>

<div class="page">
  <TitleBar title={t('rules_title')} {onback} max={ui.desk ? 0 : 720} />
  <div class="scroll"><div class="readable">
    <div class="section-header">{t('rules_tai')}</div>
    <div class="cards" role="radiogroup" aria-label={t('rules_tai')}>
      {#each TAI_PRESETS as p (p)}
        <button class="card" role="radio" aria-checked={settings.rules.tai === p} class:on={settings.rules.tai === p}
          onclick={() => choose({ ...settings.rules, tai: p })}>
          <span class="radio" class:on={settings.rules.tai === p}></span>
          <span class="text"><span class="title-medium">{t(taiLabel(p))}</span><span class="body-medium dim">{t(`tai_${p}_desc`)}</span></span>
        </button>
      {/each}
    </div>

    <div class="section-header">{t('rules_flowers')}</div>
    <div class="cards" role="radiogroup" aria-label={t('rules_flowers')}>
      {#each FLOWER_PRESETS as p (p)}
        <button class="card" role="radio" aria-checked={settings.rules.flowers === p} class:on={settings.rules.flowers === p}
          onclick={() => choose({ ...settings.rules, flowers: p })}>
          <span class="radio" class:on={settings.rules.flowers === p}></span>
          <span class="text"><span class="title-medium">{t(flowerLabel(p))}</span><span class="body-medium dim">{t(`flowers_${p}_desc`)}</span></span>
        </button>
      {/each}
    </div>

    <div class="section-header">{t('rules_base')}</div>
    <div class="row">
      <span class="text"><span class="body-large">{t('result_base', String(base))}</span><span class="body-medium dim">{t('rules_base_desc')}</span></span>
      <span class="stepper">
        <button class="btn icon tonal" aria-label="−" disabled={base <= 1} onclick={() => set('base', base - 1)}><Icon name="remove" /></button>
        <span class="value">{base}</span>
        <button class="btn icon tonal" aria-label="+" disabled={base >= 20} onclick={() => set('base', base + 1)}><Icon name="add" /></button>
      </span>
    </div>
    <p class="note body-medium dim">{t('rules_note')}</p>

    <button class="section-toggle" aria-expanded={advanced} onclick={() => (advanced = !advanced)}>
      <span class="text"><span class="title-medium">{t('rules_advanced')}</span><span class="body-medium dim">{t('rules_advanced_desc')}</span></span>
      <span class="chev" class:open={advanced}><Icon name="dropDown" /></span>
    </button>
    {#if advanced && full}
      <div class="reset"><button class="btn text" disabled={!anyChanged} onclick={() => choose({ ...settings.rules, overrides: {} })}>{t('rules_reset')}</button></div>
      {#each groups as g (g.title)}
        <div class="section-header">{t(g.title)}</div>
        {#each g.params as p (p)}
          {@const v = full.values[p]}
          <div class="row param" class:changed={changed(p)}>
            <span class="text">
              <span class="name">
                <span class="body-large">{label(p)}</span>
                {#if has('help_' + p)}
                  <button class="btn icon text info" aria-label={`${t('rules_help')}：${label(p)}`} aria-haspopup="dialog"
                    onclick={() => (helped = p)}><Icon name="info" size={20} /></button>
                {/if}
              </span>
              <!-- Always in its place, seen or not: a change moves nothing on the page. -->
              <span class="mark label-small" class:off={!changed(p)} aria-hidden={!changed(p)}>{t('rules_changed')}</span>
            </span>
            {#if FLAG_PARAMS.includes(p)}
              <button class="flag" role="switch" aria-checked={v === 1} aria-label={label(p)} onclick={() => set(p, v === 1 ? 0 : 1)}>
                <span class="track" class:on={v === 1}><span class="thumb"></span></span>
              </button>
            {:else}
              <span class="stepper">
                <button class="btn icon tonal" aria-label="−" disabled={v <= (MIN[p] ?? 0)} onclick={() => set(p, v - 1)}><Icon name="remove" /></button>
                <span class="value">{v}</span>
                <button class="btn icon tonal" aria-label="+" disabled={v >= (MAX[p] ?? 99)} onclick={() => set(p, v + 1)}><Icon name="add" /></button>
              </span>
            {/if}
          </div>
        {/each}
      {/each}
    {/if}
  </div></div>
</div>

{#if helped}
  <Dialog title={label(helped)} onclose={() => (helped = null)}>
    <p class="body-large help">{t('help_' + helped)}</p>
    {#snippet actions()}<button class="btn text" onclick={() => (helped = null)}>{t('close')}</button>{/snippet}
  </Dialog>
{/if}

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .scroll { flex: 1; overflow: auto; padding-bottom: 32px; }
  .readable { max-width: 720px; margin: 0 auto; }
  .cards { display: flex; flex-direction: column; gap: 8px; padding: 0 16px; }
  .card { display: flex; align-items: flex-start; gap: 12px; padding: 12px 14px; border-radius: 16px; border: 1px solid var(--outline-variant, var(--surface-container-highest));
    background: var(--surface-container-low, var(--surface)); color: inherit; text-align: left; cursor: pointer; font: inherit; }
  .card.on { background: var(--secondary-container); color: var(--on-secondary-container); border-color: transparent; }
  .radio { flex: none; margin-top: 3px; width: 20px; height: 20px; border-radius: 50%; border: 2px solid var(--on-surface-variant); box-sizing: border-box; }
  .radio.on { border-color: var(--primary); background: radial-gradient(var(--primary) 0 5px, transparent 5.5px); }
  .text { flex: 1; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
  .row { display: flex; align-items: center; gap: 12px; padding: 8px 16px; min-height: 56px; }
  .name { display: flex; align-items: center; gap: 2px; min-width: 0; }
  .info { flex: none; width: 36px; min-height: 36px; border-radius: 18px; color: var(--on-surface-variant); }
  .help { margin: 0; line-height: 1.6; }
  .mark { align-self: flex-start; padding: 1px 8px; border-radius: 8px; background: var(--tertiary-container); color: var(--on-tertiary-container); }
  .mark.off { visibility: hidden; }
  .stepper { display: inline-flex; align-items: center; gap: 6px; flex: none; }
  .value { min-width: 2.2em; text-align: center; font-size: 1.125rem; font-weight: 700; }
  .note { padding: 0 16px; margin: 4px 0 8px; }
  .section-toggle { display: flex; align-items: center; gap: 12px; width: 100%; padding: 14px 16px; margin-top: 8px; border: none; background: none;
    color: inherit; text-align: left; cursor: pointer; font: inherit; border-top: 1px solid var(--outline-variant, var(--surface-container-highest)); }
  .chev { transition: transform 0.15s; display: inline-flex; }
  .chev.open { transform: rotate(180deg); }
  .reset { display: flex; justify-content: flex-end; padding: 0 8px; }
  .flag { border: none; background: none; padding: 4px; cursor: pointer; }
  .track { display: block; width: 52px; height: 32px; border-radius: 16px; border: 2px solid var(--outline); box-sizing: border-box;
    background: var(--surface-container-highest); position: relative; transition: background 0.15s; }
  .thumb { position: absolute; left: 6px; top: 6px; width: 16px; height: 16px; border-radius: 50%; background: var(--outline); transition: all 0.15s; }
  .track.on { background: var(--primary); border-color: var(--primary); }
  .track.on .thumb { left: 22px; top: 2px; width: 24px; height: 24px; background: var(--on-primary); }
</style>
