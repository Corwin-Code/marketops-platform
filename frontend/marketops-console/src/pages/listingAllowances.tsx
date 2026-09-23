import { pageDescriptions, pages } from '../i18n/zh/shell';
import { ListingAllowancesPanel } from '../listing/ListingAllowancesPanel';
import { Page } from './Page';
import type { ConsolePageProps } from './pricing';

/** The Owner's launch allowance maintenance. */
export function ListingAllowancesPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.listingAllowances} description={pageDescriptions.listingAllowances}>
      <ListingAllowancesPanel key={storeId} context={context} storeId={storeId} />
    </Page>
  );
}
