import { Tooltip, Typography } from 'antd';
import {
  formatBeijingTime,
  formatRelative,
  formatStoreTime,
  formatUtc,
  STORE_TIMEZONE,
  STORE_TIMEZONE_LABEL,
} from '../format';
import { terms } from '../i18n';

/** An instant to show. */
export interface DateTimeProps {
  /** ISO-8601 instant from the backend. */
  readonly value: string | null | undefined;
  /** Also show how long ago it was. */
  readonly relative?: boolean;
}

/** Short zone marker shown after the time. */
const ZONE_HINT = STORE_TIMEZONE === 'Europe/Moscow' ? '莫斯科' : STORE_TIMEZONE;

/**
 * An instant in store time, with Beijing time and UTC on hover.
 *
 * The zone is always named next to the digits: a bare time is read in whatever
 * zone the reader happens to think in, and a price window misread by five
 * hours is a real change at the wrong moment.
 */
export function DateTime({ value, relative = false }: DateTimeProps): React.JSX.Element {
  const store = formatStoreTime(value);
  if (store === '—') {
    const raw = value?.trim() ?? '';
    const absent = (
      <Typography.Text type="secondary" data-state="absent">
        —
      </Typography.Text>
    );
    return raw === '' ? absent : <Tooltip title={`无法解析的时间：${raw}`}>{absent}</Tooltip>;
  }
  const details = (
    <div>
      <div>
        {STORE_TIMEZONE_LABEL} {store}
      </div>
      <div>
        {terms.beijingTime} {formatBeijingTime(value)}
      </div>
      <div>
        {terms.utc} {formatUtc(value)}
      </div>
    </div>
  );
  return (
    <Tooltip title={details}>
      <span style={{ whiteSpace: 'nowrap' }} data-state="present">
        <Typography.Text style={{ fontVariantNumeric: 'tabular-nums' }}>{store}</Typography.Text>{' '}
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {ZONE_HINT}
        </Typography.Text>
        {relative && (
          <Typography.Text type="secondary" style={{ fontSize: 12, marginLeft: 6 }}>
            {formatRelative(value)}
          </Typography.Text>
        )}
      </span>
    </Tooltip>
  );
}
