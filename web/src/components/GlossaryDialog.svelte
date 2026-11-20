<!-- Beginner glossary modal dialog, opened when tapping a term link inside ExplainDialog. -->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import Dialog from './Dialog.svelte';
  import GlossaryContent from './GlossaryContent.svelte';

  let { focusId = null, onclose }: { focusId?: string | null; onclose: () => void } = $props();
</script>

<Dialog title={t('glossary_title')} {onclose}>
  <div class="wrap"><GlossaryContent {focusId} /></div>
  {#snippet actions()}
    <button class="btn tonal" onclick={onclose}>{t('close')}</button>
  {/snippet}
</Dialog>

<style>
  .wrap { max-width: 520px; }
</style>
