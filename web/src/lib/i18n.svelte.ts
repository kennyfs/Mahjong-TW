// The page's words: the app's strings.xml (scripts/strings.mjs), in Taiwan's Traditional Chinese or US English (the
// settings' choice; at first the browser's, as Android picks the app's). Formats as Android's: %1$s, %2$d, %s, %d.
import strings from '../generated/strings.json';

type Table = Record<string, string | string[]>;
const ALL = strings as unknown as Record<string, Table>;

export type Lang = 'zh' | 'en';
/** The languages in the settings' order, each by its own name; `tag`: the page's lang attribute (its fonts). */
export const LANGS: { id: Lang; name: string; tag: string }[] = [
  { id: 'zh', name: '中文（台灣）', tag: 'zh-Hant-TW' },
  { id: 'en', name: 'English', tag: 'en-US' },
];
export const isLang = (v: unknown): v is Lang => LANGS.some((l) => l.id === v);

/** The first of the browser's languages the page has (any Chinese is Taiwan's); else Taiwan's, as the app falls back. */
export function browserLang(): Lang {
  for (const raw of navigator.languages ?? [navigator.language]) {
    const l = raw.toLowerCase();
    if (l === 'zh' || l.startsWith('zh-')) return 'zh';
    if (l === 'en' || l.startsWith('en-')) return 'en';
  }
  return 'zh';
}

export const lang = $state({ code: 'zh' as Lang });

/** The page in `l`: its words at once (a rune), its lang attribute, its title. */
export function setLang(l: Lang) {
  lang.code = l;
  document.documentElement.lang = LANGS.find((x) => x.id === l)!.tag;
  document.title = t('app_name');
}

function table(): Table {
  return ALL[lang.code] ?? ALL.zh;
}

/** The string `name`, its %1$s, %2$d (or %s, %d in order) filled with `args`. */
export function t(name: string, ...args: (string | number)[]): string {
  const s = table()[name];
  if (typeof s !== 'string') return name;
  if (!args.length) return s.includes('%%') ? s.replace(/%%/g, '%') : s;
  let i = 0;
  return s.replace(/%(?:(\d+)\$)?([sd%])/g, (_, n: string | undefined, c: string) => {
    if (c === '%') return '%';
    const v = n ? args[Number(n) - 1] : args[i++];
    return v === undefined ? '' : String(v);
  });
}

/** Whether there is a string `name`. */
export const has = (name: string) => typeof table()[name] === 'string';

/** A string array. */
export function ta(name: string): string[] {
  const s = table()[name];
  return Array.isArray(s) ? s : [];
}
