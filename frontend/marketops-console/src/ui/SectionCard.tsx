import { Card } from 'antd';
import type { ReactNode } from 'react';

/** A titled block of a page. */
export interface SectionCardProps {
  readonly title?: ReactNode;
  /** Controls shown at the right of the title, e.g. a refresh button. */
  readonly extra?: ReactNode;
  readonly children?: ReactNode;
  /** Machine-readable marker of what the section is showing. */
  readonly state?: string;
}

/** One section of a page, spaced the same everywhere so pages read alike. */
export function SectionCard({
  title,
  extra,
  children,
  state,
}: SectionCardProps): React.JSX.Element {
  return (
    <div style={{ marginBottom: 16 }} {...(state === undefined ? {} : { 'data-state': state })}>
      <Card {...(title === undefined ? {} : { title })} {...(extra === undefined ? {} : { extra })}>
        {children}
      </Card>
    </div>
  );
}
