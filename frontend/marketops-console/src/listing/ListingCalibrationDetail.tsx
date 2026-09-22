import { ReloadOutlined } from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Descriptions,
  Flex,
  Form,
  Input,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type {
  CalibrationActor,
  CalibrationCatalogue,
  CalibrationNextStep,
  CalibrationOverview,
  CalibrationPackage,
  CalibrationPackageDetail,
  CalibrationValue,
} from '../api/listingCalibrations';
import {
  acceptCalibration,
  activateCalibration,
  fetchCalibrationDetail,
  validateCalibration,
} from '../api/listingCalibrations';
import { formatDecimal, formatPercent } from '../format';
import {
  calibrationCategoryHelp,
  calibrationGroups,
  calibrationText as text,
  calibrationUnits,
} from '../i18n/zh/listingCalibrations';
import { useReauthenticate } from '../session/reauthenticate';
import {
  ActionModal,
  DateTime,
  DetailDrawer,
  InfoTip,
  SectionCollapse,
  TechnicalDetails,
} from '../ui';
import type { SectionCollapseItem, SubmitOutcome } from '../ui';
import { GROUP_ORDER, categorySpec, fieldsFromValue, isExample } from './calibrationValues';
import { Code, Codes, IdText, Stack, codeText } from './ListingCommon';

type Loaded =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ok'; readonly detail: CalibrationPackageDetail }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

// ------------------------------------------------------------------ shared bits

/** Where a package applies, in words. */
export function scopeText(item: CalibrationPackage): string {
  if (item.scopeKind === 'STORE') return item.storeName ?? text.storeUnknown;
  if (item.scopeKind === 'PLATFORM') return item.platformCode ?? '—';
  return text.organizationScope;
}

/** A package as a short name: code and version. */
export function packageName(item: Pick<CalibrationPackage, 'code' | 'version'>): string {
  return `${item.code} · ${text.versionLabel(item.version)}`;
}

/** A package named by its identifier, looked up in the list when it is there. */
export function packageNameById(overview: CalibrationOverview, id: string): string {
  const found = overview.packages.find((item) => item.id === id);
  return found === undefined ? id.slice(0, 8) : packageName(found);
}

/** A person's name, marked when it is the viewer. */
export function personName(actor: CalibrationActor | null, viewerId: string | undefined): string {
  if (actor === null) return '—';
  const name = actor.displayName ?? text.unknownPerson;
  return actor.userId === viewerId ? `${name}${text.you}` : name;
}

/** The first twelve characters of a digest, with the full value one copy away. */
export function DigestText({ value }: { readonly value: string | null }): React.JSX.Element {
  if (value === null || value === '') return <Typography.Text type="secondary">—</Typography.Text>;
  return (
    <Typography.Text code copyable={{ text: value }} style={{ fontSize: 12 }}>
      {value.slice(0, 12)}…
    </Typography.Text>
  );
}

/** Why a step cannot be taken, one reason per blocker, phrased for this step. */
export function blockerText(step: CalibrationNextStep, item: CalibrationPackage): string {
  return step.blockers
    .map((blocker) => {
      if (blocker === 'NOT_GRANTED') return text.stepNotGranted[step.step] ?? blocker;
      if (blocker === 'NOT_YET_EFFECTIVE') {
        return text.notYetEffective(new Date(item.effectiveFrom).toLocaleString('zh-CN'));
      }
      return codeText('calibrationBlocker', blocker);
    })
    .join('；');
}

function valueLine(value: CalibrationValue): ReactNode {
  if (value.numeric !== null) {
    const shown = formatDecimal(value.numeric);
    return value.unitCode === 'RATIO' ? `${shown}（${formatPercent(value.numeric)}）` : shown;
  }
  if (value.text !== null) return value.text;
  return null;
}

