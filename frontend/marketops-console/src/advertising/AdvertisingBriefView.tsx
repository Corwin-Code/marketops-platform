import { Alert, Collapse, Descriptions, Flex, Space, Table, Tag, Timeline, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import {
  fetchAdvertisingBrief,
  fetchAdvertisingBriefHistory,
  fetchLatestAdvertisingBrief,
} from '../api/console';
import type { AdvertisingBrief, AdvertisingBriefItem } from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { formatDecimal } from '../format';
import { codeLabel } from '../i18n';
import {
  BRIEF_KIND_LABELS,
  BRIEF_SECTION_LABELS,
  CAUSE_LABELS,
  LANE_COLORS,
  LANE_LABELS,
  REVISION_KIND_LABELS,
  SECTION_COVERAGE_COLORS,
  SECTION_COVERAGE_LABELS,
  SUBJECT_KIND_LABELS,
  VALUE_STATE_LABELS,
} from '../i18n/zh/advertising';
import { CodeTag } from '../ui/CodeTag';
import { DateTime } from '../ui/DateTime';
import { EmptyState } from '../ui/EmptyState';
import { FailureAlert } from '../ui/FailureAlert';
import { LoadingState } from '../ui/LoadingState';
import { Money } from '../ui/Money';
import { SectionCard } from '../ui/SectionCard';
import { AbsentValue, IdText, ReasonTags, dataAttributes } from './shared';

/** What the brief surface needs in order to load itself. */
export interface AdvertisingBriefViewProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** Daily action brief or weekly evidence review. */
  readonly briefKind: string;
  /**
   * The period, as the calendar names it.
   *
   * Left out to ask for the newest published period instead. The console does
   * not work one out: the reporting timezone and cut minute belong to the
   * owner's calendar, and a browser deriving a period from its own clock would
   * be inventing the schedule.
   */
  readonly periodKey?: string;
}

const ITEM_COLUMNS: TableColumnsType<AdvertisingBriefItem> = [
  {
    title: '对象类型',
    key: 'subject',
    render: (_, item) => <CodeTag labels={SUBJECT_KIND_LABELS} code={item.subjectKind} />,
  },
  {
    title: '工作类型',
    key: 'lane',
    render: (_, item) =>
      item.lane === undefined ? (
        '—'
      ) : (
        <CodeTag labels={LANE_LABELS} code={item.lane} colors={LANE_COLORS} />
      ),
  },
  {
    title: '原因',
    key: 'cause',
    render: (_, item) =>
      item.causeCode === undefined ? '—' : <CodeTag labels={CAUSE_LABELS} code={item.causeCode} />,
  },
  {
    title: '数值',
    key: 'value',
    align: 'right',
    render: (_, item) =>
      item.valueState !== 'AVAILABLE' || item.numericValue === undefined ? (
        <AbsentValue label={codeLabel(VALUE_STATE_LABELS, item.valueState)} />
      ) : item.currencyCode === undefined ? (
        formatDecimal(item.numericValue)
      ) : (
        <Money value={item.numericValue} currency={item.currencyCode} />
      ),
  },
  {
    title: '阻断',
    key: 'blockers',
    render: (_, item) => <ReasonTags codes={item.blockerCodes} empty="—" />,
  },
  {
    title: '观察时间',
    key: 'observedAt',
    render: (_, item) => <DateTime value={item.observedAt} />,
  },
  {
    title: '引用',
    key: 'reference',
    render: (_, item) => <IdText value={item.referenceId} />,
  },
];

/**
 * One published brief, and every earlier reading of the same period.
 *
 * Two things this page refuses to do. It does not show only the newest reading:
 * somebody acted on what was published on the day, and a page that quietly
 * replaced it would make that decision impossible to understand afterwards. And
 * it does not hide a topic that found nothing — a section that vanished when
 * empty would make "we looked and there was nothing" and "we never looked" the
 * same page.
 *
 * Every line links to one canonical row. Nothing here is a figure the brief
 * arrived at itself, which is why a reader who doubts a number should follow the
 * reference rather than argue with the report.
 */
