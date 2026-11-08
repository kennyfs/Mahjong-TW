// The rules as the settings choose them: a tai preset and a flower preset, chosen apart, and values changed on top
// (docs/rules.md section 11). The names and values of the parameters come from the engine ({"cmd":"rules"}), never
// from a table here. A port of the app's Rules.kt.
import { rulesInfo } from './engine';
import type { RulesInfo } from './types';

export interface RuleChoice { tai: string; flowers: string; overrides: Record<string, number> }

export const DEFAULT_RULES: RuleChoice = { tai: 'simple', flowers: 'none', overrides: {} };
export const TAI_PRESETS = ['simple', 'standard', 'masters'];
export const FLOWER_PRESETS = ['none', 'see', 'seat'];

/** The parameters of the flowers, and of the play (not tai); every other one is a tai item. */
export const FLOWER_PARAMS = ['flowers', 'flower_tai', 'flower_set', 'eight_flowers', 'seven_rob_one'];
export const PLAY_PARAMS = ['base', 'draw_streak', 'multi_ron', 'keep', 'min_tai', 'duting_loose'];
/** Parameters that are 0 or 1. */
export const FLAG_PARAMS = ['draw_streak', 'multi_ron', 'duting_loose', 'dasixi_winds', 'quanqiu_duting'];

export function ruleSpec(c: RuleChoice): string {
  const parts = [c.tai, c.flowers];
  for (const k of Object.keys(c.overrides).sort()) parts.push(`${k}=${c.overrides[k]}`);
  return parts.join(',');
}

export function parseChoice(raw: unknown): RuleChoice {
  const c = raw as Partial<RuleChoice> | null;
  if (!c || typeof c !== 'object') return { ...DEFAULT_RULES, overrides: {} };
  return {
    tai: TAI_PRESETS.includes(c.tai ?? '') ? c.tai! : 'simple',
    flowers: FLOWER_PRESETS.includes(c.flowers ?? '') ? c.flowers! : 'none',
    overrides: typeof c.overrides === 'object' && c.overrides ? { ...c.overrides } : {},
  };
}

/** The values of a full spec ("flowers=0,flower_tai=1,..."). */
export function specValues(spec: string): Record<string, number> {
  const v: Record<string, number> = {};
  for (const part of spec.split(',')) {
    const [k, x] = part.split('=');
    if (x !== undefined) v[k] = Number(x);
  }
  return v;
}

const cache = new Map<string, RulesInfo>();
/** The engine's answer for a spec, kept. */
export async function info(spec: string): Promise<RulesInfo> {
  let i = cache.get(spec);
  if (!i) {
    i = await rulesInfo(spec);
    cache.set(spec, i);
  }
  return i;
}

/** The overrides that differ from the presets' values (others dropped), for a value changed in the list. */
export async function withValue(c: RuleChoice, name: string, value: number): Promise<RuleChoice> {
  const base = (await info(`${c.tai},${c.flowers}`)).values;
  const overrides = { ...c.overrides, [name]: value };
  for (const k of Object.keys(overrides)) if (base[k] === overrides[k]) delete overrides[k];
  return { ...c, overrides };
}

/** The names of the presets, as the strings have them. */
export const taiLabel = (p: string) => `tai_${p}`;
export const flowerLabel = (p: string) => `flowers_${p}`;
