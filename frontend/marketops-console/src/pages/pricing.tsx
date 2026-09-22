import { ArrowLeftOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { useLocation, useNavigate, useParams } from 'react-router';
import { AdvertisingOutcomeHistory } from '../advertising/AdvertisingOutcomeHistory';
import type { ConsoleRequest, Recommendation } from '../api/console';
import { CommandTimeline } from '../commands/CommandTimeline';
import { DiagnosticExportPanel } from '../diagnosis/DiagnosticExportPanel';
import { SubjectDiagnosisView } from '../diagnosis/SubjectDiagnosisView';
import { pageDescriptions, pages, shell as text } from '../i18n/zh/shell';
import { commandPath, ROUTES, subjectPath } from '../layout/navigation';
import { PriorityQueue } from '../queue/PriorityQueue';
import { EmptyState } from '../ui';
import { RecommendationReview } from '../workflow/RecommendationReview';
import { NotFoundPage } from './NotFoundPage';
import { Page } from './Page';

/** What every signed-in page is given. */
export interface ConsolePageProps {
  /** Where to send requests and who is asking. */
  readonly context: ConsoleRequest;
  /** Store whose work is being shown. */
  readonly storeId: string;
}

/** Navigation state carrying the proposal opened from a diagnosis. */
export interface ReviewState {
  readonly recommendation: Recommendation;
}

/**
 * Read the proposal handed over in navigation state.
 *
 * State survives a reload only in some browsers and can be anything a stale
 * history entry left behind, so its shape is checked before it is trusted.
 */
function readReviewState(state: unknown): Recommendation | undefined {
  if (typeof state !== 'object' || state === null || !('recommendation' in state)) {
    return undefined;
  }
  const candidate: unknown = state.recommendation;
  if (typeof candidate !== 'object' || candidate === null) {
    return undefined;
  }
  const record = candidate as Record<string, unknown>;
  return typeof record.id === 'string' &&
    typeof record.subjectId === 'string' &&
    typeof record.state === 'string' &&
    typeof record.version === 'number'
    ? (candidate as Recommendation)
    : undefined;
}

function BackButton({ to, label }: { readonly to: string; readonly label: string }) {
  const navigate = useNavigate();
  return (
    <Button
      icon={<ArrowLeftOutlined />}
      onClick={() => {
        void navigate(to);
      }}
    >
      {label}
    </Button>
  );
}

/** Today's work list: the subjects most worth a look first. */
export function PricingQueuePage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  const navigate = useNavigate();
  return (
    <Page title={pages.pricingQueue} description={pageDescriptions.pricingQueue}>
      <PriorityQueue
        key={storeId}
        context={context}
        storeId={storeId}
        onSelect={(subjectId) => {
          void navigate(subjectPath(subjectId));
        }}
      />
    </Page>
  );
}

/** One subject's evidence and the proposals waiting on it. */
export function SubjectPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  const navigate = useNavigate();
  const { subjectId } = useParams();
  if (subjectId === undefined || subjectId === '') {
    return <NotFoundPage />;
  }
  return (
    <Page title={pages.subject} description={pageDescriptions.subject}>
      <SubjectDiagnosisView
        key={`${storeId}:${subjectId}`}
        context={context}
        subjectId={subjectId}
        storeId={storeId}
        onBack={() => {
          void navigate(ROUTES.pricingQueue);
        }}
        onReview={(recommendation) => {
          const state: ReviewState = { recommendation };
          void navigate(ROUTES.review, { state });
        }}
      />
    </Page>
  );
}

/** The decision on one proposal, opened from its subject's diagnosis. */
export function ReviewPage({ context }: ConsolePageProps): React.JSX.Element {
  const navigate = useNavigate();
  const location = useLocation();
  const recommendation = readReviewState(location.state);
  if (recommendation === undefined) {
    return (
      <Page title={pages.review} description={pageDescriptions.review}>
        <div data-state="missing-recommendation">
          <EmptyState description={text.missingRecommendationDescription}>
            <Button
              type="primary"
              onClick={() => {
                void navigate(ROUTES.pricingQueue);
              }}
            >
              {text.backToQueue}
            </Button>
          </EmptyState>
        </div>
      </Page>
    );
  }
  return (
    <Page
      title={pages.review}
      description={pageDescriptions.review}
      extra={<BackButton to={subjectPath(recommendation.subjectId)} label={text.backToDiagnosis} />}
    >
      <RecommendationReview
        key={recommendation.id}
        context={context}
        recommendation={recommendation}
        onDecided={(_state, commandId) => {
          // The decided proposal is not a page to come back to, so the
          // review entry is replaced rather than stacked.
          void navigate(commandId === undefined ? ROUTES.pricingQueue : commandPath(commandId), {
            replace: true,
          });
        }}
      />
    </Page>
  );
}

/** What a command did, and beside it what the change achieved. */
export function CommandPage({ context }: ConsolePageProps): React.JSX.Element {
  const { commandId } = useParams();
  if (commandId === undefined || commandId === '') {
    return <NotFoundPage />;
  }
  return (
    <Page
      title={pages.command}
      description={pageDescriptions.command}
      extra={<BackButton to={ROUTES.pricingQueue} label={text.backToQueue} />}
    >
      <CommandTimeline key={commandId} context={context} commandId={commandId} />
      {/*
        Kept separate from the timeline because provider acceptance, readback
        and outcome are three different facts and the timeline only carries
        the first two.
      */}
      <AdvertisingOutcomeHistory
        key={`outcome:${commandId}`}
        context={context}
        commandId={commandId}
      />
    </Page>
  );
}

/** The store's diagnostic export. */
export function PricingExportPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  return (
    <Page title={pages.pricingExport} description={pageDescriptions.pricingExport}>
      <DiagnosticExportPanel key={storeId} context={context} storeId={storeId} />
    </Page>
  );
}
