<!--
  Beginner glossary list (術語表; the app's GlossaryContent): strictly topologically ordered so earlier entries never
  depend on later ones. When `focusId` is provided, scrolls to and highlights that term. Prerequisite terms inside
  descriptions are also clickable to jump up to their definition.
-->
<script lang="ts">
  import { tick } from 'svelte';
  import { GLOSSARY, linkGlossary } from '../lib/glossary';
  import { lang as i18nLang, t } from '../lib/i18n.svelte';

  let { focusId = null }: { focusId?: string | null } = $props();
  let activeId = $state<string | null>(null);
  let refs = $state<Record<string, HTMLElement | undefined>>({});

  async function jumpTo(id: string) {
    activeId = id;
    await tick();
    const el = refs[id];
    const reduced = typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;
    el?.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'center' });
  }

  $effect(() => {
    const id = focusId;
    if (id) void jumpTo(id);
  });

  const lang = $derived<'zh' | 'en'>(i18nLang.code === 'en' ? 'en' : 'zh');
</script>

<div class="glossary">
  <ol class="list">
    {#each GLOSSARY as entry, idx (entry.id)}
      <li bind:this={refs[entry.id]} class="item" class:focus={activeId === entry.id}>
        <div class="head">
          <span class="num label-medium">{idx + 1}</span>
          <span class="term title-small">{t(`glossary_t_${entry.id}`)}</span>
        </div>
        <p class="desc body-medium">
          {#each linkGlossary(t(`glossary_d_${entry.id}`), lang, entry.id) as part, pi (pi)}
            {#if part.termId}
              <button type="button" class="ref" onclick={() => void jumpTo(part.termId!)}>{part.text}</button>
            {:else}{part.text}{/if}
          {/each}
        </p>
      </li>
    {/each}
  </ol>
</div>

<style>
  .glossary { display: flex; flex-direction: column; gap: 12px; }
  .list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
  .item { padding: 10px 12px; border-radius: 12px; background: var(--surface-container-low); border: 1px solid transparent;
    display: flex; flex-direction: column; gap: 4px; transition: background-color 220ms ease, border-color 220ms ease; }
  .item.focus { background: var(--primary-container); border-color: var(--primary); color: var(--on-primary-container); }
  .head { display: flex; align-items: baseline; gap: 8px; }
  .num { color: var(--on-surface-variant); min-width: 1.6em; font-variant-numeric: tabular-nums; }
  .item.focus .num { color: var(--on-primary-container); opacity: 0.8; }
  .term { font-weight: 600; }
  .desc { margin: 0; line-height: 1.5; }
  .ref { border: none; background: none; padding: 0; font: inherit; color: var(--primary);
    text-decoration: underline; text-underline-offset: 2px; cursor: pointer; }
  .item.focus .ref { color: var(--on-primary-container); }
  @media (prefers-reduced-motion: reduce) { .item { transition: none; } }
</style>
