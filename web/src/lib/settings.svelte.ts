// The settings, kept in localStorage. A port of the app's Settings.kt.
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

/** What a row of a decision's list shows for an action: its figures as numbers, bars of where its value differs from
 *  the best action's, or both. (The hand's tiles always carry each discard's cost and danger.) Until it is chosen:
 *  both where there is room for it (a computer's window), the bars on a phone. */
export type ListRows = 'numbers' | 'bars' | 'both';
export const LIST_ROWS: ListRows[] = ['numbers', 'bars', 'both'];
const defaultRows = (): ListRows => (typeof window !== 'undefined' && window.innerWidth >= 900 ? 'both' : 'bars');
/** What a review's hand shows on its tiles (the advanced settings): each discard's cost over it and its danger along
 *  its foot, the cost alone, or nothing (the list beside or under the table says it all the same). */
export type TableMarks = 'both' | 'loss' | 'none';
/** A chow's, a pung's and a win's moment: over the whole screen (CallFx.svelte), or the words over the player alone. */
export type CallFxMode = 'anim' | 'words';
/** The table's sounds (lib/sound.ts): off, soft, or full. */
export type SoundLevel = 'off' | 'soft' | 'full';
export const SOUND_LEVELS: SoundLevel[] = ['off', 'soft', 'full'];
export const TABLE_MARKS: TableMarks[] = ['both', 'loss', 'none'];
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
  listRows?: ListRows;
  level: Level;
  tableMarks?: TableMarks;
  callFx?: CallFxMode;
  sound?: SoundLevel;
  readyHint?: boolean;
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
  listRows = $state<ListRows>('bars');
  level = $state<Level>('mid');
  tableMarks = $state<TableMarks>('both');
  callFx = $state<CallFxMode>('anim');
  sound = $state<SoundLevel>('full');
  /** Marks on the discards that leave the hand ready (聽牌提示): off unless the player asks (the game tests their
   *  judgement; whether a hand is ready is only a check). */
  readyHint = $state(false);

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
    this.listRows = s.listRows && LIST_ROWS.includes(s.listRows) ? s.listRows : defaultRows();
    this.level = LEVELS.includes(s.level) ? s.level : 'mid';
    this.tableMarks = s.tableMarks && TABLE_MARKS.includes(s.tableMarks) ? s.tableMarks : 'both';
    this.callFx = s.callFx === 'words' ? 'words' : 'anim';
    this.sound = s.sound && SOUND_LEVELS.includes(s.sound) ? s.sound : 'full';
    this.readyHint = s.readyHint === true;
  }

  get spec(): string { return ruleSpec(this.rules); }

  persist() {
    save(KEYS.settings, {
      rules: this.rules, length: this.length, bot: this.bot, speed: this.speed, colors: this.colors, dark: this.dark,
      text: this.text, name: this.name, server: this.server, listRows: this.listRows, level: this.level,
      tableMarks: this.tableMarks, callFx: this.callFx, sound: this.sound,
      readyHint: this.readyHint,
    } satisfies Stored);
  }
}

export const settings = new Settings();
