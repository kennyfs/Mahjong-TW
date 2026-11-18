// The AI coach's keys on the page (docs/coach.md): the player's own, one per provider, with the custom server's
// address and model and which providers' notice has been shown. A page has no key store of the system's (the app
// encrypts its keys with Android's), so they are kept as they are in this browser's storage (localStorage) and sent
// only to their provider, from the browser, when the player sends. The settings say so, and that a shared computer
// should not keep one.
import { KEYS, load, save } from '../store';
import { customReady, type CustomServer, type ProviderId } from './providers';

const KEY = KEYS.coachKeys;

interface Stored { keys?: Partial<Record<ProviderId, string>>; custom?: CustomServer; told?: ProviderId[] }

class CoachKeys {
  private keys = $state<Partial<Record<ProviderId, string>>>({});
  custom = $state<CustomServer>({ url: '', model: '' });
  private toldList = $state<ProviderId[]>([]);

  constructor() {
    const s = load<Stored>(KEY, {});
    if (s.keys && typeof s.keys === 'object') this.keys = { ...s.keys };
    if (s.custom && typeof s.custom.url === 'string' && typeof s.custom.model === 'string') this.custom = { url: s.custom.url, model: s.custom.model };
    if (Array.isArray(s.told)) this.toldList = s.told.filter((x) => typeof x === 'string');
  }

  /** The provider can be asked: a key (a custom server: its address and model; its key may be none). */
  has(p: ProviderId): boolean {
    return p === 'custom' ? customReady(this.custom) : !!this.keys[p];
  }

  /** The key to send (a custom server's may be empty), or null without one. */
  get(p: ProviderId): string | null {
    const k = this.keys[p];
    if (k) return k;
    return p === 'custom' && customReady(this.custom) ? '' : null;
  }

  set(p: ProviderId, key: string) {
    this.keys = { ...this.keys, [p]: key.trim() };
    this.persist();
  }

  clear(p: ProviderId) {
    const k = { ...this.keys };
    delete k[p];
    this.keys = k;
    if (p === 'custom') this.custom = { url: '', model: '' };
    this.persist();
  }

  setCustom(c: CustomServer) {
    this.custom = { url: c.url.trim(), model: c.model.trim() };
    this.persist();
  }

  /** Whether the notice of what is sent where has been shown for the provider (once each). */
  told(p: ProviderId) { return this.toldList.includes(p); }
  setTold(p: ProviderId) {
    if (!this.told(p)) this.toldList = [...this.toldList, p];
    this.persist();
  }

  private persist() {
    save(KEY, { keys: this.keys, custom: this.custom, told: this.toldList } satisfies Stored);
  }
}

export const coachKeys = new CoachKeys();
