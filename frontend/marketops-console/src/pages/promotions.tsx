import { pageDescriptions, pages } from '../i18n/zh/shell';
import { PromotionsView } from '../promotions/PromotionsView';
import { Page } from './Page';
import type { ConsolePageProps } from './pricing';

/** The store's current Ozon promotions and what joining each would mean for each product. */
export function PromotionsPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.promotions} description={pageDescriptions.promotions}>
      <PromotionsView key={storeId} context={context} storeId={storeId} />
    </Page>
  );
}
