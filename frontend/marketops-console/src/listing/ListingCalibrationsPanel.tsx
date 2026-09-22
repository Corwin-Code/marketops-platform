import { ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Col, Flex, Row, Select, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type {
  CalibrationCatalogue,
  CalibrationOverview,
  CalibrationPackage,
  CalibrationPackageDetail,
  CalibrationPurpose,
  CalibrationScopeKind,
  CalibrationStage,
} from '../api/listingCalibrations';
import {
  CALIBRATION_PURPOSES,
  CALIBRATION_SCOPES,
  CALIBRATION_STAGES,
  fetchCalibrationCatalogue,
  fetchCalibrations,
} from '../api/listingCalibrations';
import { calibrationText as text } from '../i18n/zh/listingCalibrations';
import {
  DateTime,
  EmptyState,
  InfoTip,
  LoadingState,
  SectionCollapse,
  TriggerButton,
  useSearchParam,
  useSearchParamsPatch,
} from '../ui';
import { CalibrationDraftDrawer } from './ListingCalibrationDraft';
import {
  CalibrationDetailDrawer,
  blockerText,
  packageName,
  personName,
  scopeText,
} from './ListingCalibrationDetail';
import { Code, ListingProblem, Stack, codeOptions, codeText } from './ListingCommon';

type Loaded =
  | { readonly kind: 'loading' }
  | {
      readonly kind: 'ok';
      readonly overview: CalibrationOverview;
      readonly catalogue: CalibrationCatalogue;
    }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/** Stage filter values: every stage, plus the three steps still in progress together. */
type StageFilter = CalibrationStage | 'PENDING';
const PENDING: readonly CalibrationStage[] = ['DRAFTED', 'VALIDATED', 'ACCEPTED'];

function oneOf<T extends string>(value: string | undefined, allowed: readonly T[]): T | undefined {
  return value !== undefined && (allowed as readonly string[]).includes(value)
    ? (value as T)
    : undefined;
}

/** A draft nobody will take further: a later version exists, or its period has ended. */
function stale(item: CalibrationPackage): boolean {
  return item.status === 'DRAFT' && (item.superseded || item.expired);
}

function inHistory(item: CalibrationPackage): boolean {
  return item.stage === 'ENDED' || item.stage === 'RETIRED' || stale(item);
}

// ------------------------------------------------------------------ panel

export interface ListingCalibrationsPanelProps {
  readonly context: ConsoleRequest;
  /** The store chosen in the header, offered first when drafting. */
  readonly storeId: string;
}

/**
 * Calibration packages: what is in force per purpose, every package with its
 * stage and next governed step, the detail beside the list, and drafting.
 */
export function ListingCalibrationsPanel({
  context,
  storeId,
}: ListingCalibrationsPanelProps): React.JSX.Element {
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  const [draftOpen, setDraftOpen] = useState(false);
  const [draftSource, setDraftSource] = useState<CalibrationPackageDetail | undefined>(undefined);

  const [purposeParam] = useSearchParam('purpose');
  const [stageParam] = useSearchParam('stage');
  const [scopeParam] = useSearchParam('scope');
  const [storeParam] = useSearchParam('store');
  const [packageParam] = useSearchParam('package');
  const patch = useSearchParamsPatch();

  const purpose = oneOf(purposeParam, CALIBRATION_PURPOSES);
  const stage = oneOf<StageFilter>(stageParam, [...CALIBRATION_STAGES, 'PENDING']);
  const scope = oneOf(scopeParam, CALIBRATION_SCOPES);
  const store = storeParam === '' ? undefined : storeParam;
  const filtered =
    purpose !== undefined || stage !== undefined || scope !== undefined || store !== undefined;

  useEffect(() => {
    let live = true;
    void Promise.all([fetchCalibrations(context), fetchCalibrationCatalogue(context)]).then(
      ([overview, catalogue]) => {
        if (!live) return;
        if (!overview.ok) setLoaded({ kind: 'failed', failure: overview.failure });
        else if (!catalogue.ok) setLoaded({ kind: 'failed', failure: catalogue.failure });
        else setLoaded({ kind: 'ok', overview: overview.value, catalogue: catalogue.value });
      },
    );
    return () => {
      live = false;
    };
  }, [context, generation]);

  const reload = (): void => {
    setGeneration((value) => value + 1);
  };
  const openPackage = (id: string | undefined): void => {
    patch({ package: id });
  };

  const help = (
    <InfoTip
      long
      title={
        <Flex vertical gap={6}>
          <Typography.Text strong>{text.helpTitle}</Typography.Text>
          {text.help.map((line) => (
            <Typography.Text key={line}>{line}</Typography.Text>
          ))}
        </Flex>
      }
    />
  );

  const overview = loaded.kind === 'ok' ? loaded.overview : undefined;
  const canDraft =
    overview !== undefined &&
    (overview.organizationRights.prepare || overview.stores.some((item) => item.rights.prepare));

  const matches = (item: CalibrationPackage): boolean =>
    (purpose === undefined || item.purposeCode === purpose) &&
    (stage === undefined ||
      (stage === 'PENDING'
        ? PENDING.includes(item.stage) && !stale(item)
        : item.stage === stage)) &&
    (scope === undefined || item.scopeKind === scope) &&
    (store === undefined || item.storeId === store);

  return (
    <section aria-label={text.intro} data-state={loaded.kind}>
      <Stack>
        <Alert
          type="info"
          showIcon
          title={
            <span>
              {text.intro}
              {help}
            </span>
          }
          description={text.introBoundary}
        />
        <Flex gap={8} wrap justify="flex-end">
          <Button icon={<ReloadOutlined />} onClick={reload}>
            {text.refresh}
          </Button>
          {overview !== undefined && (
            <TriggerButton
              trigger={{
                label: text.draft,
                type: 'primary',
                disabled: !canDraft,
                disabledReason: text.draftDisabled,
              }}
              onClick={() => {
                setDraftSource(undefined);
                setDraftOpen(true);
              }}
            />
          )}
        </Flex>
        {loaded.kind === 'loading' && <LoadingState rows={6} />}
        {loaded.kind === 'failed' && <ListingProblem failure={loaded.failure} />}
        {loaded.kind === 'ok' && (
          <>
            <CurrentByPurpose overview={loaded.overview} onOpen={openPackage} />
            <Filters
              overview={loaded.overview}
              purpose={purpose}
              stage={stage}
              scope={scope}
              store={store}
              onChange={patch}
              filtered={filtered}
            />
            {filtered ? (
              <PackageTable
                packages={loaded.overview.packages.filter(matches)}
                overview={loaded.overview}
                empty={text.noMatches}
                onOpen={openPackage}
              />
            ) : (
              <>
                <PackageTable
                  packages={loaded.overview.packages.filter((item) => !inHistory(item))}
                  overview={loaded.overview}
                  empty={
                    loaded.overview.packages.length === 0 ? text.noPackages : text.noInProgress
                  }
                  title={text.inProgress}
                  onOpen={openPackage}
                />
                <SectionCollapse
                  size="small"
                  items={[
                    {
                      key: 'history',
                      title: (
                        <span>
                          {text.history}
                          <InfoTip title={text.historyHelp} />
                        </span>
                      ),
                      summary: text.historyCount(loaded.overview.packages.filter(inHistory).length),
                      children: (
                        <PackageTable
                          packages={loaded.overview.packages.filter(inHistory)}
                          overview={loaded.overview}
                          empty={text.noHistory}
                          onOpen={openPackage}
                        />
                      ),
                    },
                  ]}
                />
              </>
            )}
          </>
        )}
      </Stack>
      {loaded.kind === 'ok' && (
        <>
          <CalibrationDetailDrawer
            context={context}
            packageId={packageParam === '' ? undefined : packageParam}
            refreshKey={generation}
            overview={loaded.overview}
            catalogue={loaded.catalogue}
            onClose={() => {
              openPackage(undefined);
            }}
            onChanged={reload}
            onDerive={(detail) => {
              setDraftSource(detail);
              setDraftOpen(true);
            }}
            onOpenPackage={openPackage}
          />
          <CalibrationDraftDrawer
            context={context}
            open={draftOpen}
            source={draftSource}
            overview={loaded.overview}
            catalogue={loaded.catalogue}
            storeId={storeId}
            onClose={() => {
              setDraftOpen(false);
              setDraftSource(undefined);
            }}
            onCreated={(id) => {
              openPackage(id);
              reload();
            }}
          />
        </>
      )}
    </section>
  );
}

// ------------------------------------------------------------------ in force

/** Per purpose, the packages in force now, or that nothing resolves. */
function CurrentByPurpose({
  overview,
  onOpen,
}: {
  readonly overview: CalibrationOverview;
  readonly onOpen: (id: string) => void;
}): React.JSX.Element {
  return (
    <Flex vertical gap={8}>
      <Typography.Title level={5} style={{ margin: 0 }}>
        {text.currentTitle}
      </Typography.Title>
      <Row gutter={[12, 12]}>
        {CALIBRATION_PURPOSES.map((purpose) => {
          const active = overview.packages.filter(
            (item) => item.purposeCode === purpose && item.stage === 'ACTIVE',
          );
          return (
            <Col key={purpose} xs={24} md={12} xl={6}>
              <Card
                size="small"
                title={codeText('actionPurpose', purpose)}
                style={{ height: '100%' }}
                data-purpose={purpose}
                data-state={active.length === 0 ? 'unresolved' : 'resolved'}
              >
                {active.length === 0 ? (
                  <Flex vertical gap={4}>
                    <span>
                      <Code family="evidenceState" code="CALIBRATION_UNRESOLVED" />
                    </span>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {text.currentNone}
                    </Typography.Text>
                  </Flex>
                ) : (
                  <Flex vertical gap={4}>
                    {active.map((item) => (
                      <Flex key={item.id} gap={4} align="center" wrap>
                        <Code family="allowanceScope" code={item.scopeKind} />
                        <Typography.Link
                          onClick={() => {
                            onOpen(item.id);
                          }}
                        >
                          {scopeText(item)} · {packageName(item)}
                        </Typography.Link>
                      </Flex>
                    ))}
                  </Flex>
                )}
              </Card>
            </Col>
          );
        })}
      </Row>
    </Flex>
  );
}

// ------------------------------------------------------------------ filters

function Filters({
  overview,
  purpose,
  stage,
  scope,
  store,
  filtered,
  onChange,
}: {
  readonly overview: CalibrationOverview;
  readonly purpose: CalibrationPurpose | undefined;
  readonly stage: StageFilter | undefined;
  readonly scope: CalibrationScopeKind | undefined;
  readonly store: string | undefined;
  readonly filtered: boolean;
  readonly onChange: (patch: Readonly<Record<string, string | undefined>>) => void;
}): React.JSX.Element {
  const stores = new Map<string, string>();
  for (const item of overview.stores) stores.set(item.storeId, item.displayName);
  for (const item of overview.packages) {
    if (item.storeId !== null && !stores.has(item.storeId)) {
      stores.set(item.storeId, item.storeName ?? text.storeUnknown);
    }
  }
  return (
    <Flex gap={8} wrap align="center">
      <Select<string>
        allowClear
        style={{ minWidth: 180 }}
        placeholder={text.filterPurpose}
        aria-label={text.filterPurpose}
        value={purpose ?? null}
        options={codeOptions('actionPurpose', CALIBRATION_PURPOSES)}
        onChange={(value) => {
          onChange({ purpose: value });
        }}
      />
      <Select<string>
        allowClear
        style={{ minWidth: 220 }}
        placeholder={text.filterStage}
        aria-label={text.filterStage}
        value={stage ?? null}
        options={[
          { value: 'PENDING', label: text.stagePending },
          ...codeOptions('calibrationStage', CALIBRATION_STAGES),
        ]}
        onChange={(value) => {
          onChange({ stage: value });
        }}
      />
      <Select<string>
        allowClear
        style={{ minWidth: 140 }}
        placeholder={text.filterScope}
        aria-label={text.filterScope}
        value={scope ?? null}
        options={codeOptions('allowanceScope', CALIBRATION_SCOPES)}
        onChange={(value) => {
          onChange({ scope: value, ...(value === 'STORE' ? {} : { store: undefined }) });
        }}
      />
      <Select<string>
        allowClear
        style={{ minWidth: 200 }}
        placeholder={text.filterStore}
        aria-label={text.filterStore}
        value={store ?? null}
        options={[...stores].map(([id, name]) => ({ value: id, label: name }))}
        onChange={(value: string | undefined) => {
          onChange({ store: value, ...(value === undefined ? {} : { scope: 'STORE' }) });
        }}
      />
      {filtered && (
        <Button
          type="link"
          onClick={() => {
            onChange({ purpose: undefined, stage: undefined, scope: undefined, store: undefined });
          }}
        >
          {text.clearFilters}
        </Button>
      )}
    </Flex>
  );
}

// ------------------------------------------------------------------ table

function PackageTable({
  packages,
  overview,
  empty,
  title,
  onOpen,
}: {
  readonly packages: readonly CalibrationPackage[];
  readonly overview: CalibrationOverview;
  readonly empty: string;
  readonly title?: string;
  readonly onOpen: (id: string) => void;
}): React.JSX.Element {
  const viewerId = overview.viewer.userId;
  const columns: TableColumnsType<CalibrationPackage> = [
    {
      key: 'package',
      title: text.columnPackage,
      render: (_, item) => (
        <Flex vertical gap={2}>
          <Typography.Link
            onClick={(event) => {
              event.stopPropagation();
              onOpen(item.id);
            }}
          >
            {item.code}
          </Typography.Link>
          <Flex gap={4} wrap>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.versionLabel(item.version)}
            </Typography.Text>
            {item.status === 'DRAFT' && item.superseded && (
              <Tag style={{ marginInlineEnd: 0 }}>{text.newerVersion}</Tag>
            )}
            {item.status === 'DRAFT' && item.expired && (
              <Tag color="warning" style={{ marginInlineEnd: 0 }}>
                {text.expiredDraft}
              </Tag>
            )}
          </Flex>
        </Flex>
      ),
    },
    {
      key: 'purpose',
      title: text.columnPurpose,
      render: (_, item) => <Code family="actionPurpose" code={item.purposeCode} />,
    },
    {
      key: 'scope',
      title: text.columnScope,
      render: (_, item) => (
        <Flex gap={4} align="center" wrap>
          <Code family="allowanceScope" code={item.scopeKind} />
          <Typography.Text>{scopeText(item)}</Typography.Text>
        </Flex>
      ),
    },
    {
      key: 'stage',
      title: text.columnStage,
      render: (_, item) => <Code family="calibrationStage" code={item.stage} />,
    },
    {
      key: 'effective',
      title: text.columnEffective,
      render: (_, item) => (
        <Flex vertical gap={0}>
          <span>
            <DateTime value={item.effectiveFrom} /> {text.from}
          </span>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {item.effectiveTo === null ? (
              text.openEnded
            ) : (
              <>
                {text.until} <DateTime value={item.effectiveTo} />
              </>
            )}
          </Typography.Text>
        </Flex>
      ),
    },
    {
      key: 'governance',
      title: text.columnGovernance,
      render: (_, item) => (
        <Flex vertical gap={0} style={{ fontSize: 12 }}>
          {(
            [
              [text.governanceDrafted, item.drafted],
              [text.governanceValidated, item.validated],
              [text.governanceAccepted, item.accepted],
              [text.governanceActivated, item.activated],
              [text.governanceRetired, item.retired],
            ] as const
          )
            .filter(([, actor]) => actor !== null)
            .map(([label, actor]) => (
              <Typography.Text key={label} style={{ fontSize: 12 }}>
                {label}：{personName(actor, viewerId)} · <DateTime value={actor?.at} />
              </Typography.Text>
            ))}
        </Flex>
      ),
    },
    {
      key: 'integrity',
      title: text.columnIntegrity,
      render: (_, item) => {
        const failures = item.combinationFailures;
        const tags = [
          ...(item.missingCategories.length > 0
            ? [
                <Tag key="missing" color="error" style={{ marginInlineEnd: 0 }}>
                  {text.missingCount(item.missingCategories.length)}
                </Tag>,
              ]
            : []),
          ...(failures !== null && failures.length > 0
            ? [
                <Tag key="failures" color="error" style={{ marginInlineEnd: 0 }}>
                  {text.failureCount(failures.length)}
                </Tag>,
              ]
            : []),
          ...(item.digestIntact
            ? []
            : [
                <Tag key="digest" color="error" style={{ marginInlineEnd: 0 }}>
                  {text.digestChanged}
                </Tag>,
              ]),
        ];
        if (tags.length > 0)
          return (
            <Flex gap={4} wrap>
              {tags}
            </Flex>
          );
        return failures === null ? (
          <Typography.Text type="secondary">{text.notEvaluated}</Typography.Text>
        ) : (
          <Tag color="success" style={{ marginInlineEnd: 0 }}>
            {text.complete}
          </Tag>
        );
      },
    },
    {
      key: 'next',
      title: text.columnNext,
      render: (_, item) => {
        const step = item.nextStep;
        if (step === null)
          return <Typography.Text type="secondary">{text.nextNone}</Typography.Text>;
        return (
          <Flex vertical gap={2} style={{ maxWidth: 260 }}>
            <Code family="calibrationStep" code={step.step} />
            {step.blockers.length > 0 ? (
              <Typography.Text type="danger" style={{ fontSize: 12 }}>
                {blockerText(step, item)}
              </Typography.Text>
            ) : step.stepUpRequired ? (
              <Typography.Text type="warning" style={{ fontSize: 12 }}>
                {text.nextStepUp}
              </Typography.Text>
            ) : (
              <Typography.Text type="success" style={{ fontSize: 12 }}>
                {text.nextReady}
              </Typography.Text>
            )}
          </Flex>
        );
      },
    },
  ];

  return (
    <Flex vertical gap={8}>
      {title !== undefined && (
        <Typography.Title level={5} style={{ margin: 0 }}>
          {title}
        </Typography.Title>
      )}
      {packages.length === 0 ? (
        <EmptyState description={empty} />
      ) : (
        <Table<CalibrationPackage>
          rowKey="id"
          size="small"
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={columns}
          dataSource={[...packages]}
          onRow={(item) => ({
            onClick: () => {
              onOpen(item.id);
            },
            style: { cursor: 'pointer' },
          })}
        />
      )}
    </Flex>
  );
}
