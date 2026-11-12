<!--
  The advanced settings (設定 > 進階): ways of showing the analysis that are a matter of taste, each on its own: what a
  row of a decision's list shows, and what a review's hand shows on its tiles.
-->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import { settings, type CallFxMode, type ListRows, type TableMarks } from '../lib/settings.svelte';
  import TitleBar from './TitleBar.svelte';
  import Toggle from './Toggle.svelte';

  let { onback = null }: { onback?: (() => void) | null } = $props();
  const rows: [ListRows, string][] = [['numbers', t('list_rows_numbers')], ['bars', t('list_rows_bars')], ['both', t('list_rows_both')]];
  const marks: [TableMarks, string][] = [['both', t('table_marks_both')], ['loss', t('table_marks_loss')], ['none', t('table_marks_none')]];
  function setRows(v: ListRows) { settings.listRows = v; settings.persist(); }
  function setMarks(v: TableMarks) { settings.tableMarks = v; settings.persist(); }
  const fxModes: [CallFxMode, string][] = [['anim', t('call_fx_anim')], ['words', t('call_fx_words')]];
  function setFx(v: CallFxMode) { settings.callFx = v; settings.persist(); }
</script>

<div class="page">
  <TitleBar title={t('advanced_title')} {onback} max={720} />
  <div class="scroll"><div class="readable">
    <div class="block">
      <div class="body-large">{t('settings_list_rows')}</div>
      <Toggle options={rows} selected={settings.listRows} onselect={setRows} />
      <div class="body-small dim">{t('list_rows_hint')}</div>
    </div>
    <div class="block">
      <div class="body-large">{t('settings_table_marks')}</div>
      <Toggle options={marks} selected={settings.tableMarks} onselect={setMarks} />
      <div class="body-small dim">{t('table_marks_hint')}</div>
    </div>
    <div class="block">
      <div class="body-large">{t('settings_call_fx')}</div>
      <Toggle options={fxModes} selected={settings.callFx} onselect={setFx} />
      <div class="body-small dim">{t('call_fx_hint')}</div>
    </div>
  </div></div>
</div>

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .scroll { flex: 1; overflow: auto; padding-bottom: 24px; }
  .readable { max-width: 720px; margin: 0 auto; }
  .block { padding: 8px 16px; display: flex; flex-direction: column; gap: 8px; }
</style>
