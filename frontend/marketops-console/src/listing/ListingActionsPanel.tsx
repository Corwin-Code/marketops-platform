import { useCallback, useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { decide } from '../api/console';
import type {
  Allowance,
  Candidate,
  DescriptionCommand,
  Evaluation,
  LaunchAnswer,
  ListingAction,
  ListingActionPurpose,
  MeaningReviewBasis,
} from '../api/listingConversion';
import {
  cancelAction,
  fetchAction,
  fetchActions,
  fetchCandidates,
  fetchDescriptionCommand,
  fetchDescriptionGate,
  fetchEvaluation,
  evaluateNode,
  launchAction,
  prepareAction,
  prepareCandidate,
  previewAllowance,
  reviewAction,
} from '../api/listingConversion';
import { Code, ListingProblem, When, YesNo } from './ListingCommon';
import { ListingPurposeBasisDetails } from './ListingPurposeBasisDetails';
import { ListingMeaningReview } from './ListingMeaningReview';
import { ListingResponsibility } from './ListingResponsibility';
import { PromotionDeclaration, PromotionPreparationForm } from './ListingPromotionTerms';
import { useLanguage } from './i18n/language';
import { t, type UiKey } from './i18n/ui';

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
  const [purpose, setPurpose] = useState<ListingActionPurpose>('LISTING_CONVERSION');
  const [purposeReference, setPurposeReference] = useState('');
  const [purposeUseConditions, setPurposeUseConditions] = useState('');
  const [purposeEndConditions, setPurposeEndConditions] = useState('');
  const [purposeUseUntil, setPurposeUseUntil] = useState('');
  const [targetText, setTargetText] = useState('');
  const [restoresCommandId, setRestoresCommandId] = useState('');
  const [kiz, setKiz] = useState<'undeclared' | 'yes' | 'no'>('undeclared');

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
              {candidate.state === 'OPEN' && candidate.candidateKind !== 'CONTENT_DESCRIPTION' && (
                <PromotionPreparationForm
                  context={context}
                  candidateId={candidate.id}
                  kind={candidate.candidateKind}
                  onPrepared={onPrepared}
                />
              )}
              {candidate.state === 'OPEN' && candidate.candidateKind === 'CONTENT_DESCRIPTION' && (
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
                      restoresCommandId.trim() || undefined,
                      undefined,
                      purpose,
                      purpose === 'LISTING_CONVERSION'
                        ? undefined
                        : {
                            evidenceReference: purposeReference,
                            useConditions: purposeUseConditions
                              .split('\n')
                              .filter((value) => value.trim() !== ''),
                            endConditions: purposeEndConditions
                              .split('\n')
                              .filter((value) => value.trim() !== ''),
                            useUntil: purposeUseUntil.trim() || undefined,
                          },
                    ).then((outcome) => {
                      if (outcome.ok) {
                        onPrepared(outcome.value.id);
                      } else {
                        setFailure(outcome.failure);
                      }
                    });
                  }}
                >
                  <>
                    <label>
                      {t('actionPurpose', language)}
                      <select
                        value={purpose}
                        onChange={(event) => {
                          const selected = event.target.value as ListingActionPurpose;
                          setPurpose(selected);
                          if (selected === 'BOUNDED_EXPLORATION') setPath('MANUAL');
                        }}
                      >
                        <option value="LISTING_CONVERSION">
                          {t('purposeImprovement', language)}
                        </option>
                        <option value="DESCRIPTION_CORRECTION">
                          {t('purposeCorrection', language)}
                        </option>
                        <option value="BOUNDED_EXPLORATION">
                          {t('purposeExploration', language)}
                        </option>
                      </select>
                    </label>
                    <p>{t('purposeHelp', language)}</p>
                    {purpose !== 'LISTING_CONVERSION' && (
                      <fieldset>
                        <legend>{t('purposeBasis', language)}</legend>
                        <label>
                          {t('purposeEvidence', language)}
                          <input
                            required
                            maxLength={512}
                            value={purposeReference}
                            onChange={(event) => {
                              setPurposeReference(event.target.value);
                            }}
                          />
                        </label>
                        <label>
                          {t('purposeUseConditions', language)}
                          <textarea
                            required
                            value={purposeUseConditions}
                            onChange={(event) => {
                              setPurposeUseConditions(event.target.value);
                            }}
                          />
                        </label>
                        <label>
                          {t('purposeEndConditions', language)}
                          <textarea
                            required
                            value={purposeEndConditions}
                            onChange={(event) => {
                              setPurposeEndConditions(event.target.value);
                            }}
                          />
                        </label>
                        <label>
                          {t('purposeUseUntil', language)}
                          <input
                            required={purpose === 'BOUNDED_EXPLORATION'}
                            value={purposeUseUntil}
                            placeholder="2026-12-01T00:00:00Z"
                            onChange={(event) => {
                              setPurposeUseUntil(event.target.value);
                            }}
                          />
                        </label>
                      </fieldset>
                    )}

                    <label>
                      {t('path', language)}
                      <select
                        value={path}
                        onChange={(e) => {
                          setPath(e.target.value);
                        }}
                      >
                        <option value="API" disabled={purpose === 'BOUNDED_EXPLORATION'}>
                          API
                        </option>
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
                    {restoresCommandId.trim() !== '' && <p>{t('restorationApproval', language)}</p>}
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
                  <p>{t('exposureEvidence', language)}</p>
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
  const [approvalMaterial, setApprovalMaterial] = useState<MeaningReviewBasis | undefined>(
    undefined,
  );
  const receiveApprovalMaterial = useCallback((basis: MeaningReviewBasis | undefined): void => {
    setApprovalMaterial(basis);
  }, []);

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
    setApprovalMaterial(undefined);
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
          <p>
            {t('actionPurpose', language)}:{' '}
            {action.purposeCode === undefined
              ? '—'
              : action.purposeCode === 'LISTING_CONVERSION'
                ? t('purposeImprovement', language)
                : action.purposeCode === 'DESCRIPTION_CORRECTION'
                  ? t('purposeCorrection', language)
                  : action.purposeCode === 'BOUNDED_EXPLORATION'
                    ? t('purposeExploration', language)
                    : action.purposeCode === 'PROMOTION'
                      ? t('purposePromotion', language)
                      : action.purposeCode}
          </p>
          {action.purposeBasis !== undefined && (
            <ListingPurposeBasisDetails basis={action.purposeBasis} />
          )}
          <ListingResponsibility context={context} actionId={actionId} />
          {action.actionKind === 'LISTING_PROMOTION_ACTION' && (
            <PromotionDeclaration
              key={actionId}
              context={context}
              actionId={actionId}
              digest={action.promotionTermsDigest}
            />
          )}
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
            <dt>{language === 'ru' ? 'Идентификатор карточки' : '平台 Listing ID'}</dt>
            <dd>{action.platformListingId}</dd>
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
            <dt>{language === 'ru' ? 'Ось содержания' : '内容材料性轴'}</dt>
            <dd>
              {action.contentAxisMaterial === undefined ? '—' : String(action.contentAxisMaterial)}
            </dd>
            <dt>{language === 'ru' ? 'Ось воздействия' : '暴露材料性轴'}</dt>
            <dd>
              {action.exposureAxisMaterial === undefined
                ? '—'
                : String(action.exposureAxisMaterial)}
            </dd>
            <dt>{language === 'ru' ? 'Версия калибровки' : '校准版本'}</dt>
            <dd>
              {action.calibrationPackageId ?? '—'} · {action.calibrationVersion ?? '—'}
            </dd>
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
              <ListingMeaningReview
                context={context}
                actionId={actionId}
                reason={reason}
                onOutcome={settle}
              />
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
              <ListingMeaningReview
                context={context}
                actionId={actionId}
                reason={reason}
                onOutcome={settle}
                approvalMode
                onMaterialLoaded={receiveApprovalMaterial}
              />
              <button
                type="button"
                disabled={
                  approvalMaterial === undefined ||
                  approvalMaterial.reviewEvidence?.verdict !== 'ATTESTED' ||
                  approvalMaterial.materialityEvidence.state !== 'CURRENT' ||
                  reason.trim() === ''
                }
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
          {action.purposeCode !== 'DESCRIPTION_CORRECTION' &&
            action.purposeCode !== 'BOUNDED_EXPLORATION' && (
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
            )}
          {evaluation !== undefined && (
            <>
              {action.launch !== undefined && (
                <NodeEvaluationForm
                  context={context}
                  actionId={actionId}
                  evaluation={evaluation}
                  onEvaluated={setEvaluation}
                  onFailure={setFailure}
                />
              )}
              <EvaluationTable evaluation={evaluation} />
            </>
          )}
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
    <div>
      {!allowance.resolved && <p role="status">{t('allowanceUnresolved', language)}</p>}
      {allowance.gaps.length > 0 && (
        <ul>
          {allowance.gaps.map((gap) => {
            const parts = gap.split(':');
            const reason = parts.length > 1 ? parts[1] : parts[0];
            const labels: Readonly<Record<string, UiKey>> = {
              ALLOWANCE_POLICY_UNRESOLVED: 'allowancePolicyGap',
              ALLOWANCE_AXES_UNRESOLVED: 'allowanceAxesGap',
              ALLOWANCE_MISSING: 'allowanceMissingGap',
              SCOPE_COMPOSITION_UNRESOLVED: 'allowanceCompositionGap',
              CANONICAL_DEMAND_UNRESOLVED: 'allowanceDemandGap',
              RESERVE_UNRESOLVED: 'allowanceReserveGap',
              RESERVE_BELOW_ACCEPTED_POLICY: 'allowanceReserveGap',
            };
            return (
              <li key={gap}>
                {parts.length > 1 && (
                  <>
                    <Code family="allowanceAxis" code={parts[0] ?? ''} />:{' '}
                  </>
                )}
                {t(labels[reason ?? ''] ?? 'allowanceUnresolved', language)}
              </li>
            );
          })}
        </ul>
      )}
      <table
        aria-label={t('allowancePreview', language)}
        data-resolved={String(allowance.resolved)}
      >
        <thead>
          <tr>
            <th>{t('axis', language)}</th>
            <th>{t('allowanceScope', language)}</th>
            <th>{t('limit', language)}</th>
            <th>{t('reserve', language)}</th>
            <th>{t('occupied', language)}</th>
            <th>{t('headroom', language)}</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {allowance.axes.map((axis) => (
            <tr
              key={`${axis.axisCode}:${axis.scopeKind}`}
              data-sufficient={String(axis.sufficient)}
            >
              <td>
                <Code family="allowanceAxis" code={axis.axisCode} />
              </td>
              <td>
                {t(
                  axis.scopeKind === 'ORGANIZATION'
                    ? 'allowanceOrganization'
                    : axis.scopeKind === 'PLATFORM'
                      ? 'allowancePlatform'
                      : 'allowanceStore',
                  language,
                )}
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
    </div>
  );
}

function NodeEvaluationForm({
  context,
  actionId,
  evaluation,
  onEvaluated,
  onFailure,
}: {
  readonly context: ConsoleRequest;
  readonly actionId: string;
  readonly evaluation: Evaluation;
  readonly onEvaluated: (value: Evaluation) => void;
  readonly onFailure: (failure: ConsoleFailure | undefined) => void;
}): React.JSX.Element {
  const { language } = useLanguage();
  const [nodeCode, setNodeCode] = useState(evaluation.formalNodes[0]?.nodeCode ?? '');
  const [stage, setStage] = useState<'OPERATIONAL' | 'SETTLED'>('OPERATIONAL');
  const [measurementId, setMeasurementId] = useState('');
  const [lateFactReference, setLateFactReference] = useState('');
  return (
    <form
      aria-label={t('evaluationRecord', language)}
      onSubmit={(event) => {
        event.preventDefault();
        void evaluateNode(context, actionId, {
          nodeCode,
          stage,
          ...(measurementId.trim() === '' ? {} : { measurementId: measurementId.trim() }),
          ...(lateFactReference.trim() === ''
            ? {}
            : { lateFactReference: lateFactReference.trim() }),
        }).then((outcome) => {
          if (outcome.ok) {
            onEvaluated(outcome.value);
            onFailure(undefined);
          } else onFailure(outcome.failure);
        });
      }}
    >
      <label>
        {t('nodes', language)}{' '}
        <select
          required
          value={nodeCode}
          onChange={(event) => {
            setNodeCode(event.target.value);
          }}
        >
          {evaluation.formalNodes.map((node) => (
            <option key={node.nodeCode} value={node.nodeCode}>
              {node.nodeCode}
            </option>
          ))}
        </select>
      </label>
      <label>
        {t('evaluationStage', language)}{' '}
        <select
          value={stage}
          onChange={(event) => {
            setStage(event.target.value as 'OPERATIONAL' | 'SETTLED');
          }}
        >
          <option value="OPERATIONAL">OPERATIONAL</option>
          <option value="SETTLED">SETTLED</option>
        </select>
      </label>
      <label>
        {t('measurementId', language)}{' '}
        <input
          value={measurementId}
          onChange={(event) => {
            setMeasurementId(event.target.value);
          }}
        />
      </label>
      <label>
        {t('lateFactReference', language)}{' '}
        <input
          value={lateFactReference}
          onChange={(event) => {
            setLateFactReference(event.target.value);
          }}
        />
      </label>
      <button type="submit" disabled={nodeCode === ''}>
        {t('evaluationRecord', language)}
      </button>
    </form>
  );
}

function EvaluationTable({ evaluation }: { readonly evaluation: Evaluation }): React.JSX.Element {
  const { language } = useLanguage();
  return (
    <div data-plan={evaluation.planId}>
      <p>
        {t('evaluationFrozenAt', language)}: <When value={evaluation.frozenAt} />
      </p>
      <p>
        {t('evaluationBoundary', language)}: <When value={evaluation.latestBoundary} />
      </p>
      <p>
        {t('evaluationPlanDigest', language)}: <code>{evaluation.planDigest ?? '—'}</code>
      </p>
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
              <tr
                key={result.id}
                id={`evaluation-result-${result.id}`}
                data-verdict={result.verdict}
              >
                <td>
                  {result.nodeCode} <Code family="evaluationStage" code={result.stage} /> #
                  {result.revisionNo} <Code family="nodeVerdict" code={result.verdict} />
                </td>
                <td>
                  {result.primaryRatio ?? '—'} / {result.conservativeBound ?? '—'} /{' '}
                  {result.acceptedThreshold ?? '—'}
                  {result.fixedTrafficObservation !== undefined && (
                    <p>
                      {t('observedComparison', language)}:{' '}
                      {result.fixedTrafficObservation.referenceStandardized ?? '—'} /{' '}
                      {result.fixedTrafficObservation.targetStandardized ?? '—'} /{' '}
                      {result.fixedTrafficObservation.observedDifference ?? '—'}
                      <br />
                      {t('observationOnly', language)}
                    </p>
                  )}
                  {(result.qualificationGaps?.length ?? 0) > 0 && (
                    <p>
                      {t('qualification', language)}: {result.qualificationGaps?.join(', ')}
                    </p>
                  )}
                  <p>
                    {t('acquisitionTime', language)}: <When value={result.measurementAcquiredAt} />
                  </p>
                  <p>
                    {t('measurementComputedAt', language)}:{' '}
                    <When value={result.measurementComputedAt} />
                  </p>
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
      {(evaluation.revisions?.length ?? 0) > 0 && (
        <section aria-label={t('evaluationRevisions', language)}>
          <h4>{t('evaluationRevisions', language)}</h4>
          <ul>
            {evaluation.revisions?.map((revision) => (
              <li key={revision.id}>
                {t('evaluationRevisionLink', language)}:{' '}
                <a href={`#evaluation-result-${revision.originalNodeResultId}`}>
                  {revision.originalNodeResultId}
                </a>
                {' → '}
                <a href={`#evaluation-result-${revision.revisedNodeResultId}`}>
                  {revision.revisedNodeResultId}
                </a>
                {' · '}
                {revision.revisionReason === 'LATE_FACT'
                  ? t('evaluationLateFact', language)
                  : revision.revisionReason === 'CORRECTION'
                    ? t('evaluationCorrection', language)
                    : revision.revisionReason}
                {' · '}
                {revision.lateFactReference}
                {' · '}
                <When value={revision.recordedAt} />
              </li>
            ))}
          </ul>
        </section>
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
