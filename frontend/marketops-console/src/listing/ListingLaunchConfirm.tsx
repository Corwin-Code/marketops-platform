import { CheckCircleFilled, CloseCircleFilled } from '@ant-design/icons';
import { Flex, Space, Typography } from 'antd';
import { useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type {
  Allowance,
  LaunchAnswer,
  ListingAction,
  ListingDetail,
  MeaningReviewBasis,
} from '../api/listingConversion';
import {
  fetchListingDetail,
  fetchMeaningReviewBasis,
  launchAction,
  previewAllowance,
} from '../api/listingConversion';
import { actions } from '../i18n/zh/common';
import { detailText, launchText } from '../i18n/zh/listingActions';
import { FailureAlert, LoadingState, WriteConfirmModal, failureMessage } from '../ui';
import type { WriteGuard } from '../ui';
import { AllowanceTable } from './ListingActionEvidence';
import { Code, Codes, Details, IdText } from './ListingCommon';

/** One read the confirmation depends on. */
type Read<T> =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ok'; readonly value: T }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

const LOADING = { kind: 'loading' } as const;

function settled<T>(
  outcome: { ok: true; value: T } | { ok: false; failure: ConsoleFailure },
): Read<T> {
  return outcome.ok
    ? { kind: 'ok', value: outcome.value }
    : { kind: 'failed', failure: outcome.failure };
}

/** One line of the rule check, with its verdict. */
function Check({
  passed,
  label,
  detail,
}: {
  readonly passed: boolean;
  readonly label: ReactNode;
  readonly detail?: ReactNode;
}): React.JSX.Element {
  return (
    <Flex gap={8} align="flex-start" data-passed={String(passed)}>
      <Typography.Text type={passed ? 'success' : 'danger'}>
        {passed ? <CheckCircleFilled /> : <CloseCircleFilled />}
      </Typography.Text>
      <Flex vertical gap={2}>
        <Typography.Text>
          {label}
          <Typography.Text type="secondary">
            {' · '}
            {passed ? launchText.checkPassed : launchText.checkFailed}
          </Typography.Text>
        </Typography.Text>
        {detail}
      </Flex>
    </Flex>
  );
}

/** Russian text folded to a few lines, or a stated unknown. */
function Excerpt({
  value,
  unknown,
}: {
  readonly value: string | undefined;
  readonly unknown: string;
}): React.JSX.Element {
  if (value === undefined) {
    return <Typography.Text type="secondary">{unknown}</Typography.Text>;
  }
  return (
    <Typography.Paragraph
      lang="ru"
      style={{ margin: 0, whiteSpace: 'pre-wrap' }}
      ellipsis={{
        rows: 3,
        expandable: 'collapsible',
        symbol: (expanded: boolean) => (expanded ? actions.collapse : launchText.showFullText),
      }}
    >
      {value}
    </Typography.Paragraph>
  );
}

/**
 * Launching an approved action: the one step that occupies allowance and, on
 * the API path, creates the description write command.
 *
 * On opening it reads, together, the allowance projection, the listing's
 * current health and the frozen current and target text, and states what will
 * change. Confirmation is refused until every allowance axis is resolved and
 * sufficient, no binding gap remains, and the listing's necessary conditions
 * pass (a description correction is exempt), mirroring the backend's own
 * launch checks. The reason goes into the audit trail.
 */
export function LaunchConfirm({
  context,
  action,
  onLaunched,
}: {
  readonly context: ConsoleRequest;
  readonly action: ListingAction;
  readonly onLaunched: (answer: LaunchAnswer) => void;
}): React.JSX.Element {
  const [allowance, setAllowance] = useState<Read<Allowance>>(LOADING);
  const [detail, setDetail] = useState<Read<ListingDetail>>(LOADING);
  const [basis, setBasis] = useState<Read<MeaningReviewBasis>>(LOADING);
  const epoch = useRef(0);
  const api = action.executionPath === 'API';
  const promotion = action.actionKind === 'LISTING_PROMOTION_ACTION';
  const correction = action.purposeCode === 'DESCRIPTION_CORRECTION';

  const load = (): void => {
    const ticket = ++epoch.current;
    setAllowance(LOADING);
    setDetail(LOADING);
    setBasis(LOADING);
    void previewAllowance(context, action.id).then((outcome) => {
      if (ticket === epoch.current) setAllowance(settled(outcome));
    });
    void fetchListingDetail(context, action.platformListingId).then((outcome) => {
      if (ticket === epoch.current) setDetail(settled(outcome));
    });
    void fetchMeaningReviewBasis(context, action.id).then((outcome) => {
      if (ticket === epoch.current) setBasis(settled(outcome));
    });
  };

  const allowanceValue = allowance.kind === 'ok' ? allowance.value : undefined;
  const resolved =
    allowanceValue !== undefined && allowanceValue.resolved && allowanceValue.gaps.length === 0;
  const sufficient =
    allowanceValue !== undefined &&
    allowanceValue.axes.length > 0 &&
    allowanceValue.axes.every((axis) => axis.sufficient);
  const bindingClear = action.bindingGaps.length === 0;
  const necessaryState =
    detail.kind === 'ok' ? (detail.value.health?.necessaryState ?? 'UNKNOWN') : undefined;
  const healthPassed = correction || necessaryState === 'PASS';

  // What will change must be on screen before the write can be authorised:
  // the text or promotion read counts as much as the allowance and health.
  const blockedReason =
    allowance.kind === 'loading' || detail.kind === 'loading' || basis.kind === 'loading'
      ? launchText.loading
      : allowance.kind === 'failed'
        ? launchText.allowanceFailed(failureMessage(allowance.failure))
        : undefined;

  const guard: WriteGuard | undefined =
    allowance.kind === 'ok' && detail.kind !== 'loading'
      ? {
          passed: resolved && sufficient && bindingClear && healthPassed,
          content: (
            <Flex vertical gap={6}>
              <Check passed={resolved} label={launchText.checkAllowanceResolved} />
              <Check passed={sufficient} label={launchText.checkAllowanceSufficient} />
              <Check
                passed={bindingClear}
                label={launchText.checkBinding}
                {...(bindingClear
                  ? {}
                  : { detail: <Codes family="bindingGap" codes={action.bindingGaps} /> })}
              />
              <Check
                passed={healthPassed}
                label={correction ? launchText.checkHealthCorrection : launchText.checkHealth}
                detail={
                  detail.kind === 'failed' ? (
                    <Typography.Text type="secondary">
                      {launchText.checkHealthUnknown}
                    </Typography.Text>
                  ) : (
                    <Code family="healthState" code={necessaryState} />
                  )
                }
              />
            </Flex>
          ),
        }
      : undefined;

  const impact = (
    <Flex vertical gap={12}>
      <Details
        column={1}
        items={[
          {
            key: 'path',
            label: launchText.impactPath,
            children: (
              <Space size={6} wrap>
                <Code family="executionPath" code={action.executionPath} />
                <Typography.Text>
                  {api ? launchText.pathApi : launchText.pathManual}
                </Typography.Text>
              </Space>
            ),
          },
          {
            key: 'affected',
            label: launchText.impactAffected,
            children: (
              <Space size={6} wrap>
                <Code family="affectedSetState" code={action.affectedSetState} />
                <Typography.Text type="secondary">
                  {detailText.variants(action.affectedVariantCount)}
                </Typography.Text>
              </Space>
            ),
          },
          ...(promotion
            ? [
                {
                  key: 'promotion',
                  label: launchText.impactPromotion,
                  children: (
                    <Flex vertical gap={2}>
                      <Typography.Text>
                        {launchText.impactPromotionKey}：
                        {basis.kind === 'loading'
                          ? launchText.impactLoading
                          : basis.kind === 'ok' && basis.value.promotionTerms !== undefined
                            ? basis.value.promotionTerms.nativePromotionKey
                            : launchText.impactPromotionKeyUnknown}
                      </Typography.Text>
                      <IdText
                        label={launchText.impactTermsDigest}
                        value={action.promotionTermsDigest}
                      />
                    </Flex>
                  ),
                },
              ]
            : [
                {
                  key: 'text',
                  label: launchText.impactText,
                  children:
                    basis.kind === 'loading' ? (
                      <LoadingState rows={2} />
                    ) : basis.kind === 'failed' ? (
                      <Flex vertical gap={4}>
                        <Typography.Text type="secondary">
                          {launchText.impactTextUnknown}
                        </Typography.Text>
                        <Excerpt
                          value={action.targetText}
                          unknown={launchText.impactTargetUnknown}
                        />
                      </Flex>
                    ) : (
                      <Flex vertical gap={6}>
                        <Typography.Text type="secondary">
                          {launchText.impactCurrent}
                        </Typography.Text>
                        <Excerpt
                          value={basis.value.currentText}
                          unknown={launchText.impactCurrentUnknown}
                        />
                        <Typography.Text type="secondary">
                          {launchText.impactTarget}
                        </Typography.Text>
                        <Excerpt
                          value={basis.value.targetText ?? action.targetText}
                          unknown={launchText.impactTargetUnknown}
                        />
                      </Flex>
                    ),
                },
              ]),
        ]}
      />
      <div>
        <Typography.Text type="secondary">{launchText.impactAllowance}</Typography.Text>
        <div style={{ marginTop: 4 }}>
          {allowance.kind === 'loading' ? (
            <LoadingState rows={2} />
          ) : allowance.kind === 'failed' ? (
            <FailureAlert failure={allowance.failure} />
          ) : (
            <AllowanceTable allowance={allowance.value} />
          )}
        </div>
      </div>
    </Flex>
  );

  return (
    <WriteConfirmModal
      trigger={{ label: launchText.launch, type: 'primary' }}
      title={launchText.title}
      impact={impact}
      {...(guard === undefined ? {} : { guard })}
      {...(blockedReason === undefined ? {} : { blockedReason })}
      consequence={api ? launchText.consequenceApi : launchText.consequenceManual}
      confirmText={api ? launchText.confirmApi : launchText.confirmManual}
      reasonLabel={launchText.reasonLabel}
      onOpen={load}
      onConfirm={async (reason) => {
        const outcome = await launchAction(context, action.id, reason);
        if (!outcome.ok) return outcome.failure;
        onLaunched(outcome.value);
        return undefined;
      }}
    />
  );
}
