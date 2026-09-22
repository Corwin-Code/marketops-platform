import { InputNumber } from 'antd';
import { InstantPicker } from './ListingCommon';

/** A complete UUID, as the backend's identifiers are written. */
export const UUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/**
 * A store-time instant as a form field: the value is the UTC instant as an ISO
 * string, or empty when cleared, so a `required` rule sees a cleared picker.
 */
export function InstantField({
  value,
  onChange,
  disabled = false,
  ariaLabel,
}: {
  readonly value?: string;
  readonly onChange?: (iso: string) => void;
  readonly disabled?: boolean;
  readonly ariaLabel?: string;
}): React.JSX.Element {
  return (
    <InstantPicker
      value={value ?? ''}
      disabled={disabled}
      {...(ariaLabel === undefined ? {} : { ariaLabel })}
      onChange={(iso) => {
        onChange?.(iso);
      }}
    />
  );
}

/** A decimal typed as text as a form field: never converted to a JavaScript number. */
export function DecimalField({
  value,
  onChange,
  ariaLabel,
}: {
  readonly value?: string;
  readonly onChange?: (next: string) => void;
  readonly ariaLabel?: string;
}): React.JSX.Element {
  return (
    <InputNumber<string>
      stringMode
      style={{ width: '100%' }}
      value={value === undefined || value === '' ? null : value}
      {...(ariaLabel === undefined ? {} : { 'aria-label': ariaLabel })}
      onChange={(next) => {
        onChange?.(next ?? '');
      }}
    />
  );
}

/** The non-blank lines of a multi-line field. */
export function nonBlankLines(value: string | undefined): string[] {
  return (value ?? '').split('\n').filter((line) => line.trim() !== '');
}

/** Whether an ISO instant is readable. */
export function isInstant(value: string | undefined): value is string {
  return value !== undefined && value !== '' && Number.isFinite(new Date(value).valueOf());
}
