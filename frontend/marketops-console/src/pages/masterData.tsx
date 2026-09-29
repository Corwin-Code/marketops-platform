import { pageDescriptions, pages } from '../i18n/zh/shell';
import { MasterDataView } from '../masterdata/MasterDataView';
import { Page } from './Page';
import type { ConsolePageProps } from './pricing';

/** Which internal SKU each marketplace listing is mapped to, and whether its marketplace cost is adopted. */
export function MasterDataPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.masterData} description={pageDescriptions.masterData}>
      <MasterDataView key={storeId} context={context} storeId={storeId} />
    </Page>
  );
}
