import { ReloadOutlined } from '@ant-design/icons';
import {
  App,
  Button,
  Col,
  Flex,
  Form,
  Input,
  Row,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  captureFeedbackSource,
  classifyFeedback,
  fetchFeedbackDetail,
  fetchFeedbackOverview,
} from '../api/listingConversion';
import type {
  ListingFeedbackDetail,
  ListingFeedbackItem,
  ListingFeedbackOverview,
} from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { feedbackText as text } from '../i18n/zh/listingHealth';
import { ActionModal, DetailDrawer, EmptyState, LoadingState, SectionCard } from '../ui';
import {
  Code,
  Details,
  Hint,
  IdText,
  InstantPicker,
  ListingProblem,
  Stack,
  SubTitle,
  When,
  codeOptions,
} from './ListingCommon';

const THEME_PATTERN = /^[A-Z][A-Z0-9_]{1,62}$/;
const DAY_MS = 24 * 60 * 60 * 1000;

type Preset = '7' | '30' | '90' | 'custom';

interface Period {
  readonly from: string;
  readonly to: string;
}

/** The last `days` days up to now. */
function lastDays(days: number): Period {
  const now = Date.now();
  return {
    from: new Date(now - days * DAY_MS).toISOString(),
    to: new Date(now).toISOString(),
  };
}

interface CaptureValues {
  readonly rawObservationId?: string;
  readonly itemPointer?: string;
  readonly identityPointer?: string;
  readonly listingPointer?: string;
  readonly textPointer?: string;
}

interface ClassifyValues {
  readonly theme?: string;
  readonly qualification?: string;
  readonly reason?: string;
}

/**
 * Buyer feedback of one listing: the themes over a period, the items behind
 * them, and each item's original reference and classification history in a
 * drawer beside the list, where a human correction is appended. Themes are an
 * aid to reading feedback, never proof of a product fact.
 */
