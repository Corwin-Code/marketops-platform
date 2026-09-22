import { Flex, Typography } from 'antd';
import { useEffect, useId } from 'react';
import type { ReactNode } from 'react';
import { product } from '../i18n/zh/shell';

/** A page's heading and what it is for. */
export interface PageProps {
  readonly title: string;
  /** One sentence saying what the page is for. */
  readonly description?: string;
  /** Controls at the right of the heading, e.g. a way back. */
  readonly extra?: ReactNode;
  readonly children?: ReactNode;
}

/**
 * The top of every page: one heading, one sentence of purpose, then the work.
 *
 * The browser tab carries the same title so several open tabs can be told
 * apart.
 */
export function Page({ title, description, extra, children }: PageProps): React.JSX.Element {
  const headingId = useId();
  useEffect(() => {
    document.title = `${title} · ${product.name}`;
  }, [title]);
  return (
    <section aria-labelledby={headingId}>
      <Flex justify="space-between" align="flex-start" gap={12} wrap style={{ marginBottom: 16 }}>
        <div style={{ minWidth: 0 }}>
          <Typography.Title level={1} id={headingId} style={{ fontSize: 22, margin: 0 }}>
            {title}
          </Typography.Title>
          {description !== undefined && (
            <Typography.Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
              {description}
            </Typography.Paragraph>
          )}
        </div>
        {extra}
      </Flex>
      {children}
    </section>
  );
}
