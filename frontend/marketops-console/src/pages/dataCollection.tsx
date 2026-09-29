import { DataCollectionView } from '../datacollection/DataCollectionView';
import { pageDescriptions, pages } from '../i18n/zh/shell';
import { Page } from './Page';
import type { ConsolePageProps } from './pricing';

/** When the store's Ozon data is read automatically, what was read last, and what the scheduler did. */
export function DataCollectionPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.dataCollection} description={pageDescriptions.dataCollection}>
      <DataCollectionView key={storeId} context={context} storeId={storeId} />
    </Page>
  );
}
