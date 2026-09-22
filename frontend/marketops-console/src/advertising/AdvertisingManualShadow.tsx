import { Button, Card, Flex, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { fetchAdvertisingManualPackets } from '../api/console';
import type { AdvertisingManualPacket, AdvertisingManualVerification } from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { actions } from '../i18n';
import {
  COMPLETENESS_LABELS,
  CONFLICT_STATE_COLORS,
  CONFLICT_STATE_LABELS,
  EVIDENCE_GRADE_COLORS,
  EVIDENCE_GRADE_LABELS,
  FIELD_PATH_LABELS,
  MANUAL_ACTION_KIND_LABELS,
  MANUAL_PACKET_STATE_COLORS,
  MANUAL_PACKET_STATE_LABELS,
  OBSERVATION_SOURCE_LABELS,
} from '../i18n/zh/advertising';
import { CodeTag } from '../ui/CodeTag';
import { DateTime } from '../ui/DateTime';
import { EmptyState } from '../ui/EmptyState';
import { FailureAlert } from '../ui/FailureAlert';
import { LoadingState } from '../ui/LoadingState';
import { AdvertisingEvidenceDetails } from './AdvertisingEvidenceDetails';
import { AdvertisingOutcomeHistory } from './AdvertisingOutcomeHistory';
import {
  AdvertisingManualPacketControls,
  AdvertisingManualProposalControls,
} from './AdvertisingManualControls';
import { ReasonTags, dataAttributes } from './shared';

/** What the manual shadow surface needs in order to load itself. */
export interface AdvertisingManualShadowProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The advertising object whose manual work is being read. */
  readonly objectId: string;
  readonly caseId?: string;
}

/** What one observation proves, in the words the page has always used. */
function proofLabel(
  packet: AdvertisingManualPacket,
  verification: AdvertisingManualVerification,
): { readonly text: string; readonly color: 'success' | 'processing' | 'warning' | 'default' } {
  if (verification.qualifiedForCurrentProof) {
    return verification.id === packet.currentProofId && packet.configurationProven
      ? { text: '当前配置证明', color: 'success' }
      : { text: '历史观察，需核对当前证明', color: 'processing' };
  }
  return verification.provesConfiguration
    ? { text: '历史记录，不满足当前证据要求', color: 'warning' }
    : { text: '仅为报告，不构成证明', color: 'default' };
}

/**
 * Work somebody was asked to do by hand, and what was observed afterwards.
 *
 * A manual packet is a written instruction. It cannot become a command, an
 * outbox row, an attempt or a call to a marketplace — there is no path from
 * this surface to a provider, which is exactly why budget and pause work lives
 * here rather than in the controlled write path.
 *
 * The distinction this page exists to hold open is between somebody reporting
 * they made a change and somebody establishing that the change is there. A
 * self-report is a report; only an official readback, an official export or a
 * second person's independent look proves the configuration, and this surface
 * labels the two differently on purpose.
 */
