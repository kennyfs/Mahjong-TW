<!--
  A table before there is one (the engine still downloading, a battle's first view on its way): the table itself
  (Table.svelte) with a placeholder hand, so that every part is where and as large as it will be once dealt, drawn as
  shapes in a lighter tone of the felt, pulsing, as a page shows while it loads. The screen gives what its table will
  have: the names, the scores, the hand's label, the tiles' cap.
-->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import type { View } from '../lib/types';
  import Table from './Table.svelte';

  let { names = null, scores = null, handLabel = '', handShort = '', maxHand = 64, me = 0 }: {
    names?: string[] | null; scores?: number[] | null; handLabel?: string; handShort?: string; maxHand?: number; me?: number;
  } = $props();
  // (Words of a plate's length where the names are not known yet.)
  const shownNames = $derived(names ?? [0, 1, 2, 3].map((s) => (s === me ? t('you') : t('cpu_n', s))));
  const empty = { melds: [], flowers: '', discards: [] };
  const view = $derived<View>({
    rules: 'simple1,none',
    position: { setup: { dealer: me, east: -1, round: 0, streak: 0 }, me, hand: '123456789m1234567p', drawn: null, after_call: false,
      claim: null, players: [empty, empty, empty, empty], wall_left: 56 },
    phase: 'self', current: (me + 1) % 4, seat_winds: [0, 1, 2, 3].map((s) => (s - me + 4) % 4), my_turn: false, legal: [], events: [],
    event_count: 0, result: null,
  });
</script>

<div class="skel-table" role="status" aria-label={t('loading')}>
  <Table {view} {me} names={shownNames} {scores} interactive={false} {handLabel} {handShort} {maxHand} hints={false} />
</div>

<style>
  .skel-table { position: absolute; inset: 0; overflow: hidden; background: var(--felt); pointer-events: none; }
  /* Every shape a block of the felt's lighter tone; no words, no faces. */
  .skel-table :global(svg.tile > *) { display: none; }
  .skel-table :global(svg.tile), .skel-table :global(.badge-row), .skel-table :global(.corner-info), .skel-table :global(.box) {
    background: color-mix(in srgb, var(--on-felt) 14%, transparent); border-color: transparent; box-shadow: none; border-radius: 6px;
    animation: pulse 1.6s ease-in-out infinite; }
  .skel-table :global(.badge-row *), .skel-table :global(.corner-info *), .skel-table :global(.box *) { visibility: hidden; }
  @keyframes pulse { 0%, 100% { opacity: 0.7; } 50% { opacity: 1; } }
  @media (prefers-reduced-motion: reduce) { .skel-table :global(*) { animation: none !important; } }
</style>
