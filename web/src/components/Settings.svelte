<!--
  The settings, in Thirteen's order: the look (text size; the colors and dark theme, each a menu), the game (the
  rules, practice's length, the computer players and their pace, sounds), what helps in practice (聽牌提示, 顯示分析),
  the analysis (from which loss on a decision is a mistake), help, and about (the source, the app, the privacy
  policy). A computer shows the list at the left and the rules beside it. A setting's longer words are behind an ⓘ by
  its name (a dialog), so that the list stays short.
-->
<script lang="ts">
  import { LANGS, t, type Lang } from '../lib/i18n.svelte';
  import { nav } from '../lib/nav.svelte';
  import { practice } from '../lib/practice.svelte';
  import { flowerLabel, taiLabel } from '../lib/rules';
  import { BOTS, settings, type Colors, type Dark, type Length, type Level, type SoundLevel, type Speed, type TextSize } from '../lib/settings.svelte';
  import { playSound } from '../lib/sound';
  import { tour } from '../lib/tour.svelte';
  import { ui } from '../lib/ui.svelte';
  import Dialog from './Dialog.svelte';
  import Icon from './Icon.svelte';
  import Menu from './Menu.svelte';
  import Rules from './Rules.svelte';
  import SettingRow from './SettingRow.svelte';
  import TitleBar from './TitleBar.svelte';
  import Toggle from './Toggle.svelte';

  const lengths: [Length, string][] = [['hand', t('length_hand')], ['round', t('length_round')], ['game', t('length_game')]];
  const speeds: [Speed, string][] = [['slow', t('speed_slow')], ['normal', t('speed_normal')], ['fast', t('speed_fast')], ['manual', t('speed_manual')]];
  const colors: [Colors, string][] = [['TABLE', t('colors_table')], ['MINIMAL', t('colors_minimal')]];
  const darks: [Dark, string][] = [['SYSTEM', t('dark_system')], ['LIGHT', t('dark_light')], ['DARK', t('dark_dark')]];
  const texts: [TextSize, string][] = [['SMALL', t('text_small')], ['NORMAL', t('text_normal')], ['LARGE', t('text_large')]];
  const bots: [string, string][] = BOTS.map((b) => [b.spec, t(b.label)]);
  const sounds: [SoundLevel, string][] = [['off', t('sound_off')], ['soft', t('sound_soft')], ['full', t('sound_full')]];
  const levels: [Level, string][] = [['beginner', t('level_beginner')], ['mid', t('level_mid')], ['expert', t('level_expert')]];
  const rulesValue = $derived(t('rules_chip', t(taiLabel(settings.rules.tai)), t(flowerLabel(settings.rules.flowers))) +
    (Object.keys(settings.rules.overrides).length ? t('sep') + t('rules_custom') : ''));

  function setLength(v: Length) { settings.length = v; settings.persist(); void practice.settingsChanged(); }
  function setBot(v: string) { settings.bot = v; settings.persist(); void practice.settingsChanged(); }
  function setLevel(v: Level) { settings.level = v; settings.persist(); }
  function setSpeed(v: Speed) { settings.speed = v; settings.persist(); practice.paceChanged(); }
  function setColors(v: Colors) { settings.colors = v; settings.persist(); }
  function setDark(v: Dark) { settings.dark = v; settings.persist(); }
  function setSound(v: SoundLevel) { settings.sound = v; settings.persist(); playSound('discard'); }
  const onOff: [string, string][] = [['off', t('sound_off')], ['on', t('ready_hint_on')]];
  function setReady(v: string) { settings.readyHint = v === 'on'; settings.persist(); }
  // (Turned on while a practice hand is in play: that hand has had it, and keeps it till its end.)
  function setHints(v: string) {
    settings.practiceHints = v === 'on';
    settings.persist();
    if (settings.practiceHints) practice.markHinted();
  }
  function setText(v: TextSize) { settings.text = v; settings.persist(); }
  /** The setting whose words are shown (its ⓘ tapped). */
  let helped = $state<{ title: string; text: string } | null>(null);
  const label = <V,>(list: [V, string][], v: V) => list.find(([x]) => x === v)?.[1] ?? '';
  // About (as Thirteen's): the source, the app, the privacy policy.
  const SOURCE = 'https://github.com/kennyfs/Mahjong-TW';
  const PLAY = 'https://play.google.com/store/apps/details?id=io.github.kennyfs.mahjong';
  const PRIVACY = 'https://github.com/kennyfs/Mahjong-TW/blob/main/docs/privacy.md';
</script>

