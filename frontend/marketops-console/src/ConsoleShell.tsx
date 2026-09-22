import { lazy, useMemo } from 'react';
import { Navigate, Route, Routes } from 'react-router';
import type { ConsoleRequest } from './api/console';
import type { ConsoleConfig } from './config';
import { AppLayout } from './layout/AppLayout';
import { ROUTES } from './layout/navigation';
import { NotFoundPage } from './pages/NotFoundPage';
import type { Session } from './session/session';

// Each business area is its own chunk, loaded when one of its pages is opened.
const pricing = () => import('./pages/pricing');
const availability = () => import('./pages/availability');
const advertising = () => import('./pages/advertising');
const PricingQueuePage = lazy(async () => ({ default: (await pricing()).PricingQueuePage }));
const SubjectPage = lazy(async () => ({ default: (await pricing()).SubjectPage }));
const ReviewPage = lazy(async () => ({ default: (await pricing()).ReviewPage }));
const CommandPage = lazy(async () => ({ default: (await pricing()).CommandPage }));
const PricingExportPage = lazy(async () => ({ default: (await pricing()).PricingExportPage }));
const AvailabilityRisksPage = lazy(async () => ({
  default: (await availability()).AvailabilityRisksPage,
}));
const AvailabilityCasesPage = lazy(async () => ({
  default: (await availability()).AvailabilityCasesPage,
}));
const AvailabilityAuthorityPage = lazy(async () => ({
  default: (await availability()).AvailabilityAuthorityPage,
}));
const AdvertisingQueuePage = lazy(async () => ({
  default: (await advertising()).AdvertisingQueuePage,
}));
const AdvertisingCasePage = lazy(async () => ({
  default: (await advertising()).AdvertisingCasePage,
}));
const AdvertisingOperationsPage = lazy(async () => ({
  default: (await advertising()).AdvertisingOperationsPage,
}));
const AdvertisingBriefPage = lazy(async () => ({
  default: (await advertising()).AdvertisingBriefPage,
}));
const ListingPage = lazy(async () => ({ default: (await import('./pages/listing')).ListingPage }));
const ListingAllowancesPage = lazy(async () => ({
  default: (await import('./pages/listingAllowances')).ListingAllowancesPage,
}));
const SystemStatusPage = lazy(async () => ({
  default: (await import('./pages/system')).SystemStatusPage,
}));

/** What the shell needs in order to show a signed-in operator their work. */
export interface ConsoleShellProps {
  /** Where requests go and which environment this is. */
  readonly config: ConsoleConfig;
  /** The signed-in operator. */
  readonly session: Session;
  /** Store whose work is being shown. */
  readonly storeId: string;
  /** Request implementation, replaced in tests. */
  readonly fetchImpl?: typeof fetch | undefined;
  /** The instant the session clock was last read. */
  readonly now: number;
  /** Called when the operator signs out. */
  readonly onSignOut: () => void;
}

/**
 * The signed-in console: every page, each at its own address, inside one frame.
 *
 * The pricing journey keeps its order — work list, the subject's evidence, the
 * proposal, the command that carries it out — because a proposal is only
 * opened from its diagnosis and a command only from a decision. The other
 * areas are reachable directly from the menu.
 *
 * The request context is memoised on the token so a re-render does not restart
 * every child's fetch, and so a new token after re-authentication does.
 */
export function ConsoleShell({
  config,
  session,
  storeId,
  fetchImpl,
  now,
  onSignOut,
}: ConsoleShellProps): React.JSX.Element {
  const context: ConsoleRequest = useMemo(
    () =>
      fetchImpl === undefined
        ? { apiBaseUrl: config.apiBaseUrl, accessToken: session.accessToken }
        : { apiBaseUrl: config.apiBaseUrl, accessToken: session.accessToken, fetchImpl },
    [config.apiBaseUrl, session.accessToken, fetchImpl],
  );
  const page = { context, storeId };

  return (
    <Routes>
      <Route
        element={
          <AppLayout
            displayName={session.displayName}
            storeId={storeId}
            environment={config.environment}
            expiresAt={session.expiresAt}
            now={now}
            onSignOut={onSignOut}
          />
        }
      >
        <Route index element={<Navigate to={ROUTES.pricingQueue} replace />} />
        <Route path={ROUTES.pricingQueue} element={<PricingQueuePage {...page} />} />
        <Route path={ROUTES.subject} element={<SubjectPage {...page} />} />
        {/* The review opens beside the diagnosis; the old address redirects there. */}
        <Route path={ROUTES.review} element={<ReviewPage />} />
        <Route path={ROUTES.command} element={<CommandPage {...page} />} />
        <Route path={ROUTES.pricingExport} element={<PricingExportPage {...page} />} />
        <Route path={ROUTES.availabilityRisks} element={<AvailabilityRisksPage {...page} />} />
        <Route path={ROUTES.availabilityCases} element={<AvailabilityCasesPage {...page} />} />
        <Route
          path={ROUTES.availabilityAuthority}
          element={<AvailabilityAuthorityPage {...page} />}
        />
        <Route path={ROUTES.advertisingQueue} element={<AdvertisingQueuePage {...page} />} />
        <Route path={ROUTES.advertisingCase} element={<AdvertisingCasePage {...page} />} />
        <Route
          path={ROUTES.advertisingOperations}
          element={<AdvertisingOperationsPage {...page} />}
        />
        <Route
          path={ROUTES.advertisingDaily}
          element={<AdvertisingBriefPage key="daily" kind="daily" {...page} />}
        />
        <Route
          path={ROUTES.advertisingWeekly}
          element={<AdvertisingBriefPage key="weekly" kind="weekly" {...page} />}
        />
        <Route path={ROUTES.listing} element={<ListingPage {...page} />} />
        <Route path={ROUTES.listingAllowances} element={<ListingAllowancesPage {...page} />} />
        <Route
          path={ROUTES.systemStatus}
          element={<SystemStatusPage config={config} fetchImpl={fetchImpl} />}
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
