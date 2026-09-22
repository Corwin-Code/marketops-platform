import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import 'dayjs/locale/zh-cn';
import localizedFormat from 'dayjs/plugin/localizedFormat';
import relativeTime from 'dayjs/plugin/relativeTime';
import timezone from 'dayjs/plugin/timezone';
import utc from 'dayjs/plugin/utc';

/**
 * Time as operators read it.
 *
 * The store trades on Moscow time, so every instant is shown and entered in the
 * store's zone; Beijing time and UTC are offered on hover for the people who
 * plan from elsewhere. The backend speaks UTC instants, and the conversion
 * happens only here so no screen invents its own.
 */

dayjs.extend(utc);
dayjs.extend(timezone);
dayjs.extend(relativeTime);
dayjs.extend(localizedFormat);
dayjs.locale('zh-cn');

const DEFAULT_STORE_TIMEZONE = 'Europe/Moscow';

/** Whether the runtime knows a zone, so a mistyped setting falls back instead of throwing later. */
function isKnownTimezone(zone: string): boolean {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

function resolveStoreTimezone(configured: string | undefined): string {
  const zone = configured?.trim() ?? '';
  return zone !== '' && isKnownTimezone(zone) ? zone : DEFAULT_STORE_TIMEZONE;
}

/** IANA zone the store operates in. */
export const STORE_TIMEZONE: string = resolveStoreTimezone(
  import.meta.env.VITE_MARKETOPS_STORE_TIMEZONE,
);

/** How the store zone is named next to a time. */
export const STORE_TIMEZONE_LABEL: string =
  STORE_TIMEZONE === DEFAULT_STORE_TIMEZONE ? '莫斯科时间' : STORE_TIMEZONE;

const BEIJING_TIMEZONE = 'Asia/Shanghai';

/** Parse an instant, or nothing when it is absent or unreadable. */
function parse(iso: string | null | undefined): Dayjs | undefined {
  if (iso === null || iso === undefined || iso.trim() === '') {
    return undefined;
  }
  const parsed = dayjs(iso);
  return parsed.isValid() ? parsed : undefined;
}

function formatIn(iso: string | null | undefined, zone: string, pattern: string): string {
  const parsed = parse(iso);
  return parsed === undefined ? '—' : parsed.tz(zone).format(pattern);
}

/** `YYYY-MM-DD HH:mm` in the store zone. */
export function formatStoreTime(iso: string | null | undefined): string {
  return formatIn(iso, STORE_TIMEZONE, 'YYYY-MM-DD HH:mm');
}

/** `YYYY-MM-DD` in the store zone. */
export function formatStoreDate(iso: string | null | undefined): string {
  return formatIn(iso, STORE_TIMEZONE, 'YYYY-MM-DD');
}

/** `YYYY-MM-DD HH:mm` in Beijing time. */
export function formatBeijingTime(iso: string | null | undefined): string {
  return formatIn(iso, BEIJING_TIMEZONE, 'YYYY-MM-DD HH:mm');
}

/** `YYYY-MM-DD HH:mm:ss` in UTC. */
export function formatUtc(iso: string | null | undefined): string {
  const parsed = parse(iso);
  return parsed === undefined ? '—' : parsed.utc().format('YYYY-MM-DD HH:mm:ss');
}

/** How long ago or ahead, in Chinese, e.g. `5 分钟前`. */
export function formatRelative(iso: string | null | undefined): string {
  const parsed = parse(iso);
  return parsed === undefined ? '—' : parsed.fromNow();
}

/** An instant as a store-zone value for a date picker's initial value. */
export function toStoreDayjs(iso: string | null | undefined): Dayjs | undefined {
  return parse(iso)?.tz(STORE_TIMEZONE);
}

/**
 * Read a picked wall-clock time as store time and return the UTC instant.
 *
 * A picker hands back the digits the operator chose, and those digits mean
 * Moscow time whatever zone the browser happens to be in.
 */
export function storeLocalToIso(d: Dayjs): string {
  return dayjs.tz(d.format('YYYY-MM-DDTHH:mm:ss'), STORE_TIMEZONE).toISOString();
}
