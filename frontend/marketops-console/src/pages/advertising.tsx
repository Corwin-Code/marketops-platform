import { useNavigate, useParams } from 'react-router';
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

/** Advertising cases waiting for a person. */
export function AdvertisingQueuePage({ context }: ConsolePageProps): React.JSX.Element {
  const navigate = useNavigate();
  return (
    <Page title={pages.advertisingQueue} description={pageDescriptions.advertisingQueue}>
      <AdvertisingQueue
        context={context}
        onSelect={(caseId) => {
          void navigate(advertisingCasePath(caseId));
        }}
      />
    </Page>
  );
}

/** One advertising case. */
export function AdvertisingCasePage({ context }: ConsolePageProps): React.JSX.Element {
  const navigate = useNavigate();
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
          void navigate(ROUTES.advertisingQueue);
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
