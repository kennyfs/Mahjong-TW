<!--
  A chip that opens a list of choices (the app's SelectRow): `options` as [value, label]. The list is as wide as its
  longest choice, whatever the one chosen. The browser opens and closes it (a popover: over the page, closed by a tap
  outside it or Escape), and Floating UI places it: under the chip (over it when there is more room above), moved left
  as far as it must to stay inside the window, and following the chip while the page scrolls.
-->
<script lang="ts" generics="T">
  import { autoUpdate, computePosition, flip, shift, size } from '@floating-ui/dom';
  import Icon from './Icon.svelte';
  interface Props { label: string; options: [T, string][]; selected?: T; onselect: (v: T) => void; disabled?: boolean; chip?: boolean }
  let { label, options, selected, onselect, disabled = false, chip = true }: Props = $props();
  const uid = $props.id();
  const id = `menu-${uid}`;
  let open = $state(false);
  let button = $state<HTMLButtonElement>()!;
  let list = $state<HTMLElement>()!;
  /** The window's edge a list keeps away from, px. */
  const MARGIN = 8;

  $effect(() => {
    if (!open) return;
    return autoUpdate(button, list, () => {
      computePosition(button, list, {
        strategy: 'fixed',
        placement: 'bottom-start',
        middleware: [
          flip({ padding: MARGIN }),
          shift({ padding: MARGIN }),
          size({ padding: MARGIN, apply: ({ availableHeight }) => { list.style.maxHeight = `${Math.min(availableHeight, innerHeight * 0.6)}px`; } }),
        ],
      }).then(({ x, y }) => {
        list.style.left = `${x}px`;
        list.style.top = `${y}px`;
      });
    });
  });
</script>

<div class="menu">
  <button bind:this={button} class={chip ? 'chip' : 'btn outlined'} {disabled} popovertarget={id} aria-haspopup="listbox" aria-expanded={open}>
    <span class="ellipsis">{label}</span><Icon name="dropDown" />
  </button>
  <!-- Placed before it is drawn: `beforetoggle` comes as it opens, and the placing is done before the next frame. -->
  <ul {id} popover="auto" role="listbox" bind:this={list} onbeforetoggle={(e) => (open = e.newState === 'open')}>
    {#each options as [v, l] (l)}
      <li role="option" aria-selected={v === selected}>
        <button class:on={v === selected} onclick={() => { list.hidePopover(); onselect(v); }}>{l}</button>
      </li>
    {/each}
  </ul>
</div>

<style>
  .menu { display: inline-block; min-width: 0; max-width: 100%; }
  .menu > button { max-width: 100%; }
  ul { position: fixed; inset: auto; margin: 0; border: none; padding: 6px 0; list-style: none; width: max-content;
    max-width: calc(100vw - 16px); overflow: auto; color: inherit; background: var(--surface-container); border-radius: 8px;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25); box-sizing: border-box; }
  li button { display: block; width: 100%; text-align: left; min-height: 48px; padding: 0 16px; border: none; background: none; cursor: pointer;
    font-size: 1rem; color: inherit; white-space: nowrap; }
  li button:hover { background: var(--surface-container-highest); }
  li button.on { font-weight: 700; color: var(--primary); }
</style>