function JsonBlock({ value }: { readonly value: unknown }): React.JSX.Element {
  const rendered = JSON.stringify(value, null, 2);
  return (
    <div style={{ position: 'relative' }}>
      <Typography.Text
        copyable={{ text: rendered }}
        style={{ position: 'absolute', right: 8, top: 4 }}
      />
      <pre
        style={{
          margin: 0,
          padding: 8,
          fontSize: 12,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-all',
          background: 'rgba(0, 0, 0, 0.02)',
          border: '1px solid rgba(0, 0, 0, 0.06)',
          borderRadius: 6,
          maxHeight: 320,
          overflow: 'auto',
        }}
      >
        {rendered}
      </pre>
    </div>
  );
}

// ------------------------------------------------------------------ drawer

export interface CalibrationDetailDrawerProps {
  readonly context: ConsoleRequest;
  /** The package shown; undefined keeps the drawer closed. */
  readonly packageId: string | undefined;
  /** Changes whenever the list is reloaded, so the detail follows. */
  readonly refreshKey: number;
  readonly overview: CalibrationOverview;
  readonly catalogue: CalibrationCatalogue;
  readonly onClose: () => void;
  /** After a lifecycle step succeeded. */
  readonly onChanged: () => void;
  readonly onDerive: (detail: CalibrationPackageDetail) => void;
  readonly onOpenPackage: (packageId: string) => void;
}

/**
 * One calibration package beside the list: what it holds, whether it is
 * complete, its governance history, and the next step for the person looking.
 */
