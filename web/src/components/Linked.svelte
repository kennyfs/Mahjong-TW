<!--
  Words with the glossary's terms in them linked (lib/glossary.ts): a tap opens the glossary (GlossaryDialog, shown by
  App.svelte) at the term. Each term is linked at its first appearance in `text` only, so that a paragraph is not all
  underlines. For the words of the tips and the guide (the explanation, ExplainDialog, links every appearance). The
  app's LinkedText.
-->
<script lang="ts">
  import { linkGlossary, type TextPart } from '../lib/glossary';
  import { glossaryLink } from '../lib/glossarylink.svelte';
  import { lang as i18nLang } from '../lib/i18n.svelte';

  let { text }: { text: string } = $props();
  const parts = $derived.by(() => {
    const seen = new Set<string>();
    return linkGlossary(text, i18nLang.code === 'en' ? 'en' : 'zh').map((p): TextPart => {
      if (!p.termId || !seen.has(p.termId)) { if (p.termId) seen.add(p.termId); return p; }
      return { text: p.text };
    });
  });
</script>

{#each parts as part, i (i)}{#if part.termId}<button type="button" class="ref" onclick={() => (glossaryLink.term = part.termId!)}>{part.text}</button>{:else}{part.text}{/if}{/each}

<style>
  .ref { border: none; background: none; padding: 0; font: inherit; color: var(--primary);
    text-decoration: underline; text-underline-offset: 2px; cursor: pointer; }
</style>
