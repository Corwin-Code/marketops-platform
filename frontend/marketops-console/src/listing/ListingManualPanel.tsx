import type { ConsoleRequest } from '../api/console';
import { manualText } from '../i18n/zh/listingManual';
import { Stack } from './ListingCommon';
import { ListingManualEngagements } from './ListingManualEngagements';
import { ListingManualPackets } from './ListingManualPackets';

export interface ListingManualPanelProps {
  readonly context: ConsoleRequest;
}

/**
 * The manual execution path and simple promotion engagements.
 *
 * A packet carries exactly the approved material; the executor reports, and a
 * different person verifies. An engagement is entered on the platform's own
 * console and only recorded, exited and released here. Every row action opens
 * its own dialog, filled only from that row.
 */
export function ListingManualPanel({ context }: ListingManualPanelProps): React.JSX.Element {
  return (
    <section aria-label={manualText.region}>
      <Stack>
        <ListingManualPackets context={context} />
        <ListingManualEngagements context={context} />
      </Stack>
    </section>
  );
}
