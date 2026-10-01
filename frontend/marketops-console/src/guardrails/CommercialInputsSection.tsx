import { App, Flex, Form, Input, InputNumber, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { CommercialInput, CommercialInputs } from '../api/commercialInputs';
import { enterCommercialInput, fetchCommercialInputs } from '../api/commercialInputs';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { formatMoney } from '../format';
import { codeLabel } from '../i18n';
import {
  commercialInputsText as text,
  INPUT_LABELS,
  POLICY_SCOPE_LABELS,
  POLICY_STATUS_LABELS,
} from '../i18n/zh/guardrails';
import { ActionModal, DateTime, FailureAlert, InfoTip, SectionCard } from '../ui';
import type { TagColor } from '../ui';

type Load =
  | { readonly kind: 'loading' }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure }
  | { readonly kind: 'loaded'; readonly inputs: CommercialInputs };

interface EntryValues {
  readonly amount?: number;
  readonly reason?: string;
}

/** One row of the inputs table: a code and the version of it in force, if any. */
interface InputLine {
  readonly inputCode: string;
  readonly inForce: CommercialInput | undefined;
}

const STATUS_COLORS: Readonly<Record<string, TagColor>> = {
  ACTIVE: 'success',
  ENDED: 'default',
  CANCELLED: 'default',
};

/**
 * The store's required profit and safety buffer per unit: what the price guardrail adds to the unit
 * cost when it works out the lowest acceptable price.
 */
export function CommercialInputsSection({
  context,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  const [entering, setEntering] = useState<InputLine | undefined>(undefined);

  useEffect(() => {
    let live = true;
    void fetchCommercialInputs(context, storeId).then((outcome) => {
      if (!live) return;
      setLoad(
        outcome.ok
          ? { kind: 'loaded', inputs: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, storeId, generation]);

  const lineColumns: TableColumnsType<InputLine> = [
    {
      key: 'input',
      title: text.input,
      render: (_, line) => codeLabel(INPUT_LABELS, line.inputCode),
    },
    {
      key: 'value',
      title: text.value,
      render: (_, line) =>
        line.inForce === undefined ? (
          <Typography.Text type="warning">{text.notSet}</Typography.Text>
        ) : (
          formatMoney(line.inForce.amount, line.inForce.currencyCode)
        ),
    },
    {
      key: 'scope',
      title: text.scope,
      render: (_, line) =>
        line.inForce === undefined ? '—' : codeLabel(POLICY_SCOPE_LABELS, line.inForce.scopeKind),
    },
    {
      key: 'effectiveFrom',
      title: text.effectiveFrom,
      render: (_, line) =>
        line.inForce === undefined ? '—' : <DateTime value={line.inForce.effectiveFrom} />,
    },
    {
      key: 'note',
      title: text.note,
      render: (_, line) => (
        <Typography.Text type="secondary">{line.inForce?.note ?? '—'}</Typography.Text>
      ),
    },
    {
      key: 'actions',
      title: '',
      render: (_, line) => (
        <Typography.Link
          onClick={() => {
            setEntering(line);
          }}
        >
          {text.set}
        </Typography.Link>
      ),
    },
  ];

  const historyColumns: TableColumnsType<CommercialInput> = [
    {
      key: 'input',
      title: text.input,
      render: (_, version) => codeLabel(INPUT_LABELS, version.inputCode),
    },
    {
      key: 'value',
      title: text.amount,
      render: (_, version) => formatMoney(version.amount, version.currencyCode),
    },
    {
      key: 'status',
      title: text.status,
      render: (_, version) => (
        <Tag color={STATUS_COLORS[version.status] ?? 'default'}>
          {codeLabel(POLICY_STATUS_LABELS, version.status)}
        </Tag>
      ),
    },
    {
      key: 'effectiveFrom',
      title: text.effectiveFrom,
      render: (_, version) => <DateTime value={version.effectiveFrom} />,
    },
    {
      key: 'enteredBy',
      title: text.enteredBy,
      render: (_, version) => (version.enteredByViewer ? text.enteredByYou : text.enteredByOther),
    },
    {
      key: 'note',
      title: text.note,
      render: (_, version) => (
        <Typography.Text type="secondary">{version.note ?? '—'}</Typography.Text>
      ),
    },
  ];

  let body: React.JSX.Element;
  if (load.kind === 'loading') {
    body = <Typography.Text type="secondary">…</Typography.Text>;
  } else if (load.kind === 'failed') {
    body = <FailureAlert failure={load.failure} />;
  } else {
    const { inputCodes, inForce, versions } = load.inputs;
    const lines: InputLine[] = inputCodes.map((inputCode) => ({
      inputCode,
      inForce: inForce[inputCode],
    }));
    body = (
      <Flex vertical gap={16}>
        <Table<InputLine>
          rowKey="inputCode"
          size="small"
          columns={lineColumns}
          dataSource={lines}
          pagination={false}
          scroll={{ x: 'max-content' }}
        />
        {versions.length > 0 && (
          <Flex vertical gap={8}>
            <Typography.Text strong>{text.history}</Typography.Text>
            <Table<CommercialInput>
              rowKey="inputId"
              size="small"
              columns={historyColumns}
              dataSource={[...versions]}
              pagination={false}
              scroll={{ x: 'max-content' }}
            />
          </Flex>
        )}
      </Flex>
    );
  }

  const label = entering === undefined ? '' : codeLabel(INPUT_LABELS, entering.inputCode);
  const currency = load.kind === 'loaded' ? (load.inputs.currencyCode ?? undefined) : undefined;
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
      <ActionModal<EntryValues>
        open={entering !== undefined}
        onClose={() => {
          setEntering(undefined);
        }}
        title={text.setTitle(label)}
        consequence={text.setConsequence}
        initialValues={{
          amount:
            entering?.inForce?.amount === undefined || entering.inForce.amount === null
              ? 0
              : Number(entering.inForce.amount),
        }}
        width={520}
        onSubmit={async (values) => {
          if (entering === undefined || values.amount === undefined) return undefined;
          const outcome = await enterCommercialInput(context, {
            storeId,
            inputCode: entering.inputCode,
            amount: Number(values.amount.toFixed(2)),
            reason: (values.reason ?? '').trim(),
          });
          if (!outcome.ok) return outcome.failure;
          void message.success(text.entered);
          setGeneration((value) => value + 1);
          return undefined;
        }}
      >
        <Form.Item<EntryValues>
          name="amount"
          label={text.amount}
          rules={[{ required: true, message: text.amountRequired }]}
        >
          <InputNumber min={0} step={10} precision={2} suffix={currency} />
        </Form.Item>
        <Form.Item<EntryValues>
          name="reason"
          label={text.note}
          rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
        >
          <Input.TextArea rows={2} maxLength={500} showCount placeholder={text.reasonPlaceholder} />
        </Form.Item>
      </ActionModal>
    </SectionCard>
  );
}
