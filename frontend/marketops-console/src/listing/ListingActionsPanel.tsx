import { ReloadOutlined } from '@ant-design/icons';
import { Button, Select, Space, Table, Tag } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { ListingAction } from '../api/listingConversion';
import { fetchActionsBy } from '../api/listingConversion';
import { actionListText } from '../i18n/zh/listingActions';
import { t } from '../i18n/zh/listing';
import { EmptyState, LoadingState, SectionCard, useSearchParam, useSearchParamsPatch } from '../ui';
import { ActionDetail } from './ListingActionDetail';
import { CandidatePreparation } from './ListingCandidatePreparation';
import { Code, Codes, ListingName, ListingProblem, Stack, codeText } from './ListingCommon';

export interface ListingActionsPanelProps {
  readonly context: ConsoleRequest;
}

const ACTION_STATES = [
  'DRAFT',
  'REVIEWED',
  'APPROVED',
  'APPROVED_NOT_LAUNCHABLE',
  'LAUNCHED',
  'VERIFIED',
  'CLOSED',
  'CANCELLED',
  'CONTAINED',
] as const;

const ALL = 'ALL';

/** Address-bar keys of this tab; the other tabs keep their own. */
const FOR_PARAM = 'for';
const STATE_PARAM = 'astate';
const ACTION_PARAM = 'action';
const DETAIL_TAB_PARAM = 'atab';

/**
 * Candidates, exact actions, review, approval, allowance and launch.
 *
 * The listing being prepared (`for`), the state filter and the open action live
 * in the address bar, so a reload or a shared link returns to the same place.
 * Nothing here decides: the deterministic gates, the calibration, the
 * independence rule and the allowance are applied on the server, and this
 * screen shows what they said.
 */
export function ListingActionsPanel({ context }: ListingActionsPanelProps): React.JSX.Element {
  const [actionId] = useSearchParam(ACTION_PARAM);
  const patch = useSearchParamsPatch();

  if (actionId !== undefined) {
    return (
      <ActionDetail
        key={actionId}
        context={context}
        actionId={actionId}
        onBack={() => {
          patch({ [ACTION_PARAM]: undefined, [DETAIL_TAB_PARAM]: undefined });
        }}
      />
    );
  }
  return <ActionList context={context} />;
}

function ActionList({ context }: { readonly context: ConsoleRequest }): React.JSX.Element {
  const [listingId] = useSearchParam(FOR_PARAM);
  const [rawState, setRawState] = useSearchParam(STATE_PARAM);
  const patch = useSearchParamsPatch();
  const stateFilter =
    rawState !== undefined && (ACTION_STATES as readonly string[]).includes(rawState)
      ? rawState
      : undefined;
  const [actions, setActions] = useState<readonly ListingAction[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    let active = true;
    setActions(undefined);
    void fetchActionsBy(context, {
      ...(stateFilter === undefined ? {} : { state: stateFilter }),
      ...(listingId === undefined ? {} : { listingId }),
    }).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setActions(outcome.value);
        setFailure(undefined);
      } else {
        setActions(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, stateFilter, listingId, generation]);

  const open = (action: ListingAction): void => {
    patch({ [ACTION_PARAM]: action.id, [DETAIL_TAB_PARAM]: undefined });
  };

  const columns: TableColumnsType<ListingAction> = [
    {
      key: 'listing',
      title: t('listing'),
      render: (_, action) => (
        <ListingName
          nativeListingKey={action.nativeListingKey}
          listingId={action.platformListingId}
        />
      ),
    },
    {
      key: 'kind',
      title: t('actionKind'),
      render: (_, action) => codeText('actionKind', action.actionKind),
    },
    {
      key: 'state',
      title: t('state'),
      render: (_, action) => <Code family="actionState" code={action.state} />,
    },
    {
      key: 'path',
      title: t('path'),
      render: (_, action) => <Code family="executionPath" code={action.executionPath} />,
    },
    {
      key: 'materiality',
      title: t('materiality'),
      render: (_, action) => <Code family="materialityRoute" code={action.materialityRoute} />,
    },
    {
      key: 'gaps',
      title: t('bindingGaps'),
      render: (_, action) => <Codes family="bindingGap" codes={action.bindingGaps} />,
    },
  ];

  return (
    <section aria-label={t('actions')} data-state={actions === undefined ? 'loading' : 'loaded'}>
      <Stack>
        {listingId !== undefined && (
          <CandidatePreparation
            key={listingId}
            context={context}
            listingId={listingId}
            onPrepared={(prepared) => {
              patch({ [ACTION_PARAM]: prepared, [DETAIL_TAB_PARAM]: undefined });
            }}
          />
        )}
        <SectionCard
          title={actionListText.title}
          extra={
            <Space wrap>
              {listingId !== undefined && (
                <Tag
                  closable
                  color="processing"
                  onClose={(event) => {
                    event.preventDefault();
                    patch({ [FOR_PARAM]: undefined });
                  }}
                >
                  {actionListText.onlyThisListing}
                </Tag>
              )}
              <Select<string>
                aria-label={actionListText.stateFilter}
                style={{ minWidth: 160 }}
                value={stateFilter ?? ALL}
                options={[
                  { value: ALL, label: actionListText.allStates },
                  ...ACTION_STATES.map((state) => ({
                    value: state,
                    label: codeText('actionState', state),
                  })),
                ]}
                onChange={(value) => {
                  setRawState(value === ALL ? undefined : value);
                }}
              />
              <Button
                icon={<ReloadOutlined />}
                onClick={() => {
                  setGeneration((value) => value + 1);
                }}
              >
                {t('refresh')}
              </Button>
            </Space>
          }
        >
          <Stack>
            {failure !== undefined && <ListingProblem failure={failure} />}
            {actions === undefined && failure === undefined && <LoadingState />}
            {actions?.length === 0 && (
              <EmptyState
                description={
                  listingId === undefined ? t('noActions') : actionListText.noActionsForListing
                }
              />
            )}
            {actions !== undefined && actions.length > 0 && (
              <Table<ListingAction>
                size="middle"
                rowKey="id"
                columns={columns}
                dataSource={[...actions]}
                pagination={false}
                scroll={{ x: 'max-content' }}
                onRow={(action) =>
                  ({
                    'data-action': action.id,
                    'data-action-state': action.state,
                    role: 'link',
                    tabIndex: 0,
                    'aria-label': actionListText.openAction(action.nativeListingKey),
                    style: { cursor: 'pointer' },
                    onClick: () => {
                      open(action);
                    },
                    onKeyDown: (event: React.KeyboardEvent<HTMLElement>) => {
                      if (event.key === 'Enter') open(action);
                    },
                  }) as React.HTMLAttributes<HTMLElement>
                }
              />
            )}
          </Stack>
        </SectionCard>
      </Stack>
    </section>
  );
}
