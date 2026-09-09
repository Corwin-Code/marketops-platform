import { useState } from 'react';
import type { ConsoleRequest } from '../api/console';
import { ListingActionsPanel } from './ListingActionsPanel';
import { ListingGovernancePanel } from './ListingGovernancePanel';
import { ListingHealthPanel } from './ListingHealthPanel';
import { ListingManualPanel } from './ListingManualPanel';
import type { Language } from './i18n/language';
import { LanguageProvider, LanguageSwitch, useLanguage } from './i18n/language';
import { t } from './i18n/ui';

export interface ListingConversionShellProps {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly onBack: () => void;
  readonly initialLanguage?: Language;
  readonly storage?: Pick<Storage, 'getItem' | 'setItem'>;
}

type Tab = 'health' | 'actions' | 'manual' | 'governance';

/** The listing conversion console: four tabs, two languages, one journey. */
export function ListingConversionShell(props: ListingConversionShellProps): React.JSX.Element {
  return (
    <LanguageProvider
      {...(props.initialLanguage === undefined ? {} : { initial: props.initialLanguage })}
      {...(props.storage === undefined ? {} : { storage: props.storage })}
    >
      <Tabs context={props.context} storeId={props.storeId} onBack={props.onBack} />
    </LanguageProvider>
  );
}

function Tabs({
  context,
  storeId,
  onBack,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly onBack: () => void;
}): React.JSX.Element {
  const { language } = useLanguage();
  const [tab, setTab] = useState<Tab>('health');
  const [prepareListing, setPrepareListing] = useState<string | undefined>(undefined);
  const tabs: readonly { readonly key: Tab; readonly label: string }[] = [
    { key: 'health', label: t('tabHealth', language) },
    { key: 'actions', label: t('tabActions', language) },
    { key: 'manual', label: t('tabManual', language) },
    { key: 'governance', label: t('tabGovernance', language) },
  ];
  return (
    <section aria-label={t('title', language)} lang={language}>
      <h2>{t('title', language)}</h2>
      <button type="button" onClick={onBack}>
        ← {t('back', language)}
      </button>
      <LanguageSwitch />
      <nav aria-label={t('title', language)}>
        {tabs.map((entry) => (
          <button
            key={entry.key}
            type="button"
            aria-pressed={tab === entry.key}
            onClick={() => {
              setTab(entry.key);
            }}
          >
            {entry.label}
          </button>
        ))}
      </nav>
      {tab === 'health' && (
        <ListingHealthPanel
          context={context}
          onPrepare={(listingId) => {
            setPrepareListing(listingId);
            setTab('actions');
          }}
        />
      )}
      {tab === 'actions' && (
        <ListingActionsPanel
          context={context}
          {...(prepareListing === undefined ? {} : { listingId: prepareListing })}
        />
      )}
      {tab === 'manual' && <ListingManualPanel context={context} />}
      {tab === 'governance' && <ListingGovernancePanel context={context} storeId={storeId} />}
    </section>
  );
}