export function ListingFeedbackPanel({
  context,
  listingId,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [preset, setPreset] = useState<Preset>('30');
  const [period, setPeriod] = useState<Period>(() => lastDays(30));
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');
  const [overview, setOverview] = useState<ListingFeedbackOverview | undefined>();
  const [failure, setFailure] = useState<ConsoleFailure | undefined>();
  const [loading, setLoading] = useState(true);
  const [generation, setGeneration] = useState(0);
  const [openItem, setOpenItem] = useState<string | undefined>();
  const [detail, setDetail] = useState<ListingFeedbackDetail | undefined>();
  const [detailFailure, setDetailFailure] = useState<ConsoleFailure | undefined>();
  const detailTicket = useRef(0);

  useEffect(() => {
    let live = true;
    setLoading(true);
    void fetchFeedbackOverview(context, listingId, period.from, period.to).then((result) => {
      if (!live) return;
      setLoading(false);
      if (result.ok) {
        setOverview(result.value);
        setFailure(undefined);
      } else {
        setOverview(undefined);
        setFailure(result.failure);
      }
    });
    return () => {
      live = false;
    };
  }, [context, listingId, period, generation]);

  const loadDetail = useCallback(
    async (itemId: string): Promise<void> => {
      const ticket = ++detailTicket.current;
      setDetail(undefined);
      setDetailFailure(undefined);
      const result = await fetchFeedbackDetail(context, listingId, itemId);
      if (ticket !== detailTicket.current) return;
      if (result.ok) setDetail(result.value);
      else setDetailFailure(result.failure);
    },
    [context, listingId],
  );

  const openDetail = (itemId: string): void => {
    setOpenItem(itemId);
    void loadDetail(itemId);
  };
  const closeDetail = (): void => {
    detailTicket.current += 1;
    setOpenItem(undefined);
    setDetail(undefined);
    setDetailFailure(undefined);
  };
  const reloadOverview = (): void => {
    setGeneration((value) => value + 1);
  };

  const customInvalid =
    customFrom === '' || customTo === ''
      ? text.periodRequired
      : Date.parse(customTo) <= Date.parse(customFrom)
        ? text.periodOrder
        : undefined;

  const periodFilter = (
    <Flex gap={8} wrap align="center">
      <Segmented<Preset>
        aria-label={text.period}
        value={preset}
        options={[
          { value: '7', label: text.last7 },
          { value: '30', label: text.last30 },
          { value: '90', label: text.last90 },
          { value: 'custom', label: text.custom },
        ]}
        onChange={(next) => {
          setPreset(next);
          if (next !== 'custom') setPeriod(lastDays(Number(next)));
        }}
      />
      <Button icon={<ReloadOutlined />} aria-label={t('refresh')} onClick={reloadOverview} />
      <CaptureFeedback
        context={context}
        listingId={listingId}
        onCaptured={(itemId) => {
          void message.success(text.captured);
          reloadOverview();
          openDetail(itemId);
        }}
      />
    </Flex>
  );

  return (
    <section aria-label={t('feedback')} data-state={loading ? 'loading' : 'loaded'}>
      <SectionCard title={t('feedback')} extra={periodFilter}>
        <Stack>
          <Hint>{t('feedbackBoundary')}</Hint>
          {preset === 'custom' && (
            <Flex gap={8} wrap align="center">
              <Typography.Text type="secondary">{t('feedbackFrom')}</Typography.Text>
              <div style={{ width: 220 }}>
                <InstantPicker
                  value={customFrom}
                  onChange={setCustomFrom}
                  ariaLabel={t('feedbackFrom')}
                />
              </div>
              <Typography.Text type="secondary">{t('feedbackTo')}</Typography.Text>
              <div style={{ width: 220 }}>
                <InstantPicker
                  value={customTo}
                  onChange={setCustomTo}
                  ariaLabel={t('feedbackTo')}
                />
              </div>
              <Button
                disabled={customInvalid !== undefined}
                onClick={() => {
                  setPeriod({ from: customFrom, to: customTo });
                }}
              >
                {text.load}
              </Button>
              {customInvalid !== undefined && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {customInvalid}
                </Typography.Text>
              )}
            </Flex>
          )}
          {failure !== undefined && <ListingProblem failure={failure} />}
          {loading && overview === undefined && failure === undefined && <LoadingState />}
          {overview !== undefined && <FeedbackOverview overview={overview} onOpen={openDetail} />}
        </Stack>
      </SectionCard>
      <DetailDrawer
        open={openItem !== undefined}
        onClose={closeDetail}
        title={text.detailTitle}
        loading={openItem !== undefined && detail === undefined && detailFailure === undefined}
        failure={detailFailure}
        onRetry={() => {
          if (openItem !== undefined) void loadDetail(openItem);
        }}
        {...(detail === undefined
          ? {}
          : {
              footer: (
                <Flex justify="flex-end">
                  <ClassifyFeedback
                    key={`${detail.original.id}:${String(detail.classifications.length)}`}
                    context={context}
                    listingId={listingId}
                    detail={detail}
                    onClassified={() => {
                      void message.success(text.corrected);
                      reloadOverview();
                      void loadDetail(detail.original.id);
                    }}
                  />
                </Flex>
              ),
            })}
      >
        {detail !== undefined && <FeedbackDetail detail={detail} />}
      </DetailDrawer>
    </section>
  );
}

