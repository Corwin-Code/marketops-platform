import { Alert, DatePicker, Descriptions, Flex, Space, Tag, Typography } from 'antd';
import type { DescriptionsProps, TagProps } from 'antd';
import type { Dayjs } from 'dayjs';
import type { ReactNode } from 'react';
import type { ConsoleFailure } from '../api/console';
import { storeLocalToIso, toStoreDayjs } from '../format';
import { LISTING_CODES } from '../i18n/zh/listingCodes';
import type { ListingCodeFamily } from '../i18n/zh/listingCodes';
import { LISTING_TEXT, t } from '../i18n/zh/listing';
import { CodeTag, DateTime, FailureAlert } from '../ui';

/** A colour a code tag may take. */
type TagColor = NonNullable<TagProps['color']>;

const GOOD: TagColor = 'success';
const ATTENTION: TagColor = 'warning';
const BAD: TagColor = 'error';
const MOVING: TagColor = 'processing';

/**
 * Colours per code family: green for good or complete, gold for pending or
 * needing attention, red for blocked, failed or breached, blue for in progress.
 * A code without a colour stays neutral.
 */
const CODE_COLORS: Partial<Record<ListingCodeFamily, Readonly<Record<string, TagColor>>>> = {
  actionState: {
    DRAFT: ATTENTION,
    REVIEWED: ATTENTION,
    APPROVED: GOOD,
    APPROVED_NOT_LAUNCHABLE: ATTENTION,
    LAUNCHED: MOVING,
    VERIFIED: GOOD,
    CLOSED: 'default',
    CANCELLED: 'default',
    CONTAINED: BAD,
  },
  materialityRoute: {
    MATERIAL_IMPACT: BAD,
    ORDINARY_IMPACT: GOOD,
    MATERIALITY_UNRESOLVED: ATTENTION,
  },
  ratioState: { DEFINED: GOOD, UNDEFINED: ATTENTION, NOT_AVAILABLE: ATTENTION },
  nodeVerdict: { MET: GOOD, NOT_MET: BAD, UNDETERMINED: ATTENTION },
  protectionVerdict: { PASS: GOOD, FAIL: BAD, UNDETERMINED: ATTENTION },
  healthState: { PASS: GOOD, FAIL: BAD, UNKNOWN: ATTENTION },
  eligibility: { ELIGIBLE: GOOD, INELIGIBLE: BAD, UNKNOWN: ATTENTION },
  opportunity: {
    DESCRIPTION_NOT_RUSSIAN: ATTENTION,
    KIZ_MARKING_UNDECLARED: ATTENTION,
    SOURCE_STRATIFICATION_MISSING: ATTENTION,
    NOT_SELLABLE_AT_LAST_OBSERVATION: ATTENTION,
    FEEDBACK_THEMES_PRESENT: ATTENTION,
  },
  qualificationReason: Object.fromEntries(
    Object.keys(LISTING_CODES.qualificationReason).map((code) => [code, ATTENTION]),
  ),
  bindingGap: Object.fromEntries(Object.keys(LISTING_CODES.bindingGap).map((code) => [code, BAD])),
  decisionBlocker: Object.fromEntries(
    Object.keys(LISTING_CODES.decisionBlocker).map((code) => [code, BAD]),
  ),
  gateReason: Object.fromEntries(Object.keys(LISTING_CODES.gateReason).map((code) => [code, BAD])),
  errorCode: Object.fromEntries(Object.keys(LISTING_CODES.errorCode).map((code) => [code, BAD])),
  commandState: {
    PENDING: ATTENTION,
    LEASED: MOVING,
    EXECUTING: MOVING,
    PLATFORM_PENDING: MOVING,
    READBACK_PENDING: MOVING,
    READBACK_MATCHED: GOOD,
    RETRY_WAIT: ATTENTION,
    UNKNOWN_REQUIRES_READBACK: ATTENTION,
    READBACK_MISMATCH: BAD,
    LATER_CHANGE_OR_MISMATCH_INVESTIGATION: BAD,
    MANUAL_RESOLUTION: ATTENTION,
    FAILED_FINAL: BAD,
    TERMINATED_WITHOUT_PROVIDER_CALL: 'default',
    COMPENSATION_PENDING: ATTENTION,
    COMPENSATED: GOOD,
    COMPENSATION_FAILED: BAD,
  },
  readbackMatch: {
    MATCHES_TARGET: GOOD,
    MATCHES_PRIOR: ATTENTION,
    DIFFERENT: BAD,
    UNREADABLE: BAD,
  },
  attemptOutcome: {
    IN_FLIGHT: MOVING,
    ACCEPTED: GOOD,
    REJECTED: BAD,
    RETRIABLE_ERROR: ATTENTION,
    TIMEOUT: ATTENTION,
    UNKNOWN_STATE: ATTENTION,
  },
  packetState: {
    ISSUED: ATTENTION,
    REPORTED: MOVING,
    VERIFIED: GOOD,
    EXPIRED: BAD,
    WITHDRAWN: 'default',
  },
  reportState: { APPLIED: GOOD, NOT_APPLIED: BAD, PARTIAL: ATTENTION },
  managementMatch: {
    MATCHED_TARGET: GOOD,
    MATCHED_PRIOR: ATTENTION,
    DIFFERENT: BAD,
    UNKNOWN: ATTENTION,
  },
  displayState: { DISPLAYED: GOOD, NOT_DISPLAYED: BAD, UNKNOWN: ATTENTION },
  engagementState: { ACTIVE: MOVING, EXITING: ATTENTION, STOPPED: ATTENTION, CLEARED: GOOD },
  occupationState: { ACQUIRED: MOVING, ACTUAL: GOOD, UNKNOWN: ATTENTION, RELEASED: 'default' },
  containmentState: { ACTIVE: BAD, REENABLED: GOOD },
  associationState: {
    OPEN: ATTENTION,
    LINKED: GOOD,
    UNDER_VERIFICATION: MOVING,
    CLOSED: 'default',
  },
  queueState: { QUEUED: ATTENTION, RUNNING: MOVING, FINISHED: GOOD, FAILED: BAD },
  simulationState: { COMPUTED: GOOD, NO_SOLUTION: BAD, UNDETERMINED: ATTENTION },
  candidateState: { OPEN: ATTENTION, SELECTED: GOOD, DISMISSED: 'default' },
  reviewVerdict: { ATTESTED: GOOD, RETURNED: ATTENTION },
  bindingState: { BOUND: GOOD, INAPPLICABLE: BAD },
  descriptionExecutionState: { MANAGEMENT_VERIFIED: GOOD, NATIVE_COMPLETION_UNPROVEN: ATTENTION },
  descriptionExecutionGap: Object.fromEntries(
    Object.keys(LISTING_CODES.descriptionExecutionGap).map((code) => [code, ATTENTION]),
  ),
  affectedSetState: { COMPLETE: GOOD, INCOMPLETE: ATTENTION, CONFLICTED: BAD },
  clockState: {
    IN_COVERAGE: MOVING,
    OUT_OF_COVERAGE: 'default',
    SLO_UNRESOLVED: ATTENTION,
    COVERAGE_UNRESOLVED: ATTENTION,
    CONTINUOUS_RISK: BAD,
  },
  deferralState: {
    ACTIVE: MOVING,
    REVIEW_DUE: ATTENTION,
    EXPIRED: 'default',
    INVALIDATED: 'default',
  },
  dependencyHoldState: {
    ACTIVE: MOVING,
    RESUMED: GOOD,
    EXPIRED: 'default',
    INVALIDATED: ATTENTION,
  },
  taskLane: { NECESSARY_RISK: BAD, ACTION: ATTENTION, QUALIFIED_OPPORTUNITY: MOVING },
  taskState: { OPEN: ATTENTION, ASSIGNED: MOVING, IN_PROGRESS: MOVING, DONE: GOOD },
  batchState: { OPEN: MOVING, CLOSED: 'default' },
  membershipState: { ACTIVE: GOOD, REMOVED: 'default' },
  applicabilityState: {
    APPLICABLE_FOR_REVIEW: GOOD,
    SOURCE_REVISED: ATTENTION,
    TARGET_SCOPE_CHANGED: ATTENTION,
    FAILURE_RETAINED: BAD,
    UNDETERMINED_RETAINED: ATTENTION,
  },
  aiInvocationState: {
    PREPARED: MOVING,
    DISPATCHED: MOVING,
    SUCCEEDED: GOOD,
    OUTPUT_REJECTED: BAD,
    PARTIAL_OUTPUT_REJECTED: ATTENTION,
    PROVIDER_FAILED: BAD,
    PROVIDER_OUTCOME_UNKNOWN: ATTENTION,
    REFUSED: BAD,
  },
  evidenceState: {
    CURRENT: GOOD,
    UNCHANGED_DEPENDENCIES: GOOD,
    MEANING_REVIEW_UNQUALIFIED: BAD,
    AFFECTED_SET_CHANGED_OR_UNQUALIFIED: BAD,
    CURRENT_EXPOSURE_UNRESOLVED: ATTENTION,
    EXPOSURE_CLASSIFICATION_CHANGED: BAD,
    CALIBRATION_UNRESOLVED: BAD,
    CALIBRATION_CONFLICTED: BAD,
  },
  feedbackQualification: { CONFIRMED: GOOD, UNCERTAIN: ATTENTION, CONFLICTED: BAD },
  participationState: { PARTICIPATING: MOVING, UNKNOWN: ATTENTION },
  nativeCoverage: { COMPLETE: GOOD, PARTIAL: ATTENTION, UNKNOWN: ATTENTION },
  meaningAnswer: { UNKNOWN: ATTENTION },
};

