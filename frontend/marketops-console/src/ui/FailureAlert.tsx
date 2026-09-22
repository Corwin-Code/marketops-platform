import { Alert, Flex, Typography } from 'antd';
import type { ReactNode } from 'react';
import type { ConsoleFailure } from '../api/console';
import { ERROR_CODE_LABELS, codeLabel, refusedWithStatus, terms } from '../i18n';

/** Chinese label for a backend code, only when the console knows it. */
function knownCodeLabel(code: string | undefined): string | undefined {
  return code !== undefined && Object.hasOwn(ERROR_CODE_LABELS, code)
    ? ERROR_CODE_LABELS[code]
    : undefined;
}

/**
 * One sentence saying why a request failed, for places that take plain text.
 *
 * The backend's own detail text is never shown: it is phrased for the server
 * log, and the stable code is what the console can translate reliably.
 */
export function failureMessage(failure: ConsoleFailure): string {
  switch (failure.kind) {
    case 'unauthenticated':
      return '登录已失效，请重新登录';
    case 'step-up-required':
      return '该操作需要近期重新登录验证身份';
    case 'forbidden':
      return '你没有执行此操作的权限';
    case 'refused':
      return failure.code === undefined
        ? refusedWithStatus(failure.status)
        : codeLabel(ERROR_CODE_LABELS, failure.code);
    case 'unreachable':
      return '无法连接后端服务，请检查网络或稍后重试';
    case 'malformed':
      return '服务返回的数据格式异常';
  }
}

/** A failure to show and an optional way forward. */
export interface FailureAlertProps {
  readonly failure: ConsoleFailure;
  /** A control offered beside the message, e.g. retry or sign in again. */
  readonly action?: ReactNode;
}

/**
 * A failed request, explained in Chinese.
 *
 * The correlation identifier is copyable so an operator can hand it to support
 * without retyping; the more specific reason is added when the backend named a
 * code the console knows.
 */
export function FailureAlert({ failure, action }: FailureAlertProps): React.JSX.Element {
  const message = failureMessage(failure);
  const code = 'code' in failure ? failure.code : undefined;
  const correlationId = 'correlationId' in failure ? failure.correlationId : undefined;
  const reason = failure.kind === 'refused' ? undefined : knownCodeLabel(code);
  const reasonLine = reason !== undefined && reason !== message ? reason : undefined;
  const description =
    reasonLine === undefined && correlationId === undefined ? undefined : (
      <Flex vertical gap={2}>
        {reasonLine !== undefined && <Typography.Text>{reasonLine}</Typography.Text>}
        {correlationId !== undefined && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {terms.correlationId}：
            <Typography.Text type="secondary" style={{ fontSize: 12 }} copyable code>
              {correlationId}
            </Typography.Text>
          </Typography.Text>
        )}
      </Flex>
    );
  const severe =
    failure.kind === 'refused' || failure.kind === 'unreachable' || failure.kind === 'malformed';
  return (
    <Alert
      type={severe ? 'error' : 'warning'}
      showIcon
      title={message}
      {...(description === undefined ? {} : { description })}
      {...(action === undefined ? {} : { action })}
      data-state={failure.kind}
      {...(code === undefined ? {} : { 'data-code': code })}
    />
  );
}
