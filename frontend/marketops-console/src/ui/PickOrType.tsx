import { Button, Flex, Input, Select, Typography } from 'antd';
import type { FormRule } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { ConsoleOutcome } from '../api/console';
import { picker } from '../i18n/zh/common';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Whether a typed value is a well-formed identifier. */
export function isUuid(value: string | undefined): value is string {
  return value !== undefined && UUID.test(value.trim());
}

/** Form rules for an identifier that is picked or typed. */
export function idRules(label: string, required = true): FormRule[] {
  return [
    ...(required ? [{ required: true, whitespace: true, message: `请选择或填写${label}` }] : []),
    {
      validator: (_: unknown, value: string | undefined) =>
        value === undefined || value.trim() === '' || isUuid(value)
          ? Promise.resolve()
          : Promise.reject(new Error(picker.invalidId)),
    },
  ];
}

/** What a remote list read gives a picker. */
export interface Remote<T> {
  readonly value: T | undefined;
  readonly loading: boolean;
  readonly failed: boolean;
}

/**
 * One read for a picker, repeated whenever `key` changes. Without a key nothing
 * is read; a failed read is a fallback signal, never an error shown.
 */
export function useRemote<T>(
  key: string | undefined,
  load: () => Promise<ConsoleOutcome<T>>,
): Remote<T> {
  const loadRef = useRef(load);
  loadRef.current = load;
  const [state, setState] = useState<{
    readonly key: string | undefined;
    readonly value: T | undefined;
    readonly failed: boolean;
  }>({ key: undefined, value: undefined, failed: false });
  useEffect(() => {
    if (key === undefined) return;
    let live = true;
    void loadRef.current().then((outcome) => {
      if (!live) return;
      setState({ key, value: outcome.ok ? outcome.value : undefined, failed: !outcome.ok });
    });
    return () => {
      live = false;
    };
  }, [key]);
  const current = key !== undefined && state.key === key;
  return {
    value: current ? state.value : undefined,
    loading: key !== undefined && !current,
    failed: current && state.failed,
  };
}

/** One choice of a picker. */
export interface PickOption {
  readonly value: string;
  readonly label: ReactNode;
  /** Plain text the operator's search is matched against. */
  readonly search: string;
  readonly disabled?: boolean;
}

const MANUAL = '__manual__';

/**
 * Choose a record from a list, or type its identifier.
 *
 * The list is the normal way; typing is always offered as 「其他（手动输入）」
 * and becomes the only way when the list cannot be read, so an older backend
 * never blocks the task. With `onSearch` the list is searched by the caller
 * (for example on the server) instead of being filtered in place.
 */
export function PickOrType({
  value,
  onChange,
  id,
  options,
  loading,
  unavailable,
  disabled = false,
  placeholder,
  onSearch,
}: {
  readonly value?: string | undefined;
  readonly onChange?: ((value: string | undefined) => void) | undefined;
  readonly id?: string | undefined;
  readonly options: readonly PickOption[];
  readonly loading: boolean;
  readonly unavailable: boolean;
  readonly disabled?: boolean;
  readonly placeholder?: string;
  /** Search typed text elsewhere; the options shown are then the caller's answer. */
  readonly onSearch?: (text: string) => void;
}): React.JSX.Element {
  const [manual, setManual] = useState(false);
  const known = options.some((option) => option.value === value);
  // A remotely searched list may be empty only for the current search text.
  const empty = onSearch === undefined && !loading && !unavailable && options.length === 0;
  const typing =
    !disabled &&
    (unavailable ||
      empty ||
      manual ||
      (onSearch === undefined && !loading && value !== undefined && value !== '' && !known));
  if (typing) {
    return (
      <Flex vertical gap={2}>
        <Input
          id={id}
          value={value ?? ''}
          placeholder={picker.typeId}
          allowClear
          onChange={(event) => {
            onChange?.(event.target.value === '' ? undefined : event.target.value);
          }}
        />
        {unavailable || empty ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {unavailable ? picker.listUnavailable : picker.listEmpty}
          </Typography.Text>
        ) : (
          <Button
            type="link"
            size="small"
            style={{ padding: 0, alignSelf: 'flex-start' }}
            onClick={() => {
              setManual(false);
              onChange?.(undefined);
            }}
          >
            {picker.backToList}
          </Button>
        )}
      </Flex>
    );
  }
  return (
    <Select<string>
      {...(id === undefined ? {} : { id })}
      value={value === undefined || value === '' ? null : value}
      loading={loading}
      disabled={disabled}
      allowClear
      placeholder={placeholder ?? picker.pick}
      showSearch={
        onSearch === undefined ? { optionFilterProp: 'search' } : { filterOption: false, onSearch }
      }
      options={[
        ...options.map((option) => ({
          value: option.value,
          label: option.label,
          search: option.search,
          disabled: option.disabled === true,
        })),
        { value: MANUAL, label: picker.manualEntry, search: picker.manualEntry },
      ]}
      onChange={(next: string | undefined) => {
        if (next === MANUAL) {
          setManual(true);
          onChange?.(undefined);
        } else {
          onChange?.(next);
        }
      }}
    />
  );
}
