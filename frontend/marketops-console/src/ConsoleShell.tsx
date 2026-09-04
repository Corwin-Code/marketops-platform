import { useMemo, useState } from 'react';
import type { ConsoleRequest, Recommendation } from './api/console';
import { CommandTimeline } from './commands/CommandTimeline';
import { SubjectDiagnosisView } from './diagnosis/SubjectDiagnosisView';
import { DiagnosticExportPanel } from './diagnosis/DiagnosticExportPanel';
import { AvailabilityCases } from './availability/AvailabilityCases';
import { AvailabilityQueue } from './availability/AvailabilityQueue';
import { AvailabilityAuthorityPanel } from './availability/AvailabilityAuthorityPanel';
import { AdvertisingQueue } from './advertising/AdvertisingQueue';
import { AdvertisingCaseView } from './advertising/AdvertisingCaseView';
import { PriorityQueue } from './queue/PriorityQueue';
import { RecommendationReview } from './workflow/RecommendationReview';
import type { Session } from './session/session';

/** What the shell needs in order to show a signed-in operator their work. */
export interface ConsoleShellProps {
  /** Origin every request is sent to. */
  readonly apiBaseUrl: string;
  /** The signed-in operator. */
  readonly session: Session;
  /** Store whose work is being shown. */
  readonly storeId: string;
  /** Request implementation, replaced in tests. */
  readonly fetchImpl?: typeof fetch;
  /** A proposal to review, when the operator has opened one. */
  readonly initialRecommendation?: Recommendation;
  /** Called when the operator signs out. */
  readonly onSignOut: () => void;
}

/** Which part of the journey the operator is looking at. */
type View =
  | { readonly name: 'queue' }
  | { readonly name: 'diagnosis'; readonly subjectId: string }
  | { readonly name: 'review'; readonly recommendation: Recommendation }
  | { readonly name: 'command'; readonly commandId: string }
  | { readonly name: 'advertising-case'; readonly caseId: string };

/**
 * The signed-in console: one journey, in the order the work happens.
 *
 * Work list, then the subject's evidence, then the proposal, then the command
 * that carries it out. Each step is reachable only from the one before it,
 * because the order is the point: this product exists so that a price change is
 * the end of a chain of evidence rather than something typed into a box.
 *
 * The request context is memoised on the token so a re-render does not restart
 * every child's fetch, and so a new token after re-authentication does.
 */
export function ConsoleShell({
  apiBaseUrl,
  session,
  storeId,
  fetchImpl,
  initialRecommendation,
  onSignOut,
}: ConsoleShellProps): React.JSX.Element {
  const context: ConsoleRequest = useMemo(
    () =>
      fetchImpl === undefined
        ? { apiBaseUrl, accessToken: session.accessToken }
        : { apiBaseUrl, accessToken: session.accessToken, fetchImpl },
    [apiBaseUrl, session.accessToken, fetchImpl],
  );
  const [view, setView] = useState<View>(
    initialRecommendation === undefined
      ? { name: 'queue' }
      : { name: 'review', recommendation: initialRecommendation },
  );

  return (
    <main aria-labelledby="console-heading">
      <h1 id="console-heading">MarketOps Russia</h1>
      <nav aria-label="Session">
        <p>
          Signed in{session.displayName === undefined ? '' : ` as ${session.displayName}`}, working
          in store {storeId}.
        </p>
        <button type="button" onClick={onSignOut}>
          Sign out
        </button>
      </nav>

      {view.name === 'queue' && (
        <DiagnosticExportPanel key={storeId} context={context} storeId={storeId} />
      )}

      {view.name === 'queue' && <AvailabilityQueue context={context} />}

      {view.name === 'queue' && <AvailabilityCases context={context} />}

      {view.name === 'queue' && <AvailabilityAuthorityPanel context={context} />}

      {view.name === 'queue' && (
        <AdvertisingQueue
          context={context}
          onSelect={(caseId) => {
            setView({ name: 'advertising-case', caseId });
          }}
        />
      )}

      {view.name === 'advertising-case' && (
        <AdvertisingCaseView
          key={view.caseId}
          context={context}
          caseId={view.caseId}
          onBack={() => {
            setView({ name: 'queue' });
          }}
        />
      )}

      {view.name === 'queue' && (
        <PriorityQueue
          context={context}
          storeId={storeId}
          onSelect={(subjectId) => {
            setView({ name: 'diagnosis', subjectId });
          }}
        />
      )}

      {view.name === 'diagnosis' && (
        <SubjectDiagnosisView
          key={`${storeId}:${view.subjectId}`}
          context={context}
          subjectId={view.subjectId}
          storeId={storeId}
          onBack={() => {
            setView({ name: 'queue' });
          }}
          onReview={(recommendation) => {
            setView({ name: 'review', recommendation });
          }}
        />
      )}

      {view.name === 'review' && (
        <>
          <button
            type="button"
            onClick={() => {
              setView({ name: 'diagnosis', subjectId: view.recommendation.subjectId });
            }}
          >
            Back to diagnosis
          </button>
          <RecommendationReview
            context={context}
            recommendation={view.recommendation}
            onDecided={(state, commandId) => {
              setView(commandId === undefined ? { name: 'queue' } : { name: 'command', commandId });
              void state;
            }}
          />
        </>
      )}

      {view.name === 'command' && <CommandTimeline context={context} commandId={view.commandId} />}
    </main>
  );
}
