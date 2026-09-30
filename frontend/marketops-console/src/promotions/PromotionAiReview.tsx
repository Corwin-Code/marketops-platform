import type { ConsoleRequest } from '../api/console';
import { promotionsText as text } from '../i18n/zh/promotions';
import { AiSummaryCard } from '../storediagnosis/AiInterpretation';

/**
 * The model's advice on the store's promotions above the estimates: which products to join, keep,
 * skip or leave, and why. Advisory only; the estimates in the tables stay the official answer.
 */
export function PromotionAiReview({
  context,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}): React.JSX.Element {
  return (
    <AiSummaryCard
      context={context}
      storeId={storeId}
      source="promotion"
      labels={{
        title: text.aiTitle,
        hint: text.aiHint,
        none: text.aiNone,
        actions: text.aiActions,
      }}
      // The facts cite the products' demand values, which this page does not list one by one.
      referenceLabel={() => undefined}
    />
  );
}
