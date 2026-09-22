import { useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import { AdvertisingBriefView } from '../advertising/AdvertisingBriefView';
import { AdvertisingCaseView } from '../advertising/AdvertisingCaseView';
import { AdvertisingOperations } from '../advertising/AdvertisingOperations';
import { AdvertisingQueue } from '../advertising/AdvertisingQueue';
import { pageDescriptions, pages } from '../i18n/zh/shell';
import { advertisingCasePath, ROUTES } from '../layout/navigation';
import { NotFoundPage } from './NotFoundPage';
import { Page } from './Page';
import type { ConsolePageProps } from './pricing';

/** Brief kinds the backend publishes, as the calendar names them. */
export const BRIEF_KINDS = {
  daily: 'DAILY_ACTION_BRIEF',
  weekly: 'WEEKLY_EVIDENCE_REVIEW',
} as const;

/** History state set when a case is opened from the queue. */
const FROM_QUEUE_STATE = { fromAdvertisingQueue: true } as const;

function cameFromQueue(state: unknown): boolean {
  return (
    typeof state === 'object' &&
    state !== null &&
    (state as Record<string, unknown>).fromAdvertisingQueue === true
  );
}

/** Advertising cases waiting for a person. */
export function AdvertisingQueuePage({ context }: ConsolePageProps): React.JSX.Element {
  const navigate = useNavigate();
  return (
    <Page title={pages.advertisingQueue} description={pageDescriptions.advertisingQueue}>
      <AdvertisingQueue
        context={context}
        onSelect={(caseId) => {
          void navigate(advertisingCasePath(caseId), { state: FROM_QUEUE_STATE });
        }}
      />
    </Page>
  );
}

/** One advertising case. */
export function AdvertisingCasePage({ context }: ConsolePageProps): React.JSX.Element {
  const navigate = useNavigate();
  const location = useLocation();
  // Only a case opened from the queue in this tab steps back in history; a
  // case reached by a link, a reload into a fresh tab or the sign-in callback
  // (whose previous entry is the identity provider) goes to the queue route.
  // Read once on arrival: switching tabs replaces the entry and drops its
  // state, but the entry behind it is still the queue.
  const [hasInAppHistory] = useState(
    () => location.key !== 'default' && cameFromQueue(location.state),
  );
  const { caseId } = useParams();
  if (caseId === undefined || caseId === '') {
    return <NotFoundPage />;
  }
  return (
    <Page title={pages.advertisingCase} description={pageDescriptions.advertisingCase}>
      <AdvertisingCaseView
        key={caseId}
        context={context}
        caseId={caseId}
        onBack={() => {
          // Going back returns to the queue with its lane and page intact; a
          // case opened directly (a link, a reload) falls back to the queue.
          if (hasInAppHistory) {
            void navigate(-1);
          } else {
            void navigate(ROUTES.advertisingQueue);
          }
        }}
      />
    </Page>
  );
}

/** What advertising is doing and what is holding it. */
export function AdvertisingOperationsPage({ context }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.advertisingOperations} description={pageDescriptions.advertisingOperations}>
      <AdvertisingOperations context={context} />
    </Page>
  );
}

/**
 * The newest published brief of one kind.
 *
 * Asked for by kind and not by date: the period belongs to the owner's
 * reporting calendar, and a browser naming one from its own clock would be
 * deciding which cut of the facts a person is judged on.
 */
export function AdvertisingBriefPage({
  context,
  kind,
}: ConsolePageProps & { readonly kind: keyof typeof BRIEF_KINDS }): React.JSX.Element {
  const title = kind === 'daily' ? pages.advertisingDaily : pages.advertisingWeekly;
  const description =
    kind === 'daily' ? pageDescriptions.advertisingDaily : pageDescriptions.advertisingWeekly;
  return (
    <Page title={title} description={description}>
      <AdvertisingBriefView key={kind} context={context} briefKind={BRIEF_KINDS[kind]} />
    </Page>
  );
}
