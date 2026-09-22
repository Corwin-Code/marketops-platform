import {
  Alert,
  App,
  Button,
  Checkbox,
  Col,
  Flex,
  Form,
  Input,
  Row,
  Select,
  Typography,
} from 'antd';
import type { FormRule } from 'antd';
import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import type { ConsoleRequest } from '../api/console';
import type {
  Containment,
  DescriptionObservationSummary,
  DisplayObservationSummary,
  ListingAction,
  ListingObservations,
  ManualPacket,
  PromotionObservationSummary,
} from '../api/listingConversion';
import {
  CONTAINMENT_LIST_LIMIT,
  fetchAction,
  fetchContainments,
  fetchListingObservations,
  fetchPromotionTerms,
  verifyPacket,
} from '../api/listingConversion';
import { dialog } from '../i18n/zh/common';
import { packetText, verifyText } from '../i18n/zh/listingManual';
import { FormDrawer } from '../ui';
import {
  Code,
  Hint,
  RussianText,
  Stack,
  SubTitle,
  When,
  codeOptions,
  codeText,
} from './ListingCommon';
import {
  descriptionOptions,
  displayOptions,
  promotionOptions,
  useRemote,
} from './ListingManualPickers';
import type { PickOption } from './ListingManualPickers';

const TEXT_LIMIT = 512;
const OBSERVATION_LIMIT = 20;

const HUMAN = 'INDEPENDENT_HUMAN';
const OFFICIAL = 'OFFICIAL_EVIDENCE';
const MARKETPLACE = 'MARKETPLACE_RAW';
const MANUAL_ENTRY = 'MANUAL_ENTRY';
const UNKNOWN = 'UNKNOWN';
const MATCHED_TARGET = 'MATCHED_TARGET';
const SHARED_VERSION = 'SHARED_VERSION';
/** Report qualifications the database accepts for a qualified verification. */
const QUALIFYING_REPORTS: ReadonlySet<string> = new Set([
  'WITHIN_PACKET_AUTHORITY',
  'LAWFUL_LATE_REPORT',
]);

type Match = 'MATCHED_TARGET' | 'MATCHED_PRIOR' | 'DIFFERENT' | 'UNKNOWN';

/** Which evidence the packet's action is verified with. */
type Mode = 'description' | 'promotion' | 'unknown';

/**
 * Whether an effective emergency-stop scope holds the listing: certainly,
 * possibly (a scope this client cannot resolve), unknown (the stops could not
 * be read in full) or provably not.
 */
type Coverage = 'CONTAINED' | 'POSSIBLE' | 'UNKNOWN' | 'CLEAR';

/** The promotion the action declared: an observation of any other is refused. */
interface PromotionIdentity {
  readonly nativePromotionKey: string;
  readonly engagementKind: string;
}

interface VerifyValues {
  basis?: string;
  managementObservationId?: string;
  managementMatch?: string;
  displayObservationId?: string;
  promotionObservationId?: string;
  attested?: boolean;
  note?: string;
}

/** Who may not verify a packet independently: its executor and its reporters. */
function executorsOf(packet: ManualPacket): ReadonlySet<string> {
  return new Set([packet.executorUserId, ...packet.reports.map((report) => report.reporterUserId)]);
}

/** The operation time a report states; older reads may not carry it. */
function reportedOperationTime(report: ManualPacket['reports'][number]): string | undefined {
  return report.operationTime;
}

/** Milliseconds of an instant, or nothing when it cannot be read. */
function instant(value: string | undefined): number | undefined {
  if (value === undefined) return undefined;
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? undefined : parsed;
}

/** The earliest moment evidence may have been observed at, and where it came from. */
interface OperationFloor {
  readonly at: string;
  readonly millis: number;
  readonly fromReports: boolean;
}

/**
 * The operation time evidence must follow: the later of the packet's issue
 * time and the latest reported operation time, as the database computes it.
 * A promotion counts only the executor's own reports.
 */
function operationFloor(packet: ManualPacket, executorOnly: boolean): OperationFloor {
  let floor: OperationFloor = {
    at: packet.issuedAt,
    millis: instant(packet.issuedAt) ?? 0,
    fromReports: false,
  };
  for (const report of packet.reports) {
    if (executorOnly && report.reporterUserId !== packet.executorUserId) continue;
    const at = reportedOperationTime(report);
    const millis = instant(at);
    if (at !== undefined && millis !== undefined && millis >= floor.millis) {
      floor = { at, millis, fromReports: true };
    }
  }
  return floor;
}

/**
 * The reports the qualification looks at: those at the latest stated
 * operation time, or the last one recorded when times are not known.
 */
