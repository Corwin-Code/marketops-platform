import { Tag, Tooltip, Typography } from 'antd';
import type { TagProps } from 'antd';
import { codeLabel, terms } from '../i18n';
import type { CodeLabels } from '../i18n';

/** A colour a tag may take. */
export type TagColor = NonNullable<TagProps['color']>;

/** A backend code and how to name and colour it. */
export interface CodeTagProps {
  /** Chinese labels for the codes this tag may show. */
  readonly labels: CodeLabels;
  /** The raw backend code, or nothing. */
  readonly code: string | null | undefined;
  /** Colours per known code; a code without one stays neutral. */
  readonly colors?: Readonly<Record<string, TagColor>>;
}

/**
 * A backend code shown as a Chinese tag.
 *
 * The raw code stays on the element and in the tooltip, so support can match a
 * screen to a log line. An unrecognised code keeps a neutral colour: colouring
 * it like a known state would claim a meaning the console does not have.
 */
export function CodeTag({ labels, code, colors }: CodeTagProps): React.JSX.Element {
  if (code === null || code === undefined || code === '') {
    return (
      <Typography.Text type="secondary" data-code="">
        —
      </Typography.Text>
    );
  }
  const known = Object.hasOwn(labels, code);
  const color = known ? colors?.[code] : undefined;
  return (
    <Tooltip title={`${terms.rawCode}：${code}`}>
      <Tag
        {...(color === undefined ? {} : { color })}
        data-code={code}
        data-state={known ? 'known' : 'unrecognized'}
      >
        {codeLabel(labels, code)}
      </Tag>
    </Tooltip>
  );
}
