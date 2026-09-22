import { DateTime } from '../ui/DateTime';

/**
 * One source instant, shown in the store zone with Beijing time and UTC on hover.
 *
 * The `timezone` the case reports is kept in the props for compatibility; the
 * console shows every instant in the one configured store zone so two screens
 * never disagree about when something happened.
 */
export function AdvertisingTimestamp({
  value,
}: {
  readonly value: string | undefined;
  readonly timezone?: string | undefined;
}): React.JSX.Element {
  const resolved = value !== undefined && Number.isFinite(Date.parse(value));
  return (
    <span data-time-state={resolved ? 'RESOLVED' : 'UNRESOLVED'}>
      <DateTime value={value} />
    </span>
  );
}
