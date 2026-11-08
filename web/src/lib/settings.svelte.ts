// The settings, kept in localStorage. A port of the app's Settings.kt.
import { DEFAULT_RULES, parseChoice, ruleSpec, type RuleChoice } from './rules';
import { KEYS, load, save } from './store';

export type Length = 'hand' | 'round' | 'game';
export type Speed = 'slow' | 'normal' | 'fast';
export type Colors = 'TABLE' | 'MINIMAL';
export type Dark = 'SYSTEM' | 'LIGHT' | 'DARK';
export type TextSize = 'SMALL' | 'NORMAL' | 'LARGE';

export const TEXT_SCALE: Record<TextSize, number> = { SMALL: 0.88, NORMAL: 1, LARGE: 1.15 };
/** A bot's pause before it plays, and before the next turn after a discard, in ms. */
export const PACE: Record<Speed, number> = { slow: 1100, normal: 650, fast: 250 };

/** The bots: one now (tile efficiency); a network later, as a spec "net:..." (docs/app.md). */
export const BOTS = [{ spec: 'greedy', label: 'bot_greedy' }];

interface Stored {
  rules: RuleChoice;
  length: Length;
  bot: string;
  speed: Speed;
  colors: Colors;
  dark: Dark;
  text: TextSize;
  name: string;
  server: string;
}

const DEFAULTS: Stored = {
  rules: DEFAULT_RULES, length: 'game', bot: 'greedy', speed: 'normal', colors: 'TABLE', dark: 'SYSTEM', text: 'NORMAL',
  name: '', server: '',
};

class Settings {
  rules = $state<RuleChoice>(DEFAULT_RULES);
  length = $state<Length>('game');
  bot = $state('greedy');
  speed = $state<Speed>('normal');
  colors = $state<Colors>('TABLE');
  dark = $state<Dark>('SYSTEM');
  text = $state<TextSize>('NORMAL');
  name = $state('');
  server = $state('');

  constructor() {
    const s = { ...DEFAULTS, ...load<Partial<Stored>>(KEYS.settings, {}) };
    this.rules = parseChoice(s.rules);
    this.length = (['hand', 'round', 'game'] as Length[]).includes(s.length) ? s.length : 'game';
    this.bot = BOTS.some((b) => b.spec === s.bot) ? s.bot : 'greedy';
    this.speed = (['slow', 'normal', 'fast'] as Speed[]).includes(s.speed) ? s.speed : 'normal';
    this.colors = s.colors === 'MINIMAL' ? 'MINIMAL' : 'TABLE';
    this.dark = (['SYSTEM', 'LIGHT', 'DARK'] as Dark[]).includes(s.dark) ? s.dark : 'SYSTEM';
    this.text = (['SMALL', 'NORMAL', 'LARGE'] as TextSize[]).includes(s.text) ? s.text : 'NORMAL';
    this.name = typeof s.name === 'string' ? s.name : '';
    this.server = typeof s.server === 'string' ? s.server : '';
  }

  get spec(): string { return ruleSpec(this.rules); }

  persist() {
    save(KEYS.settings, {
      rules: this.rules, length: this.length, bot: this.bot, speed: this.speed, colors: this.colors, dark: this.dark,
      text: this.text, name: this.name, server: this.server,
    } satisfies Stored);
  }
}

export const settings = new Settings();
