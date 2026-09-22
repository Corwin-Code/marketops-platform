import { ArrowLeftOutlined } from '@ant-design/icons';
import { Button, Flex, Tabs } from 'antd';
import type { ConsoleRequest } from '../api/console';
import { t } from '../i18n/zh/listing';
import { ListingActionsPanel } from './ListingActionsPanel';
import { ListingGovernancePanel } from './ListingGovernancePanel';
import { ListingHealthPanel } from './ListingHealthPanel';
import { ListingManualPanel } from './ListingManualPanel';
import { ListingOperationsReviewPanel } from './ListingOperationsReviewPanel';
import { useSearchParam, useSearchParamsPatch } from '../ui/useSearchParam';

export interface ListingConversionShellProps {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  /** Shown as a back button only when given; the routed page supplies its own title. */
  readonly onBack?: (() => void) | undefined;
  /**
   * @deprecated The console is Chinese only; kept so existing callers still compile.
   */
  readonly initialLanguage?: string;
  /**
   * @deprecated The console is Chinese only; kept so existing callers still compile.
   */
  readonly storage?: Pick<Storage, 'getItem' | 'setItem'>;
}

const TABS = ['health', 'actions', 'manual', 'governance', 'review'] as const;
type Tab = (typeof TABS)[number];
const DEFAULT_TAB: Tab = 'health';
const TAB_PARAM = 'tab';

/** A tab read from the address bar; anything unknown is the health queue. */
function readTab(raw: string | null | undefined): Tab {
  return (TABS as readonly string[]).includes(raw ?? '') ? (raw as Tab) : DEFAULT_TAB;
}

/** The listing conversion console: one operating journey in five tabs. */
export function ListingConversionShell({
  context,
  storeId,
  onBack,
}: ListingConversionShellProps): React.JSX.Element {
  // The open tab lives in the address bar (the default is left out), so a
  // reload or a shared link opens the same tab.
  const [rawTab, setRawTab] = useSearchParam(TAB_PARAM);
  const tab = readTab(rawTab);
  const setTab = (next: Tab): void => {
    setRawTab(next === DEFAULT_TAB ? undefined : next);
  };
  // "准备" hands a listing to the actions tab through the address bar: the tab,
  // the listing to prepare on and a closed action detail change in one step.
  const patch = useSearchParamsPatch();
  return (
    <section aria-label={t('title')} lang="zh-CN">
      {onBack !== undefined && (
        <Flex align="center" gap={12} wrap style={{ marginBottom: 8 }}>
          <Button icon={<ArrowLeftOutlined />} onClick={onBack}>
            {t('back')}
          </Button>
        </Flex>
      )}
      <div>
        <Tabs
          activeKey={tab}
          onChange={(key) => {
            setTab(key as Tab);
          }}
          items={[
            {
              key: 'health',
              label: t('tabHealth'),
              children: (
                <ListingHealthPanel
                  context={context}
                  onPrepare={(listingId) => {
                    patch({
                      [TAB_PARAM]: 'actions',
                      for: listingId,
                      action: undefined,
                      atab: undefined,
                    });
                  }}
                />
              ),
            },
            {
              key: 'actions',
              label: t('tabActions'),
              children: <ListingActionsPanel context={context} />,
            },
            {
              key: 'manual',
              label: t('tabManual'),
              children: <ListingManualPanel context={context} />,
            },
            {
              key: 'governance',
              label: t('tabGovernance'),
              children: <ListingGovernancePanel context={context} storeId={storeId} />,
            },
            {
              key: 'review',
              label: t('tabReview'),
              children: <ListingOperationsReviewPanel context={context} storeId={storeId} />,
            },
          ]}
        />
      </div>
    </section>
  );
}
