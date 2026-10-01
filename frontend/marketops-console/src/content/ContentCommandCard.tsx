import { App, Descriptions, Flex, Form, Input, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleRequest } from '../api/console';
import type { ContentCommand, ContentEvent } from '../api/contentWrites';
import {
  TERMINAL_CONTENT_STATES,
  fetchContentCommand,
  resolveContentCommand,
} from '../api/contentWrites';
import { codeLabel } from '../i18n';
import {
  CONTENT_EVENT_LABELS,
  CONTENT_GATE_LABELS,
  CONTENT_MATCH_LABELS,
  CONTENT_OUTCOME_LABELS,
  CONTENT_STATE_LABELS,
  contentCommandText as text,
} from '../i18n/zh/contentWrites';
import { ActionModal, DateTime } from '../ui';
import type { TagColor } from '../ui';
import { characterCount } from './contentText';

/** How often a command still moving is read again, in milliseconds. */
const REFRESH_MILLIS = 10_000;

const STATE_COLORS: Readonly<Record<string, TagColor>> = {
  PENDING: 'processing',
  AWAITING_TASK: 'processing',
  AWAITING_READBACK: 'processing',
  UNKNOWN_REQUIRES_READBACK: 'warning',
  READBACK_MISMATCH: 'warning',
  SUCCEEDED: 'success',
  FAILED_BEFORE_WRITE: 'default',
  FAILED: 'error',
  CANCELLED: 'default',
  CLOSED: 'default',
};

interface ReasonValues {
  readonly reason?: string;
}

/** A state as a coloured tag. */
export function ContentStateTag({ state }: { readonly state: string }): React.JSX.Element {
  return (
    <Tag color={STATE_COLORS[state] ?? 'default'}>{codeLabel(CONTENT_STATE_LABELS, state)}</Tag>
  );
}

/** One side of a field change: the text, or a note that the field is kept. */
function FieldChange({
  changed,
  before,
  after,
}: {
  readonly changed: boolean;
  readonly before: string | null;
  readonly after: string;
}): React.JSX.Element {
  if (!changed) {
    return <Typography.Text type="secondary">{text.unchangedField}</Typography.Text>;
  }
  return (
    <Flex vertical gap={4}>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {text.before}（{String(characterCount(before ?? ''))}）
      </Typography.Text>
      <Typography.Paragraph
        lang="ru"
        style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}
        type="secondary"
      >
        {before ?? '—'}
      </Typography.Paragraph>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {text.after}（{String(characterCount(after))}）
      </Typography.Text>
      <Typography.Paragraph lang="ru" style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
        {after}
      </Typography.Paragraph>
    </Flex>
  );
}

const EVENT_COLUMNS: TableColumnsType<ContentEvent> = [
  {
    key: 'when',
    title: '',
    width: 150,
    render: (_, event) => <DateTime value={event.recordedAt} />,
  },
  {
    key: 'kind',
    title: '',
    render: (_, event) => codeLabel(CONTENT_EVENT_LABELS, event.kind),
  },
  {
    key: 'outcome',
    title: '',
    render: (_, event) => (
      <Flex vertical gap={2}>
        <Space size={4} wrap>
          {event.outcome !== null && <Tag>{event.outcome}</Tag>}
          {event.httpStatus !== null && <Tag>{`HTTP ${String(event.httpStatus)}`}</Tag>}
          {event.taskStatus !== null && <Tag>{event.taskStatus}</Tag>}
          {event.stateAfter !== null && <ContentStateTag state={event.stateAfter} />}
          {event.titleMatch !== null && (
            <Tag>{`${text.titleChange}：${codeLabel(CONTENT_MATCH_LABELS, event.titleMatch)}`}</Tag>
          )}
          {event.descriptionMatch !== null && (
            <Tag>{`${text.descriptionChange}：${codeLabel(CONTENT_MATCH_LABELS, event.descriptionMatch)}`}</Tag>
          )}
        </Space>
        {event.detail !== null && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {event.kind === 'GATE_CLOSED'
              ? event.detail
                  .split(',')
                  .map((reason) => codeLabel(CONTENT_GATE_LABELS, reason))
                  .join('、')
              : event.detail}
          </Typography.Text>
        )}
      </Flex>
    ),
  },
];

/**
 * One title and description change and what became of it: the state, the change itself, every
 * call the platform made, and the two decisions a person may take about a command the worker
 * could not finish. Read again every ten seconds while it is still moving.
 */
