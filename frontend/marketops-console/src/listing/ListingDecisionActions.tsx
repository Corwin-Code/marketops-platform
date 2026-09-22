import { Alert, App, Button, Checkbox, Flex, Form, Input, Space, Table, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { ConsoleFailure, ConsoleOutcome, ConsoleRequest } from '../api/console';
import { decide } from '../api/console';
import type { LaunchAnswer, ListingAction, MeaningReviewBasis } from '../api/listingConversion';
import { cancelAction, fetchMeaningReviewBasis, reviewAction } from '../api/listingConversion';
import { dialog } from '../i18n/zh/common';
import { decisionText, launchText } from '../i18n/zh/listingActions';
import { t } from '../i18n/zh/listing';
import { ActionModal, DetailDrawer, SectionCard, SectionCollapse } from '../ui';
import type { SubmitOutcome } from '../ui';
import { Code, Codes, Details, IdText, ListingProblem } from './ListingCommon';
import { LaunchConfirm } from './ListingLaunchConfirm';
import { MeaningBasisView, MeaningReviewDrawer } from './ListingMeaningReview';

/** States in which an action can still be cancelled (mirrors the backend). */
const CANCELLABLE: readonly string[] = ['DRAFT', 'REVIEWED', 'APPROVED', 'APPROVED_NOT_LAUNCHABLE'];
/** States in which an action can be launched (mirrors the backend). */
const LAUNCHABLE: readonly string[] = ['APPROVED', 'APPROVED_NOT_LAUNCHABLE'];

interface ReasonValues {
  readonly reason?: string;
}

interface ApprovalValues {
  readonly checked?: boolean;
  readonly reason?: string;
}

/** The approval material as the card knows it. */
type MaterialState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ok'; readonly basis: MeaningReviewBasis }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/** A required reason field, empty on every opening. */
function ReasonField({ label }: { readonly label: ReactNode }): React.JSX.Element {
  return (
    <Form.Item
      name="reason"
      label={label}
      rules={[{ required: true, whitespace: true, message: dialog.reasonRequired }]}
    >
      <Input.TextArea
        rows={3}
        maxLength={500}
        showCount
        placeholder={dialog.reasonPlaceholder}
        autoFocus
      />
    </Form.Item>
  );
}

/** Why approval is refused regardless of the fields, or nothing. */
function approvalBlocked(material: MaterialState): string | undefined {
  if (material.kind !== 'ok') return t('approveNeedsMaterial');
  if (material.basis.reviewEvidence?.verdict !== 'ATTESTED') return t('approveNeedsAttestation');
  if (material.basis.materialityEvidence.state !== 'CURRENT') return t('approveNeedsMateriality');
  return undefined;
}

/** The three evidence states approval rests on, each as a tag. */
function EvidenceStates({ basis }: { readonly basis: MeaningReviewBasis }): React.JSX.Element {
  const missing = (
    <Typography.Text type="secondary">{decisionText.evidenceMissing}</Typography.Text>
  );
  const calibration = basis.calibrationEvidence.state;
  const materiality = basis.materialityEvidence.state;
  return (
    <Details
      column={{ xs: 1, md: 3 }}
      items={[
        {
          key: 'attestation',
          label: decisionText.evidenceAttestation,
          children:
            basis.reviewEvidence === undefined ? (
              missing
            ) : (
              <Code family="reviewVerdict" code={basis.reviewEvidence.verdict} />
            ),
        },
        {
          key: 'materiality',
          label: decisionText.evidenceMateriality,
          children:
            materiality === undefined ? (
              missing
            ) : (
              <Code family="evidenceState" code={materiality} />
            ),
        },
        {
          key: 'calibration',
          label: decisionText.evidenceCalibration,
          children:
            calibration === undefined ? (
              missing
            ) : (
              <Code family="evidenceState" code={calibration} />
            ),
        },
      ]}
    />
  );
}

/** What an approval covers: the path, the target and the evidence it rests on. */
function ApprovalSummary({
  action,
  basis,
}: {
  readonly action: ListingAction;
  readonly basis: MeaningReviewBasis | undefined;
}): React.JSX.Element {
  const promotion = action.actionKind === 'LISTING_PROMOTION_ACTION';
  const target = basis?.targetText ?? action.targetText;
  return (
    <Flex vertical gap={8}>
      <Details
        column={1}
        items={[
          {
            key: 'path',
            label: decisionText.summaryPath,
            children: (
              <Space size={6} wrap>
                <Code family="executionPath" code={action.executionPath} />
                <Typography.Text>
                  {action.executionPath === 'API' ? launchText.pathApi : launchText.pathManual}
                </Typography.Text>
              </Space>
            ),
          },
          {
            key: 'target',
            label: decisionText.summaryTarget,
            children: promotion ? (
              <Flex vertical gap={2}>
                {basis?.promotionTerms !== undefined && (
                  <Typography.Text>
                    {decisionText.summaryPromotion(basis.promotionTerms.nativePromotionKey)}
                  </Typography.Text>
                )}
                <IdText
                  label={decisionText.summaryTermsDigest}
                  value={action.promotionTermsDigest}
                />
              </Flex>
            ) : target === undefined ? (
              <Typography.Text type="secondary">{decisionText.targetUnknown}</Typography.Text>
            ) : (
              <Typography.Paragraph
                lang="ru"
                style={{ margin: 0, whiteSpace: 'pre-wrap' }}
                ellipsis={{ rows: 3, expandable: true, symbol: launchText.showFullText }}
              >
                {target}
              </Typography.Paragraph>
            ),
          },
        ]}
      />
      {basis !== undefined && <EvidenceStates basis={basis} />}
    </Flex>
  );
}

/**
 * The decision the action's state allows, one primary button per state.
 *
 * Review, return, approval, rejection, launch and cancellation each ask for
 * their own reason in their own dialog, so a reason written for one is never
 * sent with another. Every refusal stays in the dialog that caused it.
 */
export function DecisionCard({
  context,
  action,
  onChanged,
}: {
  readonly context: ConsoleRequest;
  readonly action: ListingAction;
  readonly onChanged: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [answer, setAnswer] = useState<LaunchAnswer | undefined>(undefined);
  const [material, setMaterial] = useState<MaterialState>({ kind: 'loading' });
  const [materialOpen, setMaterialOpen] = useState(false);
  const [materialRequest, setMaterialRequest] = useState(0);
  const epoch = useRef(0);
  const reviewed = action.state === 'REVIEWED';
  const actionId = action.id;
  const version = action.version;

  // Approval material is read as soon as an action awaits approval.
  useEffect(() => {
    if (!reviewed) return;
    const ticket = ++epoch.current;
    setMaterial({ kind: 'loading' });
    void fetchMeaningReviewBasis(context, actionId).then((outcome) => {
      if (ticket !== epoch.current) return;
      setMaterial(
        outcome.ok
          ? { kind: 'ok', basis: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
  }, [context, actionId, reviewed, version, materialRequest]);

  const settle = (outcome: ConsoleOutcome<unknown>, success: string): SubmitOutcome => {
    if (!outcome.ok) return outcome.failure;
    void message.success(success);
    onChanged();
    return undefined;
  };

  const blocked = reviewed ? approvalBlocked(material) : undefined;
  const materialBasis = material.kind === 'ok' ? material.basis : undefined;

  const buttons: ReactNode[] = [];
  if (action.state === 'DRAFT') {
    buttons.push(
      <MeaningReviewDrawer
        key="review"
        context={context}
        actionId={actionId}
        onReviewed={onChanged}
      />,
      <ActionModal<ReasonValues>
        key="return"
        trigger={{ label: decisionText.returnToAuthor }}
        title={decisionText.returnTitle}
        consequence={decisionText.returnConsequence}
        onSubmit={async (values) =>
          settle(
            await reviewAction(context, actionId, 'RETURNED', (values.reason ?? '').trim()),
            decisionText.returned,
          )
        }
      >
        <ReasonField label={decisionText.returnReason} />
      </ActionModal>,
    );
  }
  if (reviewed) {
    buttons.push(
      <ActionModal<ApprovalValues>
        key="approve"
        trigger={{
          label: decisionText.approve,
          type: 'primary',
          disabled: material.kind === 'loading' || blocked !== undefined,
          disabledReason: material.kind === 'loading' ? decisionText.approvalLoading : blocked,
          reasonPlacement: 'inline',
        }}
        title={decisionText.approveTitle}
        consequence={decisionText.approveConsequence}
        summary={<ApprovalSummary action={action} basis={materialBasis} />}
        {...(blocked === undefined ? {} : { blockedReason: blocked })}
        width={640}
        onSubmit={async (values) =>
          settle(
            await decide(
              context,
              action.recommendationId,
              'approval',
              (values.reason ?? '').trim(),
              action.recommendationVersion,
            ),
            decisionText.approved,
          )
        }
      >
        <Form.Item
          name="checked"
          valuePropName="checked"
          rules={[
            {
              validator: (_, value: boolean | undefined) =>
                value === true
                  ? Promise.resolve()
                  : Promise.reject(new Error(decisionText.approveCheckedRequired)),
            },
          ]}
        >
          <Checkbox>{decisionText.approveChecked}</Checkbox>
        </Form.Item>
        <ReasonField label={decisionText.approveReason} />
      </ActionModal>,
      <ActionModal<ReasonValues>
        key="reject"
        trigger={{ label: decisionText.reject, danger: true }}
        title={decisionText.rejectTitle}
        consequence={decisionText.rejectConsequence}
        okText={decisionText.reject}
        danger
        onSubmit={async (values) =>
          settle(
            await decide(
              context,
              action.recommendationId,
              'rejection',
              (values.reason ?? '').trim(),
              action.recommendationVersion,
            ),
            decisionText.rejected,
          )
        }
      >
        <ReasonField label={decisionText.rejectReason} />
      </ActionModal>,
    );
  }
  if (LAUNCHABLE.includes(action.state)) {
    buttons.push(
      <LaunchConfirm
        key="launch"
        context={context}
        action={action}
        onLaunched={(launched) => {
          setAnswer(launched);
          if (launched.launched) void message.success(launchText.launched);
          onChanged();
        }}
      />,
    );
  }
  if (CANCELLABLE.includes(action.state)) {
    buttons.push(
      <ActionModal<ReasonValues>
        key="cancel"
        trigger={{ label: decisionText.cancel, danger: true }}
        title={decisionText.cancelTitle}
        consequence={decisionText.cancelConsequence}
        okText={decisionText.cancel}
        danger
        onSubmit={async (values) =>
          settle(
            await cancelAction(context, actionId, (values.reason ?? '').trim()),
            decisionText.cancelled,
          )
        }
      >
        <ReasonField label={decisionText.cancelReason} />
      </ActionModal>,
    );
  }

  const unknownOccupation = action.occupations.some((occupation) => occupation.state === 'UNKNOWN');

  return (
    <SectionCard title={decisionText.title} state={action.state}>
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        {reviewed && (
          <section aria-label={decisionText.materialTitle}>
            {material.kind === 'loading' && (
              <Typography.Text type="secondary">{decisionText.approvalLoading}</Typography.Text>
            )}
            {material.kind === 'failed' && (
              <Flex vertical gap={8}>
                <Typography.Text type="danger">{decisionText.approvalUnavailable}</Typography.Text>
                <ListingProblem failure={material.failure} />
              </Flex>
            )}
            {material.kind === 'ok' && (
              <Flex vertical gap={8}>
                <EvidenceStates basis={material.basis} />
                <div>
                  <Button
                    type="link"
                    style={{ padding: 0 }}
                    onClick={() => {
                      setMaterialOpen(true);
                    }}
                  >
                    {decisionText.viewMaterial}
                  </Button>
                </div>
              </Flex>
            )}
          </section>
        )}
        {buttons.length === 0 ? (
          <Typography.Text type="secondary">{decisionText.noDecision}</Typography.Text>
        ) : (
          <Flex gap={8} wrap align="flex-start" className="decision-actions">
            {buttons}
          </Flex>
        )}
        {answer !== undefined && !answer.launched && (
          <div role="status" data-launched="false">
            <Alert
              type="warning"
              showIcon
              title={launchText.notLaunched}
              description={<Codes family="allowanceAxis" codes={answer.insufficientAxes} />}
            />
          </div>
        )}
        {action.occupations.length > 0 && (
          <SectionCollapse
            size="small"
            items={[
              {
                key: 'occupations',
                title: decisionText.occupations,
                summary: decisionText.occupationCount(action.occupations.length),
                flags: unknownOccupation
                  ? [{ key: 'unknown', label: decisionText.occupationUnknown }]
                  : [],
                defaultOpen: unknownOccupation,
                children: (
                  <Table
                    size="small"
                    rowKey="id"
                    pagination={false}
                    dataSource={[...action.occupations]}
                    columns={[
                      {
                        key: 'axis',
                        title: t('axis'),
                        render: (_, occupation) => (
                          <Code family="allowanceAxis" code={occupation.axisCode} />
                        ),
                      },
                      {
                        key: 'occupied',
                        title: t('occupied'),
                        align: 'right',
                        dataIndex: 'occupiedValue',
                      },
                      {
                        key: 'state',
                        title: t('state'),
                        render: (_, occupation) => (
                          <Code family="occupationState" code={occupation.state} />
                        ),
                      },
                    ]}
                  />
                ),
              },
            ]}
          />
        )}
      </Space>
      <DetailDrawer
        open={materialOpen}
        onClose={() => {
          setMaterialOpen(false);
        }}
        title={decisionText.materialTitle}
        loading={material.kind === 'loading'}
        failure={material.kind === 'failed' ? material.failure : undefined}
        onRetry={() => {
          setMaterialRequest((value) => value + 1);
        }}
      >
        {material.kind === 'ok' && <MeaningBasisView basis={material.basis} approvalMode />}
      </DetailDrawer>
    </SectionCard>
  );
}
