// The page's words: the app's strings.xml (scripts/strings.mjs), in Taiwan's Traditional Chinese for now. Formats as
// Android's: %1$s, %2$d, %s, %d.
import strings from '../generated/strings.json';

type Table = Record<string, string | string[]>;
const ALL = strings as unknown as Record<string, Table>;

export const lang = $state({ code: 'zh' });

function table(): Table {
  return ALL[lang.code] ?? ALL.zh;
}

/** The string `name`, its %1$s, %2$d (or %s, %d in order) filled with `args`. */
export function t(name: string, ...args: (string | number)[]): string {
  const s = table()[name];
  if (typeof s !== 'string') return name;
  if (!args.length) return s;
  let i = 0;
  return s.replace(/%(?:(\d+)\$)?([sd])/g, (_, n: string | undefined) => {
    const v = n ? args[Number(n) - 1] : args[i++];
    return v === undefined ? '' : String(v);
  });
}

/** A string array. */
export function ta(name: string): string[] {
  const s = table()[name];
  return Array.isArray(s) ? s : [];
}
