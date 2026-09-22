import { Alert, Button, Descriptions, Space, Table, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import { fetchMetricInputs, fetchEvidenceSource } from '../api/console';
import type { ConsoleFailure, ConsoleRequest, MetricInputs, EvidenceSource } from '../api/console';
import { diagnosisText, EVIDENCE_REF_KIND_LABELS, SOURCE_KIND_LABELS } from '../i18n/zh/pricing';
import {
  CodeTag,
  DateTime,
  DetailDrawer,
  EmptyState,
  FailureAlert,
  LoadingState,
  TechnicalDetails,
} from '../ui';

type Reference = MetricInputs['references'][number];

/** One explicit evidence read; typed edges never masquerade as source provenance. */
export function MetricEvidencePanel({
  context,
  subjectId,
  storeId,
  metricValueId,
}: {
  readonly context: ConsoleRequest;
  readonly subjectId: string;
  readonly storeId: string;
  readonly metricValueId: string;
}): React.JSX.Element {
  const [inputs, setInputs] = useState<MetricInputs>();
  const [selected, setSelected] = useState<string>();
  const [source, setSource] = useState<EvidenceSource>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    let active = true;
    void fetchMetricInputs(context, subjectId, storeId, metricValueId).then((result) => {
      if (!active) return;
      if (result.ok) setInputs(result.value);
      else setFailure(result.failure);
    });
    return () => {
      active = false;
    };
  }, [context, subjectId, storeId, metricValueId]);
  useEffect(() => {
    let active = true;
    if (selected !== undefined)
      void fetchEvidenceSource(context, selected).then((result) => {
        if (!active) return;
        if (result.ok) {
          setSource(result.value);
          setFailure(undefined);
        } else {
          setSource(undefined);
          setFailure(result.failure);
        }
      });
    return () => {
      active = false;
    };
  }, [context, selected]);

  const columns: TableColumnsType<Reference> = [
    {
      title: '类型',
      key: 'kind',
      render: (_, ref) => <CodeTag labels={EVIDENCE_REF_KIND_LABELS} code={ref.kind} />,
    },
    {
      title: '记录编号',
      key: 'id',
      render: (_, ref) => (
        <Typography.Text type="secondary" style={{ fontSize: 12 }} copyable={{ text: ref.id }}>
          {ref.id.slice(0, 8)}
        </Typography.Text>
      ),
    },
    {
      title: '操作',
      key: 'action',
      render: (_, ref) =>
        ref.kind === 'FACT_PROVENANCE' ? (
          <Button
            type="link"
            size="small"
            style={{ padding: 0 }}
            aria-label={`查看来源 ${ref.id}`}
            onClick={() => {
              setSource(undefined);
              setSelected(ref.id);
            }}
          >
            查看来源
          </Button>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
  ];

  return (
    <section aria-label="指标证据">
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        {failure !== undefined && <FailureAlert failure={failure} />}
        {inputs === undefined && failure === undefined && <LoadingState rows={2} />}
        {inputs !== undefined &&
          (inputs.references.length === 0 ? (
            <EmptyState description="未记录任何来源引用" />
          ) : (
            <Table<Reference>
              size="middle"
              rowKey={(ref) => `${ref.kind}:${ref.id}`}
              columns={columns}
              dataSource={[...inputs.references]}
              pagination={{ pageSize: 10, hideOnSinglePage: true, showSizeChanger: false }}
            />
          ))}
        {inputs?.truncated === true && (
          <Alert
            type="info"
            showIcon
            role="status"
            title="仅显示前 200 条引用，完整快照请使用诊断导出"
          />
        )}
        {source !== undefined && (
          <Descriptions
            aria-label="来源记录"
            bordered
            size="small"
            column={{ xs: 1, md: 2, xl: 3 }}
            title="来源记录"
            items={[
              {
                key: 'kind',
                label: '来源类型',
                children: <CodeTag labels={SOURCE_KIND_LABELS} code={source.sourceKind} />,
              },
              {
                key: 'sourceTime',
                label: '来源时间',
                children:
                  source.sourceTime === null ? (
                    <Typography.Text type="secondary">未记录</Typography.Text>
                  ) : (
                    <DateTime value={source.sourceTime} />
                  ),
              },
              {
                key: 'ingested',
                label: '入库时间',
                children: <DateTime value={source.ingestionTime} />,
              },
              {
                key: 'hash',
                label: '内容摘要（SHA-256）',
                span: 'filled',
                children:
                  source.contentSha256 === null ? (
                    <Typography.Text type="secondary">未保存源数据</Typography.Text>
                  ) : (
                    <Typography.Text
                      type="secondary"
                      style={{ fontSize: 12 }}
                      copyable={{ text: source.contentSha256 }}
                    >
                      {source.contentSha256.slice(0, 16)}…
                    </Typography.Text>
                  ),
              },
            ]}
          />
        )}
        <TechnicalDetails data={{ metricValueId }} />
      </Space>
    </section>
  );
}

/**
 * The evidence behind one measured value, beside the conclusion it supports.
 *
 * The page that opened it stays in view; the drawer is rebuilt for each value
 * so one metric's source never shows under another's title.
 */
export function MetricEvidenceDrawer({
  context,
  subjectId,
  storeId,
  metricValueId,
  metricLabel,
  onClose,
}: {
  readonly context: ConsoleRequest;
  readonly subjectId: string;
  readonly storeId: string;
  /** The value whose evidence is open, or nothing when the drawer is closed. */
  readonly metricValueId: string | undefined;
  /** Chinese name of the metric, shown in the title when known. */
  readonly metricLabel?: string | undefined;
  readonly onClose: () => void;
}): React.JSX.Element {
  return (
    <DetailDrawer
      open={metricValueId !== undefined}
      onClose={onClose}
      size={560}
      title={`${diagnosisText.evidenceTitle}${metricLabel === undefined ? '' : `：${metricLabel}`}`}
    >
      {metricValueId !== undefined && (
        <MetricEvidencePanel
          key={metricValueId}
          context={context}
          subjectId={subjectId}
          storeId={storeId}
          metricValueId={metricValueId}
        />
      )}
    </DetailDrawer>
  );
}