function latestReports(packet: ManualPacket, executorOnly: boolean): ManualPacket['reports'] {
  const reports = packet.reports.filter(
    (report) => !executorOnly || report.reporterUserId === packet.executorUserId,
  );
  const timed = reports.filter((report) => instant(reportedOperationTime(report)) !== undefined);
  if (timed.length === 0) {
    const last = reports.at(-1);
    return last === undefined ? [] : [last];
  }
  const latest = Math.max(...timed.map((report) => instant(reportedOperationTime(report)) ?? 0));
  return timed.filter((report) => instant(reportedOperationTime(report)) === latest);
}

/** What decides whether a listed observation may be chosen. */
interface EvidenceRules {
  readonly floor: number;
  readonly excluded: ReadonlySet<string>;
  readonly basis: string | undefined;
  readonly targetDigest: string | undefined;
  /** The action's promotion, when its terms can be read. */
  readonly promotion: PromotionIdentity | undefined;
}

function tooEarly(observedAt: string, rules: EvidenceRules): boolean {
  const at = instant(observedAt);
  return at === undefined || at < rules.floor;
}

function recordedByExcluded(userId: string | undefined, rules: EvidenceRules): boolean {
  return userId !== undefined && rules.excluded.has(userId);
}

function descriptionBlock(
  observation: DescriptionObservationSummary,
  rules: EvidenceRules,
): string | undefined {
  if (tooEarly(observation.observedAt, rules)) return verifyText.tooEarly;
  if (recordedByExcluded(observation.recordedByUserId, rules)) return verifyText.notIndependent;
  if (rules.basis === OFFICIAL && observation.sourceKind !== MARKETPLACE) {
    return verifyText.officialNeedsMarketplace;
  }
  return undefined;
}

function displayBlock(
  observation: DisplayObservationSummary,
  rules: EvidenceRules,
): string | undefined {
  if (tooEarly(observation.observedAt, rules)) return verifyText.tooEarly;
  if (
    recordedByExcluded(observation.recordedByUserId, rules) ||
    recordedByExcluded(observation.observerUserId, rules)
  ) {
    return verifyText.notIndependent;
  }
  const handRecorded =
    observation.evidenceGrade === HUMAN || observation.sourceKind === MANUAL_ENTRY;
  if (handRecorded && rules.basis === OFFICIAL) return verifyText.officialExcludesManualDisplay;
  if (observation.evidenceGrade === HUMAN) {
    if (
      observation.sourceKind !== MANUAL_ENTRY ||
      observation.recordedByUserId === undefined ||
      observation.recordedByUserId !== observation.observerUserId
    ) {
      return verifyText.manualDisplayNeedsObserver;
    }
  } else if (observation.sourceKind !== MARKETPLACE) {
    return verifyText.officialDisplayNeedsRaw;
  }
  if (
    observation.displayState === 'DISPLAYED' &&
    (rules.targetDigest === undefined || observation.displayedTextDigest !== rules.targetDigest)
  ) {
    return verifyText.displayTextDiffers;
  }
  return undefined;
}

function promotionBlock(
  observation: PromotionObservationSummary,
  rules: EvidenceRules,
): string | undefined {
  if (
    rules.promotion !== undefined &&
    (observation.nativePromotionKey !== rules.promotion.nativePromotionKey ||
      observation.engagementKind !== rules.promotion.engagementKind)
  ) {
    return verifyText.otherPromotion;
  }
  if (tooEarly(observation.observedAt, rules)) return verifyText.tooEarly;
  if (recordedByExcluded(observation.recordedByUserId, rules)) return verifyText.notIndependent;
  if (observation.sourceKind === MANUAL_ENTRY) {
    if (observation.recordedByUserId === undefined) return verifyText.promotionSourceUnsupported;
    return rules.basis === HUMAN ? undefined : verifyText.manualPromotionNeedsHuman;
  }
  if (observation.sourceKind === MARKETPLACE) {
    return rules.basis === OFFICIAL ? undefined : verifyText.marketplacePromotionNeedsOfficial;
  }
  return verifyText.promotionSourceUnsupported;
}

/** The management match the observed text digest supports. */
function compareDescription(
  observation: DescriptionObservationSummary,
  action: ListingAction,
): Match {
  if (action.targetTextDigest !== undefined && observation.textDigest === action.targetTextDigest) {
    return 'MATCHED_TARGET';
  }
  if (
    action.currentTextDigest !== undefined &&
    observation.textDigest === action.currentTextDigest
  ) {
    return 'MATCHED_PRIOR';
  }
  return 'DIFFERENT';
}

/**
 * The participation match an observation supports, decided exactly as
 * lc_bind_promotion_participation decides it: the observation matches the
 * target when it participates and its declaration digest equals the action's
 * promotion terms digest. 不一致 is refused by the database whenever the terms
 * do match, and also for a participation whose declaration is unknown, so it is
 * offered only when the listing does not take part or takes part under a
 * declaration that is provably a different one. Without both digests nothing is
 * proven and only 未知 remains.
 */
