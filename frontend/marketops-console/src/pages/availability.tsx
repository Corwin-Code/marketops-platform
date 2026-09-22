import { AvailabilityAuthorityPanel } from '../availability/AvailabilityAuthorityPanel';
import { AvailabilityCases } from '../availability/AvailabilityCases';
import { AvailabilityQueue } from '../availability/AvailabilityQueue';
import { pageDescriptions, pages } from '../i18n/zh/shell';
import { Page } from './Page';
import type { ConsolePageProps } from './pricing';

/** Products at risk of running out, most urgent first. */
export function AvailabilityRisksPage({ context }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.availabilityRisks} description={pageDescriptions.availabilityRisks}>
      <AvailabilityQueue context={context} />
    </Page>
  );
}

/** Who owns each availability failure and what they have done. */
export function AvailabilityCasesPage({ context }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.availabilityCases} description={pageDescriptions.availabilityCases}>
      <AvailabilityCases context={context} />
    </Page>
  );
}

/** Inbound evidence and lead-time policy. */
export function AvailabilityAuthorityPage({ context }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.availabilityAuthority} description={pageDescriptions.availabilityAuthority}>
      <AvailabilityAuthorityPanel context={context} />
    </Page>
  );
}
