<!--
  The page: the screens, with a bar of them under on a tall window and a rail at the side on a wide one, as the app.
  The colors and the text size go on <html>, so that everything, dialogs too, follows them.
-->
<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { shared } from './lib/app.svelte';
  import { t } from './lib/i18n.svelte';
  import { nav, type Screen } from './lib/nav.svelte';
  import { settings, TEXT_SCALE } from './lib/settings.svelte';
  import { measure, ui } from './lib/ui.svelte';
  import Dialog from './components/Dialog.svelte';
  import Icon, { type IconName } from './components/Icon.svelte';
  import Analyze from './components/Analyze.svelte';
  import Play from './components/Play.svelte';
  import Records from './components/Records.svelte';
  import Replay from './components/Replay.svelte';
  import Rules from './components/Rules.svelte';
  import Settings from './components/Settings.svelte';
  import TileDefs from './components/TileDefs.svelte';
  import * as engine from './lib/engine';
  import { practice } from './lib/practice.svelte';
  import { editor } from './lib/position-editor.svelte';
  import { records } from './lib/records.svelte';

  // For the page's screenshot and test scripts (scripts/*.py): the state and the engine, only when asked.
  if (new URLSearchParams(location.search).has('test')) Object.assign(window, { mahjong: { practice, engine, settings, nav, shared, editor, records } });
  const tabs: [Screen, string, IconName, IconName][] = [
    ['PLAY', 'nav_play', 'tileOutlined', 'tile'], ['BATTLE', 'nav_battle', 'groupsOutlined', 'groups'],
    ['ANALYZE', 'nav_analyze', 'assessmentOutlined', 'assessment'],
    ['RECORDS', 'nav_records', 'historyOutlined', 'historyFilled'], ['SETTINGS', 'nav_settings', 'settingsOutlined', 'settingsFilled'],
  ];
  let systemDark = $state(matchMedia('(prefers-color-scheme: dark)').matches);
  const dark = $derived(settings.dark === 'DARK' || (settings.dark === 'SYSTEM' && systemDark));

  $effect(() => {
    const root = document.documentElement;
    root.classList.toggle('dark', dark);
    root.classList.toggle('minimal', settings.colors === 'MINIMAL');
    root.style.setProperty('--text-scale', String(TEXT_SCALE[settings.text]));
    document.querySelector('meta[name=theme-color]')?.setAttribute('content', dark ? '#0f1512' : '#f5fbf6');
    tick().then(measure);
  });

  onMount(() => {
    const mq = matchMedia('(prefers-color-scheme: dark)');
    const onDark = () => (systemDark = mq.matches);
    mq.addEventListener('change', onDark);
    addEventListener('resize', measure);
    measure();
    // A phone held sideways loses much of its height to the browser's bars: the page goes full screen at the first tap
    // (a browser allows it only on one), and back when the phone is upright again. Not on a tablet.
    const phone = () => matchMedia('(pointer: coarse)').matches && Math.min(screen.width, screen.height) < 600;
    const sideways = () => innerWidth > innerHeight;
    let ours = false;
    const onTap = () => {
      if (!phone() || !sideways() || document.fullscreenElement || !document.documentElement.requestFullscreen) return;
      if (matchMedia('(display-mode: fullscreen), (display-mode: standalone)').matches) return;
      document.documentElement.requestFullscreen({ navigationUI: 'hide' }).then(() => { ours = true; }, () => {});
    };
    const onTurn = () => {
      if (ours && !sideways() && document.fullscreenElement) document.exitFullscreen().catch(() => {});
      if (!document.fullscreenElement) ours = false;
    };
    addEventListener('click', onTap, true);
    addEventListener('resize', onTurn);
    return () => {
      mq.removeEventListener('change', onDark);
      removeEventListener('resize', measure);
      removeEventListener('click', onTap, true);
      removeEventListener('resize', onTurn);
    };
  });

  function review(id: string) {
    shared.reviewId = id;
    nav.open('review');
  }
  const immersive = false;
</script>

<TileDefs />
<div class="shell" class:wide={ui.wide}>
  <nav aria-label={t('app_name')} hidden={immersive}>
    {#each tabs as [s, label, icon, chosen] (s)}
      <button class:on={nav.screen === s} aria-current={nav.screen === s ? 'page' : undefined} onclick={() => nav.go(s)}>
        <span class="pill"><Icon name={nav.screen === s ? chosen : icon} /></span><span class="label">{t(label)}</span>
      </button>
    {/each}
  </nav>
  <main>
    {#if (nav.screen === 'PLAY' || nav.screen === 'RECORDS') && nav.sub === 'review'}
      <Replay id={shared.reviewId} onback={() => nav.back()} />
    {:else if nav.screen === 'PLAY' && nav.sub === 'rules'}
      <Rules onback={() => nav.back()} />
    {:else if nav.screen === 'PLAY'}
      <Play onreview={review} />
    {:else if nav.screen === 'RECORDS'}
      <Records onreview={review} />
    {:else if nav.screen === 'ANALYZE'}
      <Analyze />
    {:else if nav.screen === 'SETTINGS'}
      <Settings />
    {:else}
      <div class="todo">{nav.screen}</div>
    {/if}
  </main>
</div>

{#if shared.error || practice.error}
  <Dialog onclose={() => { shared.error = null; practice.error = null; }}>
    <p class="body-large" style="margin:0">{shared.error ?? practice.error}</p>
    {#snippet actions()}<button class="btn text" onclick={() => { shared.error = null; practice.error = null; }}>{t('ok')}</button>{/snippet}
  </Dialog>
{/if}

<style>
  /* Clear of a notch and the rounded corners, which a page in full screen (or from the home screen) reaches. */
  .shell { height: 100dvh; display: flex; flex-direction: column-reverse;
    padding: env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left); }
  .shell.wide { flex-direction: row; }
  main { flex: 1; min-height: 0; min-width: 0; position: relative; }
  nav[hidden] { display: none; }
  nav { flex: none; display: flex; justify-content: space-around; height: 80px; background: var(--surface-container); }
  .wide nav { flex-direction: column; justify-content: flex-start; gap: 12px; height: auto; width: 88px; padding-top: 12px; background: var(--surface); }
  nav button { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; border: none; background: none;
    color: var(--on-surface-variant); cursor: pointer; font-size: 0.75rem; font-weight: 500; padding: 0; min-width: 0; }
  .wide nav button { flex: none; padding: 4px 0; }
  nav button.on { color: var(--on-surface); }
  .pill { width: 64px; height: 32px; border-radius: 16px; display: flex; align-items: center; justify-content: center; }
  .wide .pill { width: 56px; }
  nav button.on .pill { background: var(--secondary-container); color: var(--on-secondary-container); }
  nav button:hover .pill { background: var(--surface-container-highest); }
  .label { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 100%; }
  .todo { padding: 24px; }
</style>
