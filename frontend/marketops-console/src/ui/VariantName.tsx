import { Flex, Tooltip, Typography } from 'antd';
import type { ReactNode } from 'react';

/** What an operator calls an internal product variant. */
export interface VariantIdentity {
  readonly displayName: string | null | undefined;
  readonly skuCode: string | null | undefined;
}

/** The name an operator reads first, or a short identifier when there is none. */
export function variantTitle(
  identity: VariantIdentity | undefined,
  productVariantId: string,
): string {
  const name = identity?.displayName?.trim() ?? '';
  if (name !== '') return name;
  const sku = identity?.skuCode?.trim() ?? '';
  return sku !== '' ? `SKU ${sku}` : `商品 ${productVariantId.slice(0, 8)}`;
}

/**
 * An internal product variant as an operator knows it.
 *
 * The catalogue name leads (it is often Russian, and marked so); the SKU follows
 * on a second line with anything the caller adds, such as the channel; the full
 * identifier is one hover away for support and audit, never the headline.
 */
export function VariantName({
  identity,
  productVariantId,
  extra,
  strong = true,
}: {
  readonly identity: VariantIdentity | undefined;
  readonly productVariantId: string;
  /** More of the second line, e.g. the channel a risk sits on. */
  readonly extra?: ReactNode;
  readonly strong?: boolean;
}): React.JSX.Element {
  const sku = identity?.skuCode?.trim() ?? '';
  const named = (identity?.displayName?.trim() ?? '') !== '';
  return (
    <Flex vertical gap={0} data-product-variant-id={productVariantId} style={{ minWidth: 0 }}>
      <Tooltip title={`编号 ${productVariantId}`} mouseEnterDelay={0.5}>
        <Typography.Text strong={strong} {...(named ? { lang: 'ru' } : {})}>
          {variantTitle(identity, productVariantId)}
        </Typography.Text>
      </Tooltip>
      {(named && sku !== '') || extra !== undefined ? (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {named && sku !== '' ? `SKU ${sku}` : null}
          {named && sku !== '' && extra !== undefined ? ' · ' : null}
          {extra}
        </Typography.Text>
      ) : null}
    </Flex>
  );
}
