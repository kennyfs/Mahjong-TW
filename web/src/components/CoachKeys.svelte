<!--
  The AI coach's keys (the app's CoachKeyRows): a row per provider (set or not), a tap to set or remove its key. In
  the settings and on the coach's page. A key pasted in is never shown again; the custom server takes its address and
  model too.
-->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import { coachKeys } from '../lib/coach/keys.svelte';
  import { PROVIDERS, type Provider } from '../lib/coach/providers';
  import Dialog from './Dialog.svelte';
  import SettingRow from './SettingRow.svelte';

  let editing = $state<Provider | null>(null);
  let key = $state('');
  let url = $state('');
  let model = $state('');
  const nameOf = (p: Provider) => (p.id === 'custom' ? t('coach_custom') : p.label);

  function edit(p: Provider) {
    key = '';
    url = coachKeys.custom.url;
    model = coachKeys.custom.model;
    editing = p;
  }
  function saveKey() {
    const p = editing!;
    if (p.id === 'custom') coachKeys.setCustom({ url, model });
    if (key.trim()) coachKeys.set(p.id, key);
    editing = null;
  }
  function remove() {
    coachKeys.clear(editing!.id);
    editing = null;
  }
  const canSave = $derived(editing?.id === 'custom' ? url.trim().startsWith('https://') && !!model.trim() : !!key.trim());
</script>

{#each PROVIDERS as p (p.id)}
  <SettingRow title={nameOf(p)} value={t(coachKeys.has(p.id) ? 'coach_key_set' : 'coach_key_none')} onclick={() => edit(p)} />
{/each}

{#if editing}
  {@const p = editing}
  <Dialog title={t('coach_key_title', nameOf(p))} onclose={() => (editing = null)}>
    <div class="form">
      {#if p.id === 'custom'}
        <p class="body-medium">{t('coach_custom_help')}</p>
        <label class="field"><span class="body-small dim">{t('coach_custom_url')}</span>
          <input class="body-large" type="url" autocomplete="off" spellcheck="false" bind:value={url} /></label>
        <label class="field"><span class="body-small dim">{t('coach_custom_model')}</span>
          <input class="body-large" autocomplete="off" spellcheck="false" bind:value={model} /></label>
      {:else}
        <p class="body-medium">{t('coach_key_help_web', p.keySite, p.label)}</p>
        {#if p.id === 'gemini'}<p class="body-medium warn">{t('coach_gemini_free')}</p>{/if}
      {/if}
      {#if coachKeys.has(p.id) && p.id !== 'custom'}<p class="body-small dim">{t('coach_key_replace')}</p>{/if}
      <label class="field"><span class="body-small dim">{t(p.id === 'custom' ? 'coach_key_optional' : 'coach_key')}</span>
        <input class="body-large" type="password" autocomplete="off" spellcheck="false" bind:value={key} /></label>
    </div>
    {#snippet actions()}
      {#if coachKeys.has(p.id) || (p.id === 'custom' && coachKeys.get('custom') !== null)}
        <button class="btn text danger" onclick={remove}>{t('delete')}</button>
      {/if}
      <button class="btn text" onclick={() => (editing = null)}>{t('cancel')}</button>
      <button class="btn text" disabled={!canSave} onclick={saveKey}>{t('coach_key_save')}</button>
    {/snippet}
  </Dialog>
{/if}

<style>
  .form { display: flex; flex-direction: column; gap: 10px; }
  .form p { margin: 0; line-height: 1.5; }
  .warn, .danger { color: var(--error); }
  .field { display: flex; flex-direction: column; gap: 2px; padding: 6px 14px 8px; border: 1px solid var(--outline); border-radius: 6px; }
  .field:focus-within { border: 2px solid var(--primary); padding: 5px 13px 7px; }
  .field input { border: none; outline: none; background: none; color: var(--on-surface); padding: 2px 0; font: inherit; width: 100%; min-width: 0; }
</style>
