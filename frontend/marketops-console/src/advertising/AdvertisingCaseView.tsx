import { Alert, Button, Descriptions, Flex, Space, Table, Tabs, Typography } from 'antd';
import type { DescriptionsProps, TableColumnsType } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { fetchAdvertisingCase } from '../api/console';
import type { AdvertisingCase, AdvertisingRankFactor } from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { formatDecimal } from '../format';
import { actions } from '../i18n';
import {
  AD_CONFIDENCE_COLORS,
  AD_CONFIDENCE_LABELS,
  AFFECTED_SET_COLORS,
  AFFECTED_SET_LABELS,
  BIDDING_MODE_LABELS,
  BID_UNIT_LABELS,
  CAUSE_LABELS,
  CONTROL_GRANULARITY_COLORS,
  CONTROL_GRANULARITY_LABELS,
  DISCLOSURE_COLORS,
  DISCLOSURE_LABELS,
  LANE_COLORS,
  LANE_LABELS,
  OBJECT_KIND_LABELS,
  PLATFORM_LABELS,
  PROFILE_VERIFICATION_COLORS,
  PROFILE_VERIFICATION_LABELS,
  PROTECTION_TIER_COLORS,
  PROTECTION_TIER_LABELS,
  RANK_FACTOR_LABELS,
  RELATIONSHIP_KIND_LABELS,
  ROLE_LABELS,
  SOURCE_MATURITY_COLORS,
  SOURCE_MATURITY_LABELS,
} from '../i18n/zh/advertising';
import { CodeTag } from '../ui/CodeTag';
import { DateTime } from '../ui/DateTime';
import { EmptyState } from '../ui/EmptyState';
import { FailureAlert } from '../ui/FailureAlert';
import { LoadingState } from '../ui/LoadingState';
import { SectionCard } from '../ui/SectionCard';
import { TechnicalDetails } from '../ui/TechnicalDetails';
import { useSearchParam } from '../ui/useSearchParam';
import { AdvertisingWorkflow } from './AdvertisingWorkflow';
import { AdvertisingStopControls, STOP_ACTION_CODES } from './AdvertisingContainmentControls';
import { AdvertisingManualShadow } from './AdvertisingManualShadow';
import { EvidenceChip } from './EvidenceChip';
import { AdvertisingEvidenceDetails } from './AdvertisingEvidenceDetails';
import { AbsentValue, IdText, MeasureValue, ReasonTags, dataAttributes } from './shared';
import type { AdRow } from './shared';

/** The case's tabs, in their fixed order; the first is the default. */
const CASE_TABS = ['evidence', 'manual', 'workflow', 'stop'] as const;
type CaseTab = (typeof CASE_TABS)[number];
const DEFAULT_CASE_TAB: CaseTab = 'evidence';

/** A tab read from the address bar; anything unknown is the default tab. */
function readCaseTab(raw: string | undefined): CaseTab {
  return (CASE_TABS as readonly string[]).includes(raw ?? '') ? (raw as CaseTab) : DEFAULT_CASE_TAB;
}

/** What the case view needs in order to load itself. */
export interface AdvertisingCaseViewProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The case being opened. */
  readonly caseId: string;
  /** Called when the operator goes back to the queue. */
  readonly onBack: () => void;
}

