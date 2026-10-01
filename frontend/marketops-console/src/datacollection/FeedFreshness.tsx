import {
  App,
  Checkbox,
  Flex,
  Form,
  Input,
  InputNumber,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { FeedAttestation, FeedFreshness, FeedState } from '../api/feedFreshness';
import { attestFeedAbsence, fetchFeedFreshness, revokeFeedAttestation } from '../api/feedFreshness';
import { codeLabel } from '../i18n';
import {
  ATTESTATION_STATE_LABELS,
  FEED_LABELS,
  feedFreshnessText as text,
} from '../i18n/zh/dataCollection';
import { ActionModal, DateTime, FailureAlert, InfoTip, SectionCard } from '../ui';
import type { TagColor } from '../ui';

type Load =
  | { readonly kind: 'loading' }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure }
  | { readonly kind: 'loaded'; readonly freshness: FeedFreshness };

interface AttestValues {
  readonly feedCodes?: readonly string[];
  readonly statement?: string;
  readonly validDays?: number;
}

interface RevokeValues {
  readonly reason?: string;
}

const STATE_COLORS: Readonly<Record<string, TagColor>> = {
  STANDING: 'success',
  LAPSED: 'warning',
  REVOKED: 'default',
  EXPIRED: 'default',
};

/** An age in days and hours, or hours and minutes under a day. */
function age(seconds: number): string {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  return days > 0 ? text.ageDays(days, hours) : text.ageHours(hours, minutes);
}

/** What a watermark rests on, in words; the recorded reference stays in the tooltip. */
function evidenceLabel(evidence: string): string {
  if (evidence.startsWith('attestation:')) return text.evidenceAttestation;
  if (evidence.startsWith('finance-inputs:')) return text.evidenceFinanceInputs;
  if (evidence.startsWith('ingestion-run:')) return text.evidenceCollection;
  return evidence;
}

/** Why a feed has no watermark yet. */
function missingHint(feed: FeedState): string {
  if (feed.attestable) return text.missingAttestable;
  if (feed.feedCode === 'COMMERCIAL_INPUTS') return text.missingCommercialInputs;
  return text.missingCollected;
}

/**
 * How fresh each feed of the price guardrail is: the guardrail refuses an approval while any of the
 * eight feeds is older than the commercial policy allows. The platform records the collected feeds
 * after each scheduled collection; the Owner attests that the store has no returns, finance fees or
 * advertising while it has none, and those statements end by themselves.
 */
