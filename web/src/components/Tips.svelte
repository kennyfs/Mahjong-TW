<!-- Table tips (打牌要訣) on a page of their own, from the settings (#/<screen>/tips); Back leaves it. -->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import TipsContent from './TipsContent.svelte';
  import TitleBar from './TitleBar.svelte';

  let { onback }: { onback: () => void } = $props();
</script>

<div class="page">
  <TitleBar title={t('tips_title')} {onback} />
  <div class="scroll"><div class="inner"><TipsContent /></div></div>
</div>

<style>
  .page { display: flex; flex-direction: column; height: 100%; min-height: 0; }
  .scroll { flex: 1; overflow: auto; min-height: 0; }
  .inner { max-width: 640px; margin: 0 auto; padding: 8px 16px 32px; }
</style>