function comparePromotion(observation: PromotionObservationSummary, action: ListingAction): Match {
  if (observation.participationState === 'NOT_PARTICIPATING') return 'DIFFERENT';
  if (observation.participationState !== 'PARTICIPATING') return 'UNKNOWN';
  const declared = observation.declarationDigest;
  const target = action.promotionTermsDigest;
  if (declared === undefined || target === undefined) return 'UNKNOWN';
  return declared === target ? 'MATCHED_TARGET' : 'DIFFERENT';
}

/**
 * Whether one active stop's own scope covers the action's listing, or
 * undefined when the action read cannot tell: its platform and batch
 * membership are not in it.
 */
function scopeCovers(containment: Containment, action: ListingAction): boolean | undefined {
  switch (containment.scopeKind) {
    case 'ORGANIZATION':
      return true;
    case 'LISTING':
      return containment.platformListingId === undefined
        ? undefined
        : containment.platformListingId === action.platformListingId;
    case 'STORE':
      return containment.storeId === undefined ? undefined : containment.storeId === action.storeId;
    default:
      return undefined;
  }
}

/** What is known here about the listing being held by an emergency stop. */
interface ContainmentState {
  readonly coverage: Coverage;
  /**
   * The backend decided containment itself on the action read, so nothing about
   * it is left for it to check at submission.
   */
  readonly decided: boolean;
}

/**
 * Containment as the backend decided it for this action, or, when the action
 * read does not carry that answer, what the active stops still allow this
 * client to say.
 */
function containmentState(
  action: ListingAction | undefined,
  containments: readonly Containment[] | undefined,
): ContainmentState {
  if (action?.scopeContained === true) return { coverage: 'CONTAINED', decided: true };
  if (action?.scopeContained === false) return { coverage: 'CLEAR', decided: true };
  return { coverage: containmentCoverage(containments, action), decided: false };
}

/**
 * How the active stops cover the listing. A shared-version stop also covers
 * listings that depend on one in its scope, which this client cannot follow,
 * so it is only ever provably unrelated when it is not a shared-version stop.
 * The database also contains a listing on an unreleased outcome-protection
 * failure, which no stop lists, so this inference is only ever the fallback
 * for an action read that did not decide containment itself, and backendChecks
 * then leaves both to the backend.
 */
function containmentCoverage(
  containments: readonly Containment[] | undefined,
  action: ListingAction | undefined,
): Coverage {
  if (containments === undefined || action === undefined) return 'UNKNOWN';
  let possible = false;
  for (const containment of containments) {
    const covers = scopeCovers(containment, action);
    if (covers === true) return 'CONTAINED';
    if (covers === undefined || containment.causeClass === SHARED_VERSION) possible = true;
  }
  if (possible) return 'POSSIBLE';
  return containments.length >= CONTAINMENT_LIST_LIMIT ? 'UNKNOWN' : 'CLEAR';
}

/** A listed record chosen by id, when it is still listed. */
function chosen<T extends { readonly observationId: string }>(
  list: readonly T[] | undefined,
  id: string | undefined,
): T | undefined {
  return id === undefined ? undefined : list?.find((item) => item.observationId === id);
}

/** Choose one listed observation; typing an identifier is not offered here. */
function EvidenceSelect({
  value,
  onChange,
  id,
  options,
  loading,
}: {
  readonly value?: string | undefined;
  readonly onChange?: ((value: string | undefined) => void) | undefined;
  readonly id?: string | undefined;
  readonly options: readonly PickOption[];
  readonly loading: boolean;
}): React.JSX.Element {
  return (
    <Select<string>
      {...(id === undefined ? {} : { id })}
      value={value ?? null}
      loading={loading}
      allowClear
      placeholder={verifyText.noEvidence}
      notFoundContent={verifyText.evidenceNone}
      showSearch={{ optionFilterProp: 'search' }}
      options={options.map((option) => ({
        value: option.value,
        label: option.label,
        search: option.search,
        disabled: option.disabled === true,
      }))}
      onChange={(next: string | undefined) => {
        onChange?.(next ?? undefined);
      }}
    />
  );
}

