/** One source instant shown in UTC and the Store's actual timezone. */
export function AdvertisingTimestamp({
  value,
  timezone,
}: {
  readonly value: string | undefined;
  readonly timezone: string | undefined;
}): React.JSX.Element {
  const instant = value === undefined ? undefined : new Date(value);
  if (instant === undefined || !Number.isFinite(instant.getTime())) {
    return <span data-time-state="UNRESOLVED">Time unresolved</span>;
  }
  let local: string | undefined;
  try {
    if (timezone !== undefined) {
      local = new Intl.DateTimeFormat('ru-RU', {
        timeZone: timezone,
        dateStyle: 'medium',
        timeStyle: 'long',
      }).format(instant);
    }
  } catch {
    local = undefined;
  }
  return (
    <time dateTime={instant.toISOString()}>
      {instant.toISOString()} (UTC) ·{' '}
      {local === undefined ? 'Store timezone unresolved' : `${local} (${timezone ?? 'UNRESOLVED'})`}
    </time>
  );
}