/** The period's themes and items; a row opens the item. */
function FeedbackOverview({
  overview,
  onOpen,
}: {
  readonly overview: ListingFeedbackOverview;
  readonly onOpen: (itemId: string) => void;
}): React.JSX.Element {
  return (
    <>
      <Typography.Text type="secondary">
        {t('feedbackSnapshot')} <When value={overview.asOf} /> · {t('feedbackItemLimit')}：
        {overview.itemLimit}
      </Typography.Text>
      {overview.themes.length > 0 && (
        <Table
          size="middle"
          rowKey={(value) => `${value.themeCode}:${value.qualificationState}`}
          pagination={false}
          dataSource={[...overview.themes]}
          columns={[
            {
              key: 'theme',
              title: t('feedbackTheme'),
              render: (_, value) => <Tag>{value.themeCode}</Tag>,
            },
            {
              key: 'qualification',
              title: t('feedbackQualification'),
              render: (_, value) => (
                <Code family="feedbackQualification" code={value.qualificationState} />
              ),
            },
            {
              key: 'count',
              title: t('mentionCount'),
              align: 'right',
              dataIndex: 'mentionCount',
            },
          ]}
        />
      )}
      <SubTitle>{text.itemsTitle}</SubTitle>
      {overview.items.length === 0 ? (
        <EmptyState description={t('noFeedback')} />
      ) : (
        <Table<ListingFeedbackItem>
          size="middle"
          rowKey="id"
          pagination={false}
          dataSource={[...overview.items]}
          onRow={(item) =>
            ({
              'data-item': item.id,
              onClick: () => {
                onOpen(item.id);
              },
              onKeyDown: (event: React.KeyboardEvent<HTMLElement>) => {
                if (event.key === 'Enter' && event.target === event.currentTarget) onOpen(item.id);
              },
              tabIndex: 0,
              'aria-label': text.openItem,
              style: { cursor: 'pointer' },
            }) as React.HTMLAttributes<HTMLElement>
          }
          columns={[
            {
              key: 'observed',
              title: t('sourceTime'),
              render: (_, item) => <When value={item.observedAt} />,
            },
            {
              key: 'acquired',
              title: t('acquisitionTime'),
              render: (_, item) => <When value={item.acquiredAt} />,
            },
            {
              key: 'id',
              title: t('feedbackItemId'),
              render: (_, item) => <IdText value={item.id} />,
            },
          ]}
        />
      )}
    </>
  );
}

/** One item's original reference and its classification history. */
function FeedbackDetail({ detail }: { readonly detail: ListingFeedbackDetail }): React.JSX.Element {
  return (
    <Stack>
      <SubTitle>{t('feedbackOriginal')}</SubTitle>
      <Details
        column={1}
        items={[
          {
            key: 'observed',
            label: t('sourceTime'),
            children: <When value={detail.original.observedAt} />,
          },
          {
            key: 'acquired',
            label: t('acquisitionTime'),
            children: <When value={detail.original.acquiredAt} />,
          },
          {
            key: 'raw',
            label: t('feedbackRaw'),
            children: <IdText value={detail.original.rawObservationId} />,
          },
          {
            key: 'pointer',
            label: t('feedbackPointer'),
            children: <IdText value={detail.original.originalPointer} />,
          },
          {
            key: 'digest',
            label: t('feedbackDigest'),
            children: <IdText value={detail.original.originalDigest} />,
          },
        ]}
      />
      <SubTitle>{t('feedbackHistory')}</SubTitle>
      {detail.classifications.length === 0 ? (
        <EmptyState description={t('noClassification')} />
      ) : (
        <div aria-label={t('feedbackHistory')}>
          <Timeline
            items={detail.classifications.map((label) => ({
              key: label.id,
              content: (
                <Space orientation="vertical" size={2}>
                  <Space size={6} wrap>
                    <Typography.Text>
                      {t('revisionShort')} {label.revision}
                    </Typography.Text>
                    <Tag>{label.themeCode}</Tag>
                    <Code family="feedbackQualification" code={label.qualificationState} />
                    <When value={label.classifiedAt} />
                  </Space>
                  <Typography.Text>{label.reason}</Typography.Text>
                  <IdText label={t('classifiedBy')} value={label.classifiedBy} />
                </Space>
              ),
            }))}
          />
        </div>
      )}
    </Stack>
  );
}

