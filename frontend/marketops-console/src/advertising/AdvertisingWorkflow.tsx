import { AdvertisingEvidenceDetails } from './AdvertisingEvidenceDetails';
import { AdvertisingCommandTimeline } from './AdvertisingCommandTimeline';
import { useEffect, useState } from 'react';
import {
  actOnAdvertisingCandidate,
  fetchAdvertisingWorkflow,
  previewAdvertisingCandidate,
} from '../api/console';
import type {
  AdvertisingCandidateAction,
  AdvertisingDecisionPreview,
  ConsoleFailure,
  ConsoleRequest,
} from '../api/console';
import type {
  AdvertisingWorkflow as Workflow,
  AdvertisingWorkflowCandidate,
} from '../api/advertising';
import { AdvertisingProblem } from './AdvertisingQueue';
import { AdvertisingResponsibilityControls } from './AdvertisingResponsibilityControls';
import { AdvertisingTimestamp } from './AdvertisingTimestamp';

const LABELS: Record<AdvertisingCandidateAction, string> = {
  SELECT_CANDIDATE: 'Select exact candidate',
  REJECT_CANDIDATE: 'Reject candidate',
  ENDORSE: 'Record operational endorsement',
  APPROVE: 'Approve exact change',
  CREATE_COMMAND: 'Create approved command',
};

function candidateActions(
  candidate: AdvertisingWorkflowCandidate,
  allowed: readonly string[],
): AdvertisingCandidateAction[] {
  const actions: AdvertisingCandidateAction[] = [];
  if (candidate.makerUserId === undefined && candidate.state === 'DRAFT')
    actions.push('SELECT_CANDIDATE');
  if (candidate.state === 'DRAFT') actions.push('REJECT_CANDIDATE');
  if (
    candidate.state === 'VALIDATED' &&
    candidate.makerUserId !== undefined &&
    candidate.endorserUserId === undefined
  )
    actions.push('ENDORSE');
  if (candidate.state === 'READY_FOR_REVIEW' && candidate.endorserUserId !== undefined)
    actions.push('APPROVE');
  if (candidate.state === 'APPROVED' && candidate.commandId === undefined)
    actions.push('CREATE_COMMAND');
  return actions.filter((action) => allowed.includes(action));
}

const KNOWN_STAFFED_COVERAGE = new Set([
  'IN_COVERAGE',
  'OUT_OF_COVERAGE',
  'OUT_OF_COVERAGE_ACTIVE_HARM',
  'ACCEPTED_EXCEPTION_ACTIVE',
]);

function responseInstant(value: unknown): string | undefined {
  return typeof value === 'string' &&
    value.trim().length > 0 &&
    Number.isFinite(new Date(value).getTime())
    ? value
    : undefined;
}

