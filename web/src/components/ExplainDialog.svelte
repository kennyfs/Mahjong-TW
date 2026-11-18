<!--
  A decision explained by rules (解說; the app's ExplainDialog): the lesson, the opponent's signs when it is about
  defense, the reason in this hand, and a rule of thumb with figures from self-play (lib/explain.ts). The tips' page
  holds the tables the figures come from.
-->
<script lang="ts">
  import type { Explanation } from '../lib/explain';
  import { t } from '../lib/i18n.svelte';
  import Dialog from './Dialog.svelte';

  let { e, onclose, ontips }: { e: Explanation; onclose: () => void; ontips: () => void } = $props();
</script>

<Dialog title={e.title} {onclose}>
  <div class="explain">
    {#if e.found}<p class="found body-medium"><span class="star">★</span>{t('explain_found')}</p>{/if}
    <p class="choice body-medium">{e.choice}</p>
    {#if e.signs}<p class="signs body-medium">{e.signs}</p>{/if}
    {#each e.text as line, i (i)}<p class="body-large">{line}</p>{/each}
    {#if e.rule}
      <div class="rule"><span class="label-large">{t('explain_rule')}</span><p class="body-medium">{e.rule}</p></div>
    {/if}
  </div>
  {#snippet actions()}
    <button class="btn text" onclick={ontips}>{t('tips_title')}</button>
    <button class="btn text" onclick={onclose}>{t('close')}</button>
  {/snippet}
</Dialog>

<style>
  .explain { display: flex; flex-direction: column; gap: 10px; max-width: 480px; }
  p { margin: 0; }
  .choice { color: var(--on-surface-variant); }
  .found { color: var(--primary); }
  .star { margin-right: 4px; }
  .signs { padding: 8px 12px; border-radius: 12px; background: var(--surface-container-highest); }
  .rule { padding: 10px 12px; border-radius: 12px; background: var(--secondary-container); color: var(--on-secondary-container);
    display: flex; flex-direction: column; gap: 4px; }
</style>
