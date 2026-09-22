import { ArrowLeftOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router';
import type { ConsoleRequest, Recommendation } from '../api/console';
import { CommandTimeline } from '../commands/CommandTimeline';
import { DiagnosticExportPanel } from '../diagnosis/DiagnosticExportPanel';
import { REVIEW_PARAM, SubjectDiagnosisView } from '../diagnosis/SubjectDiagnosisView';
import { pageDescriptions, pages, shell as text } from '../i18n/zh/shell';
import { commandPath, ROUTES, subjectPath } from '../layout/navigation';
import { PriorityQueue } from '../queue/PriorityQueue';
import { NotFoundPage } from './NotFoundPage';
import { Page } from './Page';

/** What every signed-in page is given. */
export interface ConsolePageProps {
  /** Where to send requests and who is asking. */
  readonly context: ConsoleRequest;
  /** Store whose work is being shown. */
  readonly storeId: string;
}

/** Navigation state an older review link may still carry. */
export interface ReviewState {
  readonly recommendation: Recommendation;
}

/**
 * Read the proposal handed over in navigation state.
 *
 * State survives a reload only in some browsers and can be anything a stale
 * history entry left behind, so only the identifiers are read, and checked.
 */
function readReviewState(
  state: unknown,
): { readonly id: string; readonly subjectId: string } | undefined {
  if (typeof state !== 'object' || state === null || !('recommendation' in state)) {
    return undefined;
  }
  const candidate: unknown = state.recommendation;
  if (typeof candidate !== 'object' || candidate === null) {
    return undefined;
  }
  const record = candidate as Record<string, unknown>;
  return typeof record.id === 'string' &&
    record.id !== '' &&
    typeof record.subjectId === 'string' &&
    record.subjectId !== ''
    ? { id: record.id, subjectId: record.subjectId }
    : undefined;
}

/** Address of a subject's page with one proposal's review open. */
function reviewPath(subjectId: string, recommendationId: string): string {
  return `${subjectPath(subjectId)}?${REVIEW_PARAM}=${encodeURIComponent(recommendationId)}`;
}

/** Whether the browser history holds an earlier page of this console. */
function hasInAppHistory(): boolean {
  const state: unknown = window.history.state;
  if (typeof state !== 'object' || state === null || !('idx' in state)) {
    return false;
  }
  return typeof state.idx === 'number' && state.idx > 0;
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

/** One subject's proposals, evidence and conclusions; the review opens beside them. */
export function SubjectPage({ context, storeId }: ConsolePageProps): React.JSX.Element {
  const navigate = useNavigate();
  const location = useLocation();
  const { subjectId } = useParams();
  if (subjectId === undefined || subjectId === '') {
    return <NotFoundPage />;
  }
  return (
    <SubjectDiagnosisView
      key={`${storeId}:${subjectId}`}
      context={context}
      subjectId={subjectId}
      storeId={storeId}
      onBack={() => {
        // Back to wherever the operator came from, filters and page intact;
        // a page opened directly has no such place, so the queue it is. The
        // router's history index is checked as well as the location key,
        // because opening a review replaces the entry and gives it a key.
        if (location.key !== 'default' && hasInAppHistory()) {
          void navigate(-1);
        } else {
          void navigate(ROUTES.pricingQueue);
        }
      }}
      onOpenCommand={(commandId) => {
        void navigate(commandPath(commandId));
      }}
    />
  );
}

/**
 * The old review address. The review now opens beside the subject's
 * diagnosis, so a link carrying a proposal is sent there with the review open;
 * anything else goes to the work list.
 */
export function ReviewPage(): React.JSX.Element {
  const location = useLocation();
  const target = readReviewState(location.state);
  return (
    <Navigate
      to={target === undefined ? ROUTES.pricingQueue : reviewPath(target.subjectId, target.id)}
      replace
    />
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
