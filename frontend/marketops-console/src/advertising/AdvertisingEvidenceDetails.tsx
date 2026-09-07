/** Server-disclosed decision evidence rendered as labelled values without reinterpreting unknowns. */
export function AdvertisingEvidenceDetails({
  value,
  label,
}: {
  readonly value: unknown;
  readonly label: string;
}): React.JSX.Element {
  if (value === null || value === undefined) return <span>{label}: UNRESOLVED</span>;
  if (typeof value === 'string') {
    if (value.startsWith('{') || value.startsWith('[')) {
      try {
        const nested: unknown = JSON.parse(value);
        return <AdvertisingEvidenceDetails value={nested} label={label} />;
      } catch {
        /* A native string stays exact when it is not a JSON structure. */
      }
    }
    return <span>{value}</span>;
  }
  if (typeof value === 'number' || typeof value === 'boolean')
    return <span>{value.toString()}</span>;
  if (Array.isArray(value))
    return (
      <ul aria-label={label}>
        {value.map((item: unknown, index) => (
          <li key={index}>
            <AdvertisingEvidenceDetails value={item} label={label} />
          </li>
        ))}
      </ul>
    );
  if (typeof value === 'object')
    return (
      <dl aria-label={label}>
        {Object.entries(value).map(([key, item]: [string, unknown]) => (
          <div key={key}>
            <dt>
              {key
                .replace(/([a-z])([A-Z])/g, '$1 $2')
                .replaceAll('_', ' ')
                .replace(/^./u, (character) => character.toUpperCase())}
            </dt>
            <dd>
              <AdvertisingEvidenceDetails value={item} label={key} />
            </dd>
          </div>
        ))}
      </dl>
    );
  return <span>UNRESOLVED</span>;
}
