import { Descriptions, Space, Tag, Typography } from 'antd';
import type { DescriptionsProps } from 'antd';
import type { FollowedAction, OutcomeReading } from '../api/outcomes';
import { formatMoney } from '../format';
import { codeLabel } from '../i18n';
import {
  ACTION_LABELS,
  INDEX_LABELS,
  REASON_LABELS,
  SIGNAL_LABELS,
  SOURCE_LABELS,
  VERDICT_LABELS,
  outcomesText as text,
} from '../i18n/zh/outcomes';
import type { TagColor } from '../ui';

const VERDICT_COLORS: Record<string, TagColor> = {
  IMPROVED: 'success',
  UNCHANGED: 'default',
  REGRESSED: 'error',
  INDETERMINATE: 'warning',
};

const SIGNAL_COLORS: Record<string, TagColor> = {
  POSITIVE: 'success',
  NEGATIVE: 'error',
  MIXED: 'warning',
  NONE: 'default',
  UNAVAILABLE: 'default',
};

/** The verdict as a coloured tag; a preliminary one says so. */
export function VerdictTag({ reading }: { readonly reading: OutcomeReading }): React.JSX.Element {
  return (
    <Space size={4}>
      <Tag color={VERDICT_COLORS[reading.verdict] ?? 'default'}>
        {codeLabel(VERDICT_LABELS, reading.verdict)}
      </Tag>
      {reading.stage === 'PRELIMINARY' ? (
        <Typography.Text type="secondary">{text.preliminaryTag}</Typography.Text>
      ) : null}
    </Space>
  );
}

/** The leading signals as a coloured tag. */
export function SignalTag({ signal }: { readonly signal: string }): React.JSX.Element {
  return <Tag color={SIGNAL_COLORS[signal] ?? 'default'}>{codeLabel(SIGNAL_LABELS, signal)}</Tag>;
}

/** The newest reading of an action: the final one once it is in. */
export function latestReading(action: FollowedAction): OutcomeReading | null {
  return action.finalReading ?? action.preliminary;
}

/** Where an action stands: observing, preliminary reading in, or judged. */
export function progressText(action: FollowedAction): string {
  if (action.finalReading !== null) return text.finalDone;
  if (action.preliminary !== null) return text.preliminaryDone(action.finalDueOn);
  return text.observing(action.preliminaryDueOn);
}

/** What was done, with the price change when there was one. */
export function actionText(action: FollowedAction): string {
  const kind = codeLabel(ACTION_LABELS, action.actionKind);
  const source = codeLabel(SOURCE_LABELS, action.actionSource);
  const label = action.actionKind === 'PRICE_CHANGE' ? `${source} · ${kind}` : kind;
  if (action.targetPrice === null) return label;
  const to = formatMoney(action.targetPrice, action.currencyCode);
  return action.priorPrice === null
    ? `${label} ${to}`
    : `${label} ${text.priceChange(formatMoney(action.priorPrice, action.currencyCode), to)}`;
}

/** A before → after pair; a side nothing was known for is a dash, never zero. */
export function beforeAfter(before: number | null, after: number | null): string {
  const show = (value: number | null): string =>
    value === null ? text.missing : value.toLocaleString('zh-CN');
  return `${show(before)}${text.arrow}${show(after)}`;
}

/** One reading in full. */
export function ReadingDetails({
  reading,
  currencyCode,
}: {
  readonly reading: OutcomeReading;
  readonly currencyCode: string | null;
}): React.JSX.Element {
  const flags: string[] = [];
  if (reading.stockoutObserved) flags.push(text.stockout);
  if (reading.promotionObserved) flags.push(text.promotion);
  if (reading.priceHeld === false) flags.push(text.priceNotHeld);
  if (reading.otherActionObserved) flags.push(text.otherAction);
  const price =
    reading.observationBuyerPriceMin === null
      ? text.missing
      : reading.observationBuyerPriceMin === reading.observationBuyerPriceMax
        ? formatMoney(reading.observationBuyerPriceMin, currencyCode)
        : `${formatMoney(reading.observationBuyerPriceMin, currencyCode)} ~ ${formatMoney(
            reading.observationBuyerPriceMax,
            currencyCode,
          )}`;
  const items: DescriptionsProps['items'] = [
    {
      key: 'verdict',
      label: codeLabel(text.stage, reading.stage),
      children: (
        <Space size={8} wrap>
          <VerdictTag reading={reading} />
          <SignalTag signal={reading.leadingSignal} />
        </Space>
      ),
    },
    {
      key: 'windows',
      label: text.windowsLabel,
      children: (
        <Space orientation="vertical" size={0}>
          <span>
            {text.windows(
              reading.baselineFrom,
              reading.baselineTo,
              reading.observationFrom,
              reading.observationTo,
            )}
          </span>
          <Typography.Text type="secondary">
            {text.coverage(
              reading.baselineDaysCovered,
              reading.observationDaysCovered,
              reading.windowDays,
            )}
          </Typography.Text>
        </Space>
      ),
    },
    {
      key: 'units',
      label: text.units,
      children: beforeAfter(reading.baselineOrderedUnits, reading.observationOrderedUnits),
    },
    {
      key: 'search',
      label: text.searchUsers,
      children: beforeAfter(reading.baselineSearchUsers, reading.observationSearchUsers),
    },
    {
      key: 'index',
      label: text.priceIndex,
      children: `${codeLabel(INDEX_LABELS, reading.baselinePriceIndex)}${text.arrow}${codeLabel(
        INDEX_LABELS,
        reading.observationPriceIndex,
      )}`,
    },
    { key: 'price', label: text.buyerPrice, children: price },
    {
      key: 'flags',
      label: text.flags,
      children: flags.length === 0 ? text.noFlag : flags.join('、'),
    },
    {
      key: 'reasons',
      label: text.reasons,
      children: (
        <Space orientation="vertical" size={0}>
          {reading.reasonCodes.map((code) => (
            <span key={code}>{codeLabel(REASON_LABELS, code)}</span>
          ))}
          <Typography.Text type="secondary">
            {text.ruleVersion(reading.ruleVersion)}
          </Typography.Text>
        </Space>
      ),
    },
  ];
  return <Descriptions bordered size="small" column={{ xs: 1, md: 2 }} items={items} />;
}
