import { Link } from 'react-router';
import { Alert, Space } from 'antd';
import { EconomicsProfileSection } from '../guardrails/EconomicsProfileSection';
import { guardrailsText as text } from '../i18n/zh/guardrails';
import { pageDescriptions, pages } from '../i18n/zh/shell';
import { ROUTES } from '../layout/navigation';
import { Page } from './Page';
import type { ConsolePageProps } from './pricing';

/** What the price guardrail needs before a price change can be approved inside the platform. */
export function GuardrailsPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.guardrails} description={pageDescriptions.guardrails}>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        title={
          <Space size={8} wrap>
            {text.freshnessNote}
            <Link to={ROUTES.dataCollection}>{text.openDataCollection}</Link>
          </Space>
        }
        description={text.policyComing}
      />
      <EconomicsProfileSection key={storeId} context={context} storeId={storeId} />
    </Page>
  );
}
