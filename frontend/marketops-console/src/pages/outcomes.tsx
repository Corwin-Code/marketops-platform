import { Flex } from 'antd';
import { OutcomeTable } from '../outcomes/OutcomeTable';
import { WeeklyReviewSection } from '../outcomes/WeeklyReviewSection';
import { pageDescriptions, pages } from '../i18n/zh/shell';
import { Page } from './Page';
import type { ConsolePageProps } from './pricing';

/**
 * What became of executed actions (P10): the weekly review on top, then every action compared with
 * the period before it.
 */
export function OutcomesPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.pricingOutcomes} description={pageDescriptions.pricingOutcomes}>
      <Flex vertical gap={16} key={storeId}>
        <WeeklyReviewSection context={context} storeId={storeId} />
        <OutcomeTable context={context} storeId={storeId} />
      </Flex>
    </Page>
  );
}
