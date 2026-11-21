<!-- Beginner glossary (術語表) on a page of its own, from Settings or Guide (#/<screen>/glossary). -->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import GlossaryContent from './GlossaryContent.svelte';
  import TitleBar from './TitleBar.svelte';

  let { onback, focusId = null }: { onback: () => void; focusId?: string | null } = $props();
</script>

<div class="page">
  <TitleBar title={t('glossary_title')} {onback} max={640} />
  <div class="scroll"><div class="inner"><GlossaryContent {focusId} /></div></div>
</div>

<style>
  .page { display: flex; flex-direction: column; height: 100%; min-height: 0; }
  .scroll { flex: 1; overflow: auto; min-height: 0; }
  .inner { max-width: 640px; margin: 0 auto; padding: 8px 16px 32px; }
</style>
