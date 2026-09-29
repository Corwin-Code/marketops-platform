import { pageDescriptions, pages } from '../i18n/zh/shell';
import { StoreDiagnosisView } from '../storediagnosis/StoreDiagnosisView';
import { Page } from './Page';
import type { ConsolePageProps } from './pricing';

/** Why the store's listings do not sell: every listing with its newest marketplace signals. */
export function StoreDiagnosisPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.storeDiagnosis} description={pageDescriptions.storeDiagnosis}>
      <StoreDiagnosisView key={storeId} context={context} storeId={storeId} />
    </Page>
  );
}
