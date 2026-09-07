import { AdvertisingEvidenceDetails } from './AdvertisingEvidenceDetails';
import { useEffect, useState } from 'react';
import {
  actOnAdvertisingTask,
  advertisingControl,
  fetchAdvertisingJournal,
  fetchAdvertisingExceptions,
  fetchAdvertisingExceptionEvidence,
} from '../api/console';
import type { ConsoleFailure, ConsoleOutcome, ConsoleRequest } from '../api/console';
import type { AdvertisingWorkflow } from '../api/advertising';
import { AdvertisingProblem } from './AdvertisingQueue';
import { AdvertisingTimestamp } from './AdvertisingTimestamp';

type Row = Readonly<Record<string, unknown>>;
const text = (value: unknown): string =>
  typeof value === 'string' ? value : typeof value === 'number' ? value.toString() : 'UNRESOLVED';
const actions = (row: Row): readonly string[] =>
  Array.isArray(row.allowedActions)
    ? row.allowedActions.filter((item): item is string => typeof item === 'string')
    : [];

export function AdvertisingResponsibilityControls({
  context,
  workflow,
  timezone,
  reload,
}: {
  readonly context: ConsoleRequest;
  readonly workflow: AdvertisingWorkflow;
  readonly timezone: string | undefined;
  readonly reload: () => void;
}): React.JSX.Element {
  const [journal, setJournal] = useState<readonly Row[]>();
  const [exceptions, setExceptions] = useState<readonly Row[]>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [reason, setReason] = useState('');
  const [evidence, setEvidence] = useState('');
  const [assignee, setAssignee] = useState('');
  const [expires, setExpires] = useState('');
  const [reviewDue, setReviewDue] = useState('');
  const [busy, setBusy] = useState(false);
  const [review, setReview] = useState<Row>();
  const allowed = workflow.allowedActions;
  useEffect(() => {
    let active = true;
    void fetchAdvertisingExceptions(context, workflow.caseId).then((result) => {
      if (active && result.ok) setExceptions(result.value);
    });
    return () => {
      active = false;
    };
  }, [context, workflow]);
  async function perform(operation: Promise<ConsoleOutcome<Row>>): Promise<void> {
    setBusy(true);
    setFailure(undefined);
    const result = await operation;
    setBusy(false);
    if (result.ok) {
      setReason('');
      setReview(undefined);
      reload();
    } else setFailure(result.failure);
  }
  const task =
    workflow.taskId === undefined ? undefined : `tasks/${encodeURIComponent(workflow.taskId)}`;
  return (
    <section aria-label="Responsibility and exceptions">
      {failure !== undefined && <AdvertisingProblem failure={failure} />}
      {(allowed.some((action) => action.startsWith('TASK_') || action === 'EXCEPTION_REQUEST') ||
        exceptions?.some((row) => actions(row).length > 0) === true) && (
        <label>
          Action reason
          <textarea
            value={reason}
            maxLength={2000}
            onChange={(event) => {
              setReason(event.target.value);
            }}
          />
        </label>
      )}
      {task !== undefined && (
        <>
          {allowed.includes('TASK_ACKNOWLEDGE') && (
            <button
              type="button"
              disabled={busy}
              onClick={() => {
                void perform(advertisingControl(context, `${task}/acknowledgement`));
              }}
            >
              Acknowledge responsibility
            </button>
          )}
          {allowed.includes('TASK_ASSIGN') && (
            <>
              <label>
                Eligible assignee user ID
                <input
                  value={assignee}
                  onChange={(event) => {
                    setAssignee(event.target.value);
                  }}
                />
              </label>
              <button
                type="button"
                disabled={
                  busy || assignee.trim().length === 0 || workflow.taskVersion === undefined
                }
                onClick={() => {
                  if (workflow.taskId !== undefined)
                    void perform(
                      actOnAdvertisingTask(context, workflow.taskId, 'assignment', {
                        assigneeUserId: assignee,
                        expectedVersion: workflow.taskVersion,
                      }),
                    );
                }}
              >
                Assign responsible person
              </button>
            </>
          )}
          {allowed.includes('TASK_START') && (
            <button
              type="button"
              disabled={busy || workflow.taskVersion === undefined}
              onClick={() => {
                if (workflow.taskId !== undefined)
                  void perform(
                    actOnAdvertisingTask(context, workflow.taskId, 'start', {
                      expectedVersion: workflow.taskVersion,
                    }),
                  );
              }}
            >
              Start accountable work
            </button>
          )}
          {allowed.includes('TASK_ACTION') && (
            <>
              <label>
                Canonical data or mapping repair evidence ID
                <input
                  value={evidence}
                  onChange={(event) => {
                    setEvidence(event.target.value);
                  }}
                />
              </label>
              <button
                type="button"
                disabled={busy || reason.trim().length === 0 || evidence.trim().length === 0}
                onClick={() => {
                  void perform(
                    advertisingControl(context, `${task}/action`, {
                      actionKind: 'DATA_OR_MAPPING_REPAIR',
                      evidenceReference: evidence,
                      reason,
                    }),
                  );
                }}
              >
                Record completed data or mapping repair
              </button>
            </>
          )}
          {allowed.includes('TASK_REOPEN') && (
            <button
              type="button"
              disabled={busy || reason.trim().length === 0}
              onClick={() => {
                void perform(
                  advertisingControl(context, `${task}/reopen`, { escalated: false, reason }),
                );
              }}
            >
              Reopen accountable work
            </button>
          )}
          <button
            type="button"
            disabled={busy}
            onClick={() => {
              if (workflow.taskId !== undefined)
                void fetchAdvertisingJournal(context, workflow.taskId).then((result) => {
                  if (result.ok) setJournal(result.value);
                  else setFailure(result.failure);
                });
            }}
          >
            Read attributable journal
          </button>
          {journal !== undefined && (
            <ol aria-label="Attributable journal">
              {journal.map((row, index) => (
                <li key={text(row.id) + index.toString()}>
                  {text(row.eventKind)} · actor {text(row.actorUserId)} · role{' '}
                  {text(row.actorRoleCode)} · {text(row.disclosureState)}
                  <AdvertisingTimestamp
                    value={typeof row.occurredAt === 'string' ? row.occurredAt : undefined}
                    timezone={timezone}
                  />
                  {typeof row.reason === 'string' && <p>{row.reason}</p>}
                </li>
              ))}
            </ol>
          )}
        </>
      )}
      <h4>Time bounded risk acceptance</h4>
      {allowed.includes('EXCEPTION_REQUEST') && (
        <>
          <label>
            Exception expiry (ISO 8601 with UTC offset)
            <input
              value={expires}
              placeholder="2026-09-05T12:00:00Z"
              onChange={(event) => {
                setExpires(event.target.value);
              }}
            />
          </label>
          <label>
            Required review time (ISO 8601 with UTC offset)
            <input
              value={reviewDue}
              onChange={(event) => {
                setReviewDue(event.target.value);
              }}
            />
          </label>
          <label>
            Exception evidence reference
            <input
              value={evidence}
              onChange={(event) => {
                setEvidence(event.target.value);
              }}
            />
          </label>
          <button
            type="button"
            disabled={
              busy || !reason.trim() || !evidence.trim() || !expires.trim() || !reviewDue.trim()
            }
            onClick={() => {
              void perform(
                advertisingControl(
                  context,
                  `cases/${encodeURIComponent(workflow.caseId)}/exceptions`,
                  {
                    expiresAt: expires,
                    reviewDueAt: reviewDue,
                    reason,
                    evidenceReference: evidence,
                  },
                ),
              );
            }}
          >
            Request exact case exception
          </button>
        </>
      )}
      <ul>
        {exceptions?.map((row) => (
          <li key={text(row.id)}>
            <p>
              {text(row.state)} · revision {text(row.version)}
            </p>
            <AdvertisingTimestamp
              value={typeof row.expiresAt === 'string' ? row.expiresAt : undefined}
              timezone={timezone}
            />
            <button
              type="button"
              onClick={() => {
                void fetchAdvertisingExceptionEvidence(context, text(row.id)).then((result) => {
                  if (result.ok) setReview(result.value);
                  else setFailure(result.failure);
                });
              }}
            >
              Review frozen exception evidence
            </button>
            {(['ENDORSE', 'APPROVE', 'END'] as const)
              .filter((action) => actions(row).includes(action))
              .map((action) => (
                <button
                  type="button"
                  key={action}
                  disabled={busy || !reason.trim()}
                  onClick={() => {
                    const route = { ENDORSE: 'endorsement', APPROVE: 'approval', END: 'end' }[
                      action
                    ];
                    void perform(
                      advertisingControl(
                        context,
                        `exceptions/${encodeURIComponent(text(row.id))}/${route}`,
                        { expectedVersion: row.version, reason },
                      ),
                    );
                  }}
                >
                  {action === 'END' ? 'End exception and rebuild decisions' : `${action} exception`}
                </button>
              ))}
          </li>
        ))}
      </ul>
      {review !== undefined && (
        <AdvertisingEvidenceDetails value={review} label="Frozen exception evidence" />
      )}
    </section>
  );
}
