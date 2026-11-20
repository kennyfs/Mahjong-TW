/**
 * Beginner glossary (術語表; the app's Glossary.kt). Entries are strictly topologically ordered so no earlier
 * definition relies on a term introduced later. Also provides longest-match inline term linking for Explain prose.
 */

export interface GlossaryEntry {
  id: string;
  /** Chinese phrases matched in Explain prose (longest-first). */
  zh: string[];
  /** English phrases matched in Explain prose (case-insensitive word-boundary, longest-first). */
  en: string[];
}

export const GLOSSARY: GlossaryEntry[] = [
  { id: 'wall', zh: ['牌牆'], en: ['the wall'] },
  { id: 'hand_tiles', zh: ['手牌'], en: [] },
  { id: 'river', zh: ['牌河'], en: ['river'] },
  { id: 'turn', zh: ['早巡', '一、二巡'], en: ['early turns'] },
  { id: 'suit_tile', zh: ['數牌'], en: ['number tiles', 'number tile'] },
  { id: 'honor', zh: ['字牌'], en: ['honors', 'honor'] },
  { id: 'terminal_honor', zh: ['么九'], en: ['terminals', 'terminal'] },
  { id: 'middle', zh: ['中張'], en: ['middle tiles', 'middle tile'] },
  { id: 'chow', zh: ['順子'], en: ['chow', 'run'] },
  { id: 'pung', zh: ['刻子'], en: ['pung'] },
  { id: 'kong', zh: ['槓子', '暗槓', '明槓'], en: ['kong'] },
  { id: 'set', zh: ['面子'], en: [] },
  { id: 'pair', zh: ['對子'], en: ['pairs', 'pair'] },
  { id: 'eye', zh: ['當眼', '缺眼', '沒了眼'], en: ['the eye', 'eye'] },
  { id: 'win', zh: ['胡牌'], en: [] },
  { id: 'zimo', zh: ['自摸'], en: ['self-draw'] },
  { id: 'dealin', zh: ['放槍'], en: ['deals in', 'deal-in'] },
  { id: 'draw', zh: ['流局'], en: ['exhaustive draw'] },
  { id: 'hand_deal', zh: [], en: [] },
  { id: 'tenpai', zh: ['聽牌', '已聽', '報聽'], en: ['ready'] },
  { id: 'tai', zh: ['台數', '一台'], en: ['tai'] },
  { id: 'dealer', zh: ['莊家'], en: ['dealer'] },
  { id: 'streak', zh: ['連莊'], en: ['dealer streak'] },
  { id: 'round', zh: ['一將'], en: [] },
  { id: 'flower', zh: ['花牌'], en: ['flowers', 'flower'] },
  { id: 'meld', zh: ['副露', '吃碰'], en: ['melds', 'meld'] },
  { id: 'move', zh: [], en: [] },
  { id: 'menqing', zh: ['門清'], en: ['concealed hand', 'closed hand'] },
  { id: 'pass_lock', zh: ['過水'], en: ['missed win'] },
  { id: 'shape', zh: ['搭子'], en: ['partial sets', 'partial set', 'partial run'] },
  { id: 'lone', zh: ['孤張'], en: ['lone number tile', 'lone honors', 'lone honor', 'lone tile'] },
  { id: 'ryanmen', zh: ['兩面'], en: ['two-sided wait', 'open wait'] },
  { id: 'kanchan', zh: ['嵌張', '中洞'], en: ['closed and edge waits', 'closed wait', 'inside wait'] },
  { id: 'penchan', zh: ['邊張'], en: ['edge waits', 'edge wait'] },
  { id: 'tanki', zh: ['單吊'], en: ['single wait', 'pair wait'] },
  { id: 'shanpon', zh: ['雙碰'], en: ['dual-pair wait'] },
  { id: 'dead_pair', zh: ['死對子'], en: ['dead pair'] },
  { id: 'shanten', zh: ['向聽數', '一向聽', '兩向聽', '三向聽', '四向聽', '五向聽', '六向聽', '七向聽', '向聽'], en: ['shanten'] },
  { id: 'ukeire', zh: ['進張'], en: ['improving tiles', 'useful tiles'] },
  { id: 'efficiency', zh: ['牌效率'], en: ['tile efficiency', 'tile-efficient'] },
  { id: 'tsumogiri', zh: ['摸切'], en: ['straight from the draw', 'draw-discard'] },
  { id: 'tedashi', zh: ['手切'], en: ['hand-discard'] },
  { id: 'genbutsu', zh: ['現物'], en: ['their own discards', 'own discards', 'genbutsu'] },
  { id: 'suji', zh: ['筋牌'], en: ['suji'] },
  { id: 'non_suji', zh: ['無筋'], en: ['non-suji'] },
  { id: 'half_suji', zh: ['半筋'], en: ['half-suji'] },
  { id: 'live_tile', zh: ['生張'], en: ['unseen middle tile', 'live tile'] },
  { id: 'seen_tile', zh: ['熟張'], en: ['seen tile'] },
];

export const GLOSSARY_INDEX: Map<string, number> = new Map(GLOSSARY.map((e, i) => [e.id, i]));

const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

function buildMatcher(lang: 'zh' | 'en'): { re: RegExp; map: Map<string, string> } | null {
  const pairs: [string, string][] = [];
  for (const e of GLOSSARY) {
    for (const phrase of lang === 'zh' ? e.zh : e.en) pairs.push([phrase, e.id]);
  }
  if (!pairs.length) return null;
  pairs.sort((a, b) => b[0].length - a[0].length);
  const map = new Map<string, string>();
  for (const [p, id] of pairs) map.set(lang === 'en' ? p.toLowerCase() : p, id);
  const body = pairs.map(([p]) => esc(p)).join('|');
  const re = lang === 'en' ? new RegExp(`\\b(?:${body})\\b`, 'gi') : new RegExp(`(?:${body})`, 'g');
  return { re, map };
}

const MATCHERS: Record<'zh' | 'en', ReturnType<typeof buildMatcher>> = {
  zh: buildMatcher('zh'),
  en: buildMatcher('en'),
};

export interface TextPart {
  text: string;
  termId?: string;
}

/** Splits `text` into plain runs and clickable glossary term links (longest-match first, non-overlapping).
 *  If `beforeId` is given, only links terms strictly earlier in the topological order (for glossary cross-links). */
export function linkGlossary(text: string, lang: 'zh' | 'en', beforeId?: string): TextPart[] {
  const m = MATCHERS[lang];
  if (!m || !text) return [{ text }];
  const limit = beforeId !== undefined ? (GLOSSARY_INDEX.get(beforeId) ?? -1) : Infinity;
  const out: TextPart[] = [];
  let at = 0;
  m.re.lastIndex = 0;
  let hit: RegExpExecArray | null;
  while ((hit = m.re.exec(text)) !== null) {
    const key = lang === 'en' ? hit[0].toLowerCase() : hit[0];
    const id = m.map.get(key);
    if (!id) continue;
    if ((GLOSSARY_INDEX.get(id) ?? Infinity) >= limit) continue;
    if (hit.index > at) out.push({ text: text.slice(at, hit.index) });
    out.push({ text: hit[0], termId: id });
    at = hit.index + hit[0].length;
  }
  if (at < text.length) out.push({ text: text.slice(at) });
  return out.length ? out : [{ text }];
}