export function FeedFreshnessSection({
  context,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  const [revoking, setRevoking] = useState<FeedAttestation | undefined>(undefined);

  useEffect(() => {
    let live = true;
    void fetchFeedFreshness(context, storeId).then((outcome) => {
      if (!live) return;
      setLoad(
        outcome.ok
          ? { kind: 'loaded', freshness: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, storeId, generation]);

  const feedColumns: TableColumnsType<FeedState> = [
    {
      key: 'feed',
      title: text.feed,
      render: (_, feed) => codeLabel(FEED_LABELS, feed.feedCode),
    },
    {
      key: 'effective',
      title: (
        <Space size={4}>
          {text.effectiveAt}
          <InfoTip title={text.effectiveHint} />
        </Space>
      ),
      render: (_, feed) =>
        feed.effectiveAt === null ? (
          <Typography.Text type="secondary">{missingHint(feed)}</Typography.Text>
        ) : (
          <DateTime value={feed.effectiveAt} />
        ),
    },
    {
      key: 'age',
      title: text.age,
      render: (_, feed) => (feed.ageSeconds === null ? '—' : age(feed.ageSeconds)),
    },
    {
      key: 'evidence',
      title: text.evidence,
      render: (_, feed) =>
        feed.evidence === null ? (
          '—'
        ) : (
          <Tooltip title={feed.evidence}>
            <Typography.Text>{evidenceLabel(feed.evidence)}</Typography.Text>
          </Tooltip>
        ),
    },
  ];

  const attestationColumns: TableColumnsType<FeedAttestation> = [
    {
      key: 'feed',
      title: text.feed,
      render: (_, attestation) => codeLabel(FEED_LABELS, attestation.feedCode),
    },
    { key: 'statement', title: text.statement, dataIndex: 'statement' },
    {
      key: 'attestedAt',
      title: text.attestedAt,
      render: (_, attestation) => <DateTime value={attestation.attestedAt} />,
    },
    {
      key: 'expiresAt',
      title: text.expiresAt,
      render: (_, attestation) => <DateTime value={attestation.expiresAt} />,
    },
    {
      key: 'state',
      title: text.state,
      render: (_, attestation) => (
        <Space size={4} wrap>
          <Tag color={STATE_COLORS[attestation.state] ?? 'default'}>
            {codeLabel(ATTESTATION_STATE_LABELS, attestation.state)}
          </Tag>
          {attestation.state === 'LAPSED' && (
            <Typography.Text type="secondary">{text.lapsedBecauseOrders}</Typography.Text>
          )}
        </Space>
      ),
    },
    {
      key: 'action',
      title: '',
      render: (_, attestation) =>
        attestation.state === 'STANDING' ? (
          <Typography.Link
            onClick={() => {
              setRevoking(attestation);
            }}
          >
            {text.revoke}
          </Typography.Link>
        ) : null,
    },
  ];

  let body: React.JSX.Element;
  if (load.kind === 'loading') {
    body = <Typography.Text type="secondary">…</Typography.Text>;
  } else if (load.kind === 'failed') {
    body = <FailureAlert failure={load.failure} />;
  } else {
    const { feeds, attestations } = load.freshness;
    body = (
      <Flex vertical gap={16}>
        <Table<FeedState>
          rowKey="feedCode"
          size="small"
          columns={feedColumns}
          dataSource={[...feeds]}
          pagination={false}
          scroll={{ x: 'max-content' }}
        />
        <Flex vertical gap={8}>
          <Space size={8} wrap>
            <Typography.Text strong>{text.attestationsTitle}</Typography.Text>
            <ActionModal<AttestValues>
              trigger={{ label: text.attest, size: 'small' }}
              title={text.attestTitle}
              consequence={text.attestConsequence}
              initialValues={{
                feedCodes: ['RETURNS', 'FINANCE_FEES', 'ADVERTISING'],
                statement: text.statementDefault,
                validDays: 30,
              }}
              width={560}
              onSubmit={async (values) => {
                const outcome = await attestFeedAbsence(context, storeId, {
                  feedCodes: values.feedCodes ?? [],
                  statement: (values.statement ?? '').trim(),
                  validDays: values.validDays ?? 30,
                });
                if (!outcome.ok) return outcome.failure;
                void message.success(text.attested);
                setGeneration((value) => value + 1);
                return undefined;
              }}
            >
              <Form.Item<AttestValues>
                name="feedCodes"
                label={text.feedsLabel}
                rules={[{ required: true, type: 'array', min: 1, message: text.feedsRequired }]}
              >
                <Checkbox.Group
                  options={['RETURNS', 'FINANCE_FEES', 'ADVERTISING'].map((code) => ({
                    value: code,
                    label: codeLabel(FEED_LABELS, code),
                  }))}
                />
              </Form.Item>
              <Form.Item<AttestValues>
                name="statement"
                label={text.statement}
                rules={[{ required: true, whitespace: true, message: text.statementRequired }]}
              >
                <Input.TextArea rows={3} maxLength={500} showCount />
              </Form.Item>
              <Form.Item<AttestValues> name="validDays" label={text.validDays}>
                <InputNumber min={1} max={90} precision={0} />
              </Form.Item>
            </ActionModal>
          </Space>
          <Table<FeedAttestation>
            rowKey="attestationId"
            size="small"
            columns={attestationColumns}
            dataSource={[...attestations]}
            pagination={false}
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: text.noAttestations }}
          />
        </Flex>
      </Flex>
    );
  }

  return (
    <SectionCard
      title={
        <Space size={4}>
          {text.title}
          <InfoTip title={text.hint} long />
        </Space>
      }
    >
      {body}
      <ActionModal<RevokeValues>
        open={revoking !== undefined}
        onClose={() => {
          setRevoking(undefined);
        }}
        title={text.revokeTitle}
        consequence={text.revokeConsequence}
        danger
        width={520}
        onSubmit={async (values) => {
          if (revoking === undefined) return undefined;
          const outcome = await revokeFeedAttestation(
            context,
            storeId,
            revoking.attestationId,
            (values.reason ?? '').trim(),
          );
          if (!outcome.ok) return outcome.failure;
          void message.success(text.revoked);
          setGeneration((value) => value + 1);
          return undefined;
        }}
      >
        <Form.Item<RevokeValues>
          name="reason"
          label={text.reason}
          rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
        >
          <Input.TextArea rows={3} maxLength={500} showCount />
        </Form.Item>
      </ActionModal>
    </SectionCard>
  );
}