{#snippet head(title: string, help: string)}
  <div class="head">
    <span class="body-large">{title}</span>
    <button class="btn icon text info" aria-label={t('rules_help') + t('colon') + title} title={t('rules_help')} aria-haspopup="dialog"
      onclick={() => (helped = { title, text: help })}><Icon name="info" size={20} /></button>
  </div>
{/snippet}

{#snippet list(desk: boolean)}
  <div class="page">
    <TitleBar title={t('settings_title')} max={720} />
    <div class="scroll"><div class="readable">
      <!-- (In Thirteen's order: the look, the game, help, about; the analysis after the game.) -->
      <div class="section-header">{t('settings_look')}</div>
      <div class="block">
        <div class="body-large">{t('settings_text')}</div>
        <Toggle options={texts} selected={settings.text} onselect={setText} />
      </div>
      <SettingRow title={t('settings_colors')}>
        {#snippet trailing()}<Menu label={label(colors, settings.colors)} options={colors} selected={settings.colors} onselect={setColors} chip={false} />{/snippet}
      </SettingRow>
      <SettingRow title={t('settings_dark')}>
        {#snippet trailing()}<Menu label={label(darks, settings.dark)} options={darks} selected={settings.dark} onselect={setDark} chip={false} />{/snippet}
      </SettingRow>
      <!-- (Named in both languages, as Thirteen's: whoever cannot read one finds it by the other.) -->
      <SettingRow title={settings.lang === 'en' ? 'Language' : `${t('settings_language')} · Language`}>
        {#snippet trailing()}<Menu label={LANGS.find((l) => l.id === settings.lang)?.name ?? ''} options={LANGS.map((l) => [l.id, l.name] as [Lang, string])}
          selected={settings.lang} onselect={(v) => settings.chooseLang(v)} chip={false} />{/snippet}
      </SettingRow>

      <div class="section-header">{t('settings_game')}</div>
      <SettingRow title={t('settings_rules')} value={rulesValue} onclick={desk ? null : () => nav.open('rules')} selected={desk} />
      <div class="block">
        {@render head(t('settings_length'), t('length_help'))}
        <Toggle options={lengths} selected={settings.length} onselect={setLength} />
      </div>
      <div class="block">
        {@render head(t('settings_bot'), t('bot_help'))}
        <Toggle options={bots} selected={settings.bot} onselect={setBot} />
      </div>
      <div class="block">
        {@render head(t('settings_speed'), t('speed_manual_hint'))}
        <Toggle options={speeds} selected={settings.speed} onselect={setSpeed} />
      </div>
      <div class="block">
        <div class="body-large">{t('settings_sound')}</div>
        <Toggle options={sounds} selected={settings.sound} onselect={setSound} />
      </div>
      <!-- What helps in practice (and a review's branch); never in a battle. -->
      <div class="hints" data-tour="hints">
      <div class="section-header">{t('settings_practice_aids')}</div>
      <div class="block">
        {@render head(t('settings_ready_hint'), t('ready_hint_text'))}
        <Toggle options={onOff} selected={settings.readyHint ? 'on' : 'off'} onselect={setReady} />
      </div>
      <div class="block">
        {@render head(t('settings_practice_hints'), t('practice_hints_text'))}
        <Toggle options={onOff} selected={settings.practiceHints ? 'on' : 'off'} onselect={setHints} />
      </div>
      </div>

      <div class="section-header">{t('settings_analysis')}</div>
      <div class="block">
        {@render head(t('settings_level'), t('level_hint'))}
        <Toggle options={levels} selected={settings.level} onselect={setLevel} />
      </div>

      <div class="section-header">{t('settings_help')}</div>
      <SettingRow title={t('guide_title')} value={t('guide_row')} onclick={() => nav.open('guide')} />
      <SettingRow title={t('tour_title')} value={t('tour_row')} onclick={() => tour.start()} />

      <div class="section-header">{t('settings_about')}</div>
      <SettingRow title={t('app_name')} value={t('about_source')} href={SOURCE} />
      <SettingRow title={t('about_play')} value={t('about_play_note')} href={PLAY} />
      <SettingRow title={t('privacy_policy')} href={PRIVACY} />
      <p class="about body-medium dim">{t('about_text_net')}</p>
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

{#if helped}
  <Dialog title={helped.title} onclose={() => (helped = null)}>
    <p class="body-large help">{helped.text}</p>
    {#snippet actions()}<button class="btn text" onclick={() => (helped = null)}>{t('close')}</button>{/snippet}
  </Dialog>
{/if}

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .scroll { flex: 1; overflow: auto; padding-bottom: 24px; }
  .readable { max-width: 720px; margin: 0 auto; }
  .block { padding: 8px 16px; display: flex; flex-direction: column; gap: 8px; }
  /* The name and its ⓘ: the row as high as a name alone (the button reaches over the gaps). */
  .head { display: flex; align-items: center; gap: 2px; min-height: 24px; margin: -6px 0; }
  .info { flex: none; width: 36px; min-height: 36px; border-radius: 18px; color: var(--on-surface-variant); }
  .help { margin: 0; line-height: 1.6; white-space: pre-line; }
  .about { padding: 8px 16px 0; line-height: 1.6; }
  .desk { height: 100%; display: flex; min-height: 0; }
  .desk-list { flex: 0 0 380px; border-right: 1px solid var(--outline-variant, var(--surface-container-highest)); }
  .desk-pane { flex: 1; min-width: 0; }
  .desk-pane :global(.readable) { margin: 0; max-width: 820px; padding: 0 12px; }
</style>
