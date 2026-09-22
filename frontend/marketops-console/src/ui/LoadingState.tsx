import { Skeleton } from 'antd';

/** How much placeholder to show. */
export interface LoadingStateProps {
  /** Placeholder paragraph lines. */
  readonly rows?: number;
}

/** Placeholder shown while data is on its way, so the layout does not jump. */
export function LoadingState({ rows = 3 }: LoadingStateProps): React.JSX.Element {
  return (
    <div data-state="loading" aria-busy="true">
      <Skeleton active paragraph={{ rows }} />
    </div>
  );
}