/** Target and observed text side by side, headed by the system comparison. */
function DescriptionComparison({
  targetText,
  observation,
  computed,
}: {
  readonly targetText: string | undefined;
  readonly observation: DescriptionObservationSummary | undefined;
  readonly computed: Match;
}): React.JSX.Element {
  return (
    <Flex vertical gap={8} data-comparison={observation === undefined ? 'none' : computed}>
      {observation === undefined ? (
        <Typography.Text type="secondary">{verifyText.comparisonNone}</Typography.Text>
      ) : (
        <Flex gap={8} wrap align="center">
          <Typography.Text strong>{verifyText.comparison}：</Typography.Text>
          <Code family="managementMatch" code={computed} />
          <Typography.Text type="secondary">
            {verifyText.comparisonObservedAt} <When value={observation.observedAt} />
          </Typography.Text>
          <Typography.Text type="secondary">{verifyText.comparisonSource}</Typography.Text>
          <Code family="factSourceKind" code={observation.sourceKind} />
        </Flex>
      )}
      <Row gutter={[16, 8]}>
        <Col xs={24} md={12}>
          <Typography.Text type="secondary">{verifyText.comparisonTarget}</Typography.Text>
          {targetText === undefined ? (
            <div>
              <Typography.Text type="secondary">{verifyText.comparisonNoTarget}</Typography.Text>
            </div>
          ) : (
            <RussianText value={targetText} />
          )}
        </Col>
        <Col xs={24} md={12}>
          <Typography.Text type="secondary">{verifyText.comparisonObserved}</Typography.Text>
          {observation === undefined ? (
            <div>
              <Typography.Text type="secondary">—</Typography.Text>
            </div>
          ) : (
            <RussianText value={observation.textPreview} />
          )}
        </Col>
      </Row>
    </Flex>
  );
}

/** The participation the chosen promotion observation shows. */
function PromotionComparison({
  observation,
  computed,
  identity,
}: {
  readonly observation: PromotionObservationSummary | undefined;
  readonly computed: Match;
  readonly identity: PromotionIdentity | undefined;
}): React.JSX.Element {
  if (observation === undefined) {
    return <Typography.Text type="secondary">{verifyText.comparisonNone}</Typography.Text>;
  }
  // The conclusion is exact: the observed declaration digest is compared with
  // the action's promotion terms digest, and a matching digest is the whole
  // declaration, so it also settles that this is the action's own promotion.
  const participating = observation.participationState === 'PARTICIPATING';
  return (
    <Flex vertical gap={4} data-comparison={computed}>
      <Flex gap={8} wrap align="center">
        <Typography.Text strong>{verifyText.comparison}：</Typography.Text>
        <Code family="managementMatch" code={computed} />
        <Typography.Text type="secondary">
          {verifyText.comparisonObservedAt} <When value={observation.observedAt} />
        </Typography.Text>
        <Code family="factSourceKind" code={observation.sourceKind} />
      </Flex>
      <Typography.Text>
        {verifyText.promotionComparisonState}：
        <Code family="participationState" code={observation.participationState} />
      </Typography.Text>
      <Typography.Text>
        {verifyText.promotionComparisonKey}：{observation.nativePromotionKey}
      </Typography.Text>
      {identity !== undefined && (
        <Typography.Text type="secondary">
          {verifyText.promotionActionKey}：{identity.nativePromotionKey}
        </Typography.Text>
      )}
      <Typography.Text type="secondary">
        {computed === MATCHED_TARGET
          ? verifyText.promotionTermsMatched
          : computed === 'DIFFERENT'
            ? participating
              ? verifyText.promotionTermsDiffer
              : verifyText.promotionNotParticipating
            : participating
              ? verifyText.promotionTermsUnknown
              : verifyText.promotionUnknownState}
      </Typography.Text>
    </Flex>
  );
}

/** A short list of reasons or checks inside a preview. */
function PreviewList({ items }: { readonly items: readonly string[] }): React.JSX.Element {
  return (
    <ul style={{ margin: 0, paddingInlineStart: 20 }}>
      {items.map((item) => (
        <li key={item}>{item}</li>
      ))}
    </ul>
  );
}

/**
 * What the verification will do once submitted: why it only records evidence,
 * what the backend still has to confirm, or that it will verify. The last is
 * shown only when every condition is proven here, with no backend check left.
 */
function ResultPreview({
  reasons,
  checks,
}: {
  readonly reasons: readonly string[];
  readonly checks: readonly string[];
}): React.JSX.Element {
  if (reasons.length === 0 && checks.length > 0) {
    return (
      <div data-preview="conditional">
        <Alert
          type="info"
          showIcon
          title={verifyText.previewConditional}
          description={<PreviewList items={checks} />}
        />
      </div>
    );
  }
  if (reasons.length === 0) {
    return (
      <div data-preview="verified">
        <Alert
          type="success"
          showIcon
          title={verifyText.previewVerified}
          description={verifyText.previewVerifiedCaveat}
        />
      </div>
    );
  }
  return (
    <div data-preview="evidence-only">
      <Alert
        type="warning"
        showIcon
        title={verifyText.previewEvidenceOnly}
        description={<PreviewList items={reasons} />}
      />
    </div>
  );
}

