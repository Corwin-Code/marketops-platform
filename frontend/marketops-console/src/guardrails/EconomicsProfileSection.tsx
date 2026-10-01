import {
  App,
  Alert,
  Descriptions,
  Flex,
  Form,
  Input,
  InputNumber,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type {
  EconomicsProfiles,
  ProfileComponent,
  ProfileDraft,
  ProfileFamily,
} from '../api/economicsProfiles';
import {
  approveEconomicsProfileDraft,
  closeEconomicsProfileDraft,
  fetchEconomicsProfiles,
  submitEconomicsProfileDraft,
} from '../api/economicsProfiles';
import { formatMoney, formatPercent } from '../format';
import { codeLabel } from '../i18n';
import {
  COMPONENT_LABELS,
  DRAFT_STATE_LABELS,
  economicsProfileText as text,
  FAMILY_LABELS,
  VERIFICATION_LABELS,
} from '../i18n/zh/guardrails';
import { ActionModal, DateTime, EmptyState, FailureAlert, InfoTip, SectionCard } from '../ui';
import type { TagColor } from '../ui';

type Load =
  | { readonly kind: 'loading' }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure }
  | { readonly kind: 'loaded'; readonly profiles: EconomicsProfiles };

/** One line of a profile: a cost, or a family that does not apply. */
interface Line {
  readonly key: string;
  readonly familyCode: string;
  readonly componentCode: string | null;
  readonly value: string;
  readonly evidence: string;
}

interface ApproveValues {
  readonly verificationDays?: number;
  readonly note?: string;
}

interface CloseValues {
  readonly reason?: string;
}

const STATE_COLORS: Readonly<Record<string, TagColor>> = {
  SUBMITTED: 'processing',
  APPROVED: 'success',
  REJECTED: 'error',
  SUPERSEDED: 'default',
};

/** The lines of a profile or draft: its costs family by family, then the families that do not apply. */
function lines(
  families: readonly ProfileFamily[],
  components: readonly ProfileComponent[],
  currencyCode: string,
): readonly Line[] {
  const costs: Line[] = components.map((component) => ({
    key: component.componentCode,
    familyCode: component.familyCode,
    componentCode: component.componentCode,
    value:
      component.kind === 'FIXED'
        ? formatMoney(component.fixedAmount, currencyCode)
        : component.kind === 'PERCENTAGE'
          ? formatPercent(component.rateValue)
          : `${formatMoney(component.fixedAmount, currencyCode)} + ${formatPercent(component.rateValue)}`,
    evidence: component.evidence,
  }));
  const inapplicable: Line[] = families
    .filter((family) => family.applicability === 'VERIFIED_NOT_APPLICABLE')
    .map((family) => ({
      key: family.familyCode,
      familyCode: family.familyCode,
      componentCode: null,
      value: text.notApplicable,
      evidence: family.evidence,
    }));
  return [...costs, ...inapplicable];
}

const LINE_COLUMNS: TableColumnsType<Line> = [
  {
    key: 'family',
    title: text.family,
    render: (_, line) => codeLabel(FAMILY_LABELS, line.familyCode),
  },
  {
    key: 'component',
    title: text.component,
    render: (_, line) =>
      line.componentCode === null ? '—' : codeLabel(COMPONENT_LABELS, line.componentCode),
  },
  {
    key: 'value',
    title: text.value,
    render: (_, line) =>
      line.componentCode === null ? (
        <Typography.Text type="secondary">{line.value}</Typography.Text>
      ) : (
        line.value
      ),
  },
  {
    key: 'evidence',
    title: text.evidence,
    render: (_, line) => <Typography.Text type="secondary">{line.evidence}</Typography.Text>,
  },
];

/**
 * The store's economics projection profile: the costs the price guardrail projects a proposed price
 * with, generated from the marketplace's tariffs and published once a second Owner approves.
 */