export function AdvertisingBriefView({
  context,
  briefKind,
  periodKey,
}: AdvertisingBriefViewProps): React.JSX.Element {
  const [brief, setBrief] = useState<AdvertisingBrief | undefined>(undefined);
  const [history, setHistory] = useState<readonly AdvertisingBrief[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [nothingPublished, setNothingPublished] = useState(false);

  useEffect(() => {
    let active = true;
    // Read through a call so the two checks either side of the await are both
    // real: a plain boolean narrows to true after the first one.
    const stopped = (): boolean => !active;
    const wanted =
      periodKey === undefined
        ? fetchLatestAdvertisingBrief(context, briefKind)
        : fetchAdvertisingBrief(context, briefKind, periodKey);
    void wanted.then(async (latest) => {
      if (stopped()) {
        return;
      }
      if (!latest.ok) {
        // Nothing published yet is an answer, not a fault: a calendar with no
        // operating day for the period deliberately publishes nothing.
        if (latest.failure.kind === 'refused' && latest.failure.status === 404) {
          setNothingPublished(true);
          setFailure(undefined);
          return;
        }
        setFailure(latest.failure);
        return;
      }
      // The period the server named, never one this browser worked out.
      const readings = await fetchAdvertisingBriefHistory(
        context,
        briefKind,
        latest.value.periodKey,
      );
      if (stopped()) {
        return;
      }
      if (!readings.ok) {
        setFailure(readings.failure);
        return;
      }
      setBrief(latest.value);
      setHistory(readings.value);
      setNothingPublished(false);
      setFailure(undefined);
    });
    return () => {
      active = false;
    };
  }, [context, briefKind, periodKey]);

  const kindTitle = codeLabel(BRIEF_KIND_LABELS, briefKind);

  if (failure !== undefined) {
    return (
      <section aria-label="广告简报" data-state="error">
        <SectionCard title={kindTitle}>
          <FailureAlert failure={failure} />
        </SectionCard>
      </section>
    );
  }
  if (nothingPublished) {
    return (
      <section aria-label="广告简报" data-state="none">
        <SectionCard title={kindTitle}>
          <EmptyState description="该类简报尚未发布。报告日历中不是营业日的周期不会发布简报。" />
        </SectionCard>
      </section>
    );
  }
  if (brief === undefined || history === undefined) {
    return (
      <section aria-label="广告简报" data-state="loading">
        <SectionCard title={kindTitle}>
          <LoadingState rows={4} />
        </SectionCard>
      </section>
    );
  }

  const openSections = brief.sections
    .filter((section) => section.items.length > 0 || !section.complete)
    .map((section) => section.sectionCode);

  return (
    <section
      aria-label="广告简报"
      data-state="loaded"
      data-brief-kind={brief.briefKind}
      data-revision={brief.revisionNo}
    >
      <SectionCard
        title={
          <Space size={8} wrap>
            <span>{codeLabel(BRIEF_KIND_LABELS, brief.briefKind)}</span>
            <Typography.Text type="secondary">{brief.periodKey}</Typography.Text>
            {brief.fullyCovered ? (
              <Tag color="success">覆盖完整</Tag>
            ) : (
              <Tag color="warning">覆盖不完整</Tag>
            )}
          </Space>
        }
      >
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          <Descriptions
            bordered
            size="small"
            column={{ xs: 1, md: 2, xl: 3 }}
            items={[
              { key: 'asOf', label: '数据截止', children: <DateTime value={brief.asOf} /> },
              {
                key: 'published',
                label: '发布时间',
                children: <DateTime value={brief.publishedAt} />,
              },
              {
                key: 'revision',
                label: '版本',
                children: (
                  <Space size={4}>
                    <span>第 {brief.revisionNo} 版</span>
                    <CodeTag labels={REVISION_KIND_LABELS} code={brief.revisionKind} />
                  </Space>
                ),
              },
            ]}
          />
          <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
            数据截止时间不是生成时间：晚发布的简报描述的仍是它所标明的截止时刻。
          </Typography.Paragraph>
          {brief.restatement ? (
            <div role="status" data-restatement="true">
              <Alert
                type="info"
                showIcon
                title={`这是第 ${String(brief.revisionNo)} 版，取代了之前的版本`}
                description={
                  <Flex vertical gap={2}>
                    <span>原因：{brief.adjustmentReason ?? '该周期的底层数据已被重述'}</span>
                    {brief.lateFactReference === undefined ? null : (
                      <span>
                        迟到数据引用：
                        <IdText value={brief.lateFactReference} />
                      </span>
                    )}
                    <span>之前的版本保留在下方，未作修改。</span>
                  </Flex>
                }
              />
            </div>
          ) : null}
          {brief.gapCodes.length === 0 ? null : (
            <div aria-label="本次简报未能确定的内容">
              <Alert
                type="warning"
                showIcon
                title="本次简报未能确定的内容"
                description={<ReasonTags codes={brief.gapCodes} color="warning" />}
              />
            </div>
          )}

          <Collapse
            defaultActiveKey={openSections}
            items={brief.sections.map((section) => ({
              key: section.sectionCode,
              label: (
                <Space size={8} wrap>
                  <span>{codeLabel(BRIEF_SECTION_LABELS, section.sectionCode)}</span>
                  <CodeTag
                    labels={SECTION_COVERAGE_LABELS}
                    code={section.coverageState}
                    colors={SECTION_COVERAGE_COLORS}
                  />
                  <Typography.Text type="secondary">{section.itemCount} 项</Typography.Text>
                </Space>
              ),
              children: (
                <div
                  aria-label={codeLabel(BRIEF_SECTION_LABELS, section.sectionCode)}
                  data-section={section.sectionCode}
                  data-coverage={section.coverageState}
                >
                  <Space orientation="vertical" size="small" style={{ width: '100%' }}>
                    {section.complete ? null : (
                      <div data-blocked="true">
                        <Alert
                          type="warning"
                          showIcon
                          title={section.summaryNote ?? '该主题未被完整覆盖'}
                          {...(section.blockerCodes.length === 0
                            ? {}
                            : { description: <ReasonTags codes={section.blockerCodes} /> })}
                        />
                      </div>
                    )}
                    {section.items.length === 0 ? (
                      <Typography.Text type="secondary">
                        {section.complete ? '本周期该主题下没有内容。' : '该主题没有标准数据来源。'}
                      </Typography.Text>
                    ) : (
                      <Table<AdvertisingBriefItem>
                        size="middle"
                        rowKey={(item) => `${item.subjectKind}:${item.referenceId}`}
                        columns={ITEM_COLUMNS}
                        dataSource={[...section.items]}
                        pagination={false}
                        scroll={{ x: 'max-content' }}
                        onRow={(item) =>
                          dataAttributes({
                            'data-subject': item.subjectKind,
                            'data-value-state': item.valueState,
                          })
                        }
                      />
                    )}
                  </Space>
                </div>
              ),
            }))}
          />

          {history.length <= 1 ? null : (
            <section aria-label="之前的版本" data-readings={history.length}>
              <Typography.Title level={5}>之前的版本</Typography.Title>
              <Typography.Paragraph type="secondary">
                按发布时的原样保留。一旦有人可能据此行动，简报就不再修改。
              </Typography.Paragraph>
              <Timeline
                items={history.map((reading) => ({
                  key: reading.id,
                  content: (
                    <div data-revision={reading.revisionNo}>
                      <Space size={6} wrap>
                        <Typography.Text strong>第 {reading.revisionNo} 版</Typography.Text>
                        <CodeTag labels={REVISION_KIND_LABELS} code={reading.revisionKind} />
                        <Typography.Text type="secondary">截止</Typography.Text>
                        <DateTime value={reading.asOf} />
                        <Typography.Text type="secondary">发布</Typography.Text>
                        <DateTime value={reading.publishedAt} />
                        {reading.lateFactReference === undefined ? null : (
                          <IdText value={reading.lateFactReference} prefix="迟到数据" />
                        )}
                      </Space>
                    </div>
                  ),
                }))}
              />
            </section>
          )}
        </Space>
      </SectionCard>
    </section>
  );
}