export function AdvertisingManualShadow({
  context,
  objectId,
  caseId,
}: AdvertisingManualShadowProps): React.JSX.Element {
  const [revision, setRevision] = useState(0);
  const reload = (): void => {
    setRevision((value) => value + 1);
  };
  const [packets, setPackets] = useState<readonly AdvertisingManualPacket[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchAdvertisingManualPackets(context, objectId).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setPackets(outcome.value);
        setFailure(undefined);
      } else {
        setPackets(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, objectId, revision]);

  const state =
    failure !== undefined
      ? 'error'
      : packets === undefined
        ? 'loading'
        : packets.length === 0
          ? 'empty'
          : 'loaded';

  const verificationColumns = (
    packet: AdvertisingManualPacket,
  ): TableColumnsType<AdvertisingManualVerification> => [
    {
      title: '证据等级',
      key: 'grade',
      render: (_, verification) => (
        <CodeTag
          labels={EVIDENCE_GRADE_LABELS}
          code={verification.evidenceGrade}
          colors={EVIDENCE_GRADE_COLORS}
        />
      ),
    },
    {
      title: '证明效力',
      key: 'proof',
      render: (_, verification) => {
        const proof = proofLabel(packet, verification);
        return <Tag color={proof.color}>{proof.text}</Tag>;
      },
    },
    {
      title: '观察值',
      key: 'value',
      render: (_, verification) =>
        verification.observedValue === undefined ? (
          '—'
        ) : (
          <Space size={4}>
            <Typography.Text type="secondary">
              {verification.observedFieldPath === undefined
                ? '字段'
                : (FIELD_PATH_LABELS[verification.observedFieldPath] ??
                  verification.observedFieldPath)}
            </Typography.Text>
            <Typography.Text code>{verification.observedValue}</Typography.Text>
          </Space>
        ),
    },
    {
      title: '独立观察',
      key: 'independent',
      render: (_, verification) => {
        const independent = verification.independentObservation;
        if (independent === undefined) return '—';
        return (
          <Tooltip title="该观察不能确定变更实际生效的时间">
            <Flex vertical gap={2}>
              <Typography.Text>
                {OBSERVATION_SOURCE_LABELS[independent.evidenceSource]}
                {' · '}
                {independent.completeness === 'COMPLETE'
                  ? '完整'
                  : (COMPLETENESS_LABELS.INCOMPLETE ?? '不完整')}
              </Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                实际观察于 <DateTime value={independent.observedAt} />
              </Typography.Text>
            </Flex>
          </Tooltip>
        );
      },
    },
    {
      title: '冲突',
      key: 'conflict',
      render: (_, verification) =>
        verification.conflictState === undefined || verification.conflictState === 'NONE' ? (
          '—'
        ) : (
          <CodeTag
            labels={CONFLICT_STATE_LABELS}
            code={verification.conflictState}
            colors={CONFLICT_STATE_COLORS}
          />
        ),
    },
  ];

  return (
    <section aria-label="人工执行" data-state={state}>
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <Flex justify="space-between" align="center" wrap gap={8}>
          <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
            这里是给人执行的操作说明：不会自行触达平台，也不会创建任何指令。
          </Typography.Paragraph>
          <Button icon={<ReloadOutlined />} onClick={reload}>
            {actions.refresh}
          </Button>
        </Flex>
        {failure !== undefined && <FailureAlert failure={failure} />}
        {packets === undefined && failure === undefined && <LoadingState rows={3} />}
        {packets !== undefined && caseId !== undefined && (
          <AdvertisingManualProposalControls context={context} caseId={caseId} reload={reload} />
        )}
        {packets?.length === 0 && <EmptyState description="尚未为该对象下发人工操作单" />}
        {packets?.map((packet) => (
          <div
            key={packet.id}
            data-action={packet.actionKind}
            data-state={packet.state}
            data-proven={packet.configurationProven}
          >
            <Card
              size="small"
              title={
                <Space size={6} wrap>
                  <CodeTag labels={MANUAL_ACTION_KIND_LABELS} code={packet.actionKind} />
                  <CodeTag
                    labels={MANUAL_PACKET_STATE_LABELS}
                    code={packet.state}
                    colors={MANUAL_PACKET_STATE_COLORS}
                  />
                </Space>
              }
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  版本 {packet.version === undefined ? '未确定' : String(packet.version)}
                </Typography.Text>
              }
            >
              <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
                <AdvertisingManualPacketControls
                  context={context}
                  packet={packet}
                  reload={reload}
                />
                <Typography.Text>{packet.reason ?? '未记录原因。'}</Typography.Text>
                {packet.intendedState === undefined ? null : (
                  <Typography.Text type="secondary">
                    预期结果：<Typography.Text code>{packet.intendedState}</Typography.Text>
                  </Typography.Text>
                )}
                <span data-proven={packet.configurationProven}>
                  {packet.configurationProven ? (
                    <Tag color="success">结果配置已被证实</Tag>
                  ) : (
                    <Tag color="warning">尚无证据证实结果配置</Tag>
                  )}
                </span>
                {packet.verifications.length === 0 ? (
                  <Typography.Text type="secondary">尚未记录观察。</Typography.Text>
                ) : (
                  <div aria-label="观察记录">
                    <Table<AdvertisingManualVerification>
                      size="middle"
                      rowKey="id"
                      columns={verificationColumns(packet)}
                      dataSource={[...packet.verifications]}
                      pagination={false}
                      scroll={{ x: 'max-content' }}
                      onRow={(verification) =>
                        dataAttributes({
                          'data-grade': verification.evidenceGrade,
                          'data-proves': verification.qualifiedForCurrentProof,
                        })
                      }
                    />
                  </div>
                )}
                {packet.blockerCodes.length === 0 ? null : (
                  <div aria-label="阻断原因">
                    <ReasonTags codes={packet.blockerCodes} />
                  </div>
                )}
                {packet.state === 'MANUAL_CONFIGURATION_VERIFIED' && (
                  <AdvertisingOutcomeHistory
                    key={`${packet.id}:${String(revision)}`}
                    context={context}
                    manualPacketId={packet.id}
                    embedded
                  />
                )}
                {packet.packetDetails !== undefined && (
                  <AdvertisingEvidenceDetails
                    value={packet.packetDetails}
                    label="操作单详情与核验计划"
                  />
                )}
              </Space>
            </Card>
          </div>
        ))}
      </Space>
    </section>
  );
}