/** A backend code, shown as a Chinese tag with the raw code on hover. */
export function Code({
  family,
  code,
}: {
  readonly family: ListingCodeFamily;
  readonly code: string | null | undefined;
}): React.JSX.Element {
  const colors = CODE_COLORS[family];
  return (
    <span data-family={family} data-code={code ?? ''}>
      <CodeTag
        labels={LISTING_CODES[family]}
        code={code}
        {...(colors === undefined ? {} : { colors })}
      />
    </span>
  );
}

/** Several codes of one family, or a dash when there are none. */
export function Codes({
  family,
  codes,
}: {
  readonly family: ListingCodeFamily;
  readonly codes: readonly string[];
}): React.JSX.Element {
  if (codes.length === 0) {
    return <Typography.Text type="secondary">—</Typography.Text>;
  }
  return (
    <Flex wrap gap={4}>
      {codes.map((code) => (
        <Code key={code} family={family} code={code} />
      ))}
    </Flex>
  );
}

/** Plain Chinese label of one code, for places that take text (select options, messages). */
export function codeText(family: ListingCodeFamily, code: string): string {
  const labels: Readonly<Record<string, string>> = LISTING_CODES[family];
  return Object.hasOwn(labels, code) ? (labels[code] ?? code) : `${code}（未识别）`;
}