/** The reasons a verification with these choices would only record evidence. */
function evidenceOnlyReasons({
  mode,
  packet,
  action,
  containment,
  evidence,
  match,
  display,
}: {
  readonly mode: Mode;
  readonly packet: ManualPacket;
  readonly action: ListingAction | undefined;
  readonly containment: ContainmentState;
  readonly evidence: boolean;
  readonly match: string | undefined;
  readonly display: DisplayObservationSummary | undefined;
}): string[] {
  if (mode === 'unknown' || action === undefined) return [packetText.actionUnreadable];
  const promotion = mode === 'promotion';
  const reasons: string[] = [];
  if (action.state !== 'LAUNCHED') {
    reasons.push(verifyText.reasonActionState(codeText('actionState', action.state)));
  }
  if (containment.coverage === 'CONTAINED') reasons.push(verifyText.reasonContained);
  const deviation = packet.reports.find(
    (report) => report.operationQualification === 'UNAUTHORISED_DEVIATION',
  );
  if (deviation !== undefined) {
    reasons.push(
      verifyText.reasonDeviation(
        deviation.deviationReason === undefined
          ? codeText('operationQualification', 'UNAUTHORISED_DEVIATION')
          : codeText('deviationReason', deviation.deviationReason),
      ),
    );
  }
  const reports = latestReports(packet, promotion);
  const latest = reports.at(-1);
  const applied = reports.filter((report) => report.reportState === 'APPLIED');
  if (latest === undefined) {
    reasons.push(verifyText.reasonNoReport);
  } else if (applied.length === 0) {
    reasons.push(verifyText.reasonReport(codeText('reportState', latest.reportState)));
  } else if (
    deviation === undefined &&
    applied.every(
      (report) =>
        report.operationQualification !== undefined &&
        !QUALIFYING_REPORTS.has(report.operationQualification),
    )
  ) {
    const qualification = applied[0]?.operationQualification ?? '';
    reasons.push(
      verifyText.reasonReportQualification(codeText('operationQualification', qualification)),
    );
  }
  if (!evidence) {
    reasons.push(promotion ? verifyText.reasonNoPromotion : verifyText.reasonNoManagement);
  } else if (match === undefined || match === '') {
    reasons.push(verifyText.reasonMatchPending);
  } else if (match !== MATCHED_TARGET) {
    const label = codeText('managementMatch', match);
    reasons.push(
      promotion ? verifyText.reasonPromotionMatch(label) : verifyText.reasonMatch(label),
    );
  }
  if (!promotion) {
    if (action.targetTextDigest === undefined) reasons.push(verifyText.reasonNoTarget);
    if (display === undefined) {
      reasons.push(verifyText.reasonNoDisplay);
    } else if (display.displayState !== 'DISPLAYED') {
      reasons.push(verifyText.reasonDisplay(codeText('displayState', display.displayState)));
    }
  }
  return reasons;
}

/**
 * What only the backend can confirm before a verification with these choices
 * verifies: the stops this client could not resolve when the action read did
 * not decide containment itself, the promotion financial disclosure the
 * backend requires of the verifier itself, and a promotion observation whose
 * promotion is neither disclosed here nor settled by a matching declaration
 * digest. The verified preview is shown only when this is empty.
 */
function backendChecks({
  mode,
  containment,
  promotionKnown,
  promotionProven,
  promotionTermsUnreadable,
  evidence,
}: {
  readonly mode: Mode;
  readonly containment: ContainmentState;
  readonly promotionKnown: boolean;
  /** The chosen observation's declaration digest is the action's own terms digest. */
  readonly promotionProven: boolean;
  /** The action's promotion terms were read and are not disclosed, or the read failed. */
  readonly promotionTermsUnreadable: boolean;
  readonly evidence: boolean;
}): string[] {
  const checks: string[] = [];
  if (!containment.decided) {
    if (containment.coverage === 'POSSIBLE') checks.push(verifyText.checkContainmentPossible);
    if (containment.coverage === 'UNKNOWN') checks.push(verifyText.checkContainmentUnknown);
    // Without the backend's own answer, an unreleased outcome-protection
    // failure (ops.lc_unreleased_outcome_failures) also contains the listing
    // and no stop lists it, so it can never be ruled out from the stop list.
    checks.push(verifyText.checkOutcomeFailures);
  }
  if (mode === 'promotion') {
    // Undisclosed terms are the disclosure the backend demands of the verifier
    // for a promotion packet: it rejects the submission outright, before any
    // verification or evidence is written, whatever the chosen evidence says —
    // so a matching declaration digest cannot settle it.
    if (promotionTermsUnreadable) {
      checks.push(verifyText.checkPromotionDisclosure);
    } else if (evidence && !promotionKnown && !promotionProven) {
      checks.push(verifyText.checkPromotionIdentity);
    }
  }
  return checks;
}

