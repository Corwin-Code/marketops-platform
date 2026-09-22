import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Form,
  Input,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  captureFeedbackSource,
  classifyFeedback,
  fetchFeedbackDetail,
  fetchFeedbackOverview,
} from '../api/listingConversion';
import type { ListingFeedbackDetail, ListingFeedbackOverview } from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { EmptyState, SectionCard } from '../ui';
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

export function ListingFeedbackPanel({
  context,
  listingId,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
}): React.JSX.Element {
  const [from, setFrom] = useState(''),
    [to, setTo] = useState('');
  const [overview, setOverview] = useState<ListingFeedbackOverview | undefined>();
  const [detail, setDetail] = useState<ListingFeedbackDetail | undefined>();
  const [failure, setFailure] = useState<ConsoleFailure | undefined>();
  const [busy, setBusy] = useState(false);
  const [theme, setTheme] = useState(''),
    [reason, setReason] = useState('');
  const [qualification, setQualification] = useState('UNCERTAIN');
  const [rawObservationId, setRawObservationId] = useState('');
  const [itemPointer, setItemPointer] = useState('');
  const [identityPointer, setIdentityPointer] = useState('/id');
  const [listingPointer, setListingPointer] = useState('/listingId');
  const [textPointer, setTextPointer] = useState('/text');
  const [captured, setCaptured] = useState<string>();
  const generation = useRef(0);
  useEffect(() => {
    generation.current += 1;
    setOverview(undefined);
    setDetail(undefined);
    setFailure(undefined);
    setBusy(false);
    return () => {
      generation.current += 1;
    };
  }, [context, listingId]);

  async function loadOverview(): Promise<void> {
    const requestedGeneration = generation.current;
    setFailure(undefined);
    setOverview(undefined);
    const result = await fetchFeedbackOverview(
      context,
      listingId,
      new Date(from).toISOString(),
      new Date(to).toISOString(),
    );
    if (requestedGeneration !== generation.current) return;
    if (result.ok) setOverview(result.value);
    else setFailure(result.failure);
  }
  async function loadDetail(itemId: string): Promise<void> {
    const requestedGeneration = generation.current;
    setDetail(undefined);
    setFailure(undefined);
    const result = await fetchFeedbackDetail(context, listingId, itemId);
    if (requestedGeneration !== generation.current) return;
    if (result.ok) {
      setDetail(result.value);
      const latest = result.value.classifications.at(-1);
      setTheme(latest?.themeCode ?? '');
      setQualification(latest?.qualificationState ?? 'UNCERTAIN');
      setReason('');
    } else setFailure(result.failure);
  }

  const captureRequiredMissing =
    rawObservationId.trim() === '' ||
    identityPointer.trim() === '' ||
    listingPointer.trim() === '' ||
    textPointer.trim() === '';
  const periodMissing = from === '' || to === '';
  const themeInvalid = !THEME_PATTERN.test(theme);

  const captureForm = (
    <Form
      layout="vertical"
      disabled={busy}
      onFinish={() => {
        setBusy(true);
        setFailure(undefined);
        setCaptured(undefined);
        const requestedGeneration = generation.current;
        void captureFeedbackSource(context, listingId, {
          rawObservationId,
          itemPointer,
          identityPointer,
          listingPointer,
          textPointer,
        })
          .then(async (result) => {
            if (requestedGeneration !== generation.current) return;
            if (result.ok) {
              setCaptured(result.value);
              await loadDetail(result.value);
            } else setFailure(result.failure);
          })
          .finally(() => {
            setBusy(false);
          });
      }}
    >
      <Hint>{t('feedbackCaptureHelp')}</Hint>
      <Row gutter={16}>
        <Col xs={24} md={12}>
          <Form.Item label={t('feedbackRawObservation')} required>
            <Input
              value={rawObservationId}
              onChange={(event) => {
                setRawObservationId(event.target.value);
              }}
            />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item label={t('feedbackItemPointer')}>
            <Input
              value={itemPointer}
              placeholder="/items/0"
              onChange={(event) => {
                setItemPointer(event.target.value);
              }}
            />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item label={t('feedbackIdentityPointer')} required>
            <Input
              value={identityPointer}
              onChange={(event) => {
                setIdentityPointer(event.target.value);
              }}
            />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item label={t('feedbackListingPointer')} required>
            <Input
              value={listingPointer}
              onChange={(event) => {
                setListingPointer(event.target.value);
              }}
            />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item label={t('feedbackTextPointer')} required>
            <Input
              value={textPointer}
              onChange={(event) => {
                setTextPointer(event.target.value);
              }}
            />
          </Form.Item>
        </Col>
      </Row>
      <Button type="primary" htmlType="submit" loading={busy} disabled={captureRequiredMissing}>
        {t('feedbackCapture')}
      </Button>
    </Form>
  );

  return (
    <section aria-label={t('feedback')}>
      <SectionCard title={t('feedback')}>
        <Stack>
          <Hint>{t('feedbackBoundary')}</Hint>
          {failure !== undefined && <ListingProblem failure={failure} />}
          <Collapse
            size="small"
            items={[{ key: 'capture', label: t('feedbackCapture'), children: captureForm }]}
          />
          {captured === undefined ? null : (
            <Alert
              role="status"
              type="success"
              showIcon
              title={t('feedbackCaptured')}
              description={<IdText value={captured} />}
            />
          )}
          <Form
            layout="vertical"
            disabled={busy}
            onFinish={() => {
              setBusy(true);
              setDetail(undefined);
              void loadOverview().finally(() => {
                setBusy(false);
              });
            }}
          >
            <SubTitle>{t('feedbackPeriod')}</SubTitle>
            <Row gutter={16} align="bottom">
              <Col xs={24} md={9}>
                <Form.Item label={t('feedbackFrom')} required>
                  <InstantPicker value={from} onChange={setFrom} />
                </Form.Item>
              </Col>
              <Col xs={24} md={9}>
                <Form.Item label={t('feedbackTo')} required>
                  <InstantPicker value={to} onChange={setTo} />
                </Form.Item>
              </Col>
              <Col xs={24} md={6}>
                <Form.Item>
                  <Button type="primary" htmlType="submit" loading={busy} disabled={periodMissing}>
                    {t('feedbackLoad')}
                  </Button>
                </Form.Item>
              </Col>
            </Row>
          </Form>
          {overview !== undefined && (
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
              {overview.items.length === 0 ? (
                <EmptyState description={t('noFeedback')} />
              ) : (
                <Table
                  size="middle"
                  rowKey="id"
                  pagination={false}
                  dataSource={[...overview.items]}
                  columns={[
                    {
                      key: 'observed',
                      title: t('sourceTime'),
                      render: (_, item) => (
                        <Button
                          type="link"
                          style={{ padding: 0 }}
                          disabled={busy}
                          onClick={() => {
                            setBusy(true);
                            void loadDetail(item.id).finally(() => {
                              setBusy(false);
                            });
                          }}
                        >
                          <When value={item.observedAt} />
                        </Button>
                      ),
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
          )}
          {detail !== undefined && (
            <Card size="small" type="inner" title={t('feedbackOriginal')}>
              <Stack>
                <Details
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
                <div>
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
                                <Code
                                  family="feedbackQualification"
                                  code={label.qualificationState}
                                />
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
                </div>
                <Form
                  layout="vertical"
                  disabled={busy}
                  onFinish={() => {
                    setBusy(true);
                    setFailure(undefined);
                    const itemId = detail.original.id;
                    const requestedGeneration = generation.current;
                    void classifyFeedback(context, listingId, itemId, {
                      themeCode: theme,
                      qualificationState: qualification,
                      classifierVersion: 'HUMAN_V1',
                      reason,
                    })
                      .then(async (result) => {
                        if (requestedGeneration !== generation.current) return;
                        if (result.ok) {
                          await Promise.all([loadOverview(), loadDetail(itemId)]);
                        } else setFailure(result.failure);
                      })
                      .finally(() => {
                        setBusy(false);
                      });
                  }}
                >
                  <SubTitle>{t('feedbackCorrect')}</SubTitle>
                  <Row gutter={16}>
                    <Col xs={24} md={12}>
                      <Form.Item
                        label={t('feedbackTheme')}
                        required
                        {...(theme !== '' && themeInvalid
                          ? { validateStatus: 'error' as const, help: t('feedbackThemeInvalid') }
                          : { extra: t('feedbackThemeHelp') })}
                      >
                        <Input
                          value={theme}
                          onChange={(event) => {
                            setTheme(event.target.value);
                          }}
                        />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={12}>
                      <Form.Item label={t('feedbackQualification')}>
                        <Select
                          value={qualification}
                          onChange={setQualification}
                          options={codeOptions('feedbackQualification', [
                            'CONFIRMED',
                            'UNCERTAIN',
                            'CONFLICTED',
                          ])}
                        />
                      </Form.Item>
                    </Col>
                  </Row>
                  <Form.Item label={t('feedbackReason')} required>
                    <Input.TextArea
                      maxLength={512}
                      autoSize={{ minRows: 2, maxRows: 4 }}
                      value={reason}
                      onChange={(event) => {
                        setReason(event.target.value);
                      }}
                    />
                  </Form.Item>
                  <Button
                    type="primary"
                    htmlType="submit"
                    loading={busy}
                    disabled={themeInvalid || reason.trim() === ''}
                  >
                    {t('feedbackCorrect')}
                  </Button>
                </Form>
              </Stack>
            </Card>
          )}
        </Stack>
      </SectionCard>
    </section>
  );
}
