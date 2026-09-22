import { Button, Drawer } from 'antd';
import type { ReactNode } from 'react';
import type { ConsoleFailure } from '../api/console';
import { actions } from '../i18n/zh/common';
import { FailureAlert } from './FailureAlert';
import { LoadingState } from './LoadingState';

/** Read-only detail beside the page. */
export interface DetailDrawerProps {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly title: ReactNode;
  /** Controls at the right of the title, e.g. refresh. */
  readonly extra?: ReactNode;
  /** A fixed footer, e.g. the decision the detail supports. */
  readonly footer?: ReactNode;
  /** Drawer width: default (378px), large (736px) or pixels. */
  readonly size?: 'default' | 'large' | number;
  /** Without a mask the page stays usable, so detail can be read beside it. */
  readonly mask?: boolean;
  readonly loading?: boolean;
  readonly failure?: ConsoleFailure | undefined;
  readonly onRetry?: () => void;
  readonly children?: ReactNode;
}

/**
 * Evidence, history or a record's detail, opened beside what it supports.
 *
 * The conclusion or list that opened it stays in view, so an operator reads the
 * evidence without losing their place. Loading and failure are shown inside the
 * drawer, with a retry where the caller offers one.
 */
export function DetailDrawer({
  open,
  onClose,
  title,
  extra,
  footer,
  size = 'large',
  mask = true,
  loading = false,
  failure,
  onRetry,
  children,
}: DetailDrawerProps): React.JSX.Element {
  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={title}
      size={size}
      mask={mask}
      {...(extra === undefined ? {} : { extra })}
      {...(footer === undefined ? {} : { footer })}
    >
      {loading ? (
        <LoadingState />
      ) : failure !== undefined ? (
        <FailureAlert
          failure={failure}
          {...(onRetry === undefined
            ? {}
            : {
                action: (
                  <Button size="small" onClick={onRetry}>
                    {actions.retry}
                  </Button>
                ),
              })}
        />
      ) : (
        children
      )}
    </Drawer>
  );
}
