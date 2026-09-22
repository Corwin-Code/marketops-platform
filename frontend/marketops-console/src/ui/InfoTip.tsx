import { QuestionCircleOutlined } from '@ant-design/icons';
import { Popover, Tooltip, Typography } from 'antd';
import type { ReactNode } from 'react';
import { dialog } from '../i18n/zh/common';

/**
 * A question mark beside a heading that explains it on hover.
 *
 * Explanations an operator needs once, such as how a value is defined, move
 * here instead of taking a line on every visit. Boundaries that must be read
 * every time ("AI 仅供参考", "不调用平台接口") stay as visible text instead.
 */
export function InfoTip({
  title,
  long = false,
}: {
  readonly title: ReactNode;
  /** Longer explanations open in a popover that fits paragraphs. */
  readonly long?: boolean;
}): React.JSX.Element {
  const icon = (
    <Typography.Text
      type="secondary"
      role="img"
      aria-label={dialog.help}
      tabIndex={0}
      style={{ cursor: 'help', marginInlineStart: 4 }}
    >
      <QuestionCircleOutlined />
    </Typography.Text>
  );
  return long ? (
    <Popover content={<div style={{ maxWidth: 360 }}>{title}</div>} mouseEnterDelay={0.3}>
      {icon}
    </Popover>
  ) : (
    <Tooltip title={title} mouseEnterDelay={0.3}>
      {icon}
    </Tooltip>
  );
}