export function ContentCommandCard({
  context,
  command: initial,
  onChanged,
}: {
  readonly context: ConsoleRequest;
  readonly command: ContentCommand;
  readonly onChanged?: (command: ContentCommand) => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [command, setCommand] = useState<ContentCommand>(initial);
  const terminal = TERMINAL_CONTENT_STATES.has(command.state);

  useEffect(() => {
    setCommand(initial);
  }, [initial]);

  useEffect(() => {
    if (terminal) return undefined;
    const timer = window.setInterval(() => {
      if (document.visibilityState !== 'visible') return;
      void fetchContentCommand(context, command.id).then((outcome) => {
        if (outcome.ok) {
          setCommand(outcome.value);
          onChanged?.(outcome.value);
        }
      });
    }, REFRESH_MILLIS);
    return () => {
      window.clearInterval(timer);
    };
  }, [context, command.id, terminal, onChanged]);

  const replace = (next: ContentCommand): void => {
    setCommand(next);
    onChanged?.(next);
  };

  const canReread =
    command.state === 'UNKNOWN_REQUIRES_READBACK' || command.state === 'READBACK_MISMATCH';
  const canClose = command.state === 'PENDING' || canReread;

  return (
    <Flex vertical gap={10}>
      <Descriptions size="small" column={{ xs: 1, md: 2 }} bordered>
        <Descriptions.Item label={text.state}>
          <ContentStateTag state={command.state} />
        </Descriptions.Item>
        <Descriptions.Item label={text.offer}>{command.offerKey}</Descriptions.Item>
        <Descriptions.Item label={text.approvedAt}>
          <DateTime value={command.approvedAt} />
        </Descriptions.Item>
        <Descriptions.Item label={text.approvalExpiresAt}>
          <DateTime value={command.approvalExpiresAt} />
        </Descriptions.Item>
        {command.outcomeCode !== null && (
          <Descriptions.Item label={text.outcome} span="filled">
            <Flex vertical gap={2}>
              <Typography.Text>
                {codeLabel(CONTENT_OUTCOME_LABELS, command.outcomeCode)}
              </Typography.Text>
              {command.outcomeDetail !== null && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {command.outcomeDetail}
                </Typography.Text>
              )}
            </Flex>
          </Descriptions.Item>
        )}
        {command.state === 'PENDING' && (
          <Descriptions.Item label={text.gate} span="filled">
            {command.gateReasons.length === 0 ? (
              <Typography.Text type="success">{text.gateOpen}</Typography.Text>
            ) : (
              <Space size={[4, 4]} wrap>
                {command.gateReasons.map((reason) => (
                  <Tag key={reason} color="warning">
                    {codeLabel(CONTENT_GATE_LABELS, reason)}
                  </Tag>
                ))}
              </Space>
            )}
          </Descriptions.Item>
        )}
        {command.nativeTaskKey !== null && (
          <Descriptions.Item label={text.task}>{command.nativeTaskKey}</Descriptions.Item>
        )}
        {!terminal && command.nextActionAt !== null && (
          <Descriptions.Item label={text.nextActionAt}>
            <DateTime value={command.nextActionAt} />
          </Descriptions.Item>
        )}
        <Descriptions.Item label={text.titleChange} span="filled">
          <FieldChange
            changed={command.titleChanged}
            before={command.priorTitle}
            after={command.targetTitle}
          />
        </Descriptions.Item>
        <Descriptions.Item label={text.descriptionChange} span="filled">
          <FieldChange
            changed={command.descriptionChanged}
            before={command.priorDescription}
            after={command.targetDescription}
          />
        </Descriptions.Item>
      </Descriptions>
      {(canReread || canClose) && (
        <Space size={8} wrap>
          {canReread && (
            <ActionModal<ReasonValues>
              trigger={{ label: text.readback, size: 'small' }}
              title={text.readbackTitle}
              consequence={text.readbackConsequence}
              width={520}
              onSubmit={async () => {
                const outcome = await resolveContentCommand(context, command.id, 'readback');
                if (!outcome.ok) return outcome.failure;
                replace(outcome.value);
                void message.success(text.refreshed);
                return undefined;
              }}
            />
          )}
          {canClose && (
            <ActionModal<ReasonValues>
              trigger={{ label: text.closeCommand, size: 'small', danger: true }}
              title={text.closeTitle}
              consequence={text.closeConsequence}
              danger
              width={520}
              onSubmit={async (values) => {
                const outcome = await resolveContentCommand(
                  context,
                  command.id,
                  'closure',
                  (values.reason ?? '').trim(),
                );
                if (!outcome.ok) return outcome.failure;
                replace(outcome.value);
                void message.success(text.refreshed);
                return undefined;
              }}
            >
              <Form.Item<ReasonValues>
                name="reason"
                label={text.reason}
                rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
              >
                <Input.TextArea rows={2} maxLength={500} showCount />
              </Form.Item>
            </ActionModal>
          )}
        </Space>
      )}
      <Flex vertical gap={4}>
        <Typography.Text strong>{text.timeline}</Typography.Text>
        <Table<ContentEvent>
          rowKey="sequence"
          size="small"
          showHeader={false}
          columns={EVENT_COLUMNS}
          dataSource={[...command.events].reverse()}
          pagination={false}
          scroll={{ x: 'max-content' }}
        />
      </Flex>
    </Flex>
  );
}