/** Link a raw feedback record by its exact JSON positions. */
function CaptureFeedback({
  context,
  listingId,
  onCaptured,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
  readonly onCaptured: (itemId: string) => void;
}): React.JSX.Element {
  const pointerRule = { required: true, whitespace: true, message: text.pointerRequired };
  return (
    <ActionModal<CaptureValues>
      trigger={{ label: t('feedbackCapture') }}
      title={t('feedbackCapture')}
      consequence={text.captureConsequence}
      okText={t('feedbackCapture')}
      width={640}
      initialValues={{ identityPointer: '/id', listingPointer: '/listingId', textPointer: '/text' }}
      onSubmit={async (values) => {
        const result = await captureFeedbackSource(context, listingId, {
          rawObservationId: (values.rawObservationId ?? '').trim(),
          itemPointer: values.itemPointer ?? '',
          identityPointer: values.identityPointer ?? '',
          listingPointer: values.listingPointer ?? '',
          textPointer: values.textPointer ?? '',
        });
        if (!result.ok) return result.failure;
        onCaptured(result.value);
        return undefined;
      }}
    >
      <Form.Item
        name="rawObservationId"
        label={t('feedbackRawObservation')}
        rules={[{ required: true, whitespace: true, message: text.rawRequired }]}
      >
        <Input />
      </Form.Item>
      <Form.Item name="itemPointer" label={t('feedbackItemPointer')}>
        <Input placeholder="/items/0" />
      </Form.Item>
      <Row gutter={12}>
        <Col xs={24} md={8}>
          <Form.Item
            name="identityPointer"
            label={t('feedbackIdentityPointer')}
            rules={[pointerRule]}
          >
            <Input />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item
            name="listingPointer"
            label={t('feedbackListingPointer')}
            rules={[pointerRule]}
          >
            <Input />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item name="textPointer" label={t('feedbackTextPointer')} rules={[pointerRule]}>
            <Input />
          </Form.Item>
        </Col>
      </Row>
    </ActionModal>
  );
}

/**
 * Append a human classification to one item. The theme and qualification
 * start from the latest label; the reason starts empty every time.
 */
function ClassifyFeedback({
  context,
  listingId,
  detail,
  onClassified,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
  readonly detail: ListingFeedbackDetail;
  readonly onClassified: () => void;
}): React.JSX.Element {
  const latest = detail.classifications.at(-1);
  return (
    <ActionModal<ClassifyValues>
      trigger={{ label: text.correct, type: 'primary' }}
      title={text.correct}
      consequence={text.correctConsequence}
      okText={text.correct}
      initialValues={{
        theme: latest?.themeCode ?? '',
        qualification: latest?.qualificationState ?? 'UNCERTAIN',
      }}
      onSubmit={async (values) => {
        const result = await classifyFeedback(context, listingId, detail.original.id, {
          themeCode: values.theme ?? '',
          qualificationState: values.qualification ?? 'UNCERTAIN',
          classifierVersion: 'HUMAN_V1',
          reason: (values.reason ?? '').trim(),
        });
        if (!result.ok) return result.failure;
        onClassified();
        return undefined;
      }}
    >
      <Form.Item
        name="theme"
        label={t('feedbackTheme')}
        extra={t('feedbackThemeHelp')}
        rules={[
          { required: true, message: text.themeRequired },
          { pattern: THEME_PATTERN, message: t('feedbackThemeInvalid') },
        ]}
      >
        <Input maxLength={63} />
      </Form.Item>
      <Form.Item
        name="qualification"
        label={t('feedbackQualification')}
        rules={[{ required: true }]}
      >
        <Select
          options={codeOptions('feedbackQualification', ['CONFIRMED', 'UNCERTAIN', 'CONFLICTED'])}
        />
      </Form.Item>
      <Form.Item
        name="reason"
        label={t('feedbackReason')}
        rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
      >
        <Input.TextArea maxLength={512} showCount autoSize={{ minRows: 2, maxRows: 4 }} />
      </Form.Item>
    </ActionModal>
  );
}
