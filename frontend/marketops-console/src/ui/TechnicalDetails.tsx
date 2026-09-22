import { Collapse } from 'antd';
import type { ReactNode } from 'react';
import { terms } from '../i18n';

/** Raw material an operator rarely needs. */
export interface TechnicalDetailsProps {
  /** A value rendered as indented JSON. Takes precedence over children. */
  readonly data?: unknown;
  readonly children?: ReactNode;
  /** Header text; defaults to 技术详情. */
  readonly label?: ReactNode;
}

/**
 * Raw evidence, folded away.
 *
 * Identifiers and source records stay reachable for support and audit, but
 * collapsed, so they do not crowd out what an operator decides on.
 */
export function TechnicalDetails({
  data,
  children,
  label = terms.technicalDetails,
}: TechnicalDetailsProps): React.JSX.Element {
  const content =
    data === undefined ? (
      children
    ) : (
      <pre
        style={{
          margin: 0,
          fontSize: 12,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-all',
        }}
      >
        {JSON.stringify(data, null, 2)}
      </pre>
    );
  return (
    <div data-state="technical-details">
      <Collapse ghost size="small" items={[{ key: 'details', label, children: content }]} />
    </div>
  );
}
