import { OutcomeTable } from '../outcomes/OutcomeTable';
import { pageDescriptions, pages } from '../i18n/zh/shell';
import { Page } from './Page';
import type { ConsolePageProps } from './pricing';

/** What became of executed actions: each one compared with the period before it (P10). */
export function OutcomesPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.pricingOutcomes} description={pageDescriptions.pricingOutcomes}>
      <OutcomeTable key={storeId} context={context} storeId={storeId} />
    </Page>
  );
}
