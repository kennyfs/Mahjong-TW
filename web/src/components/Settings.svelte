<!--
  The settings: the game (the rules, practice's length, the computer players and their pace), the look, and about.
  A computer shows the list at the left and the rules beside it.
-->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import { nav } from '../lib/nav.svelte';
  import { practice } from '../lib/practice.svelte';
  import { flowerLabel, taiLabel } from '../lib/rules';
  import { BOTS, settings, type Colors, type Dark, type Length, type Speed, type TextSize } from '../lib/settings.svelte';
  import { ui } from '../lib/ui.svelte';
  import Rules from './Rules.svelte';
  import SettingRow from './SettingRow.svelte';
  import TitleBar from './TitleBar.svelte';
  import Toggle from './Toggle.svelte';

  const lengths: [Length, string][] = [['hand', t('length_hand')], ['round', t('length_round')], ['game', t('length_game')]];
  const speeds: [Speed, string][] = [['slow', t('speed_slow')], ['normal', t('speed_normal')], ['fast', t('speed_fast')]];
  const colors: [Colors, string][] = [['TABLE', t('colors_table')], ['MINIMAL', t('colors_minimal')]];
  const darks: [Dark, string][] = [['SYSTEM', t('dark_system')], ['LIGHT', t('dark_light')], ['DARK', t('dark_dark')]];
  const texts: [TextSize, string][] = [['SMALL', t('text_small')], ['NORMAL', t('text_normal')], ['LARGE', t('text_large')]];
  const rulesValue = $derived(t('rules_chip', t(taiLabel(settings.rules.tai)), t(flowerLabel(settings.rules.flowers))) +
    (Object.keys(settings.rules.overrides).length ? '・' + t('rules_custom') : ''));

  function setLength(v: Length) { settings.length = v; settings.persist(); void practice.settingsChanged(); }
  function setSpeed(v: Speed) { settings.speed = v; settings.persist(); }
  function setColors(v: Colors) { settings.colors = v; settings.persist(); }
  function setDark(v: Dark) { settings.dark = v; settings.persist(); }
  function setText(v: TextSize) { settings.text = v; settings.persist(); }
</script>

{#snippet list(desk: boolean)}
  <div class="page">
    <TitleBar title={t('settings_title')} />
    <div class="scroll"><div class="readable">
      <div class="section-header">{t('settings_game')}</div>
      <SettingRow title={t('settings_rules')} value={rulesValue} onclick={desk ? null : () => nav.open('rules')} selected={desk} />
      <div class="block">
        <div class="body-large">{t('settings_length')}</div>
        <Toggle options={lengths} selected={settings.length} onselect={setLength} />
      </div>
      <SettingRow title={t('settings_bot')} value={t(BOTS.find((b) => b.spec === settings.bot)?.label ?? 'bot_greedy')} />
      <div class="block">
        <div class="body-large">{t('settings_speed')}</div>
        <Toggle options={speeds} selected={settings.speed} onselect={setSpeed} />
      </div>

      <div class="section-header">{t('settings_look')}</div>
      <div class="block">
        <div class="body-large">{t('settings_text')}</div>
        <Toggle options={texts} selected={settings.text} onselect={setText} />
      </div>
      <div class="block">
        <div class="body-large">{t('settings_colors')}</div>
        <Toggle options={colors} selected={settings.colors} onselect={setColors} />
      </div>
      <div class="block">
        <div class="body-large">{t('settings_dark')}</div>
        <Toggle options={darks} selected={settings.dark} onselect={setDark} />
      </div>

      <div class="section-header">{t('settings_about')}</div>
      <p class="about body-medium">{t('about_text')}</p>
    </div></div>
  </div>
{/snippet}

{#if ui.desk}
  <div class="desk">
    <div class="desk-list">{@render list(true)}</div>
    <div class="desk-pane"><Rules /></div>
  </div>
{:else if nav.sub === 'rules'}
  <Rules onback={() => nav.back()} />
{:else}
  {@render list(false)}
{/if}

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .scroll { flex: 1; overflow: auto; padding-bottom: 24px; }
  .readable { max-width: 720px; margin: 0 auto; }
  .block { padding: 8px 16px; display: flex; flex-direction: column; gap: 8px; }
  .about { padding: 0 16px; line-height: 1.6; }
  .desk { height: 100%; display: flex; min-height: 0; }
  .desk-list { flex: 0 0 380px; border-right: 1px solid var(--outline-variant, var(--surface-container-highest)); }
  .desk-pane { flex: 1; min-width: 0; }
  .desk-pane :global(.readable) { margin: 0; max-width: 820px; padding: 0 12px; }
</style>