/** The form body: evidence, conclusions, attestation and the result preview. */
function VerifyForm({
  packet,
  action,
  mode,
  observations,
  observationsLoading,
  observationsFailed,
  containment,
  promotionIdentity,
  promotionTermsUnreadable,
}: {
  readonly packet: ManualPacket;
  readonly action: ListingAction | undefined;
  readonly mode: Mode;
  readonly observations: ListingObservations | undefined;
  readonly observationsLoading: boolean;
  readonly observationsFailed: boolean;
  readonly containment: ContainmentState;
  readonly promotionIdentity: PromotionIdentity | undefined;
  /** The action's promotion terms were read and are not disclosed, or the read failed. */
  readonly promotionTermsUnreadable: boolean;
}): React.JSX.Element {
  const form = Form.useFormInstance<VerifyValues>();
  const basis = Form.useWatch('basis', form);
  const managementId = Form.useWatch('managementObservationId', form);
  const displayId = Form.useWatch('displayObservationId', form);
  const promotionId = Form.useWatch('promotionObservationId', form);
  const match = Form.useWatch('managementMatch', form);

  const promotion = mode === 'promotion';
  const floor = operationFloor(packet, promotion);
  const rules: EvidenceRules = {
    floor: floor.millis,
    excluded: executorsOf(packet),
    basis,
    targetDigest: action?.targetTextDigest,
    promotion: promotionIdentity,
  };
  const management = chosen(observations?.description, managementId);
  const display = chosen(observations?.display, displayId);
  const participation = chosen(observations?.promotion, promotionId);
  const evidence = promotion ? participation : management;
  const computed: Match =
    action === undefined
      ? 'UNKNOWN'
      : promotion
        ? participation === undefined
          ? 'UNKNOWN'
          : comparePromotion(participation, action)
        : management === undefined
          ? 'UNKNOWN'
          : compareDescription(management, action);
  const allowed: readonly Match[] =
    evidence === undefined || computed === 'UNKNOWN' ? ['UNKNOWN'] : [computed, 'UNKNOWN'];

  // A basis change, or the action's promotion becoming known, can make chosen
  // evidence ineligible: drop exactly that.
  const rulesKey = JSON.stringify([
    basis ?? null,
    promotionIdentity?.nativePromotionKey ?? null,
    promotionIdentity?.engagementKind ?? null,
  ]);
  const previousRules = useRef(rulesKey);
  useEffect(() => {
    if (previousRules.current === rulesKey) return;
    previousRules.current = rulesKey;
    if (management !== undefined && descriptionBlock(management, rules) !== undefined) {
      form.setFieldValue('managementObservationId', undefined);
    }
    if (display !== undefined && displayBlock(display, rules) !== undefined) {
      form.setFieldValue('displayObservationId', undefined);
    }
    if (participation !== undefined && promotionBlock(participation, rules) !== undefined) {
      form.setFieldValue('promotionObservationId', undefined);
    }
  });

  // The conclusion belongs to the evidence it was drawn from: a change of
  // evidence clears it, and with no evidence it can only be unknown.
  const evidenceId = promotion ? promotionId : managementId;
  const evidenceKey = `${mode}:${evidenceId ?? ''}`;
  const previousEvidence = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (previousEvidence.current === evidenceKey) return;
    previousEvidence.current = evidenceKey;
    form.setFieldValue(
      'managementMatch',
      evidenceId === undefined || evidenceId === '' ? UNKNOWN : undefined,
    );
  }, [evidenceKey, evidenceId, form]);

  const matchRules: FormRule[] = [
    { required: true, message: verifyText.matchRequired },
    {
      validator: (_: unknown, value: string | undefined) =>
        value === undefined || (allowed as readonly string[]).includes(value)
          ? Promise.resolve()
          : Promise.reject(new Error(verifyText.matchRequired)),
    },
  ];
  const officialNeedsDescription: FormRule = ({ getFieldValue }) => ({
    validator: (_: unknown, value: string | undefined) =>
      mode === 'description' &&
      getFieldValue('basis') === OFFICIAL &&
      (value === undefined || value === '')
        ? Promise.reject(new Error(verifyText.officialRequiresDescription))
        : Promise.resolve(),
  });

  const listUnavailable = observationsFailed || mode === 'unknown';
  let evidenceFields: ReactNode;
  if (mode === 'unknown') {
    evidenceFields = <Hint>{packetText.actionUnreadable}</Hint>;
  } else if (promotion) {
    evidenceFields = (
      <Form.Item
        name="promotionObservationId"
        label={packetText.verifyPromotion}
        extra={
          promotionTermsUnreadable
            ? `${verifyText.promotionHelp}${verifyText.promotionIdentityUnknown}`
            : verifyText.promotionHelp
        }
      >
        <EvidenceSelect
          loading={observationsLoading}
          options={promotionOptions(
            observations?.promotion ?? [],
            rules.excluded,
            undefined,
            (observation) => promotionBlock(observation, rules),
          )}
        />
      </Form.Item>
    );
  } else {
    evidenceFields = (
      <>
        <Form.Item
          name="managementObservationId"
          label={packetText.verifyDescription}
          extra={verifyText.descriptionHelp}
          dependencies={['basis']}
          rules={[officialNeedsDescription]}
        >
          <EvidenceSelect
            loading={observationsLoading}
            options={descriptionOptions(
              observations?.description ?? [],
              { target: action?.targetTextDigest, prior: action?.currentTextDigest },
              rules.excluded,
              (observation) => descriptionBlock(observation, rules),
            )}
          />
        </Form.Item>
        <Form.Item
          name="displayObservationId"
          label={packetText.verifyDisplay}
          extra={verifyText.displayHelp}
        >
          <EvidenceSelect
            loading={observationsLoading}
            options={displayOptions(observations?.display ?? [], rules.excluded, (observation) =>
              displayBlock(observation, rules),
            )}
          />
        </Form.Item>
      </>
    );
  }

  return (
    <>
      <SubTitle>{verifyText.evidenceSection}</SubTitle>
      <Hint>
        {verifyText.window}：<When value={floor.at} />
        {' · '}
        {verifyText.windowHelp}
        {floor.fromReports ? null : ` ${verifyText.windowFromIssue}`}
      </Hint>
      <Form.Item
        name="basis"
        label={packetText.verifyBasis}
        rules={[{ required: true, message: `请选择${packetText.verifyBasis}` }]}
      >
        <Select options={codeOptions('verificationBasis', [HUMAN, OFFICIAL])} />
      </Form.Item>
      {listUnavailable && mode !== 'unknown' && <Hint>{verifyText.evidenceListUnavailable}</Hint>}
      {evidenceFields}

      <SubTitle>{verifyText.conclusionSection}</SubTitle>
      <div style={{ marginBottom: 16 }}>
        {promotion ? (
          <PromotionComparison
            observation={participation}
            computed={computed}
            identity={promotionIdentity}
          />
        ) : mode === 'description' ? (
          <DescriptionComparison
            targetText={packet.targetText ?? action?.targetText}
            observation={management}
            computed={computed}
          />
        ) : null}
      </div>
      <Form.Item
        label={promotion ? verifyText.promotionMatchLabel : packetText.verifyMatch}
        extra={verifyText.matchHelp}
        required
      >
        <Flex gap={8} align="center" wrap>
          <Form.Item name="managementMatch" noStyle rules={matchRules}>
            <Select<string>
              style={{ minWidth: 220, flex: 1 }}
              aria-label={promotion ? verifyText.promotionMatchLabel : packetText.verifyMatch}
              placeholder={verifyText.matchPlaceholder}
              disabled={evidence === undefined}
              options={codeOptions('managementMatch', allowed)}
            />
          </Form.Item>
          {evidence !== undefined && computed !== 'UNKNOWN' && (
            <Button
              type={match === computed ? 'default' : 'primary'}
              onClick={() => {
                form.setFieldValue('managementMatch', computed);
                void form.validateFields(['managementMatch']);
              }}
            >
              {verifyText.adopt}
            </Button>
          )}
        </Flex>
      </Form.Item>
      {mode === 'description' && (
        <Form.Item label={packetText.verifyDisplayState} extra={verifyText.displayLocked}>
          <span data-display-state={display?.displayState ?? UNKNOWN}>
            <Code family="displayState" code={display?.displayState ?? UNKNOWN} />
          </span>
        </Form.Item>
      )}

      <SubTitle>{verifyText.attestSection}</SubTitle>
      <Form.Item
        name="attested"
        valuePropName="checked"
        rules={[
          {
            validator: (_: unknown, value: boolean | undefined) =>
              value === true
                ? Promise.resolve()
                : Promise.reject(new Error(verifyText.attestRequired)),
          },
        ]}
      >
        <Checkbox>{verifyText.attest}</Checkbox>
      </Form.Item>
      <Form.Item
        name="note"
        label={packetText.verifyNote}
        rules={[{ required: true, whitespace: true, message: dialog.reasonRequired }]}
      >
        <Input.TextArea
          rows={3}
          maxLength={TEXT_LIMIT}
          showCount
          placeholder={packetText.verifyNotePlaceholder}
        />
      </Form.Item>
      <ResultPreview
        reasons={evidenceOnlyReasons({
          mode,
          packet,
          action,
          containment,
          evidence: evidence !== undefined,
          match,
          display,
        })}
        checks={backendChecks({
          mode,
          containment,
          promotionKnown: promotionIdentity !== undefined,
          promotionProven: computed === MATCHED_TARGET,
          promotionTermsUnreadable,
          evidence: evidence !== undefined,
        })}
      />
    </>
  );
}

