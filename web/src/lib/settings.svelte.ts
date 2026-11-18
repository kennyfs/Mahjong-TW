// The settings, kept in localStorage. A port of the app's Settings.kt.
import { browserLang, isLang, setLang, type Lang } from './i18n.svelte';
import { DEFAULT_RULES, parseChoice, ruleSpec, type RuleChoice } from './rules';
import { KEYS, load, save } from './store';

export type Length = 'hand' | 'round' | 'game';
/** The computer players' pace; `manual`: each of their steps waits for the player (lib/practice.svelte.ts). */
export type Speed = 'slow' | 'normal' | 'fast' | 'manual';
export const SPEEDS: Speed[] = ['slow', 'normal', 'fast', 'manual'];
export type Colors = 'TABLE' | 'MINIMAL';
export type Dark = 'SYSTEM' | 'LIGHT' | 'DARK';
export type TextSize = 'SMALL' | 'NORMAL' | 'LARGE';

export const TEXT_SCALE: Record<TextSize, number> = { SMALL: 0.88, NORMAL: 1, LARGE: 1.15 };

/** The bots: the network (the engine's built-in one, playing the action it values most) and tile efficiency. */
export const NET_BOT = 'net:model=/models/actor.onnx';
export const BOTS = [{ spec: NET_BOT, label: 'bot_net' }, { spec: 'greedy', label: 'bot_greedy' }];

// (The advanced settings are gone, the user's, 2026-11-13: a decision's list is always bars (DecisionPanel), a
// review's hand always shows each discard's cost and danger (lib/review.ts tileMarks), and the moments always show,
// unless the system asks for less motion (lib/fx.svelte.ts).)
/** The table's sounds (lib/sound.ts): off, soft, or full. */
export type SoundLevel = 'off' | 'soft' | 'full';
export const SOUND_LEVELS: SoundLevel[] = ['off', 'soft', 'full'];
/** Which losses the review counts as mistakes and stops at: a beginner's only the large ones, an expert's the small
 *  ones too (lib/review.ts). */
export type Level = 'beginner' | 'mid' | 'expert';
export const LEVELS: Level[] = ['beginner', 'mid', 'expert'];

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
  level: Level;
  sound?: SoundLevel;
  readyHint?: boolean;
  practiceHints?: boolean;
  /** The page's language; none: the browser's. */
  lang?: Lang;
  /** The AI coach's last choices: how many of the latest hands, which provider; each provider's model and thinking. */
  coachHands?: number;
  coachProvider?: string;
  coachModels?: Record<string, string>;
  coachEffort?: Record<string, string>;
}

const DEFAULTS: Stored = {
  rules: DEFAULT_RULES, length: 'game', bot: NET_BOT, speed: 'normal', colors: 'TABLE', dark: 'SYSTEM', text: 'NORMAL',
  name: '', server: '', level: 'mid',
};

class Settings {
  rules = $state<RuleChoice>(DEFAULT_RULES);
  length = $state<Length>('game');
  bot = $state(NET_BOT);
  speed = $state<Speed>('normal');
  colors = $state<Colors>('TABLE');
  dark = $state<Dark>('SYSTEM');
  text = $state<TextSize>('NORMAL');
  name = $state('');
  server = $state('');
  level = $state<Level>('mid');
  sound = $state<SoundLevel>('full');
  /** Marks on the discards that leave the hand ready (聽牌提示): off unless the player asks (the game tests their
   *  judgement; whether a hand is ready is only a check). */
  readyHint = $state(false);
  /** 練習時顯示分析: practice's decisions with the analysis shown, as a branch's; the hands played so count for
   *  nothing (lib/practice.svelte.ts). */
  practiceHints = $state(false);
  /** The page's language (i18n): the browser's till one is chosen. */
  lang = $state<Lang>('zh');
  private langChosen = false;
  /** The AI coach's (lib/coach): how many hands, which provider, and per provider its model and thinking. */
  coachHands = $state(100);
  coachProvider = $state('');
  coachModels = $state<Record<string, string>>({});
  coachEffort = $state<Record<string, string>>({});

  constructor() {
    const s = { ...DEFAULTS, ...load<Partial<Stored>>(KEYS.settings, {}) };
    this.rules = parseChoice(s.rules);
    this.length = (['hand', 'round', 'game'] as Length[]).includes(s.length) ? s.length : 'game';
    this.bot = BOTS.some((b) => b.spec === s.bot) ? s.bot : NET_BOT;
    this.speed = SPEEDS.includes(s.speed) ? s.speed : 'normal';
    this.colors = s.colors === 'MINIMAL' ? 'MINIMAL' : 'TABLE';
    this.dark = (['SYSTEM', 'LIGHT', 'DARK'] as Dark[]).includes(s.dark) ? s.dark : 'SYSTEM';
    this.text = (['SMALL', 'NORMAL', 'LARGE'] as TextSize[]).includes(s.text) ? s.text : 'NORMAL';
    this.name = typeof s.name === 'string' ? s.name : '';
    this.server = typeof s.server === 'string' ? s.server : '';
    this.level = LEVELS.includes(s.level) ? s.level : 'mid';
    this.sound = s.sound && SOUND_LEVELS.includes(s.sound) ? s.sound : 'full';
    this.readyHint = s.readyHint === true;
    this.practiceHints = s.practiceHints === true;
    this.langChosen = isLang(s.lang);
    this.lang = isLang(s.lang) ? s.lang : browserLang();
    setLang(this.lang);
    this.coachHands = [50, 100, 200].includes(s.coachHands as number) ? s.coachHands! : 100;
    this.coachProvider = typeof s.coachProvider === 'string' ? s.coachProvider : '';
    const strings = (o: unknown) => (o && typeof o === 'object' ? Object.fromEntries(Object.entries(o).filter(([, v]) => typeof v === 'string')) : {});
    this.coachModels = strings(s.coachModels);
    this.coachEffort = strings(s.coachEffort);
  }

  /** The page's language, chosen (kept from now on). */
  chooseLang(l: Lang) {
    this.lang = l;
    this.langChosen = true;
    setLang(l);
    this.persist();
  }

  get spec(): string { return ruleSpec(this.rules); }

  persist() {
    save(KEYS.settings, {
      rules: this.rules, length: this.length, bot: this.bot, speed: this.speed, colors: this.colors, dark: this.dark,
      text: this.text, name: this.name, server: this.server, level: this.level, sound: this.sound,
      readyHint: this.readyHint, practiceHints: this.practiceHints, lang: this.langChosen ? this.lang : undefined,
      coachHands: this.coachHands, coachProvider: this.coachProvider || undefined, coachModels: this.coachModels, coachEffort: this.coachEffort,
    } satisfies Stored);
  }
}

export const settings = new Settings();