export function CalibrationDetailDrawer({
  context,
  packageId,
  refreshKey,
  overview,
  catalogue,
  onClose,
  onChanged,
  onDerive,
  onOpenPackage,
}: CalibrationDetailDrawerProps): React.JSX.Element {
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    if (packageId === undefined) return;
    let live = true;
    setLoaded({ kind: 'loading' });
    void fetchCalibrationDetail(context, packageId).then((outcome) => {
      if (!live) return;
      setLoaded(
        outcome.ok
          ? { kind: 'ok', detail: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, packageId, refreshKey, generation]);

  const detail = loaded.kind === 'ok' ? loaded.detail : undefined;
  const summary = detail?.summary;
  const reload = (): void => {
    setGeneration((value) => value + 1);
  };

  return (
    <DetailDrawer
      open={packageId !== undefined}
      onClose={onClose}
      size={960}
      mask={false}
      title={
        summary === undefined ? (
          text.detailTitle
        ) : (
          <Flex gap={8} align="center" wrap>
            <span>{packageName(summary)}</span>
            <Code family="calibrationStage" code={summary.stage} />
          </Flex>
        )
      }
      extra={
        <Flex gap={8}>
          <Button icon={<ReloadOutlined />} onClick={reload}>
            {text.refresh}
          </Button>
          {detail !== undefined && (
            <Button
              disabled={!detail.summary.rights.prepare}
              title={detail.summary.rights.prepare ? undefined : text.deriveNewDisabled}
              onClick={() => {
                onDerive(detail);
              }}
            >
              {text.deriveNew}
            </Button>
          )}
        </Flex>
      }
      {...(detail?.summary.nextStep === null || detail === undefined
        ? {}
        : {
            footer: (
              <Flex justify="flex-end">
                <StepAction
                  context={context}
                  detail={detail}
                  step={detail.summary.nextStep}
                  overview={overview}
                  onDone={() => {
                    reload();
                    onChanged();
                  }}
                />
              </Flex>
            ),
          })}
      loading={loaded.kind === 'loading'}
      failure={loaded.kind === 'failed' ? loaded.failure : undefined}
      onRetry={reload}
    >
      {detail !== undefined && (
        <DetailBody
          detail={detail}
          overview={overview}
          catalogue={catalogue}
          onOpenPackage={onOpenPackage}
        />
      )}
    </DetailDrawer>
  );
}

// ------------------------------------------------------------------ body

function DetailBody({
  detail,
  overview,
  catalogue,
  onOpenPackage,
}: {
  readonly detail: CalibrationPackageDetail;
  readonly overview: CalibrationOverview;
  readonly catalogue: CalibrationCatalogue;
  readonly onOpenPackage: (packageId: string) => void;
}): React.JSX.Element {
  const summary = detail.summary;
  const link = (id: string | null): ReactNode =>
    id === null ? (
      text.labelNone
    ) : (
      <Typography.Link
        onClick={() => {
          onOpenPackage(id);
        }}
      >
        {packageNameById(overview, id)}
      </Typography.Link>
    );

  return (
    <Stack>
      <Integrity detail={detail} overview={overview} />
      <Descriptions
        bordered
        size="small"
        column={{ xs: 1, md: 2 }}
        items={[
          {
            key: 'purpose',
            label: text.labelPurpose,
            children: <Code family="actionPurpose" code={summary.purposeCode} />,
          },
          {
            key: 'scope',
            label: text.labelScope,
            children: (
              <Flex gap={4} align="center" wrap>
                <Code family="allowanceScope" code={summary.scopeKind} />
                <span>{scopeText(summary)}</span>
              </Flex>
            ),
          },
          {
            key: 'effective',
            label: text.labelEffective,
            span: 'filled',
            children: (
              <span>
                <DateTime value={summary.effectiveFrom} /> {text.from} ·{' '}
                {summary.effectiveTo === null ? (
                  text.openEnded
                ) : (
                  <>
                    {text.until} <DateTime value={summary.effectiveTo} />
                  </>
                )}
              </span>
            ),
          },
          { key: 'replaces', label: text.labelReplaces, children: link(summary.replacesPackageId) },
          {
            key: 'replacedBy',
            label: text.labelReplacedBy,
            children: link(summary.replacedByPackageId),
          },
          {
            key: 'evidence',
            label: text.labelEvidence,
            span: 'filled',
            children: detail.evidenceReference,
          },
          {
            key: 'rationale',
            label: text.labelRationale,
            span: 'filled',
            children: (
              <Typography.Text style={{ whiteSpace: 'pre-wrap' }}>
                {detail.rationale}
              </Typography.Text>
            ),
          },
          {
            key: 'impact',
            label: text.labelImpact,
            span: 'filled',
            children: (
              <Typography.Text style={{ whiteSpace: 'pre-wrap' }}>{detail.impact}</Typography.Text>
            ),
          },
          {
            key: 'differences',
            label: text.labelDifferences,
            span: 'filled',
            children: (
              <Typography.Text style={{ whiteSpace: 'pre-wrap' }}>
                {detail.differences}
              </Typography.Text>
            ),
          },
        ]}
      />
      <ValueSections detail={detail} catalogue={catalogue} />
      <Typography.Title level={5} style={{ margin: 0 }}>
        {text.sectionTimeline}
      </Typography.Title>
      {detail.events.length === 0 ? (
        <Typography.Text type="secondary">{text.noEvents}</Typography.Text>
      ) : (
        <Timeline
          items={detail.events.map((event) => ({
            key: event.id,
            color:
              event.kind === 'ACTIVATED' ? 'green' : event.kind === 'RETIRED' ? 'gray' : 'blue',
            content: (
              <Flex vertical gap={2}>
                <Flex gap={6} align="center" wrap>
                  <Code family="calibrationEvent" code={event.kind} />
                  <DateTime value={event.occurredAt} />
                  <Typography.Text>
                    {text.eventBy(
                      personName(
                        {
                          userId: event.actorUserId,
                          displayName: event.actorName,
                          at: null,
                          reference: null,
                          digest: null,
                        },
                        overview.viewer.userId,
                      ),
                    )}
                  </Typography.Text>
                </Flex>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {text.eventReference}：{event.evidenceReference}
                </Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {text.eventDigest}：<DigestText value={event.packageDigest} />
                </Typography.Text>
              </Flex>
            ),
          }))}
        />
      )}
      <TechnicalDetails>
        <Flex vertical gap={4}>
          <IdText label={text.technicalId} value={summary.id} />
          <IdText label={text.technicalCurrentDigest} value={detail.currentDigest} />
          <IdText label={text.technicalDraftDigest} value={summary.drafted?.digest} />
          <IdText label={text.technicalValidatedDigest} value={summary.validated?.digest} />
          <IdText label={text.technicalAcceptedDigest} value={summary.accepted?.digest} />
        </Flex>
      </TechnicalDetails>
    </Stack>
  );
}

/** Missing categories, combination failures and the replacement, in view before any step. */
function Integrity({
  detail,
  overview,
}: {
  readonly detail: CalibrationPackageDetail;
  readonly overview: CalibrationOverview;
}): React.JSX.Element {
  const summary = detail.summary;
  const draft = summary.status === 'DRAFT';
  const failures = summary.combinationFailures ?? [];
  const blocks: ReactNode[] = [];
  if (summary.missingCategories.length > 0) {
    blocks.push(
      <Alert
        key="missing"
        type="error"
        showIcon
        title={text.missingTitle}
        description={
          <Flex vertical gap={6}>
            <Codes family="calibrationCategory" codes={summary.missingCategories} />
            <Typography.Text type="secondary">{text.missingHelp}</Typography.Text>
          </Flex>
        }
      />,
    );
  }
  if (failures.length > 0) {
    blocks.push(
      <Alert
        key="failures"
        type="error"
        showIcon
        title={text.failuresTitle}
        description={
          <Flex vertical gap={6}>
            <Codes family="calibrationFailure" codes={failures} />
            <Typography.Text type="secondary">{text.failuresHelp}</Typography.Text>
          </Flex>
        }
      />,
    );
  }
  if (!summary.digestIntact) {
    blocks.push(
      <Alert
        key="digest"
        type="error"
        showIcon
        title={text.digestTitle}
        description={text.digestHelp}
      />,
    );
  }
  if (draft && summary.superseded) {
    blocks.push(
      <Alert
        key="superseded"
        type="warning"
        showIcon
        title={text.supersededNote(summary.latestVersionOfCode)}
      />,
    );
  }
  if (draft && summary.expired) {
    blocks.push(<Alert key="expired" type="warning" showIcon title={text.expiredNote} />);
  }
  if (draft) {
    const overlaps = summary.activeOverlapIds;
    const only = overlaps.length === 1 ? overlaps[0] : undefined;
    if (overlaps.length > 1) {
      blocks.push(
        <Alert key="replacement" type="error" showIcon title={text.replacementConflict} />,
      );
    } else if ((only ?? null) !== summary.replacesPackageId) {
      blocks.push(
        <Alert key="replacement" type="error" showIcon title={text.replacementMismatch} />,
      );
    } else {
      blocks.push(
        <Alert
          key="replacement"
          type="info"
          showIcon
          title={
            only === undefined
              ? text.replacementNone
              : text.replacementPlanned(packageNameById(overview, only))
          }
        />,
      );
    }
  }
  if (
    blocks.length === 0 ||
    (draft && summary.missingCategories.length === 0 && failures.length === 0)
  ) {
    blocks.unshift(
      <Alert
        key="ok"
        type={draft ? 'success' : 'info'}
        showIcon
        title={draft ? text.integrityOk : text.integrityNotEvaluated}
      />,
    );
  }
  return (
    <Flex vertical gap={8} data-section="integrity">
      <Typography.Title level={5} style={{ margin: 0 }}>
        {text.sectionIntegrity}
      </Typography.Title>
      {blocks}
    </Flex>
  );
}

/** The values, grouped as the draft form groups them, with missing categories flagged. */
function ValueSections({
  detail,
  catalogue,
}: {
  readonly detail: CalibrationPackageDetail;
  readonly catalogue: CalibrationCatalogue;
}): React.JSX.Element {
  const purpose = detail.summary.purposeCode;
  const ordinal = new Map(
    catalogue.categories.map((category) => [category.code, category.ordinal]),
  );
  const byOrdinal = (a: string, b: string): number =>
    (ordinal.get(a) ?? 99) - (ordinal.get(b) ?? 99) || a.localeCompare(b);
  const values = new Map(detail.values.map((value) => [value.categoryCode, value]));
  const items: SectionCollapseItem[] = GROUP_ORDER.flatMap((group) => {
    const codes = [
      ...new Set([
        ...detail.requiredCategories,
        ...detail.values.map((value) => value.categoryCode),
      ]),
    ]
      .filter((code) => (categorySpec(code)?.group ?? 'protection') === group)
      .sort(byOrdinal);
    if (codes.length === 0) return [];
    const missing = codes.filter((code) => !values.has(code));
    return [
      {
        key: group,
        title: calibrationGroups[group] ?? group,
        summary: text.groupCount(codes.length - missing.length),
        defaultOpen: true,
        ...(missing.length === 0
          ? {}
          : {
              flags: [
                {
                  key: 'missing',
                  label: text.groupMissing(missing.length),
                  color: 'error' as const,
                },
              ],
            }),
        children: (
          <Flex vertical gap={12}>
            {codes.map((code) => {
              const value = values.get(code);
              return value === undefined ? (
                <Flex key={code} gap={8} align="center" wrap>
                  <Typography.Text strong>{codeText('calibrationCategory', code)}</Typography.Text>
                  <Tag color="error" style={{ marginInlineEnd: 0 }}>
                    {text.missingTitle}
                  </Tag>
                </Flex>
              ) : (
                <ValueBlock
                  key={code}
                  value={value}
                  example={isExample(code, purpose, fieldsFromValue(value))}
                />
              );
            })}
          </Flex>
        ),
      },
    ];
  });
  return (
    <Flex vertical gap={8}>
      <Typography.Title level={5} style={{ margin: 0 }}>
        {text.sectionValues}
      </Typography.Title>
      <SectionCollapse size="small" items={items} />
    </Flex>
  );
}

function ValueBlock({
  value,
  example,
}: {
  readonly value: CalibrationValue;
  readonly example: boolean;
}): React.JSX.Element {
  const help = calibrationCategoryHelp[value.categoryCode];
  const line = valueLine(value);
  return (
    <Flex vertical gap={4} data-category={value.categoryCode}>
      <Flex gap={8} align="center" wrap>
        <Typography.Text strong>
          {codeText('calibrationCategory', value.categoryCode)}
          {help !== undefined && <InfoTip title={help} long />}
        </Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {value.categoryCode}
        </Typography.Text>
        {example && (
          <Tag color="gold" style={{ marginInlineEnd: 0 }}>
            {text.valueExample}
          </Tag>
        )}
      </Flex>
      <Descriptions
        size="small"
        bordered
        column={{ xs: 1, md: 3 }}
        items={[
          ...(line === null
            ? []
            : [
                {
                  key: 'value',
                  label: value.numeric !== null ? text.valueNumeric : text.valueText,
                  children: line,
                },
              ]),
          {
            key: 'unit',
            label: text.valueUnit,
            children: `${value.unitCode}${
              calibrationUnits[value.unitCode] === undefined
                ? ''
                : `（${calibrationUnits[value.unitCode] ?? ''}）`
            }`,
          },
          ...(value.windowDays === null
            ? []
            : [
                {
                  key: 'window',
                  label: text.valueWindow,
                  children: text.valueWindowDays(value.windowDays),
                },
              ]),
          {
            key: 'note',
            label: text.valueNote,
            span: 'filled' as const,
            children: value.scopeNote,
          },
          {
            key: 'evidence',
            label: text.valueEvidence,
            span: 'filled' as const,
            children: value.evidenceReference,
          },
        ]}
      />
      {value.json !== null && <JsonBlock value={value.json} />}
    </Flex>
  );
}

// ------------------------------------------------------------------ steps

interface StepValues {
  readonly evidenceReference?: string;
}

/**
 * The next lifecycle step: validate, accept or activate. The digest is taken
 * from what the backend computed for this stage, never typed; the button stays
 * disabled with its reasons while anything blocks the step.
 */
function StepAction({
  context,
  detail,
  step,
  overview,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly detail: CalibrationPackageDetail;
  readonly step: CalibrationNextStep;
  readonly overview: CalibrationOverview;
  readonly onDone: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const reauthenticate = useReauthenticate();
  const summary = detail.summary;
  const viewerId = overview.viewer.userId;
  const blocked = step.blockers.length > 0;
  const reasons = blocked ? blockerText(step, summary) : undefined;
  const label = codeText('calibrationStep', step.step);
  const holdsAccept = summary.rights.accept;

  const overlaps = summary.activeOverlapIds;
  const replaced = overlaps.length === 1 ? overlaps[0] : undefined;

  const submit = async (values: StepValues): Promise<SubmitOutcome> => {
    const decision = {
      digest: step.digest,
      evidenceReference: (values.evidenceReference ?? '').trim(),
    };
    const outcome =
      step.step === 'VALIDATE'
        ? await validateCalibration(context, summary.id, decision)
        : step.step === 'ACCEPT'
          ? await acceptCalibration(context, summary.id, decision)
          : await activateCalibration(context, summary.id, decision);
    if (!outcome.ok) return outcome.failure;
    void message.success(text.stepDone[step.step] ?? label);
    onDone();
    return undefined;
  };

  return (
    <ActionModal<StepValues>
      trigger={{
        label,
        type: 'primary',
        disabled: blocked,
        ...(reasons === undefined ? {} : { disabledReason: reasons }),
        reasonPlacement: 'inline',
      }}
      title={text.stepTitle[step.step] ?? label}
      consequence={text.stepConsequence[step.step]}
      okText={label}
      width={640}
      {...(reasons === undefined ? {} : { blockedReason: text.stepBlocked(reasons) })}
      summary={
        <Flex vertical gap={8}>
          <Descriptions
            size="small"
            bordered
            column={1}
            items={[
              {
                key: 'package',
                label: text.stepPackage,
                children: (
                  <Flex gap={6} align="center" wrap>
                    <span>{packageName(summary)}</span>
                    <Code family="actionPurpose" code={summary.purposeCode} />
                    <Code family="allowanceScope" code={summary.scopeKind} />
                    <span>{scopeText(summary)}</span>
                  </Flex>
                ),
              },
              {
                key: 'drafter',
                label: text.stepDrafter,
                children: (
                  <span>
                    {personName(summary.drafted, viewerId)} ·{' '}
                    <DateTime value={summary.drafted?.at} />
                  </span>
                ),
              },
              ...(summary.validated === null
                ? []
                : [
                    {
                      key: 'validator',
                      label: text.stepValidator,
                      children: (
                        <span>
                          {personName(summary.validated, viewerId)} ·{' '}
                          <DateTime value={summary.validated.at} />
                        </span>
                      ),
                    },
                  ]),
              {
                key: 'digest',
                label: (
                  <span>
                    {text.stepDigest}
                    <InfoTip title={text.stepDigestHelp} />
                  </span>
                ),
                children: <DigestText value={step.digest} />,
              },
            ]}
          />
          {step.step === 'VALIDATE' && holdsAccept && (
            <Alert type="warning" showIcon title={text.stepOwnerWarning} />
          )}
          {step.step === 'ACCEPT' && (
            <Alert
              type="info"
              showIcon
              title={text.stepIndependence}
              description={
                detail.eligibleAcceptors.length === 0
                  ? text.stepNoAcceptors
                  : text.stepAcceptors(detail.eligibleAcceptors.join('、'))
              }
            />
          )}
          {step.step === 'ACTIVATE' && (
            <Alert
              type={replaced === undefined ? 'info' : 'warning'}
              showIcon
              title={
                replaced === undefined
                  ? text.replacementNone
                  : text.replacementPlanned(packageNameById(overview, replaced))
              }
            />
          )}
          {step.stepUpRequired && (
            <Alert
              type="warning"
              showIcon
              title={text.stepUpTitle}
              description={text.stepUpHelp}
              {...(reauthenticate === undefined
                ? {}
                : {
                    action: (
                      <Button
                        size="small"
                        onClick={() => {
                          void reauthenticate().catch(() => undefined);
                        }}
                      >
                        {text.stepUpAction}
                      </Button>
                    ),
                  })}
            />
          )}
        </Flex>
      }
      onSubmit={submit}
    >
      <Form.Item
        name="evidenceReference"
        label={text.stepEvidence[step.step] ?? label}
        extra={text.stepEvidenceHelp}
        rules={[{ required: true, whitespace: true, message: text.stepEvidenceRequired }]}
      >
        <Input.TextArea
          rows={2}
          maxLength={512}
          showCount
          placeholder={text.stepEvidencePlaceholder}
        />
      </Form.Item>
    </ActionModal>
  );
}
