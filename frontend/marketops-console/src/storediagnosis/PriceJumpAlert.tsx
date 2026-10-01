import { Alert, Button, Flex, Space } from 'antd';
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import type { ConsoleRequest } from '../api/console';
import type { EndedPromotion } from '../api/promotions';
import { fetchEndedPromotions } from '../api/promotions';
import { formatPercent, formatStoreDate, isDecimal } from '../format';
import { priceJumpText as text } from '../i18n/zh/storeDiagnosis';
import { ROUTES } from '../layout/navigation';
import type { FindingsLoad } from './DiagnosisConclusions';

/** The rule whose findings the alert gathers. */
const BUYER_PRICE_JUMP = 'BUYER_PRICE_JUMP';

/** The lookback the rule states, or the configured default when a finding does not say. */
function lookbackOf(details: readonly Readonly<Record<string, string>>[]): number {
  const stated = Number(details[0]?.lookbackDays);
  return Number.isInteger(stated) && stated >= 1 && stated <= 31 ? stated : 7;
}

/** The smallest and the largest rise, as the rule stated them. */
function riseRange(
  details: readonly Readonly<Record<string, string>>[],
): readonly [string, string] | undefined {
  const rises = details
    .map((detail) => detail.riseOverRecentLow)
    .filter((rise): rise is string => isDecimal(rise))
    .sort((left, right) => Number(left) - Number(right));
  const lowest = rises[0];
  const highest = rises[rises.length - 1];
  return lowest === undefined || highest === undefined ? undefined : [lowest, highest];
}

/**
 * Buyer prices that rose sharply in the last days, across the store, with the promotions the store
 * took part in that ended meanwhile: a promotion that ends raises the price of every product in it
 * at once, and nothing else on the page would say so. Shown only while the rule finds such a rise.
 */
export function PriceJumpAlert({
  context,
  storeId,
  load,
  onShowProducts,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly load: FindingsLoad;
  readonly onShowProducts: () => void;
}): React.JSX.Element | null {
  const details =
    load.kind === 'loaded'
      ? load.findings.listings.flatMap((listing) =>
          listing.findings
            .filter((finding) => finding.ruleCode === BUYER_PRICE_JUMP)
            .map((finding) => finding.detail),
        )
      : [];
  const lookbackDays = lookbackOf(details);
  const jumped = details.length > 0;
  const [ended, setEnded] = useState<readonly EndedPromotion[] | undefined>(undefined);

  useEffect(() => {
    if (!jumped) return undefined;
    let live = true;
    void fetchEndedPromotions(context, storeId, lookbackDays).then((outcome) => {
      if (live) setEnded(outcome.ok ? outcome.value.promotions : undefined);
    });
    return () => {
      live = false;
    };
  }, [context, storeId, jumped, lookbackDays]);

  if (!jumped) return null;
  const range = riseRange(details);
  const joined = (ended ?? []).filter((promotion) => promotion.participating === true);
  return (
    <Alert
      type="warning"
      showIcon
      title={text.title(details.length, lookbackDays)}
      description={
        <Flex vertical gap={4}>
          {range !== undefined && (
            <span>{text.rise(formatPercent(range[0]), formatPercent(range[1]))}</span>
          )}
          {ended !== undefined &&
            (joined.length === 0 ? (
              <span>{text.noEndedPromotion(lookbackDays)}</span>
            ) : (
              joined.map((promotion) => (
                <span key={promotion.promotionId}>
                  {text.endedPromotion(
                    promotion.title ?? promotion.nativePromotionKey,
                    formatStoreDate(promotion.endsAt),
                    promotion.participantCount,
                  )}
                </span>
              ))
            ))}
          <span>{text.meaning}</span>
          <Space size={8} wrap>
            <Button size="small" onClick={onShowProducts}>
              {text.showProducts}
            </Button>
            <Link to={ROUTES.promotions}>{text.openPromotions}</Link>
          </Space>
        </Flex>
      }
    />
  );
}
