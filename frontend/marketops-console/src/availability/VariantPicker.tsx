import { Flex, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { VariantOption, VariantPurpose } from '../api/availability';
import { fetchAvailabilityVariants } from '../api/availability';
import type { ConsoleRequest } from '../api/console';
import { variantPickerText as text } from '../i18n/zh/availabilityAuthority';
import { PickOrType, useRemote } from '../ui';
import type { PickOption } from '../ui';

/** How long typing pauses before the server is asked. */
const SEARCH_DELAY_MS = 300;

/** A variant already chosen, named by the caller when the list may not include it. */
export interface KnownVariant {
  readonly productVariantId: string;
  readonly skuCode: string | null;
  readonly displayName: string | null;
}

/**
 * Choose an internal product variant by SKU or name, never by typing an
 * identifier unless the list cannot be read.
 *
 * The list is searched on the server and narrowed to the variants the person's
 * own grant for `purpose` covers, so it never offers a product they could not
 * act on. A variant chosen earlier keeps its name while other searches run.
 */
export function VariantPicker({
  context,
  purpose,
  value,
  onChange,
  id,
  known,
  placeholder,
}: {
  readonly context: ConsoleRequest;
  readonly purpose: VariantPurpose;
  readonly value?: string | undefined;
  readonly onChange?: ((value: string | undefined) => void) | undefined;
  readonly id?: string | undefined;
  /** A variant the caller already knows by name, e.g. one read from a list row. */
  readonly known?: KnownVariant | undefined;
  readonly placeholder?: string;
}): React.JSX.Element {
  const [typed, setTyped] = useState('');
  const [search, setSearch] = useState('');
  const seen = useRef(new Map<string, KnownVariant>());

  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(typed.trim());
    }, SEARCH_DELAY_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [typed]);

  const remote = useRemote(`${purpose}|${search}`, () =>
    fetchAvailabilityVariants(context, {
      purpose,
      limit: 20,
      ...(search === '' ? {} : { q: search }),
    }),
  );

  for (const variant of remote.value ?? []) {
    seen.current.set(variant.productVariantId, variant);
  }
  if (known !== undefined) {
    seen.current.set(known.productVariantId, known);
  }

  const listed: readonly VariantOption[] = remote.value ?? [];
  const options: PickOption[] = listed.map((variant) => option(variant, variant.status));
  const selected = value === undefined ? undefined : seen.current.get(value);
  if (selected !== undefined && !options.some((item) => item.value === selected.productVariantId)) {
    options.unshift(option(selected, null));
  }

  return (
    <PickOrType
      id={id}
      value={value}
      onChange={onChange}
      options={options}
      loading={remote.loading}
      unavailable={remote.failed}
      placeholder={placeholder ?? text.placeholder}
      onSearch={setTyped}
    />
  );
}

function option(variant: KnownVariant, status: string | null): PickOption {
  const name = variant.displayName ?? variant.skuCode ?? variant.productVariantId.slice(0, 8);
  return {
    value: variant.productVariantId,
    search: `${variant.skuCode ?? ''} ${name}`,
    label: (
      <Flex gap={6} align="center" wrap={false} style={{ minWidth: 0 }}>
        <Typography.Text lang="ru" ellipsis style={{ minWidth: 0 }}>
          {name}
        </Typography.Text>
        {variant.skuCode === null ? null : (
          <Typography.Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
            SKU {variant.skuCode}
          </Typography.Text>
        )}
        {status !== null && status !== 'ACTIVE' && (
          <Tag style={{ marginInlineEnd: 0 }}>{text.inactive}</Tag>
        )}
      </Flex>
    ),
  };
}
