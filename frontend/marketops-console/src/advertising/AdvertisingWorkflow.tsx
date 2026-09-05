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
          {workflow.slo !== undefined && (
            <p>
              Acknowledgement:{' '}
              {workflow.slo.acknowledgementBreached === true
                ? 'BREACHED'
                : workflow.slo.acknowledgementBreached === false
                  ? 'within current staffed clock'
                  : 'UNRESOLVED'}{' '}
              · Action:{' '}
              {workflow.slo.actionBreached === true
                ? 'BREACHED'
                : workflow.slo.actionBreached === false
                  ? 'within current staffed clock'
                  : 'UNRESOLVED'}{' '}
              ·
              {workflow.slo.actionPaused === true
                ? 'action clock paused; exposure age continues'
                : 'action clock active'}{' '}
              · Exposure age{' '}
              {typeof workflow.slo.wallClockExposureAgeSeconds === 'number'
                ? workflow.slo.wallClockExposureAgeSeconds
                : 'UNRESOLVED'}{' '}
              seconds
            </p>
          )}
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
