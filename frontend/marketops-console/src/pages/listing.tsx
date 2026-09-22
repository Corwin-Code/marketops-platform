import { useNavigate } from 'react-router';
import { pageDescriptions, pages } from '../i18n/zh/shell';
import { ROUTES } from '../layout/navigation';
import { ListingConversionShell } from '../listing/ListingConversionShell';
import { Page } from './Page';
import type { ConsolePageProps } from './pricing';

/** Listing conversion and content work. */
export function ListingPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  const navigate = useNavigate();
  return (
    <Page title={pages.listing} description={pageDescriptions.listing}>
      <ListingConversionShell
        key={storeId}
        context={context}
        storeId={storeId}
        onBack={() => {
          void navigate(ROUTES.pricingQueue);
        }}
      />
    </Page>
  );
}
