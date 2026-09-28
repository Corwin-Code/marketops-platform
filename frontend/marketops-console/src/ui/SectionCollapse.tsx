import { Collapse, Flex, Tag, Typography } from 'antd';
import type { ReactNode } from 'react';
import type { TagColor } from './CodeTag';

/** A state that must stay visible while a section is folded. */
export interface SectionFlag {
  readonly key: string;
  readonly label: ReactNode;
  readonly color?: TagColor;
}

/** One foldable section. */
export interface SectionCollapseItem {
  readonly key: string;
  readonly title: ReactNode;
  /** A short summary shown in the header, e.g. `12 项`. */
  readonly summary?: ReactNode;
  /** Missing, doubtful, stale or conflicting states, shown even when folded. */
  readonly flags?: readonly SectionFlag[];
  /** Controls at the right of the header. */
  readonly extra?: ReactNode;
  readonly defaultOpen?: boolean;
  /**
   * Render the children even while the section is folded.
   *
   * Needed when the folded material is part of a form: an unmounted `Form.Item`
   * is left out of what `validateFields` collects, so its value would never
   * reach the submitted body.
   */
  readonly forceRender?: boolean;
  readonly children: ReactNode;
}

/**
 * Secondary material, folded, with its state still in view.
 *
 * Folding is for what an operator reads occasionally. The header carries a
 * summary and every warning flag, so a folded section never hides that
 * something is missing, doubtful or stale. Blocking alerts and rule verdicts do
 * not belong in here.
 *
 * Which sections are open is the reader's choice by default. Pass `openKeys`
 * and `onOpenChange` to hold that choice yourself, for a section that has to
 * open on its own when something inside it needs attention.
 */
export function SectionCollapse({
  items,
  ghost = false,
  size = 'middle',
  openKeys,
  onOpenChange,
}: {
  readonly items: readonly SectionCollapseItem[];
  readonly ghost?: boolean;
  readonly size?: 'small' | 'middle' | 'large';
  /** The open sections, when the owner holds that state. */
  readonly openKeys?: readonly string[];
  readonly onOpenChange?: (keys: readonly string[]) => void;
}): React.JSX.Element {
  return (
    <Collapse
      ghost={ghost}
      size={size}
      {...(openKeys === undefined
        ? {
            defaultActiveKey: items
              .filter((item) => item.defaultOpen === true)
              .map((item) => item.key),
          }
        : {
            activeKey: [...openKeys],
            onChange: (keys: string[]): void => {
              onOpenChange?.(keys);
            },
          })}
      items={items.map((item) => ({
        key: item.key,
        ...(item.forceRender === true ? { forceRender: true } : {}),
        label: (
          <Flex gap={8} wrap align="center">
            <Typography.Text strong>{item.title}</Typography.Text>
            {item.summary !== undefined && (
              <Typography.Text type="secondary">{item.summary}</Typography.Text>
            )}
            {item.flags?.map((flag) => (
              <Tag key={flag.key} color={flag.color ?? 'warning'} style={{ marginInlineEnd: 0 }}>
                {flag.label}
              </Tag>
            ))}
          </Flex>
        ),
        ...(item.extra === undefined ? {} : { extra: item.extra }),
        children: item.children,
      }))}
    />
  );
}
