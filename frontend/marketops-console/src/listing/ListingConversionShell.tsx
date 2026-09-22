import { ArrowLeftOutlined } from '@ant-design/icons';
import { Button, Flex, Tabs } from 'antd';
import { useState } from 'react';
import type { ConsoleRequest } from '../api/console';
import { t } from '../i18n/zh/listing';
import { ListingActionsPanel } from './ListingActionsPanel';
import { ListingGovernancePanel } from './ListingGovernancePanel';
import { ListingHealthPanel } from './ListingHealthPanel';
import { ListingManualPanel } from './ListingManualPanel';
import { ListingOperationsReviewPanel } from './ListingOperationsReviewPanel';

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

type Tab = 'health' | 'actions' | 'manual' | 'governance' | 'review';

/** The listing conversion console: one operating journey in five tabs. */
export function ListingConversionShell({
  context,
  storeId,
  onBack,
}: ListingConversionShellProps): React.JSX.Element {
  const [tab, setTab] = useState<Tab>('health');
  const [prepareListing, setPrepareListing] = useState<string | undefined>(undefined);
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
          destroyOnHidden
          items={[
            {
              key: 'health',
              label: t('tabHealth'),
              children: (
                <ListingHealthPanel
                  context={context}
                  onPrepare={(listingId) => {
                    setPrepareListing(listingId);
                    setTab('actions');
                  }}
                />
              ),
            },
            {
              key: 'actions',
              label: t('tabActions'),
              children: (
                <ListingActionsPanel
                  context={context}
                  {...(prepareListing === undefined ? {} : { listingId: prepareListing })}
                />
              ),
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
