import { Tabs, Typography } from 'antd';
import type { ConsoleRequest } from '../api/console';
import { availabilityText } from '../i18n/zh/availability';
import { authorityText } from '../i18n/zh/availabilityAuthority';
import { SectionCard, useSearchParam } from '../ui';
import { InboundAttestations } from './InboundAttestations';
import { LeadTimePolicies } from './LeadTimePolicies';

export interface AvailabilityAuthorityPanelProps {
  readonly context: ConsoleRequest;
}

const TABS = ['inbound', 'lead-time'] as const;

/**
 * Governed procurement intake: inbound supply claims and lead-time and safety
 * policy versions. Every change is attributable, effective-dated or versioned,
 * and triggers a recalculation; nothing here writes marketplace stock.
 */
export function AvailabilityAuthorityPanel({
  context,
}: AvailabilityAuthorityPanelProps): React.JSX.Element {
  const [tab, setTab] = useSearchParam('tab');
  const active = (TABS as readonly string[]).includes(tab ?? '') ? (tab ?? 'inbound') : 'inbound';
  return (
    <section aria-label={availabilityText.authorityTitle}>
      <SectionCard title={availabilityText.authorityTitle}>
        <Typography.Paragraph type="secondary">
          {availabilityText.authorityHint}
        </Typography.Paragraph>
        <Tabs
          activeKey={active}
          onChange={(next) => {
            setTab(next === 'inbound' ? undefined : next);
          }}
          destroyOnHidden
          items={[
            {
              key: 'inbound',
              label: authorityText.tabInbound,
              children: <InboundAttestations context={context} />,
            },
            {
              key: 'lead-time',
              label: authorityText.tabLeadTime,
              children: <LeadTimePolicies context={context} />,
            },
          ]}
        />
      </SectionCard>
    </section>
  );
}
