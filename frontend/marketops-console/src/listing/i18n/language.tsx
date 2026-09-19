import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import catalogue from './backend-codes.json';

/** The two languages the desktop console speaks. */
export type Language = 'zh' | 'ru';

/** Every language, in the order the switch offers them. */
export const LANGUAGES: readonly Language[] = ['zh', 'ru'];

const STORAGE_KEY = 'marketops.listing.language';

/** One backend code family: code to its labels. */
type Family = Readonly<Record<string, Readonly<Record<Language, string>>>>;

/** The catalogue as the backend's families, checked for completeness by a backend test. */
export const BACKEND_CODES: Readonly<Record<string, Family>> = catalogue;

/** Which language a stored preference names, if any. */
export function storedLanguage(storage: Pick<Storage, 'getItem'> | undefined): Language {
  try {
    const stored = storage?.getItem(STORAGE_KEY);
    return stored === 'ru' ? 'ru' : 'zh';
  } catch {
    return 'zh';
  }
}

/**
 * The label for one backend code in one language.
 *
 * A code the catalogue does not carry is shown as itself, marked as
 * unrecognised, rather than as nothing: a blank where a state belongs reads as
 * "fine", and the one thing a code family must never do is disappear.
 */
export function labelFor(
  family: string,
  code: string | null | undefined,
  language: Language,
): string {
  if (code === null || code === undefined || code === '') {
    return language === 'zh' ? '—' : '—';
  }
  const labels = BACKEND_CODES[family]?.[code];
  if (labels === undefined) {
    return language === 'zh' ? `${code}（未识别）` : `${code} (не распознано)`;
  }
  return labels[language];
}

interface LanguageContextValue {
  readonly language: Language;
  readonly setLanguage: (language: Language) => void;
}

const LanguageContext = createContext<LanguageContextValue>({
  language: 'zh',
  setLanguage: () => undefined,
});

/** What the provider needs. */
export interface LanguageProviderProps {
  readonly initial?: Language;
  readonly storage?: Pick<Storage, 'getItem' | 'setItem'>;
  readonly children: React.ReactNode;
}

/** Holds the operator's language for the listing console and remembers it in this browser. */
export function LanguageProvider({
  initial,
  storage,
  children,
}: LanguageProviderProps): React.JSX.Element {
  const [language, setLanguageState] = useState<Language>(initial ?? storedLanguage(storage));
  const setLanguage = useCallback(
    (next: Language) => {
      setLanguageState(next);
      try {
        storage?.setItem(STORAGE_KEY, next);
      } catch {
        // A browser that refuses storage still gets the language for this page.
      }
    },
    [storage],
  );
  const value = useMemo(() => ({ language, setLanguage }), [language, setLanguage]);
  return <LanguageContext.Provider value={value}>{children}</LanguageContext.Provider>;
}

/** The current language and a way to change it. */
export function useLanguage(): LanguageContextValue {
  return useContext(LanguageContext);
}

/** The language switch, on every listing screen. */
export function LanguageSwitch(): React.JSX.Element {
  const { language, setLanguage } = useLanguage();
  return (
    <fieldset aria-label={language === 'zh' ? '语言' : 'Язык'}>
      <legend>{language === 'zh' ? '语言 / Язык' : 'Язык / 语言'}</legend>
      {LANGUAGES.map((candidate) => (
        <button
          key={candidate}
          type="button"
          aria-pressed={language === candidate}
          onClick={() => {
            setLanguage(candidate);
          }}
        >
          {candidate === 'zh' ? '中文' : 'Русский'}
        </button>
      ))}
    </fieldset>
  );
}
