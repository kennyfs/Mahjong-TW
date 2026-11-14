<!--
  A hand's end: the table as it ended stays till the player's tap (anywhere over it), Space or Enter; then the result.
  While a moment plays, a tap (or a key) is the table's (a result's moment waits for it, or all of the hand's end goes:
  Table.svelte). ≫ and 「按一下以繼續」 show at the bottom right once the result's moment starts (or at once
  without one), until the tap. Practice, a review's branch and a table
  battle (whose time to be ready runs from the hand's end all the same).
-->
<script lang="ts">
  import { fxOn, resultTap } from '../lib/fx.svelte';
  import { t } from '../lib/i18n.svelte';

  let { onnext }: { onnext: () => void } = $props();
  /** The tap began over a moment: it only ended the moment. */
  let overFx = false;
  const go = () => onnext();
  function key(e: KeyboardEvent) {
    if ((e.key !== ' ' && e.key !== 'Enter') || e.repeat || e.ctrlKey || e.metaKey || e.altKey) return;
    const el = e.target as HTMLElement | null;
    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)) return;
    if (el?.tagName === 'BUTTON' && !el.classList.contains('tap-on')) return;
    if (fxOn.on) return;
    e.preventDefault();
    go();
  }
</script>

<svelte:window onkeydown={key} />
<button class="tap-on" aria-label={t('tap_to_continue')} onpointerdown={() => (overFx = fxOn.on)}
  onclick={() => { if (!overFx) go(); overFx = false; }}></button>
<!-- (Over the moments too, which cover the screen; gone at the tap, while the moment plays out.) -->
{#if (resultTap.on && !resultTap.tapped) || !fxOn.on}<span class="hint" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M4 6l7 6-7 6zM13 6l7 6-7 6z" /></svg>{t('tap_to_continue')}</span>{/if}

<style>
  .tap-on { position: absolute; inset: 0; z-index: 5; border: none; background: transparent; cursor: pointer; padding: 0; }
  .hint { position: absolute; z-index: 65; pointer-events: none; right: 12px; bottom: 12px; display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px 8px 12px;
    border-radius: 999px; background: rgba(8, 11, 10, 0.82); color: #fff6dc; font-weight: 700; font-size: 0.95rem;
    box-shadow: 0 4px 14px rgba(0, 0, 0, 0.4); animation: in 0.25s ease-out both; }
  .hint svg { width: 22px; height: 22px; fill: #f6c453; animation: nudge 1.2s ease-in-out infinite; }
  @keyframes in { from { opacity: 0; transform: translateY(6px); } }
  @keyframes nudge { 0%, 100% { transform: none; } 50% { transform: translateX(3px); } }
  @media (prefers-reduced-motion: reduce) { .hint, .hint svg { animation: none; } }
</style>