/**
 * Independent verification of one reported packet, by someone other than its
 * executor or reporters.
 *
 * Evidence comes first: only observations of this listing taken after the
 * operation, not recorded by the executor or a reporter, and fitting the
 * chosen basis can be picked. The management conclusion is then the system's
 * comparison of the observed text with the target (or 未知), adopted
 * explicitly; a promotion conclusion is the same comparison of the observed
 * declaration digest with the action's terms digest; the buyer display
 * conclusion is the chosen display observation's own state. A preview states
 * that the submission verifies the packet and action, why it only records
 * evidence, or — when this client could not settle every condition itself —
 * which of them the backend still decides.
 */
export function VerifyDrawer({
  context,
  packet,
  open,
  onClose,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly packet: ManualPacket;
  readonly open: boolean;
  readonly onClose: () => void;
  readonly onDone: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const action = useRemote(open ? `action:${packet.actionId}` : undefined, () =>
    fetchAction(context, packet.actionId),
  );
  const listingId = action.value?.platformListingId;
  const observations = useRemote(
    open && listingId !== undefined ? `observations:${listingId}` : undefined,
    () => fetchListingObservations(context, listingId ?? '', OBSERVATION_LIMIT),
  );
  // The action read decides containment itself; the stop list is read only to
  // fall back on when it does not carry that answer. A verifier without the
  // governance read cannot rule a stop out either way: the preview then leaves
  // containment to the backend.
  const containments = useRemote(
    open && action.value !== undefined && action.value.scopeContained === undefined
      ? 'containments:active'
      : undefined,
    () => fetchContainments(context, true),
  );
  const mode: Mode =
    action.value === undefined
      ? 'unknown'
      : action.value.actionKind === 'LISTING_PROMOTION_ACTION'
        ? 'promotion'
        : 'description';
  // The declared promotion is disclosed only with financial access; without
  // it, other promotions' observations cannot be ruled out here.
  const terms = useRemote(
    open && mode === 'promotion' ? `promotion-terms:${packet.actionId}` : undefined,
    () => fetchPromotionTerms(context, packet.actionId),
  );
  const declared = terms.value?.terms;
  const promotionIdentity: PromotionIdentity | undefined =
    declared === undefined
      ? undefined
      : {
          nativePromotionKey: declared.nativePromotionKey,
          engagementKind: declared.engagementKind,
        };
  const latest = packet.reports.at(-1);

  return (
    <FormDrawer<VerifyValues>
      open={open}
      onClose={onClose}
      title={`${packetText.verifyTitle} · ${packet.nativeListingKey}`}
      submitText={packetText.verifySubmit}
      initialValues={{ basis: HUMAN }}
      intro={
        <Stack>
          <div>
            <Typography.Text type="secondary">{packetText.verifyIntroReport}</Typography.Text>
            {latest === undefined ? (
              <div>
                <Typography.Text type="secondary">{packetText.verifyNoReport}</Typography.Text>
              </div>
            ) : (
              <Flex gap={8} wrap align="center" style={{ marginTop: 4 }}>
                <Code family="reportState" code={latest.reportState} />
                <Typography.Text>{latest.note}</Typography.Text>
              </Flex>
            )}
          </div>
          {(action.loading || observations.loading) && (
            <Hint>{packetText.loadingObservations}</Hint>
          )}
        </Stack>
      }
      onSubmit={async (values) => {
        const description = mode === 'description';
        const promotion = mode === 'promotion';
        const managementObservationId = description
          ? chosen(observations.value?.description, values.managementObservationId)?.observationId
          : undefined;
        const display = description
          ? chosen(observations.value?.display, values.displayObservationId)
          : undefined;
        const promotionObservationId = promotion
          ? chosen(observations.value?.promotion, values.promotionObservationId)?.observationId
          : undefined;
        const outcome = await verifyPacket(
          context,
          packet.id,
          values.basis ?? '',
          values.managementMatch ?? UNKNOWN,
          display?.displayState ?? UNKNOWN,
          (values.note ?? '').trim(),
          {
            ...(managementObservationId === undefined ? {} : { managementObservationId }),
            ...(display === undefined ? {} : { displayObservationId: display.observationId }),
            ...(promotionObservationId === undefined ? {} : { promotionObservationId }),
          },
        );
        if (!outcome.ok) return outcome.failure;
        void message.success(packetText.verified);
        onDone();
        return undefined;
      }}
    >
      <VerifyForm
        packet={packet}
        action={action.value}
        mode={mode}
        observations={observations.value}
        observationsLoading={observations.loading}
        observationsFailed={action.failed || observations.failed}
        containment={containmentState(action.value, containments.value)}
        promotionIdentity={promotionIdentity}
        promotionTermsUnreadable={
          mode === 'promotion' && !terms.loading && promotionIdentity === undefined
        }
      />
    </FormDrawer>
  );
}
