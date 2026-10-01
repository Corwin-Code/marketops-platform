import { Link } from 'react-router';
import { Alert, Flex, Space } from 'antd';
import { CommercialInputsSection } from '../guardrails/CommercialInputsSection';
import { CommercialPolicySection } from '../guardrails/CommercialPolicySection';
import { EconomicsProfileSection } from '../guardrails/EconomicsProfileSection';
import { WriteSwitchesSection } from '../guardrails/WriteSwitchesSection';
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
      />
      <Flex vertical gap={16} key={storeId}>
        <WriteSwitchesSection context={context} storeId={storeId} />
        <CommercialPolicySection context={context} storeId={storeId} />
        <CommercialInputsSection context={context} storeId={storeId} />
        <EconomicsProfileSection context={context} storeId={storeId} />
      </Flex>
    </Page>
  );
}
