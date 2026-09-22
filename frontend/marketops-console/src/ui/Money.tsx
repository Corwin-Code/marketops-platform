import { Typography } from 'antd';
import { formatMoney, isDecimal } from '../format';

/** An exact amount and its currency. */
export interface MoneyProps {
  /** Decimal string exactly as the backend sent it. */
  readonly value: string | null | undefined;
  /** ISO currency code, or nothing when unknown. */
  readonly currency?: string | null;
  /** Render in bold, for totals. */
  readonly strong?: boolean;
}

/**
 * A monetary amount in aligned figures.
 *
 * Tabular digits keep a column of amounts readable at a glance; absence is a
 * dash, never zero, because a missing cost and a zero cost mean opposite things.
 */
export function Money({ value, currency = null, strong = false }: MoneyProps): React.JSX.Element {
  const present = isDecimal(value);
  return (
    <Typography.Text
      strong={strong}
      {...(present ? {} : { type: 'secondary' as const })}
      style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}
      data-state={present ? 'present' : 'absent'}
    >
      {formatMoney(value, currency)}
    </Typography.Text>
  );
}
