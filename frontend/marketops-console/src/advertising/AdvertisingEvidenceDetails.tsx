import { Descriptions, Flex, Typography } from 'antd';
import { states } from '../i18n';
import { EVIDENCE_KEY_LABELS } from '../i18n/zh/advertising';
import { TechnicalDetails } from '../ui/TechnicalDetails';

/** A camelCase key in Chinese, or the key itself when the console does not know it. */
export function evidenceKeyLabel(key: string): string {
  return Object.hasOwn(EVIDENCE_KEY_LABELS, key) ? (EVIDENCE_KEY_LABELS[key] ?? key) : key;
}

/** One disclosed value, without reinterpreting unknowns. */
function EvidenceValue({ value }: { readonly value: unknown }): React.JSX.Element {
  if (value === null || value === undefined) {
    return <Typography.Text type="secondary">未确定</Typography.Text>;
  }
  if (typeof value === 'string') {
    if (value.startsWith('{') || value.startsWith('[')) {
      try {
        const nested: unknown = JSON.parse(value);
        return <EvidenceValue value={nested} />;
      } catch {
        /* A native string stays exact when it is not a JSON structure. */
      }
    }
    return <Typography.Text style={{ wordBreak: 'break-all' }}>{value}</Typography.Text>;
  }
  if (typeof value === 'boolean') {
    return <Typography.Text>{value ? states.yes : states.no}</Typography.Text>;
  }
  if (typeof value === 'number') {
    return <Typography.Text>{String(value)}</Typography.Text>;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return <Typography.Text type="secondary">无</Typography.Text>;
    }
    return (
      <Flex vertical gap={4}>
        {value.map((item: unknown, index) => (
          <EvidenceValue key={index} value={item} />
        ))}
      </Flex>
    );
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value);
    if (entries.length === 0) {
      return <Typography.Text type="secondary">无</Typography.Text>;
    }
    return (
      <Descriptions
        bordered
        size="small"
        column={1}
        items={entries.map(([key, item]: [string, unknown]) => ({
          key,
          label: evidenceKeyLabel(key),
          children: <EvidenceValue value={item} />,
        }))}
      />
    );
  }
  return <Typography.Text type="secondary">未确定</Typography.Text>;
}

/**
 * Server-disclosed decision evidence rendered as labelled values.
 *
 * Folded away by default: the evidence stays reachable for review and support,
 * but does not crowd out what an operator decides on. Keys are named in Chinese
 * where the console knows them and kept as they arrived otherwise.
 */
export function AdvertisingEvidenceDetails({
  value,
  label,
  inline = false,
}: {
  readonly value: unknown;
  readonly label: string;
  /** Render the values directly instead of in a folded panel. */
  readonly inline?: boolean;
}): React.JSX.Element {
  const content = (
    <div aria-label={label} data-state="evidence">
      <EvidenceValue value={value} />
    </div>
  );
  return inline ? content : <TechnicalDetails label={label}>{content}</TechnicalDetails>;
}
