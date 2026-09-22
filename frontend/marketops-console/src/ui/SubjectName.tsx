import { Flex, Tooltip, Typography } from 'antd';
import type { SubjectIdentity } from '../api/console';

/** The name an operator reads first: product and variant, or a short identifier. */
export function subjectTitle(identity: SubjectIdentity | undefined, subjectId: string): string {
  const name = [identity?.productName, identity?.variantName]
    .filter((part): part is string => part !== null && part !== undefined && part.trim() !== '')
    .filter((part, index, parts) => parts.indexOf(part) === index)
    .join(' · ');
  return name === '' ? `商品 ${subjectId.slice(0, 8)}` : name;
}

/** SKU codes and variant attributes, for the line under the name. */
export function subjectSubtitle(identity: SubjectIdentity | undefined): string | undefined {
  if (identity === undefined) {
    return undefined;
  }
  const parts = [
    identity.skuCode === null ? undefined : `SKU ${identity.skuCode}`,
    identity.platformSkuKey === null
      ? undefined
      : `${identity.platformCode ?? '平台'} ${identity.platformSkuKey}`,
    identity.colorLabel ?? undefined,
    identity.sizeLabel ?? undefined,
  ].filter((part): part is string => part !== undefined && part.trim() !== '');
  return parts.length === 0 ? undefined : parts.join(' · ');
}

/**
 * A listing variant as an operator knows it.
 *
 * The business name leads; SKUs follow on a second line; the full identifier is
 * one hover away for support and audit, never the headline.
 */
export function SubjectName({
  identity,
  subjectId,
  strong = true,
}: {
  readonly identity: SubjectIdentity | undefined;
  readonly subjectId: string;
  readonly strong?: boolean;
}): React.JSX.Element {
  const subtitle = subjectSubtitle(identity);
  return (
    <Flex vertical gap={0} data-subject-id={subjectId}>
      <Tooltip title={`编号 ${subjectId}`} mouseEnterDelay={0.5}>
        <Typography.Text strong={strong}>{subjectTitle(identity, subjectId)}</Typography.Text>
      </Tooltip>
      {subtitle !== undefined && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {subtitle}
        </Typography.Text>
      )}
    </Flex>
  );
}
