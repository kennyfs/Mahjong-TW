// What the page keeps in the browser (localStorage): the settings, the practice game in play, the records. Reads
// and writes never throw: a private window or blocked storage only loses what would have been kept.

export function load<T>(key: string, fallback: T): T {
  try {
    const s = localStorage.getItem(key);
    return s === null ? fallback : (JSON.parse(s) as T);
  } catch {
    return fallback;
  }
}

export function save(key: string, value: unknown): boolean {
  try {
    if (value === null || value === undefined) localStorage.removeItem(key);
    else localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

/** Like `save`, but tells a full storage (worth making room for) from storage that cannot be written at all. */
export function saveOrFull(key: string, value: unknown): 'saved' | 'full' | 'failed' {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return 'saved';
  } catch (e) {
    const full = e instanceof DOMException && (e.name === 'QuotaExceededError' || e.name === 'NS_ERROR_DOM_QUOTA_REACHED');
    return full ? 'full' : 'failed';
  }
}

export const KEYS = {
  settings: 'mahjong.settings',
  practice: 'mahjong.practice',
  records: 'mahjong.records',
  battles: 'mahjong.battles',
  welcome: 'mahjong.welcome',
  battleSession: 'mahjong.battle.session',
  /** The AI coach's keys (lib/coach/keys.svelte.ts) and its last answers. */
  coachKeys: 'mahjong.coach.keys',
  coachSessions: 'mahjong.coach.sessions',
};
