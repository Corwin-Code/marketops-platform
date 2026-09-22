import { pageDescriptions, pages } from '../i18n/zh/shell';
import { ListingConversionShell } from '../listing/ListingConversionShell';
import { Page } from './Page';
import type { ConsolePageProps } from './pricing';

/** Listing conversion and content work. */
export function ListingPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.listing} description={pageDescriptions.listing}>
      <ListingConversionShell key={storeId} context={context} storeId={storeId} />
    </Page>
  );
}
