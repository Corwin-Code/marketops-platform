import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { decide } from '../api/console';
import type {
  Allowance,
  Candidate,
  DescriptionCommand,
  Evaluation,
  LaunchAnswer,
  ListingAction,
} from '../api/listingConversion';
import {
  cancelAction,
  fetchAction,
  fetchActions,
  fetchCandidates,
  fetchDescriptionCommand,
  fetchDescriptionGate,
  fetchEvaluation,
  launchAction,
  prepareAction,
  prepareCandidate,
  previewAllowance,
  reviewAction,
} from '../api/listingConversion';
import { Code, ListingProblem, When, YesNo } from './ListingCommon';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

export interface ListingActionsPanelProps {
  readonly context: ConsoleRequest;
  /** A listing the operator arrived from, to prepare a candidate on. */
  readonly listingId?: string;
}

const ACTION_STATES = [
  'DRAFT',
  'REVIEWED',
  'APPROVED',
  'APPROVED_NOT_LAUNCHABLE',
  'LAUNCHED',
  'VERIFIED',
  'CLOSED',
  'CANCELLED',
  'CONTAINED',
] as const;

/**
 * Candidates, exact actions, review, approval, allowance and launch.
 *
 * Every step is a separate button with its own answer from the backend, and
 * every refusal is shown by the backend's own reason. Nothing here decides;
 * the deterministic gates, the calibration, the independence rule and the
 * allowance are all applied on the server, and this screen shows what they
 * said.
 */