/** Select options for a list of codes of one family. */
export function codeOptions(
  family: ListingCodeFamily,
  codes: readonly string[],
): { value: string; label: string }[] {
  return codes.map((code) => ({ value: code, label: codeText(family, code) }));
}

const LOCAL_MESSAGES: ReadonlySet<string> = new Set(Object.values(LISTING_TEXT));

/**
 * One failure, said in Chinese; the backend's own detail text is never appended.
 *
 * A form that refuses to send an incomplete entry reports it as a local refusal
 * whose detail is one of this console's own Chinese sentences; that sentence is
 * what the operator needs, so it is shown instead of a bare HTTP status.
 */
export function ListingProblem({
  failure,
}: {
  readonly failure: ConsoleFailure;
}): React.JSX.Element {
  if (
    failure.kind === 'refused' &&
    failure.code === undefined &&
    failure.correlationId === undefined &&
    LOCAL_MESSAGES.has(failure.detail)
  ) {
    return (
      <div role="alert" data-failure={failure.kind} data-state="local-validation">
        <Alert type="warning" showIcon title={failure.detail} />
      </div>
    );
  }
  return (
    <div role="alert" data-failure={failure.kind}>
      <FailureAlert failure={failure} />
    </div>
  );
}

/** A yes / no / undeclared answer. */
export function YesNo({ value }: { readonly value: boolean | undefined }): React.JSX.Element {
  if (value === undefined) {
    return <Tag data-state="undeclared">{t('undeclared')}</Tag>;
  }
  return (
    <Tag color={value ? 'blue' : 'default'} data-state={value ? 'yes' : 'no'}>
      {value ? t('yes') : t('no')}
    </Tag>
  );
}