/** Display the server snapshot; never derive a staffed deadline from browser time. */
function AdvertisingResponseTiming({
  slo,
  timezone,
}: {
  readonly slo: Workflow['slo'];
  readonly timezone: string | undefined;
}): React.JSX.Element {
  const current = Array.isArray(slo) ? undefined : slo;
  const coverage = current?.coverageState;
  const knownCoverage = typeof coverage === 'string' && KNOWN_STAFFED_COVERAGE.has(coverage);
  const ackDue = responseInstant(current?.acknowledgementDueAt);
  const actionDue = responseInstant(current?.actionDueAt);
  const acknowledgedAt = responseInstant(current?.acknowledgedAt);
  const actedAt = responseInstant(current?.firstAttributableActionAt);
  const timeliness = (breached: unknown, due: string | undefined): string =>
    breached === true
      ? 'BREACHED'
      : breached === false && knownCoverage && due !== undefined
        ? 'NOT_BREACHED as of this response'
        : 'UNRESOLVED';
  const completion = (
    value: string | undefined,
    raw: unknown,
    due: string | undefined,
  ): React.JSX.Element =>
    value !== undefined ? (
      <>
        recorded at <AdvertisingTimestamp value={value} timezone={timezone} />
      </>
    ) : (
      <>
        {raw === null && knownCoverage && due !== undefined
          ? 'not recorded as of this response'
          : 'UNRESOLVED'}
      </>
    );
  let actionClock = 'UNRESOLVED';
  if (actedAt !== undefined) actionClock = 'stage completed';
  else if (current?.actionPaused === true) actionClock = 'paused';
  else if (
    knownCoverage &&
    actionDue !== undefined &&
    typeof current?.actionBreached === 'boolean' &&
    current.actionPaused === false &&
    current.firstAttributableActionAt === null
  ) {
    if (coverage === 'IN_COVERAGE') actionClock = 'active';
    else if (coverage === 'OUT_OF_COVERAGE' || coverage === 'OUT_OF_COVERAGE_ACTIVE_HARM')
      actionClock = 'awaiting staffed coverage';
  }
  const age = current?.wallClockExposureAgeSeconds;

  return (
    <div role="group" aria-label="Advertising response timing">
      <p aria-label="Acknowledgement completion">
        Acknowledgement completion: {completion(acknowledgedAt, current?.acknowledgedAt, ackDue)}
      </p>
      <p aria-label="Action-stage completion">
        Action-stage completion:{' '}
        {completion(actedAt, current?.firstAttributableActionAt, actionDue)}
      </p>
      <p aria-label="Acknowledgement timeliness">
        Acknowledgement timeliness: {timeliness(current?.acknowledgementBreached, ackDue)}
      </p>
      <p aria-label="Action timeliness">
        Action timeliness: {timeliness(current?.actionBreached, actionDue)}
      </p>
      <p aria-label="Action clock state">Action clock: {actionClock}</p>
      {current?.actionPaused === true && (
        <p>Current action pause is reported; exposure age continues.</p>
      )}
      <p>
        Exposure age{' '}
        {typeof age === 'number' && Number.isSafeInteger(age) && age >= 0 ? age : 'UNRESOLVED'}{' '}
        seconds
      </p>
    </div>
  );
}

