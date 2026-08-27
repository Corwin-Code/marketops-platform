import { formatAmount, formatFreshness, presentationOf } from './confidence';
import type { ConfidenceState, ValueState } from './confidence';

/** What one value needs in order to be shown honestly. */
export interface ValueCellProps {
  /** Operator-facing name of the figure. */
  readonly label: string;
  /** The number, or {@code null} when there is none. */
  readonly value: string | null;
  /** Currency of a monetary figure, or {@code null}. */
  readonly currencyCode?: string | null;
  /** Whether a number was produced at all. */
  readonly valueState?: ValueState;
  /** How much weight the figure can carry. */
  readonly confidenceState?: ConfidenceState;
  /** How old the freshest contributing fact is. */
  readonly freshnessSeconds?: number | null;
}

/**
 * One figure, shown with the doubt attached to it.
 *
 * The qualifier is a sibling of the value rather than a tooltip or a colour,
 * because an operator scanning a list reads text and does not hover. The tone
 * is also on the element as data, so a browser test can assert that nothing
 * uncertain was ever rendered as confirmed without depending on styling.
 */
export function ValueCell({
  label,
  value,
  currencyCode = null,
  valueState = 'AVAILABLE',
  confidenceState,
  freshnessSeconds = null,
}: ValueCellProps): React.JSX.Element {
  const presentation = presentationOf(valueState, confidenceState);
  return (
    <div className="value-cell" data-tone={presentation.tone} data-label={label}>
      <span className="value-cell-label">{label}</span>
      <span className="value-cell-amount">{formatAmount(value, currencyCode)}</span>
      <span className="value-cell-qualifier" title={presentation.explanation}>
        {presentation.label}
      </span>
      <span className="value-cell-freshness">{formatFreshness(freshnessSeconds)}</span>
    </div>
  );
}
