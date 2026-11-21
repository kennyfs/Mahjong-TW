<!--
  A decision explained by rules (解說; the app's ExplainDialog): the lesson, the opponent's signs when it is about
  defense, the reason in this hand, and a rule of thumb with figures from self-play (lib/explain.ts). Technical terms
  are clickable links that open the beginner glossary scrolled to that term.
-->
<script lang="ts">
  import type { Explanation } from '../lib/explain';
  import { linkGlossary } from '../lib/glossary';
  import { lang as i18nLang, t } from '../lib/i18n.svelte';
  import Dialog from './Dialog.svelte';
  import GlossaryDialog from './GlossaryDialog.svelte';

  let { e, onclose, ontips }: { e: Explanation; onclose: () => void; ontips: () => void } = $props();
  let glossaryTerm = $state<string | null>(null);
  const lang = $derived<'zh' | 'en'>(i18nLang.code === 'en' ? 'en' : 'zh');
</script>

{#snippet linked(text: string)}
  {#each linkGlossary(text, lang) as part, i (i)}
    {#if part.termId}
      <button type="button" class="ref" onclick={() => (glossaryTerm = part.termId!)}>{part.text}</button>
    {:else}{part.text}{/if}
  {/each}
{/snippet}

<Dialog title={e.title} {onclose}>
  <div class="explain">
    {#if e.found}<p class="found body-medium"><span class="star">★</span>{@render linked(t('explain_found'))}</p>{/if}
    {#if e.you && e.alt}
      <div class="choices body-medium">
        <span class="choice-chip"><span class="tag">{t('explain_you')}</span><b>{e.you}</b></span>
        <span class="choice-chip alt"><span class="tag">{t(e.found ? 'explain_eff_choice' : 'explain_better')}</span><b>{e.alt}</b></span>
      </div>
    {:else}
      <p class="choice body-medium">{e.choice}</p>
    {/if}
    {#if e.signs}<p class="signs body-medium">{@render linked(e.signs)}</p>{/if}
    {#each e.text as line, i (i)}<p class="body-large">{@render linked(line)}</p>{/each}
    {#if e.rule}
      <div class="rule"><span class="label-large">{t('explain_rule')}</span><p class="body-medium">{@render linked(e.rule)}</p></div>
    {/if}
  </div>
  {#snippet actions()}
    <button class="btn text" onclick={ontips}>{t('tips_title')}</button>
    <button class="btn tonal" onclick={onclose}>{t('close')}</button>
  {/snippet}
</Dialog>

{#if glossaryTerm}
  <GlossaryDialog focusId={glossaryTerm} onclose={() => (glossaryTerm = null)} />
{/if}

<style>
  .explain { display: flex; flex-direction: column; gap: 10px; max-width: 480px; }
  p { margin: 0; line-height: 1.55; }
  .choice { color: var(--on-surface-variant); }
  .choices { display: flex; flex-wrap: wrap; gap: 6px; }
  .choice-chip { display: inline-flex; align-items: baseline; gap: 5px; padding: 4px 10px; border-radius: 10px;
    background: var(--surface-container-highest); color: var(--on-surface); white-space: nowrap; }
  .choice-chip.alt { background: var(--secondary-container); color: var(--on-secondary-container); }
  .choice-chip .tag { font-size: 0.85em; opacity: 0.8; }
  .choice-chip b { font-weight: 600; }
  .found { color: var(--primary); }
  .star { margin-right: 4px; }
  .signs { padding: 8px 12px; border-radius: 12px; background: var(--surface-container-highest); }
  .rule { padding: 10px 12px; border-radius: 12px; background: var(--secondary-container); color: var(--on-secondary-container);
    display: flex; flex-direction: column; gap: 4px; }
  .ref { border: none; background: none; padding: 0; font: inherit; color: var(--primary);
    text-decoration: underline; text-underline-offset: 2px; cursor: pointer; }
  .rule .ref { color: var(--on-secondary-container); }
</style>