/** Each interaction round-trips through the existing workflow authority. */
export function AdvertisingWorkflow({
  context,
  caseId,
  timezone,
}: {
  readonly context: ConsoleRequest;
  readonly caseId: string;
  readonly timezone: string | undefined;
}): React.JSX.Element {
  const [workflow, setWorkflow] = useState<Workflow>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const [notice, setNotice] = useState<string>();
  const [preview, setPreview] = useState<AdvertisingDecisionPreview>();

  useEffect(() => {
    let active = true;
    void fetchAdvertisingWorkflow(context, caseId).then((result) => {
      if (!active) return;
      if (result.ok) {
        setWorkflow(result.value);
        setFailure(undefined);
      } else {
        setWorkflow(undefined);
        setFailure(result.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, caseId, revision]);

  async function act(
    candidate: AdvertisingWorkflowCandidate,
    action: AdvertisingCandidateAction,
  ): Promise<void> {
    setBusy(true);
    setNotice(undefined);
    setPreview(undefined);
    const result = await actOnAdvertisingCandidate(
      context,
      caseId,
      candidate.id,
      candidate.recommendationId,
      action,
      candidate.version,
      reason.trim(),
    );
    setBusy(false);
    if (result.ok) {
      setNotice(`${LABELS[action]} recorded. Current authority reloaded.`);
      setReason('');
      setRevision((value) => value + 1);
    } else {
      setFailure(result.failure);
    }
  }

  async function review(candidate: AdvertisingWorkflowCandidate): Promise<void> {
    setBusy(true);
    const result = await previewAdvertisingCandidate(context, candidate.recommendationId);
    setBusy(false);
    if (result.ok) {
      setPreview(result.value);
      setFailure(undefined);
    } else {
      setFailure(result.failure);
    }
  }

  const liveTime = (key: string, legacy: string | undefined): string | undefined =>
    workflow?.slo === undefined
      ? legacy
      : typeof workflow.slo[key] === 'string'
        ? workflow.slo[key]
        : undefined;

  return (
    <section
      aria-label="Advertising workflow"
      data-state={workflow === undefined ? 'loading' : 'loaded'}
    >
      <h3>Action and responsibility</h3>
      {failure !== undefined && <AdvertisingProblem failure={failure} />}
      {notice !== undefined && <p role="status">{notice}</p>}
      {workflow === undefined ? (
        <p>Workflow authority is unavailable until the service answers.</p>
      ) : (
        <>
          <p>
            Disposition {workflow.operatingDisposition} · Task {workflow.taskId ?? 'unresolved'} ·{' '}
            {workflow.taskState} · accountable role {workflow.accountableRole}
          </p>
          <dl>
            <dt>First raised</dt>
            <dd>
              <AdvertisingTimestamp value={workflow.firstRaisedAt} timezone={timezone} />
            </dd>
            <dt>Acknowledge by</dt>
            <dd>
              <AdvertisingTimestamp
                value={liveTime('acknowledgementDueAt', workflow.acknowledgementDueAt)}
                timezone={timezone}
              />
            </dd>
            <dt>Act by</dt>
            <dd>
              <AdvertisingTimestamp
                value={liveTime('actionDueAt', workflow.actionDueAt)}
                timezone={timezone}
              />
            </dd>
            <dt>Escalate by</dt>
            <dd>
              <AdvertisingTimestamp
                value={liveTime('escalationDueAt', workflow.escalationDueAt)}
                timezone={timezone}
              />
            </dd>
            <dt>Staffing coverage</dt>
            <dd>{liveTime('coverageState', workflow.coverageState) ?? 'UNRESOLVED'}</dd>
            <dt>Next staffed response</dt>
            <dd>
              <AdvertisingTimestamp
                value={liveTime('nextStaffedResponseAt', workflow.nextStaffedResponseAt)}
                timezone={timezone}
              />
            </dd>
          </dl>
          <AdvertisingResponseTiming slo={workflow.slo} timezone={timezone} />
          <AdvertisingResponsibilityControls
            context={context}
            workflow={workflow}
            timezone={timezone}
            reload={() => {
              setRevision((value) => value + 1);
            }}
          />
          {workflow.allowedActions.length > 0 && (
            <label>
              Reason for this decision
              <textarea
                value={reason}
                maxLength={2000}
                onChange={(event) => {
                  setReason(event.target.value);
                }}
              />
            </label>
          )}
          {workflow.candidates.length === 0 ? (
            <p>
              No finite candidate is established. Missing evidence cannot become a target entered by
              hand.
            </p>
          ) : (
            <ul>
              {workflow.candidates.map((candidate) => (
                <li key={candidate.id} data-candidate-id={candidate.id}>
                  <h4>
                    Candidate {candidate.ordinal}: {candidate.basis}
                  </h4>
                  <p>
                    Exact native bid {candidate.currentBidAmount ?? 'unresolved'} →{' '}
                    {candidate.targetBidAmount ?? 'unresolved'} {candidate.currency ?? ''} (
                    {candidate.unit ?? 'unit unresolved'})
                  </p>
                  <p>
                    {candidate.state} · revision {candidate.version}
                  </p>
                  {candidate.commandId !== undefined && (
                    <AdvertisingCommandTimeline
                      context={context}
                      commandId={candidate.commandId}
                      timezone={timezone}
                    />
                  )}
                  {candidateActions(candidate, workflow.allowedActions).map((action) => (
                    <button
                      type="button"
                      key={action}
                      disabled={busy || (action !== 'CREATE_COMMAND' && reason.trim().length === 0)}
                      onClick={() => {
                        void act(candidate, action);
                      }}
                    >
                      {LABELS[action]}
                    </button>
                  ))}
                  {workflow.allowedActions.some(
                    (action) => action === 'ENDORSE' || action === 'APPROVE',
                  ) && (
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => {
                        void review(candidate);
                      }}
                    >
                      Review complete decision evidence
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {preview !== undefined && (
            <section aria-label="Advertising decision preview">
              <h4>Current guardrail verdict: {preview.verdict.passed ? 'PASS' : 'BLOCKED'}</h4>
              <p>
                Endorsement, approval and command creation each recheck current authority and scope.
              </p>
              <ul>
                {[
                  ...preview.verdict.reasons,
                  ...preview.gateReasons,
                  ...preview.unresolvedReasons,
                ].map((value, index) => (
                  <li key={`${String(index)}-${value}`}>{value}</li>
                ))}
              </ul>
              <AdvertisingEvidenceDetails value={preview} label="Complete decision evidence" />
            </section>
          )}
        </>
      )}
    </section>
  );
}