export function ListingActionsPanel({
  context,
  listingId,
}: ListingActionsPanelProps): React.JSX.Element {
  const { language } = useLanguage();
  const [filter, setFilter] = useState<string | undefined>(undefined);
  const [actions, setActions] = useState<readonly ListingAction[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [selected, setSelected] = useState<string | undefined>(undefined);
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    let active = true;
    void fetchActions(context, filter).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setActions(outcome.value);
        setFailure(undefined);
      } else {
        setActions(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, filter, generation]);

  if (selected !== undefined) {
    return (
      <ActionDetail
        context={context}
        actionId={selected}
        onBack={() => {
          setSelected(undefined);
          setGeneration((value) => value + 1);
        }}
      />
    );
  }

  return (
    <section
      aria-label={t('actions', language)}
      data-state={actions === undefined ? 'loading' : 'loaded'}
    >
      <h3>{t('actions', language)}</h3>
      {listingId !== undefined && (
        <CandidatePreparation
          context={context}
          listingId={listingId}
          onPrepared={(actionId) => {
            setSelected(actionId);
          }}
        />
      )}
      <fieldset>
        <legend>{t('state', language)}</legend>
        <button
          type="button"
          aria-pressed={filter === undefined}
          onClick={() => {
            setFilter(undefined);
          }}
        >
          —
        </button>
        {ACTION_STATES.map((state) => (
          <button
            key={state}
            type="button"
            aria-pressed={filter === state}
            onClick={() => {
              setFilter(state);
            }}
          >
            <Code family="actionState" code={state} />
          </button>
        ))}
      </fieldset>
      {failure !== undefined && <ListingProblem failure={failure} />}
      {actions === undefined && failure === undefined && <p>{t('loading', language)}</p>}
      {actions?.length === 0 && <p>{t('nothing', language)}</p>}
      {actions !== undefined && actions.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>{t('listing', language)}</th>
              <th>{t('state', language)}</th>
              <th>{t('path', language)}</th>
              <th>{t('materiality', language)}</th>
              <th>{t('bindingGaps', language)}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {actions.map((action) => (
              <tr key={action.id} data-action={action.id} data-action-state={action.state}>
                <td>{action.nativeListingKey}</td>
                <td>
                  <Code family="actionState" code={action.state} />
                </td>
                <td>
                  <Code family="executionPath" code={action.executionPath} />
                </td>
                <td>
                  <Code family="materialityRoute" code={action.materialityRoute} />
                </td>
                <td>
                  {action.bindingGaps.map((gap) => (
                    <span key={gap}>
                      <Code family="bindingGap" code={gap} />{' '}
                    </span>
                  ))}
                </td>
                <td>
                  <button
                    type="button"
                    onClick={() => {
                      setSelected(action.id);
                    }}
                  >
                    {t('open', language)}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

interface CandidatePreparationProps {
  readonly context: ConsoleRequest;
  readonly listingId: string;
  readonly onPrepared: (actionId: string) => void;
}

function CandidatePreparation({
  context,
  listingId,
  onPrepared,
}: CandidatePreparationProps): React.JSX.Element {
  const { language } = useLanguage();
  const [candidates, setCandidates] = useState<readonly Candidate[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [kind, setKind] = useState('CONTENT_DESCRIPTION');
  const [roundKey, setRoundKey] = useState('round-1');
  const [evidence, setEvidence] = useState('');
  const [path, setPath] = useState('API');
  const [targetText, setTargetText] = useState('');
  const [restoresCommandId, setRestoresCommandId] = useState('');
  const [kiz, setKiz] = useState<'undeclared' | 'yes' | 'no'>('undeclared');
  const [exposureShare, setExposureShare] = useState('');

  useEffect(() => {
    let active = true;
    void fetchCandidates(context, listingId).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setCandidates(outcome.value);
        setFailure(undefined);
      } else {
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, listingId, generation]);

  return (
    <section aria-label={t('candidates', language)} data-listing={listingId}>
      <h4>{t('candidates', language)}</h4>
      {failure !== undefined && <ListingProblem failure={failure} />}
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void prepareCandidate(
            context,
            listingId,
            kind,
            roundKey,
            evidence === '' ? [] : [evidence],
          ).then((outcome) => {
            if (outcome.ok) {
              setGeneration((value) => value + 1);
              setFailure(undefined);
            } else {
              setFailure(outcome.failure);
            }
          });
        }}
      >
        <label>
          <Code family="candidateKind" code={kind} />
          <select
            value={kind}
            onChange={(e) => {
              setKind(e.target.value);
            }}
          >
            {[
              'CONTENT_DESCRIPTION',
              'OFFICIAL_PROMOTION_PARTICIPATION',
              'SELLER_DIRECT_DISCOUNT',
            ].map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
        </label>
        <label>
          round{' '}
          <input
            value={roundKey}
            onChange={(e) => {
              setRoundKey(e.target.value);
            }}
          />
        </label>
        <label>
          {t('evidence', language)}{' '}
          <input
            value={evidence}
            onChange={(e) => {
              setEvidence(e.target.value);
            }}
          />
        </label>
        <button type="submit">{t('submit', language)}</button>
      </form>
      {candidates !== undefined && (
        <ul>
          {candidates.map((candidate) => (
            <li key={candidate.id} data-candidate={candidate.id}>
              <Code family="candidateKind" code={candidate.candidateKind} /> ·{' '}
              {candidate.comparisonRoundKey} ·{' '}
              <Code family="candidateState" code={candidate.state} />
              {candidate.state === 'OPEN' && (
                <form
                  aria-label={candidate.id}
                  onSubmit={(event) => {
                    event.preventDefault();
                    void prepareAction(
                      context,
                      candidate.id,
                      candidate.candidateKind === 'CONTENT_DESCRIPTION' ? path : 'MANUAL',
                      targetText,
                      kiz === 'undeclared' ? undefined : kiz === 'yes',
                      exposureShare,
                      restoresCommandId.trim() || undefined,
                    ).then((outcome) => {
                      if (outcome.ok) {
                        onPrepared(outcome.value.id);
                      } else {
                        setFailure(outcome.failure);
                      }
                    });
                  }}
                >
                  {candidate.candidateKind === 'CONTENT_DESCRIPTION' && (
                    <>
                      <label>
                        {t('path', language)}
                        <select
                          value={path}
                          onChange={(e) => {
                            setPath(e.target.value);
                          }}
                        >
                          <option value="API">API</option>
                          <option value="MANUAL">MANUAL</option>
                        </select>
                      </label>
                      <label>
                        {t('restoresCommandId', language)}
                        <input
                          value={restoresCommandId}
                          onChange={(e) => {
                            setRestoresCommandId(e.target.value);
                          }}
                          placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                          pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                        />
                      </label>
                      {restoresCommandId.trim() !== '' && (
                        <p>{t('restorationApproval', language)}</p>
                      )}
                      <label>
                        {t('targetText', language)}
                        <textarea
                          disabled={restoresCommandId.trim() !== ''}
                          value={targetText}
                          onChange={(e) => {
                            setTargetText(e.target.value);
                          }}
                        />
                      </label>
                      <label>
                        {t('kiz', language)}
                        <select
                          value={kiz}
                          onChange={(e) => {
                            setKiz(e.target.value as 'undeclared' | 'yes' | 'no');
                          }}
                        >
                          <option value="undeclared">{t('undeclared', language)}</option>
                          <option value="yes">{t('yes', language)}</option>
                          <option value="no">{t('no', language)}</option>
                        </select>
                      </label>
                    </>
                  )}
                  <label>
                    {t('materiality', language)}{' '}
                    <input
                      value={exposureShare}
                      onChange={(e) => {
                        setExposureShare(e.target.value);
                      }}
                      placeholder="0.10"
                    />
                  </label>
                  <button type="submit">{t('submit', language)}</button>
                </form>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

interface ActionDetailProps {
  readonly context: ConsoleRequest;
  readonly actionId: string;
  readonly onBack: () => void;
}

function ActionDetail({ context, actionId, onBack }: ActionDetailProps): React.JSX.Element {
  const { language } = useLanguage();
  const [action, setAction] = useState<ListingAction | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [reason, setReason] = useState('');
  const [allowance, setAllowance] = useState<Allowance | undefined>(undefined);
  const [answer, setAnswer] = useState<LaunchAnswer | undefined>(undefined);
  const [evaluation, setEvaluation] = useState<Evaluation | undefined>(undefined);
  const [command, setCommand] = useState<DescriptionCommand | undefined>(undefined);
  const [gate, setGate] = useState<readonly string[] | undefined>(undefined);
  const [message, setMessage] = useState<string | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchAction(context, actionId).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setAction(outcome.value);
        setFailure(undefined);
      } else {
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, actionId, generation]);

  const refresh = (): void => {
    setGeneration((value) => value + 1);
  };
  const settle = (outcome: { readonly ok: boolean; readonly failure?: ConsoleFailure }): void => {
    if (outcome.ok) {
      setMessage(t('done', language));
      setFailure(undefined);
      refresh();
    } else if (outcome.failure !== undefined) {
      setFailure(outcome.failure);
    }
  };

  return (
    <section aria-label={t('actions', language)} data-action={actionId}>
      <button type="button" onClick={onBack}>
        ← {t('actions', language)}
      </button>
      {failure !== undefined && <ListingProblem failure={failure} />}
      {message !== undefined && <p role="status">{message}</p>}
      {action === undefined && failure === undefined && <p>{t('loading', language)}</p>}
      {action !== undefined && (
        <>
          <h3>
            {action.nativeListingKey} · <Code family="actionState" code={action.state} /> ·{' '}
            <Code family="executionPath" code={action.executionPath} /> ·{' '}
            <Code family="materialityRoute" code={action.materialityRoute} />
          </h3>
          <dl>
            <dt>{t('author', language)}</dt>
            <dd>{action.authorUserId}</dd>
            <dt>{t('reviewer', language)}</dt>
            <dd>
              {action.reviews.map((review) => (
                <span key={review.id}>
                  {review.reviewerUserId} <Code family="reviewVerdict" code={review.verdict} />{' '}
                </span>
              ))}
            </dd>
            <dt>{t('affectedSet', language)}</dt>
            <dd>
              {action.affectedSetDigest} ({action.affectedVariantCount})
            </dd>
            <dt>{t('currentDigest', language)}</dt>
            <dd>{action.currentTextDigest ?? '—'}</dd>
            <dt>{t('targetDigest', language)}</dt>
            <dd>{action.targetTextDigest ?? '—'}</dd>
            <dt>{t('kiz', language)}</dt>
            <dd>
              <YesNo value={action.kizMarkedDeclared} />
            </dd>
            <dt>{t('version', language)}</dt>
            <dd>{action.version}</dd>
          </dl>
          {action.restoresCommandId !== undefined && (
            <p>
              {t('restoresCommandId', language)}: <code>{action.restoresCommandId}</code> ·{' '}
              {t('restorationApproval', language)}
            </p>
          )}
          {action.targetText !== undefined && (
            <details>
              <summary>{t('targetText', language)}</summary>
              <pre lang="ru">{action.targetText}</pre>
            </details>
          )}
          {action.bindingGaps.length > 0 && (
            <p>
              {t('bindingGaps', language)}:{' '}
              {action.bindingGaps.map((gap) => (
                <span key={gap}>
                  <Code family="bindingGap" code={gap} />{' '}
                </span>
              ))}
            </p>
          )}
          <label>
            {t('reason', language)}{' '}
            <input
              value={reason}
              onChange={(e) => {
                setReason(e.target.value);
              }}
            />
          </label>
          {action.state === 'DRAFT' && (
            <>
              <button
                type="button"
                onClick={() => {
                  void reviewAction(context, actionId, 'ATTESTED', reason).then(settle);
                }}
              >
                {t('attest', language)}
              </button>
              <button
                type="button"
                onClick={() => {
                  void reviewAction(context, actionId, 'RETURNED', reason).then(settle);
                }}
              >
                {t('returnToAuthor', language)}
              </button>
            </>
          )}
          {action.state === 'REVIEWED' && (
            <>
              <button
                type="button"
                onClick={() => {
                  void decide(
                    context,
                    action.recommendationId,
                    'approval',
                    reason,
                    action.recommendationVersion,
                  ).then(settle);
                }}
              >
                {t('approve', language)}
              </button>
              <button
                type="button"
                onClick={() => {
                  void decide(
                    context,
                    action.recommendationId,
                    'rejection',
                    reason,
                    action.recommendationVersion,
                  ).then(settle);
                }}
              >
                {t('reject', language)}
              </button>
            </>
          )}
          {(action.state === 'APPROVED' || action.state === 'APPROVED_NOT_LAUNCHABLE') && (
            <>
              <button
                type="button"
                onClick={() => {
                  void previewAllowance(context, actionId).then((outcome) => {
                    if (outcome.ok) setAllowance(outcome.value);
                    else setFailure(outcome.failure);
                  });
                }}
              >
                {t('allowancePreview', language)}
              </button>
              <button
                type="button"
                onClick={() => {
                  void launchAction(context, actionId).then((outcome) => {
                    if (outcome.ok) {
                      setAnswer(outcome.value);
                      refresh();
                    } else setFailure(outcome.failure);
                  });
                }}
              >
                {t('launch', language)}
              </button>
            </>
          )}
          {['DRAFT', 'REVIEWED', 'APPROVED', 'APPROVED_NOT_LAUNCHABLE'].includes(action.state) && (
            <button
              type="button"
              onClick={() => {
                void cancelAction(context, actionId, reason).then(settle);
              }}
            >
              {t('cancel', language)}
            </button>
          )}
          {allowance !== undefined && <AllowanceTable allowance={allowance} />}
          {answer !== undefined && (
            <p role="status" data-launched={String(answer.launched)}>
              {answer.launched
                ? t('launched', language)
                : `${t('notLaunched', language)}: ${answer.insufficientAxes.join(', ')}`}
            </p>
          )}
          {action.occupations.length > 0 && (
            <ul>
              {action.occupations.map((occupation) => (
                <li key={occupation.id}>
                  <Code family="allowanceAxis" code={occupation.axisCode} />{' '}
                  {occupation.occupiedValue}{' '}
                  <Code family="occupationState" code={occupation.state} />
                </li>
              ))}
            </ul>
          )}
          {action.launch !== undefined && (
            <p>
              {t('launched', language)} <When value={action.launch.launchedAt} />
            </p>
          )}
          <button
            type="button"
            onClick={() => {
              void fetchEvaluation(context, actionId).then((outcome) => {
                if (outcome.ok) setEvaluation(outcome.value);
                else setFailure(outcome.failure);
              });
            }}
          >
            {t('evaluation', language)}
          </button>
          {evaluation !== undefined && <EvaluationTable evaluation={evaluation} />}
          {action.executionPath === 'API' && action.launch !== undefined && (
            <button
              type="button"
              onClick={() => {
                void fetchDescriptionCommand(context, actionId).then((outcome) => {
                  if (outcome.ok) {
                    setCommand(outcome.value);
                    void fetchDescriptionGate(context, outcome.value.id).then((gateOutcome) => {
                      if (gateOutcome.ok) setGate(gateOutcome.value);
                      else setFailure(gateOutcome.failure);
                    });
                  } else setFailure(outcome.failure);
                });
              }}
            >
              {t('descriptionCommand', language)}
            </button>
          )}
          {command !== undefined && <CommandTimeline command={command} gate={gate} />}
        </>
      )}
    </section>
  );
}

function AllowanceTable({ allowance }: { readonly allowance: Allowance }): React.JSX.Element {
  const { language } = useLanguage();
  return (
    <table aria-label={t('allowancePreview', language)} data-resolved={String(allowance.resolved)}>
      <thead>
        <tr>
          <th>{t('axis', language)}</th>
          <th>{t('limit', language)}</th>
          <th>{t('reserve', language)}</th>
          <th>{t('occupied', language)}</th>
          <th>{t('headroom', language)}</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {allowance.axes.map((axis) => (
          <tr key={axis.axisCode} data-sufficient={String(axis.sufficient)}>
            <td>
              <Code family="allowanceAxis" code={axis.axisCode} />
            </td>
            <td>{axis.limitValue}</td>
            <td>{axis.reserveValue}</td>
            <td>{axis.occupiedValue}</td>
            <td>{axis.headroom}</td>
            <td>{axis.sufficient ? t('sufficient', language) : t('insufficient', language)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function EvaluationTable({ evaluation }: { readonly evaluation: Evaluation }): React.JSX.Element {
  const { language } = useLanguage();
  return (
    <div data-plan={evaluation.planId}>
      <h4>{t('nodes', language)}</h4>
      <ul>
        {evaluation.formalNodes.map((node) => (
          <li key={node.nodeCode}>
            {node.nodeCode} · {node.maturityDays} · {node.method} · {node.threshold ?? '—'}
          </li>
        ))}
      </ul>
      {evaluation.results.length === 0 ? (
        <p>{t('nothing', language)}</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>{t('nodes', language)}</th>
              <th>{t('ratio', language)}</th>
              <th>{t('protection', language)}</th>
            </tr>
          </thead>
          <tbody>
            {evaluation.results.map((result) => (
              <tr key={result.id} data-verdict={result.verdict}>
                <td>
                  {result.nodeCode} <Code family="evaluationStage" code={result.stage} /> #
                  {result.revisionNo} <Code family="nodeVerdict" code={result.verdict} />
                </td>
                <td>
                  {result.primaryRatio ?? '—'} / {result.conservativeBound ?? '—'} /{' '}
                  {result.acceptedThreshold ?? '—'}
                </td>
                <td>
                  <Code family="protectionVerdict" code={result.protectionVerdict} />{' '}
                  {Object.entries(result.protectionVector).map(([key, value]) => (
                    <span key={key}>
                      <Code family="protection" code={key} />=
                      <Code family="protectionVerdict" code={value} />{' '}
                    </span>
                  ))}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function CommandTimeline({
  command,
  gate,
}: {
  readonly command: DescriptionCommand;
  readonly gate: readonly string[] | undefined;
}): React.JSX.Element {
  const { language } = useLanguage();
  return (
    <section
      aria-label={t('descriptionCommand', language)}
      data-command={command.id}
      data-command-state={command.state}
    >
      <h4>
        {t('descriptionCommand', language)}: <Code family="commandState" code={command.state} />
      </h4>
      <p>
        {t('attempts', language)}: {command.attemptNo} · {command.retryBudgetRemaining} ·{' '}
        <Code
          family="errorCode"
          code={command.priorTextCaptured ? undefined : 'RESTORE_UNSUPPORTED'}
        />
      </p>
      <h5>{t('executionObservation', language)}</h5>
      <p>{t('executionBoundary', language)}</p>
      {command.executionReceipts.length === 0 && <p>{t('noExecutionObservation', language)}</p>}
      <ul>
        {command.executionReceipts.map((receipt) => (
          <li key={receipt.id} data-execution-state={receipt.executionState}>
            <Code family="descriptionExecutionState" code={receipt.executionState} />{' '}
            <When value={receipt.recordedAt} />
            <p>
              {receipt.taskEventId === undefined
                ? t('taskDeliveryPending', language)
                : t('taskDeliveryRecorded', language)}
            </p>
            <ul>
              {receipt.gaps.map((gap) => (
                <li key={gap}>
                  <Code family="descriptionExecutionGap" code={gap} />
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
      <h5>{t('gate', language)}</h5>
      {gate === undefined ? (
        <p>{t('loading', language)}</p>
      ) : gate.length === 0 ? (
        <p>{t('gateOpen', language)}</p>
      ) : (
        <ul>
          {gate.map((reason) => (
            <li key={reason}>
              <Code family="gateReason" code={reason} />
            </li>
          ))}
        </ul>
      )}
      <ul>
        {command.attempts.map((attempt) => (
          <li key={attempt.id}>
            #{attempt.attemptNo} {attempt.purpose}{' '}
            <Code family="attemptOutcome" code={attempt.outcomeClass} /> {attempt.errorCode ?? ''}
          </li>
        ))}
      </ul>
      <ul>
        {command.readbacks.map((readback) => (
          <li key={readback.id}>
            <Code family="readbackMatch" code={readback.matchState} />{' '}
            <When value={readback.observedAt} />
          </li>
        ))}
      </ul>
    </section>
  );
}