function nativeCode(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

/** A measure cell that keeps its test hooks. */
function measure(
  key: string,
  label: string,
  state: string,
  content: React.ReactNode,
): NonNullable<DescriptionsProps['items']>[number] {
  return {
    key,
    label,
    children: (
      <span data-measure={key} data-measure-state={state}>
        {content}
      </span>
    ),
  };
}

const RANK_COLUMNS: TableColumnsType<AdvertisingRankFactor> = [
  {
    title: '排名因素',
    key: 'code',
    render: (_, factor) => <CodeTag labels={RANK_FACTOR_LABELS} code={factor.code} />,
  },
  {
    title: '数值',
    key: 'value',
    align: 'right',
    render: (_, factor) =>
      factor.value === undefined ? <AbsentValue label="未确定" /> : formatDecimal(factor.value),
  },
  {
    title: '权重',
    key: 'weight',
    align: 'right',
    render: (_, factor) =>
      factor.weight === undefined ? <AbsentValue label="—" /> : formatDecimal(factor.weight),
  },
  {
    title: '贡献',
    key: 'contribution',
    align: 'right',
    render: (_, factor) =>
      factor.contribution === undefined ? (
        <AbsentValue label="—" />
      ) : (
        formatDecimal(factor.contribution)
      ),
  },
  {
    title: '证据说明',
    key: 'note',
    render: (_, factor) =>
      factor.absenceReason === undefined ? (
        <Typography.Text type="secondary">已记录</Typography.Text>
      ) : (
        <Typography.Text>{factor.absenceReason}</Typography.Text>
      ),
  },
];

/**
 * One advertising case, and everything the decision would rest on.
 *
 * Every measure is shown with its own state rather than as a number that might
 * be missing, and the rank factors are shown with their contributions so an
 * operator can see why this case is where it is in the queue rather than having
 * to trust the position.
 */
export function AdvertisingCaseView({
  context,
  caseId,
  onBack,
}: AdvertisingCaseViewProps): React.JSX.Element {
  const [detail, setDetail] = useState<AdvertisingCase | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [revision, setRevision] = useState(0);
  // The open tab lives in the address bar, so a reload or a shared link opens
  // the same tab; the default tab is left out of the URL.
  const [rawTab, setRawTab] = useSearchParam('tab');
  const tab = readCaseTab(rawTab);

  useEffect(() => {
    let active = true;
    void fetchAdvertisingCase(context, caseId).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setDetail(outcome.value);
        setFailure(undefined);
      } else {
        setDetail(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, caseId, revision]);

  const back = (
    <Button icon={<ArrowLeftOutlined />} onClick={onBack}>
      返回广告工作台
    </Button>
  );

  if (failure !== undefined) {
    return (
      <section aria-label="广告事项" data-state="error">
        <SectionCard title="广告事项" extra={back}>
          <FailureAlert failure={failure} />
        </SectionCard>
      </section>
    );
  }
  if (detail === undefined) {
    return (
      <section aria-label="广告事项" data-state="loading">
        <SectionCard title="广告事项" extra={back}>
          <LoadingState rows={6} />
        </SectionCard>
      </section>
    );
  }

  const currency = detail.profitCurrencyCode;
  const nativeRules = detail.semanticProfile?.nativeRules;
  const nativeCurrency =
    typeof nativeRules === 'object' && nativeRules !== null && 'currencyCode' in nativeRules
      ? nativeCode(nativeRules.currencyCode)
      : undefined;
  const nativeUnit = nativeCode(detail.semanticProfile?.bidUnitCode);
  const hasStopActions = STOP_ACTION_CODES.some((code) =>
    detail.allowedControlActions.includes(code),
  );

  const measures: DescriptionsProps['items'] = [
    measure(
      'spend',
      '官方花费',
      detail.officialSpendState,
      <MeasureValue
        state={detail.officialSpendState}
        value={detail.officialSpendAmount}
        currency={currency}
        kind="money"
      />,
    ),
    measure(
      'profit',
      '贡献利润',
      detail.contributionProfitState,
      <MeasureValue
        state={detail.contributionProfitState}
        value={detail.contributionProfitAmount}
        currency={currency}
        kind="money"
      />,
    ),
    measure(
      'profit-per-ad-rub',
      '每广告卢布利润',
      detail.profitPerAdRubState,
      <MeasureValue state={detail.profitPerAdRubState} value={detail.profitPerAdRubValue} />,
    ),
    measure(
      'traffic',
      '有效流量',
      detail.eligibleTrafficState,
      <MeasureValue
        state={detail.eligibleTrafficState}
        value={
          detail.eligibleTrafficCount === undefined
            ? undefined
            : String(Math.trunc(detail.eligibleTrafficCount))
        }
        kind="count"
      />,
    ),
    measure(
      'max-cpc',
      '最高 CPC',
      detail.maxCpcState,
      <MeasureValue
        state={detail.maxCpcState}
        value={detail.maxCpcAmount}
        currency={currency}
        kind="money"
      />,
    ),
    measure(
      'current-bid',
      '当前平台出价',
      detail.currentBidState,
      <Space size={4} wrap>
        <MeasureValue
          state={detail.currentBidState}
          value={detail.currentBidAmount}
          currency={nativeCurrency}
          kind="money"
        />
        <CodeTag labels={BID_UNIT_LABELS} code={nativeUnit ?? 'UNRESOLVED'} />
      </Space>,
    ),
  ];

  const structure: DescriptionsProps['items'] = [
    {
      key: 'platform',
      label: '平台',
      children: <CodeTag labels={PLATFORM_LABELS} code={detail.platformCode} />,
    },
    {
      key: 'kind',
      label: '对象类型',
      children: <CodeTag labels={OBJECT_KIND_LABELS} code={detail.nativeObjectKind} />,
    },
    {
      key: 'nativeKey',
      label: '平台对象标识',
      children:
        detail.nativeObjectKey === undefined ? (
          <AbsentValue label="未确定" />
        ) : (
          <IdText value={detail.nativeObjectKey} />
        ),
    },
    {
      key: 'bidding',
      label: '出价方式',
      children: <CodeTag labels={BIDDING_MODE_LABELS} code={detail.biddingMode} />,
    },
    {
      key: 'granularity',
      label: '控制粒度',
      children: (
        <CodeTag
          labels={CONTROL_GRANULARITY_LABELS}
          code={detail.controlGranularityState}
          colors={CONTROL_GRANULARITY_COLORS}
        />
      ),
    },
    {
      key: 'profile',
      label: '语义配置',
      children: (
        <Space size={4} wrap>
          <CodeTag
            labels={PROFILE_VERIFICATION_LABELS}
            code={nativeCode(detail.semanticProfile?.verificationState) ?? 'UNRESOLVED'}
            colors={PROFILE_VERIFICATION_COLORS}
          />
          <CodeTag
            labels={SOURCE_MATURITY_LABELS}
            code={nativeCode(detail.semanticProfile?.sourceMaturity) ?? 'UNRESOLVED'}
            colors={SOURCE_MATURITY_COLORS}
          />
        </Space>
      ),
    },
  ];

  const relationColumns: TableColumnsType<AdRow> = [
    {
      title: '上级对象',
      key: 'parent',
      render: (_, relation) => (
        <Space size={4} wrap>
          <CodeTag labels={OBJECT_KIND_LABELS} code={nativeCode(relation.parentKind)} />
          <IdText value={nativeCode(relation.parentObjectId)} />
        </Space>
      ),
    },
    {
      title: '下级对象',
      key: 'child',
      render: (_, relation) => (
        <Space size={4} wrap>
          <CodeTag
            labels={OBJECT_KIND_LABELS}
            code={nativeCode(relation.childKind) ?? 'LISTING_VARIANT'}
          />
          <IdText
            value={nativeCode(relation.childObjectId) ?? nativeCode(relation.listingVariantId)}
          />
        </Space>
      ),
    },
    {
      title: '关系',
      key: 'kind',
      render: (_, relation) => (
        <CodeTag labels={RELATIONSHIP_KIND_LABELS} code={nativeCode(relation.relationshipKind)} />
      ),
    },
  ];

  const evidenceTab = (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <section aria-label="平台原生结构">
        <Typography.Title level={5}>平台原生结构</Typography.Title>
        <Descriptions bordered size="small" column={{ xs: 1, md: 2, xl: 3 }} items={structure} />
        <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
          合成或未验证的语义配置不提供生产写入授权。
        </Typography.Paragraph>
        <AdvertisingEvidenceDetails value={nativeRules} label="平台出价单位、步长与回读规则" />
        {detail.nativeRelationships.length > 0 && (
          <Table<AdRow>
            size="middle"
            rowKey={(relation) => String(relation.id)}
            columns={relationColumns}
            dataSource={[...detail.nativeRelationships]}
            pagination={false}
            scroll={{ x: 'max-content' }}
          />
        )}
      </section>
      <section aria-label="影响范围">
        <Typography.Title level={5}>影响范围</Typography.Title>
        <Flex gap={8} align="center" wrap>
          <CodeTag
            labels={AFFECTED_SET_LABELS}
            code={detail.affectedSetResolution}
            colors={AFFECTED_SET_COLORS}
          />
          <Typography.Text>
            涉及商品规格 {detail.affectedProductVariantIds.length} 个
          </Typography.Text>
          <IdText value={detail.affectedSetDigest} prefix="摘要" />
        </Flex>
        {detail.affectedSetResolution !== 'COMPLETE' && (
          <Alert
            style={{ marginTop: 8 }}
            type="warning"
            showIcon
            title="影响范围尚不完整，完整影响未知。"
          />
        )}
        {detail.affectedProductVariantIds.length === 0 ? (
          <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
            尚未确定完整的商品规格成员。
          </Typography.Paragraph>
        ) : (
          <TechnicalDetails label="商品规格编号">
            <Flex vertical gap={2}>
              {detail.affectedProductVariantIds.map((id) => (
                <IdText key={id} value={id} />
              ))}
            </Flex>
          </TechnicalDetails>
        )}
        <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
          经济证据被遮蔽时，结构成员仍然可见。
        </Typography.Paragraph>
      </section>
    </Space>
  );

  return (
    <section aria-label="广告事项" data-state="loaded" data-case-id={detail.id}>
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <SectionCard
          title={
            detail.nativeObjectName === undefined ? (
              <span>{detail.adNativeObjectId}</span>
            ) : (
              <span lang="ru">{detail.nativeObjectName}</span>
            )
          }
          extra={
            <Space>
              <Button
                icon={<ReloadOutlined />}
                onClick={() => {
                  setRevision((value) => value + 1);
                }}
              >
                {actions.refresh}
              </Button>
              {back}
            </Space>
          }
        >
          <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
            <Space size={[4, 8]} wrap>
              <CodeTag labels={PLATFORM_LABELS} code={detail.platformCode} />
              <CodeTag labels={OBJECT_KIND_LABELS} code={detail.nativeObjectKind} />
              <CodeTag labels={LANE_LABELS} code={detail.lane} colors={LANE_COLORS} />
              {detail.protectionTier === undefined ? null : (
                <CodeTag
                  labels={PROTECTION_TIER_LABELS}
                  code={detail.protectionTier}
                  colors={PROTECTION_TIER_COLORS}
                />
              )}
              <CodeTag labels={CAUSE_LABELS} code={detail.causeCode} />
              <EvidenceChip state={detail.evidenceState} of="证据" showOf />
              <span data-confidence={detail.confidenceState}>
                <CodeTag
                  labels={AD_CONFIDENCE_LABELS}
                  code={detail.confidenceState}
                  colors={AD_CONFIDENCE_COLORS}
                />
              </span>
              <span data-disclosure={detail.disclosureState}>
                <CodeTag
                  labels={DISCLOSURE_LABELS}
                  code={detail.disclosureState}
                  colors={DISCLOSURE_COLORS}
                />
              </span>
            </Space>
            <Flex gap={24} wrap>
              <Typography.Text type="secondary">
                负责角色：
                {detail.accountableRoleCode === undefined ? (
                  '尚未分配'
                ) : (
                  <CodeTag labels={ROLE_LABELS} code={detail.accountableRoleCode} />
                )}
              </Typography.Text>
              <Typography.Text type="secondary">
                数据截止：
                <DateTime value={detail.asOf} />
              </Typography.Text>
              <IdText value={detail.adNativeObjectId} prefix="对象编号" />
            </Flex>
            {detail.blockerCodes.length > 0 && (
              <section aria-label="阻断原因">
                <Alert
                  type="error"
                  showIcon
                  title="该事项存在阻断"
                  description={<ReasonTags codes={detail.blockerCodes} />}
                />
              </section>
            )}
          </Space>
        </SectionCard>

        <SectionCard title="关键指标">
          <section aria-label="关键指标">
            <Descriptions bordered size="small" column={{ xs: 1, md: 2, xl: 3 }} items={measures} />
          </section>
        </SectionCard>

        <SectionCard title="排名依据">
          <section aria-label="排名依据">
            {detail.rankFactors.length === 0 ? (
              <div data-empty="rank-factors">
                <EmptyState description="排名因素已遮蔽或未确定。系统排序保持不变，缺失的因素不按零计算。" />
              </div>
            ) : (
              <Table<AdvertisingRankFactor>
                size="middle"
                rowKey="code"
                columns={RANK_COLUMNS}
                dataSource={[...detail.rankFactors]}
                pagination={false}
                scroll={{ x: 'max-content' }}
                onRow={(factor) => dataAttributes({ 'data-factor': factor.code })}
              />
            )}
          </section>
        </SectionCard>

        <SectionCard>
          <Tabs
            activeKey={tab}
            onChange={(key) => {
              const next = readCaseTab(key);
              setRawTab(next === DEFAULT_CASE_TAB ? undefined : next);
            }}
            items={[
              { key: 'evidence', label: '证据', children: evidenceTab },
              {
                key: 'manual',
                label: '人工执行',
                // The manual work for this object, kept beside the evidence.
                // Budget and pause changes live entirely here and reach no
                // marketplace.
                children: (
                  <AdvertisingManualShadow
                    context={context}
                    objectId={detail.adNativeObjectId}
                    caseId={detail.id}
                  />
                ),
              },
              {
                key: 'workflow',
                label: '工作流',
                children: (
                  <AdvertisingWorkflow
                    context={context}
                    caseId={detail.id}
                    timezone={detail.storeTimezone}
                  />
                ),
              },
              {
                key: 'stop',
                label: '止损',
                children: hasStopActions ? (
                  <AdvertisingStopControls
                    context={context}
                    objectId={detail.adNativeObjectId}
                    allowedActions={detail.allowedControlActions}
                  />
                ) : (
                  <EmptyState description="当前没有你可用的止损操作" />
                ),
              },
            ]}
          />
        </SectionCard>
      </Space>
    </section>
  );
}
