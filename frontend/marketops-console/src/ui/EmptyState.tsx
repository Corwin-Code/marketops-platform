import { Empty } from 'antd';
import type { ReactNode } from 'react';
import { states } from '../i18n';

/** What to say when there is nothing to show. */
export interface EmptyStateProps {
  readonly description?: ReactNode;
  /** A way forward, e.g. a button to create the first item. */
  readonly children?: ReactNode;
}

/** An explicit "nothing here", so an empty list is not mistaken for a failed one. */
export function EmptyState({
  description = states.empty,
  children,
}: EmptyStateProps): React.JSX.Element {
  return (
    <div data-state="empty">
      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={description}>
        {children}
      </Empty>
    </div>
  );
}
