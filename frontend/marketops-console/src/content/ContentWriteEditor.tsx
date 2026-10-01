import { App, Button, Flex, Input, Space, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { ContentCommand, ContentCurrent } from '../api/contentWrites';
import {
  TERMINAL_CONTENT_STATES,
  confirmContentChange,
  fetchContentCurrent,
} from '../api/contentWrites';
import { contentEditorText as text } from '../i18n/zh/contentWrites';
import { DateTime, FailureAlert, WriteConfirmModal } from '../ui';
import { ContentCommandCard } from './ContentCommandCard';
import { characterCount, sameContent } from './contentText';

/** Ozon's content rating rewards a description longer than this (official reference, 2026-10-01). */
const RATED_DESCRIPTION_LENGTH = 500;

type Loaded =
  | { readonly kind: 'loading' }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure }
  | { readonly kind: 'loaded'; readonly current: ContentCurrent };

/**
 * Settle a listing's final title and description and confirm writing them to Ozon (W2).
 *
 * The confirmation is the approval. The editor starts from the card as the newest catalog facts
 * show it; the Qwen drafts can be dropped in and edited. What the platform does with a confirmed
 * change, and what became of it, is shown right below.
 */
export function ContentWriteEditor({
  context,
  platformListingVariantId,
  draftTitle,
  draftDescription,
  draftInvocationId,
}: {
  readonly context: ConsoleRequest;
  readonly platformListingVariantId: string;
  readonly draftTitle: string | null;
  readonly draftDescription: string | null;
  readonly draftInvocationId: string | null;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [source, setSource] = useState<string | null>(null);
  const [latest, setLatest] = useState<ContentCommand | null>(null);

  useEffect(() => {
    let live = true;
    void fetchContentCurrent(context, platformListingVariantId).then((outcome) => {
      if (!live) return;
      if (!outcome.ok) {
        setLoaded({ kind: 'failed', failure: outcome.failure });
        return;
      }
      setLoaded({ kind: 'loaded', current: outcome.value });
      setTitle(outcome.value.title ?? '');
      setDescription(outcome.value.description ?? '');
      setLatest(outcome.value.latestCommand);
    });
    return () => {
      live = false;
    };
  }, [context, platformListingVariantId]);

  const onCommandChanged = useCallback((command: ContentCommand) => {
    setLatest(command);
  }, []);

  if (loaded.kind === 'loading') {
    return <Typography.Text type="secondary">…</Typography.Text>;
  }
  if (loaded.kind === 'failed') {
    return <FailureAlert failure={loaded.failure} />;
  }
  const { current } = loaded;
  const { titleWritable } = current;
  const haveCurrent = current.title !== null && current.description !== null;
  const titleChanged =
    titleWritable && current.title !== null && !sameContent(title, current.title);
  const descriptionChanged =
    current.description !== null && !sameContent(description, current.description);
  const live = latest !== null && !TERMINAL_CONTENT_STATES.has(latest.state);
  const titleLength = characterCount(title.trim());
  const descriptionLength = characterCount(description.trim());
  const problems: string[] = [];
  if (!haveCurrent) problems.push(text.noCurrent);
  if (titleWritable && (titleLength === 0 || titleLength > current.titleLimit))
    problems.push(text.titleLimit(current.titleLimit));
  if (descriptionLength === 0 || descriptionLength > current.descriptionLimit) {
    problems.push(`${text.fieldDescription}：1–${String(current.descriptionLimit)}`);
  }
  if (haveCurrent && !titleChanged && !descriptionChanged) problems.push(text.nothingChanged);
  if (live) problems.push(text.liveCommand);

  return (
    <Flex vertical gap={10}>
      <Flex justify="space-between" align="center" gap={8} wrap>
        <Typography.Text strong>{text.title}</Typography.Text>
        <Button
          size="small"
          type={open ? 'default' : 'primary'}
          onClick={() => {
            setOpen((value) => !value);
          }}
        >
          {open ? text.close : text.open}
        </Button>
      </Flex>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {text.hint}
      </Typography.Text>
      {open && (
        <Flex vertical gap={8}>
          {current.observedAt !== null && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.currentObservedAt} <DateTime value={current.observedAt} />
            </Typography.Text>
          )}
          {!haveCurrent && <Typography.Text type="warning">{text.noCurrent}</Typography.Text>}
          <Space size={8} wrap>
            {((titleWritable && draftTitle !== null) || draftDescription !== null) && (
              <Button
                size="small"
                onClick={() => {
                  if (titleWritable && draftTitle !== null) setTitle(draftTitle);
                  if (draftDescription !== null) setDescription(draftDescription);
                  setSource(draftInvocationId);
                }}
              >
                {text.useDrafts}
              </Button>
            )}
            <Button
              size="small"
              onClick={() => {
                setTitle(current.title ?? '');
                setDescription(current.description ?? '');
                setSource(null);
              }}
            >
              {text.resetToCurrent}
            </Button>
          </Space>
          <Flex vertical gap={4}>
            <Space size={6}>
              <Typography.Text>{text.fieldTitle}</Typography.Text>
              <Tag color={titleChanged ? 'processing' : 'default'}>
                {!titleWritable ? text.locked : titleChanged ? text.changed : text.unchanged}
              </Tag>
            </Space>
            <Input
              lang="ru"
              value={title}
              maxLength={current.titleLimit}
              showCount
              disabled={!titleWritable}
              onChange={(event) => {
                setTitle(event.target.value);
              }}
            />
            {!titleWritable && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {text.titleLocked}
              </Typography.Text>
            )}
          </Flex>
          <Flex vertical gap={4}>
            <Space size={6}>
              <Typography.Text>{text.fieldDescription}</Typography.Text>
              <Tag color={descriptionChanged ? 'processing' : 'default'}>
                {descriptionChanged ? text.changed : text.unchanged}
              </Tag>
            </Space>
            <Input.TextArea
              lang="ru"
              value={description}
              maxLength={current.descriptionLimit}
              showCount
              autoSize={{ minRows: 4, maxRows: 16 }}
              onChange={(event) => {
                setDescription(event.target.value);
              }}
            />
            {descriptionLength < RATED_DESCRIPTION_LENGTH && (
              // The character count sits under the text area; keep the hint clear of it.
              <Typography.Text type="secondary" style={{ fontSize: 12, marginTop: 20 }}>
                {text.descriptionShort(descriptionLength)}
              </Typography.Text>
            )}
          </Flex>
          <div>
            <WriteConfirmModal
              trigger={{
                label: text.confirm,
                type: 'primary',
                disabled: problems.length > 0,
                disabledReason: problems.join(' '),
              }}
              title={text.confirmTitle}
              impact={
                <Flex vertical gap={4}>
                  <Typography.Text>
                    {text.fieldTitle}：{titleChanged ? text.changed : text.unchanged}
                    {titleChanged &&
                      ` · ${String(characterCount(current.title))} → ${String(titleLength)}`}
                  </Typography.Text>
                  {titleChanged && (
                    <Typography.Text lang="ru" type="secondary">
                      {title.trim()}
                    </Typography.Text>
                  )}
                  <Typography.Text>
                    {text.fieldDescription}：{descriptionChanged ? text.changed : text.unchanged}
                    {descriptionChanged &&
                      ` · ${String(characterCount(current.description))} → ${String(descriptionLength)}`}
                  </Typography.Text>
                </Flex>
              }
              guard={{
                passed: problems.length === 0,
                content:
                  problems.length === 0 ? (
                    <Typography.Text type="success">✓</Typography.Text>
                  ) : (
                    <Typography.Text type="warning">{problems.join(' ')}</Typography.Text>
                  ),
              }}
              consequence={text.confirmConsequence}
              confirmText={text.confirm}
              reasonLabel={text.reason}
              reasonRequired={false}
              onConfirm={async (reason) => {
                const outcome = await confirmContentChange(context, {
                  platformListingVariantId,
                  // A title the platform cannot change goes back exactly as the card holds it.
                  title: titleWritable ? title.trim() : (current.title ?? '').trim(),
                  description: description.trim(),
                  sourceInvocationId: source,
                  reason: reason.trim() === '' ? null : reason.trim(),
                });
                if (!outcome.ok) return outcome.failure;
                setLatest(outcome.value);
                void message.success(text.submitted);
                return undefined;
              }}
            />
          </div>
        </Flex>
      )}
      {latest !== null && (
        <Flex vertical gap={6}>
          <Typography.Text strong>{text.latest}</Typography.Text>
          <ContentCommandCard context={context} command={latest} onChanged={onCommandChanged} />
        </Flex>
      )}
    </Flex>
  );
}
