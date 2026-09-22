import { pageDescriptions, pages } from '../i18n/zh/shell';
import { ListingCalibrationsPanel } from '../listing/ListingCalibrationsPanel';
import { Page } from './Page';
import type { ConsolePageProps } from './pricing';

/** Calibration packages: drafted and validated by the professional, accepted and activated by the Owner. */
export function ListingCalibrationsPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.listingCalibrations} description={pageDescriptions.listingCalibrations}>
      <ListingCalibrationsPanel key={storeId} context={context} storeId={storeId} />
    </Page>
  );
}