/** An instant in store time; the moment is the fact, the format is presentation. */
export function When({ value }: { readonly value: string | undefined }): React.JSX.Element {
  return <DateTime value={value} />;
}

/** A raw identifier, digest or reference: small, secondary and copyable, never the headline. */
export function IdText({
  value,
  label,
}: {
  readonly value: string | null | undefined;
  readonly label?: ReactNode;
}): React.JSX.Element {
  if (value === null || value === undefined || value === '') {
    return <Typography.Text type="secondary">—</Typography.Text>;
  }
  return (
    <Typography.Text type="secondary" style={{ fontSize: 12, wordBreak: 'break-all' }}>
      {label === undefined ? null : <>{label}：</>}
      <Typography.Text type="secondary" style={{ fontSize: 12 }} copyable code>
        {value}
      </Typography.Text>
    </Typography.Text>
  );
}

/** Russian product text (data), kept as-is and marked as Russian. */
export function RussianText({ value }: { readonly value: string }): React.JSX.Element {
  return (
    <Typography.Paragraph
      lang="ru"
      style={{
        whiteSpace: 'pre-wrap',
        margin: 0,
        padding: 12,
        background: 'rgba(0, 0, 0, 0.02)',
        border: '1px solid rgba(0, 0, 0, 0.06)',
        borderRadius: 6,
      }}
    >
      {value}
    </Typography.Paragraph>
  );
}

/** A one-line operator hint under a heading. */
export function Hint({ children }: { readonly children: ReactNode }): React.JSX.Element {
  return (
    <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
      {children}
    </Typography.Paragraph>
  );
}

/** A small sub-heading inside a section card. */
export function SubTitle({ children }: { readonly children: ReactNode }): React.JSX.Element {
  return (
    <Typography.Title level={5} style={{ marginTop: 0 }}>
      {children}
    </Typography.Title>
  );
}

/** Details in the shared bordered style. */
export function Details({
  items,
  column = { xs: 1, md: 2, xl: 3 },
}: {
  readonly items: DescriptionsProps['items'];
  readonly column?: DescriptionsProps['column'];
}): React.JSX.Element {
  return (
    <Descriptions
      bordered
      size="small"
      column={column}
      {...(items === undefined ? {} : { items })}
    />
  );
}

/** Vertical stack of blocks with the shared spacing. */
export function Stack({ children }: { readonly children: ReactNode }): React.JSX.Element {
  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      {children}
    </Space>
  );
}

/**
 * A store-time instant picker bound to an ISO string.
 *
 * The digits the operator picks mean Moscow time whatever zone the browser is
 * in; the value handed back is the UTC instant, or an empty string when cleared.
 */
export function InstantPicker({
  value,
  onChange,
  disabled = false,
  ariaLabel,
}: {
  readonly value: string;
  readonly onChange: (iso: string) => void;
  readonly disabled?: boolean;
  readonly ariaLabel?: string;
}): React.JSX.Element {
  return (
    <DatePicker
      showTime
      style={{ width: '100%' }}
      disabled={disabled}
      value={value === '' ? null : (toStoreDayjs(value) ?? null)}
      placeholder={t('pickStoreTime')}
      {...(ariaLabel === undefined ? {} : { 'aria-label': ariaLabel })}
      onChange={(picked: Dayjs | null) => {
        onChange(picked === null ? '' : storeLocalToIso(picked));
      }}
    />
  );
}

/** Rules message for a required field. */
export function requiredRule(label: string): { required: true; message: string } {
  return { required: true, message: `请填写${label}` };
}