export function EconomicsProfileSection({
  context,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  const [approving, setApproving] = useState<ProfileDraft | undefined>(undefined);
  const [closing, setClosing] = useState<ProfileDraft | undefined>(undefined);

  useEffect(() => {
    let live = true;
    void fetchEconomicsProfiles(context, storeId).then((outcome) => {
      if (!live) return;
      setLoad(
        outcome.ok
          ? { kind: 'loaded', profiles: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, storeId, generation]);

  const reload = (): void => {
    setGeneration((value) => value + 1);
  };

  const draftColumns: TableColumnsType<ProfileDraft> = [
    {
      key: 'submittedAt',
      title: text.submittedAt,
      render: (_, draft) => <DateTime value={draft.submittedAt} />,
    },
    {
      key: 'state',
      title: text.state,
      render: (_, draft) => (
        <Tag color={STATE_COLORS[draft.state] ?? 'default'}>
          {codeLabel(DRAFT_STATE_LABELS, draft.state)}
        </Tag>
      ),
    },
    {
      key: 'submitter',
      title: text.submitter,
      render: (_, draft) => (draft.submittedByViewer ? text.submitterYou : text.submitterOther),
    },
    {
      key: 'content',
      title: text.supportedPrices,
      render: (_, draft) =>
        text.draftContent(
          draft.components.length,
          text.supportedPricesValue(
            formatMoney(draft.minimumSupportedPrice, draft.currencyCode),
            formatMoney(draft.maximumSupportedPrice, draft.currencyCode),
          ),
        ),
    },
    {
      key: 'actions',
      title: '',
      render: (_, draft) =>
        draft.state !== 'SUBMITTED' ? null : draft.submittedByViewer ? (
          <Space size={8} wrap>
            <Typography.Text type="secondary">{text.waitingForOther}</Typography.Text>
            <Typography.Link
              onClick={() => {
                setClosing(draft);
              }}
            >
              {text.withdraw}
            </Typography.Link>
          </Space>
        ) : (
          <Space size={8} wrap>
            <Typography.Link
              onClick={() => {
                setApproving(draft);
              }}
            >
              {text.approve}
            </Typography.Link>
            <Typography.Link
              type="danger"
              onClick={() => {
                setClosing(draft);
              }}
            >
              {text.reject}
            </Typography.Link>
          </Space>
        ),
    },
  ];

  let body: React.JSX.Element;
  if (load.kind === 'loading') {
    body = <Typography.Text type="secondary">…</Typography.Text>;
  } else if (load.kind === 'failed') {
    body = <FailureAlert failure={load.failure} />;
  } else {
    const { profile, drafts, declaredModes } = load.profiles;
    const fbs = declaredModes.length === 1 && declaredModes[0] === 'SELLER_FULFILLED';
    body = (
      <Flex vertical gap={16}>
        {!fbs && <Alert type="warning" showIcon title={text.notFbs} />}
        {profile === null ? (
          <EmptyState description={text.noProfile} />
        ) : (
          <Flex vertical gap={8}>
            <Descriptions size="small" column={{ xs: 1, md: 2 }} bordered title={text.inForce}>
              <Descriptions.Item label={text.version}>{profile.version}</Descriptions.Item>
              <Descriptions.Item label={text.effectiveFrom}>
                <DateTime value={profile.effectiveFrom} />
              </Descriptions.Item>
              <Descriptions.Item label={text.verification}>
                {codeLabel(VERIFICATION_LABELS, profile.verificationState)}
              </Descriptions.Item>
              <Descriptions.Item label={text.verificationExpiresAt}>
                <DateTime value={profile.verificationExpiresAt} />
              </Descriptions.Item>
              <Descriptions.Item label={text.supportedPrices}>
                {text.supportedPricesValue(
                  formatMoney(profile.minimumSupportedPrice, profile.currencyCode),
                  formatMoney(profile.maximumSupportedPrice, profile.currencyCode),
                )}
              </Descriptions.Item>
            </Descriptions>
            <Table<Line>
              rowKey="key"
              size="small"
              columns={LINE_COLUMNS}
              dataSource={[...lines(profile.families, profile.components, profile.currencyCode)]}
              pagination={false}
              scroll={{ x: 'max-content' }}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.evidence}：{profile.evidence}
            </Typography.Text>
          </Flex>
        )}
        <Flex vertical gap={8}>
          <Space size={8} wrap>
            <Typography.Text strong>{text.drafts}</Typography.Text>
            <ActionModal<object>
              trigger={{ label: text.generate, size: 'small', disabled: !fbs }}
              title={text.generateTitle}
              consequence={text.generateConsequence}
              width={560}
              onSubmit={async () => {
                const outcome = await submitEconomicsProfileDraft(context, storeId);
                if (!outcome.ok) return outcome.failure;
                void message.success(text.generated);
                reload();
                return undefined;
              }}
            />
          </Space>
          <Table<ProfileDraft>
            rowKey="draftId"
            size="small"
            columns={draftColumns}
            dataSource={[...drafts]}
            pagination={false}
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: text.noDrafts }}
            expandable={{
              expandedRowRender: (draft) => (
                <Table<Line>
                  rowKey="key"
                  size="small"
                  columns={LINE_COLUMNS}
                  dataSource={[...lines(draft.families, draft.components, draft.currencyCode)]}
                  pagination={false}
                  scroll={{ x: 'max-content' }}
                />
              ),
            }}
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
      <ActionModal<ApproveValues>
        open={approving !== undefined}
        onClose={() => {
          setApproving(undefined);
        }}
        title={text.approveTitle}
        consequence={text.approveConsequence}
        initialValues={{ verificationDays: 30 }}
        width={520}
        onSubmit={async (values) => {
          if (approving === undefined) return undefined;
          const note = (values.note ?? '').trim();
          const outcome = await approveEconomicsProfileDraft(context, approving.draftId, {
            expectedVersion: approving.version,
            note: note === '' ? null : note,
            verificationDays: values.verificationDays ?? 30,
          });
          if (!outcome.ok) return outcome.failure;
          void message.success(text.approved);
          reload();
          return undefined;
        }}
      >
        <Form.Item<ApproveValues> name="verificationDays" label={text.verificationDays}>
          <InputNumber min={1} max={90} precision={0} />
        </Form.Item>
        <Form.Item<ApproveValues> name="note" label={text.note}>
          <Input.TextArea rows={2} maxLength={500} showCount />
        </Form.Item>
      </ActionModal>
      <ActionModal<CloseValues>
        open={closing !== undefined}
        onClose={() => {
          setClosing(undefined);
        }}
        title={closing?.submittedByViewer === true ? text.withdrawTitle : text.rejectTitle}
        consequence={text.closeConsequence}
        danger
        width={520}
        onSubmit={async (values) => {
          if (closing === undefined) return undefined;
          const outcome = await closeEconomicsProfileDraft(context, closing.draftId, {
            expectedVersion: closing.version,
            reason: (values.reason ?? '').trim(),
          });
          if (!outcome.ok) return outcome.failure;
          void message.success(text.closed);
          reload();
          return undefined;
        }}
      >
        <Form.Item<CloseValues>
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
