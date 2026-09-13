import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest, ConsoleOutcome } from '../api/console';
import {
  fetchPromotionTerms,
  fetchCandidateSimulations,
  prepareAction,
  recordPromotionFact,
  simulatePromotionCandidate,
  type ListingPurposeBasis,
  type PromotionContextObservationInput,
  type PromotionTerms,
  type PromotionTermsView,
  type PromotionSimulation,
} from '../api/listingConversion';
import { ListingProblem, YesNo } from './ListingCommon';
import { labelFor, useLanguage } from './i18n/language';
import { t } from './i18n/ui';

interface TermRow {
  name: string;
  value: string;
}

const PROMOTION_AXIS_CODES = [
  'CONCURRENT_LISTINGS',
  'AFFECTED_VARIANTS',
  'REVENUE_EXPOSURE',
  'CATEGORY_SHARE',
] as const;
type PromotionAxisCode = (typeof PROMOTION_AXIS_CODES)[number];
interface PromotionAxisDraft {
  value: string;
  unitCode: string;
  evidenceReference: string;
}

function emptyPromotionAxes(): Record<PromotionAxisCode, PromotionAxisDraft> {
  return Object.fromEntries(
    PROMOTION_AXIS_CODES.map((axis) => [axis, { value: '', unitCode: '', evidenceReference: '' }]),
  ) as Record<PromotionAxisCode, PromotionAxisDraft>;
}

function instant(value: string): string | undefined {
  const parsed = new Date(value);
  return Number.isFinite(parsed.valueOf()) ? parsed.toISOString() : undefined;
}

/** One exact declaration form for preparation and independently entered observations. */
export function PromotionTermsForm({
  label,
  kind,
  onSave,
  onSaved,
  onUnknown,
  observation = false,
  heading,
  help,
  children,
  preparation,
}: {
  readonly label: string;
  readonly kind: string;
  readonly onSave: (terms: PromotionTerms) => Promise<ConsoleOutcome<string>>;
  readonly onSaved: (id: string) => void;
  readonly onUnknown?: (nativeKey: string) => Promise<ConsoleOutcome<string>>;
  readonly observation?: boolean;
  readonly heading?: string;
  readonly help?: string;
  readonly children?: React.ReactNode;
  readonly preparation?: (draft: PromotionTerms | undefined) => React.ReactNode;
}): React.JSX.Element {
  const { language } = useLanguage();
  const [nativeKey, setNativeKey] = useState('');
  const [termsKnown, setTermsKnown] = useState(!observation);
  const [source, setSource] = useState('');
  const [freeze, setFreeze] = useState('');
  const [auto, setAuto] = useState('');
  const [terms, setTerms] = useState<TermRow[]>([{ name: '', value: '' }]);
  const [obligations, setObligations] = useState<TermRow[]>([{ name: '', value: '' }]);
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [pending, setPending] = useState(false);
  const duplicate = [terms, obligations].some(
    (rows) => new Set(rows.map((r) => r.name)).size !== rows.length,
  );
  const completeRows = [terms, obligations].every((rows) =>
    rows.every((entry) => entry.name.trim() !== '' && entry.value.trim() !== ''),
  );
  const draft =
    termsKnown &&
    !duplicate &&
    completeRows &&
    nativeKey.trim() !== '' &&
    source.trim() !== '' &&
    freeze !== '' &&
    auto !== ''
      ? {
          engagementKind: kind,
          nativePromotionKey: nativeKey,
          termsEvidenceReference: source,
          priceFreeze: freeze === 'yes',
          autoParticipation: auto === 'yes',
          terms: Object.fromEntries(terms.map((entry) => [entry.name, entry.value])),
          obligations: Object.fromEntries(obligations.map((entry) => [entry.name, entry.value])),
        }
      : undefined;
  const fields = (
    rows: TermRow[],
    setRows: (rows: TermRow[]) => void,
    title: 'promotionTerms' | 'promotionObligations',
  ) => (
    <fieldset>
      <legend>{t(title, language)}</legend>
      {rows.map((r, index) => (
        <div key={index}>
          <label>
            {t('promotionFieldName', language)} {index + 1}
            <input
              required
              maxLength={128}
              value={r.name}
              onChange={(e) => {
                setRows(rows.map((v, i) => (i === index ? { ...v, name: e.target.value } : v)));
              }}
            />
          </label>
          <label>
            {t('promotionFieldValue', language)} {index + 1}
            <input
              required
              maxLength={512}
              value={r.value}
              onChange={(e) => {
                setRows(rows.map((v, i) => (i === index ? { ...v, value: e.target.value } : v)));
              }}
            />
          </label>
        </div>
      ))}
      <button
        type="button"
        disabled={rows.length >= 64}
        onClick={() => {
          setRows([...rows, { name: '', value: '' }]);
        }}
      >
        {t('promotionAddTerm', language)}
      </button>
    </fieldset>
  );
  return (
    <form
      aria-label={label}
      onSubmit={(event) => {
        event.preventDefault();
        if (pending || (termsKnown && (duplicate || freeze === '' || auto === ''))) return;
        if (!termsKnown && onUnknown === undefined) return;
        setPending(true);
        const saving = termsKnown
          ? onSave({
              engagementKind: kind,
              nativePromotionKey: nativeKey,
              termsEvidenceReference: source,
              priceFreeze: freeze === 'yes',
              autoParticipation: auto === 'yes',
              terms: Object.fromEntries(terms.map((r) => [r.name, r.value])),
              obligations: Object.fromEntries(obligations.map((r) => [r.name, r.value])),
            })
          : onUnknown?.(nativeKey);
        void saving?.then((outcome) => {
          setPending(false);
          if (outcome.ok) onSaved(outcome.value);
          else setFailure(outcome.failure);
        });
      }}
    >
      <h4>
        {heading ?? t(observation ? 'promotionObservation' : 'promotionDeclaration', language)}
      </h4>
      {children}
      <p>
        {help ?? t(observation ? 'promotionObservationHelp' : 'promotionDeclarationHelp', language)}
      </p>
      {failure !== undefined && <ListingProblem failure={failure} />}
      <label>
        {t('promotionNativeKey', language)}
        <input
          required
          maxLength={128}
          value={nativeKey}
          onChange={(e) => {
            setNativeKey(e.target.value);
          }}
        />
      </label>
      {observation && (
        <label>
          {t('promotionTermsObserved', language)}
          <input
            type="checkbox"
            checked={termsKnown}
            onChange={(e) => {
              setTermsKnown(e.target.checked);
            }}
          />
        </label>
      )}
      {termsKnown && (
        <>
          <label>
            {t(observation ? 'promotionDeclarationSource' : 'evidence', language)}
            <input
              required
              maxLength={512}
              value={source}
              onChange={(e) => {
                setSource(e.target.value);
              }}
            />
          </label>
          {fields(terms, setTerms, 'promotionTerms')}
          {fields(obligations, setObligations, 'promotionObligations')}
          <label>
            {t('promotionPriceFreeze', language)}
            <select
              required
              value={freeze}
              onChange={(e) => {
                setFreeze(e.target.value);
              }}
            >
              <option value="">{t('undeclared', language)}</option>
              <option value="yes">{t('yes', language)}</option>
              <option value="no">{t('no', language)}</option>
            </select>
          </label>
          <label>
            {t('promotionAutoParticipation', language)}
            <select
              required
              value={auto}
              onChange={(e) => {
                setAuto(e.target.value);
              }}
            >
              <option value="">{t('undeclared', language)}</option>
              <option value="yes">{t('yes', language)}</option>
              <option value="no">{t('no', language)}</option>
            </select>
          </label>
        </>
      )}
      {termsKnown && duplicate && <p role="alert">{t('promotionDuplicateTerm', language)}</p>}
      {preparation?.(draft)}
      <button type="submit" disabled={pending || (termsKnown && duplicate)}>
        {t('submit', language)}
      </button>
    </form>
  );
}

export function PromotionPreparationForm({
  context,
  candidateId,
  kind,
  onPrepared,
}: {
  readonly context: ConsoleRequest;
  readonly candidateId: string;
  readonly kind: string;
  readonly onPrepared: (id: string) => void;
}): React.JSX.Element {
  const { language } = useLanguage();
  const [purpose, setPurpose] = useState<'PROMOTION' | 'BOUNDED_EXPLORATION'>('PROMOTION');
  const [basisEvidence, setBasisEvidence] = useState('');
  const [useCondition, setUseCondition] = useState('');
  const [endCondition, setEndCondition] = useState('');
  const [useUntil, setUseUntil] = useState('');
  const [simulationId, setSimulationId] = useState<string>();
  const useUntilValue = new Date(useUntil);
  const purposeBasis: ListingPurposeBasis | undefined =
    purpose === 'BOUNDED_EXPLORATION' &&
    basisEvidence.trim() !== '' &&
    useCondition.trim() !== '' &&
    endCondition.trim() !== '' &&
    Number.isFinite(useUntilValue.valueOf())
      ? {
          evidenceReference: basisEvidence,
          useConditions: [useCondition],
          endConditions: [endCondition],
          useUntil: useUntilValue.toISOString(),
        }
      : undefined;
  return (
    <PromotionTermsForm
      label={candidateId}
      kind={kind}
      onSaved={onPrepared}
      preparation={(draft) => (
        <>
          <fieldset>
            <legend>{t('actionPurpose', language)}</legend>
            <label>
              {t('actionPurpose', language)}
              <select
                value={purpose}
                onChange={(event) => {
                  setPurpose(event.target.value as 'PROMOTION' | 'BOUNDED_EXPLORATION');
                  setSimulationId(undefined);
                }}
              >
                <option value="PROMOTION">{t('purposePromotion', language)}</option>
                <option value="BOUNDED_EXPLORATION">{t('purposeExploration', language)}</option>
              </select>
            </label>
            {purpose === 'BOUNDED_EXPLORATION' && (
              <>
                <label>
                  {t('purposeEvidence', language)}
                  <input
                    required
                    maxLength={512}
                    value={basisEvidence}
                    onChange={(event) => {
                      setBasisEvidence(event.target.value);
                    }}
                  />
                </label>
                <label>
                  {t('purposeUseConditions', language)}
                  <textarea
                    required
                    maxLength={512}
                    value={useCondition}
                    onChange={(event) => {
                      setUseCondition(event.target.value);
                    }}
                  />
                </label>
                <label>
                  {t('purposeEndConditions', language)}
                  <textarea
                    required
                    maxLength={512}
                    value={endCondition}
                    onChange={(event) => {
                      setEndCondition(event.target.value);
                    }}
                  />
                </label>
                <label>
                  {t('purposeUseUntil', language)}
                  <input
                    required
                    type="datetime-local"
                    value={useUntil}
                    onChange={(event) => {
                      setUseUntil(event.target.value);
                    }}
                  />
                </label>
              </>
            )}
          </fieldset>
          <PromotionSimulationSelection
            context={context}
            candidateId={candidateId}
            purpose={purpose}
            declaration={draft}
            selected={simulationId}
            onSelected={setSimulationId}
          />
        </>
      )}
      onSave={async (terms) => {
        if (
          simulationId === undefined ||
          (purpose === 'BOUNDED_EXPLORATION' && purposeBasis === undefined)
        )
          return {
            ok: false as const,
            failure: {
              kind: 'refused' as const,
              status: 400,
              detail: t('promotionSimulationRequired', language),
            },
          };
        const outcome = await prepareAction(
          context,
          candidateId,
          'MANUAL',
          '',
          undefined,
          undefined,
          terms,
          purpose,
          purposeBasis,
          simulationId,
        );
        return outcome.ok ? { ok: true, value: outcome.value.id } : outcome;
      }}
    />
  );
}

function PromotionSimulationSelection({
  context,
  candidateId,
  purpose,
  declaration,
  selected,
  onSelected,
}: {
  readonly context: ConsoleRequest;
  readonly candidateId: string;
  readonly purpose: 'PROMOTION' | 'BOUNDED_EXPLORATION';
  readonly declaration: PromotionTerms | undefined;
  readonly selected: string | undefined;
  readonly onSelected: (id: string | undefined) => void;
}): React.JSX.Element {
  const { language } = useLanguage();
  const [simulations, setSimulations] = useState<readonly PromotionSimulation[]>([]);
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [busy, setBusy] = useState(false);
  const [listPrice, setListPrice] = useState('');
  const [discount, setDiscount] = useState('');
  const [unitCost, setUnitCost] = useState('');
  const [currency, setCurrency] = useState('RUB');
  const [referenceProfit, setReferenceProfit] = useState('');
  const [scenarios, setScenarios] = useState<TermRow[]>([{ name: '', value: '' }]);
  const [periodStart, setPeriodStart] = useState('');
  const [periodEnd, setPeriodEnd] = useState('');
  const [assumptions, setAssumptions] = useState('');
  const [sourceReference, setSourceReference] = useState('');
  const [platformFee, setPlatformFee] = useState('');
  const [fixedFee, setFixedFee] = useState('');
  const [returnLoss, setReturnLoss] = useState('');
  const [advertising, setAdvertising] = useState('');
  const [variableTax, setVariableTax] = useState('');
  const declarationIdentity = JSON.stringify(declaration);
  const qualified = simulations.filter(
    (value) =>
      value.purposeCode === purpose &&
      value.qualificationState === 'QUALIFIED_CONDITIONAL_ECONOMICS',
  );
  async function load(): Promise<void> {
    const result = await fetchCandidateSimulations(context, candidateId);
    if (result.ok) {
      setSimulations(result.value);
      setFailure(undefined);
    } else {
      setSimulations([]);
      setFailure(result.failure);
    }
  }
  useEffect(() => {
    onSelected(undefined);
    setSimulations([]);
  }, [purpose, declarationIdentity, onSelected]);
  return (
    <fieldset>
      <legend>{t('promotionSimulation', language)}</legend>
      <p>{t('promotionSimulationBoundary', language)}</p>
      {failure === undefined ? null : <ListingProblem failure={failure} />}
      <button
        type="button"
        disabled={busy}
        onClick={() => {
          setBusy(true);
          void load().finally(() => {
            setBusy(false);
          });
        }}
      >
        {t('promotionSimulationLoad', language)}
      </button>
      <label>
        {t('promotionSimulationSelect', language)}
        <select
          required
          value={selected ?? ''}
          onChange={(event) => {
            onSelected(event.target.value === '' ? undefined : event.target.value);
          }}
        >
          <option value="">{t('undeclared', language)}</option>
          {qualified.map((value) => (
            <option key={value.id} value={value.id}>
              {value.id} · {value.computedAt}
            </option>
          ))}
        </select>
      </label>
      <details>
        <summary>{t('promotionSimulationCreate', language)}</summary>
        <label>
          {t('simulationListPrice', language)}
          <input
            value={listPrice}
            onChange={(event) => {
              setListPrice(event.target.value);
            }}
          />
        </label>
        <label>
          {t('simulationDiscount', language)}
          <input
            value={discount}
            onChange={(event) => {
              setDiscount(event.target.value);
            }}
          />
        </label>
        <label>
          {t('simulationUnitCost', language)}
          <input
            value={unitCost}
            onChange={(event) => {
              setUnitCost(event.target.value);
            }}
          />
        </label>
        <label>
          {t('simulationCurrency', language)}
          <input
            maxLength={3}
            value={currency}
            onChange={(event) => {
              setCurrency(event.target.value.toUpperCase());
            }}
          />
        </label>
        <label>
          {t('simulationReferenceProfit', language)}
          <input
            value={referenceProfit}
            onChange={(event) => {
              setReferenceProfit(event.target.value);
            }}
          />
        </label>
        <label>
          {t('simulationPlatformFee', language)}
          <input
            value={platformFee}
            onChange={(event) => {
              setPlatformFee(event.target.value);
            }}
          />
        </label>
        <label>
          {t('simulationFixedFee', language)}
          <input
            value={fixedFee}
            onChange={(event) => {
              setFixedFee(event.target.value);
            }}
          />
        </label>
        <label>
          {t('simulationReturnLoss', language)}
          <input
            value={returnLoss}
            onChange={(event) => {
              setReturnLoss(event.target.value);
            }}
          />
        </label>
        <label>
          {t('simulationAdvertising', language)}
          <input
            value={advertising}
            onChange={(event) => {
              setAdvertising(event.target.value);
            }}
          />
        </label>
        <label>
          {t('simulationVariableTax', language)}
          <input
            value={variableTax}
            onChange={(event) => {
              setVariableTax(event.target.value);
            }}
          />
        </label>
        {scenarios.map((scenario, index) => (
          <div key={index}>
            <label>
              {t('simulationScenarioCode', language)} {index + 1}
              <input
                value={scenario.name}
                onChange={(event) => {
                  setScenarios(
                    scenarios.map((value, row) =>
                      row === index ? { ...value, name: event.target.value } : value,
                    ),
                  );
                }}
              />
            </label>
            <label>
              {t('simulationQuantity', language)} {index + 1}
              <input
                value={scenario.value}
                onChange={(event) => {
                  setScenarios(
                    scenarios.map((value, row) =>
                      row === index ? { ...value, value: event.target.value } : value,
                    ),
                  );
                }}
              />
            </label>
          </div>
        ))}
        <button
          type="button"
          disabled={scenarios.length >= 64}
          onClick={() => {
            setScenarios([...scenarios, { name: '', value: '' }]);
          }}
        >
          {t('simulationAddScenario', language)}
        </button>
        <label>
          {t('feedbackFrom', language)}
          <input
            type="datetime-local"
            value={periodStart}
            onChange={(event) => {
              setPeriodStart(event.target.value);
            }}
          />
        </label>
        <label>
          {t('feedbackTo', language)}
          <input
            type="datetime-local"
            value={periodEnd}
            onChange={(event) => {
              setPeriodEnd(event.target.value);
            }}
          />
        </label>
        <label>
          {t('simulationAssumptions', language)}
          <textarea
            maxLength={512}
            value={assumptions}
            onChange={(event) => {
              setAssumptions(event.target.value);
            }}
          />
        </label>
        <label>
          {t('evidence', language)}
          <input
            maxLength={512}
            value={sourceReference}
            onChange={(event) => {
              setSourceReference(event.target.value);
            }}
          />
        </label>
        <button
          type="button"
          disabled={busy || declaration === undefined}
          onClick={() => {
            const start = new Date(periodStart),
              end = new Date(periodEnd);
            if (
              declaration === undefined ||
              !Number.isFinite(start.valueOf()) ||
              !Number.isFinite(end.valueOf()) ||
              scenarios.some(
                (scenario) => scenario.name.trim() === '' || scenario.value.trim() === '',
              )
            ) {
              setFailure({
                kind: 'refused',
                status: 400,
                detail: t('promotionSimulationRequired', language),
              });
              return;
            }
            setBusy(true);
            setFailure(undefined);
            const money = (amount: string) =>
              amount === '' ? null : { amount, currencyCode: currency };
            void simulatePromotionCandidate(
              context,
              candidateId,
              {
                listPrice,
                sellerDiscountRate: discount === '' ? null : discount,
                discountAlreadyInNetRevenue: false,
                unitCost: unitCost === '' ? null : unitCost,
                stepFees: platformFee === '' ? [] : [{ priceFloor: '0', feePerUnit: platformFee }],
                feesKnown: platformFee !== '',
                scenarios: scenarios.map((scenario) => ({
                  code: scenario.name,
                  quantity: scenario.value,
                  necessary: true,
                  conservative: true,
                })),
                referenceProfitLine: referenceProfit === '' ? null : referenceProfit,
                currencyCode: currency,
                expenses: {
                  fixedPromotionFee: money(fixedFee),
                  returnLossPerUnit: money(returnLoss),
                  advertisingPerUnit: money(advertising),
                  variableTaxPerUnit: money(variableTax),
                },
                context: {
                  periodStart: start.toISOString(),
                  periodEnd: end.toISOString(),
                  sourceReferences: { USER_REFERENCE: sourceReference },
                  assumptions,
                  commercialDeclaration: declaration,
                },
              },
              purpose,
            )
              .then(async (result) => {
                if (!result.ok) {
                  setFailure(result.failure);
                  return;
                }
                await load();
                if (
                  result.value.purposeCode === purpose &&
                  result.value.qualificationState === 'QUALIFIED_CONDITIONAL_ECONOMICS'
                )
                  onSelected(result.value.id);
              })
              .finally(() => {
                setBusy(false);
              });
          }}
        >
          {t('promotionSimulationCreate', language)}
        </button>
      </details>
      {qualified.length === 0 ? <p>{t('promotionSimulationNone', language)}</p> : null}
    </fieldset>
  );
}

export function PromotionObservationForm({
  context,
  listingId,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
}): React.JSX.Element {
  const { language } = useLanguage();
  const [kind, setKind] = useState('');
  const [state, setState] = useState('UNKNOWN');
  const [observedAt, setObservedAt] = useState('');
  const [reference, setReference] = useState('');
  const [completeContext, setCompleteContext] = useState(false);
  const [coverageStart, setCoverageStart] = useState('');
  const [coverageEnd, setCoverageEnd] = useState('');
  const [verificationExpiresAt, setVerificationExpiresAt] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [effectiveTo, setEffectiveTo] = useState('');
  const [newTransactionsState, setNewTransactionsState] = useState('');
  const [residualObligationState, setResidualObligationState] = useState('');
  const [originalAuthorityReference, setOriginalAuthorityReference] = useState('');
  const [originalAuthorityValidUntil, setOriginalAuthorityValidUntil] = useState('');
  const [axisDrafts, setAxisDrafts] = useState(emptyPromotionAxes);
  const [saved, setSaved] = useState<string>();
  const save = (
    declaration: PromotionTerms | null,
    nativeKey: string,
  ): Promise<ConsoleOutcome<string>> => {
    setSaved(undefined);
    const time = new Date(observedAt);
    if (kind === '' || reference.trim() === '' || !Number.isFinite(time.valueOf())) {
      return Promise.resolve({
        ok: false,
        failure: {
          kind: 'refused',
          status: 400,
          detail: t('promotionObservationRequired', language),
        },
      });
    }
    let promotionContext: PromotionContextObservationInput | undefined;
    if (completeContext) {
      const coverageStartValue = instant(coverageStart);
      const coverageEndValue = instant(coverageEnd);
      const verificationExpiresAtValue = instant(verificationExpiresAt);
      const effectiveFromValue = instant(effectiveFrom);
      const effectiveToValue = instant(effectiveTo);
      const authorityUntilValue = instant(originalAuthorityValidUntil);
      const partiallyEnteredAxis = Object.values(axisDrafts).some((axis) => {
        const entered = [axis.value, axis.unitCode, axis.evidenceReference].filter(
          (value) => value.trim() !== '',
        ).length;
        return entered > 0 && entered < 3;
      });
      const authorityInvalid =
        state === 'PARTICIPATING'
          ? originalAuthorityReference.trim() === '' || authorityUntilValue === undefined
          : originalAuthorityReference.trim() !== '' || originalAuthorityValidUntil !== '';
      if (
        declaration === null ||
        coverageStartValue === undefined ||
        coverageEndValue === undefined ||
        coverageStartValue >= coverageEndValue ||
        verificationExpiresAtValue === undefined ||
        effectiveFromValue === undefined ||
        effectiveToValue === undefined ||
        effectiveFromValue >= effectiveToValue ||
        newTransactionsState === '' ||
        residualObligationState === '' ||
        partiallyEnteredAxis ||
        authorityInvalid
      ) {
        return Promise.resolve({
          ok: false,
          failure: {
            kind: 'refused',
            status: 400,
            detail: t('promotionContextRequired', language),
          },
        });
      }
      promotionContext = {
        coverageStart: coverageStartValue,
        coverageEnd: coverageEndValue,
        verificationExpiresAt: verificationExpiresAtValue,
        records: [
          {
            declaration,
            participationState: state,
            effectiveFrom: effectiveFromValue,
            effectiveTo: effectiveToValue,
            newTransactionsState,
            residualObligationState,
            originalAuthorityReference:
              originalAuthorityReference === '' ? null : originalAuthorityReference,
            originalAuthorityValidUntil: authorityUntilValue ?? null,
            axisDemands: Object.fromEntries(
              PROMOTION_AXIS_CODES.filter((axis) => axisDrafts[axis].value.trim() !== '').map(
                (axis) => [axis, axisDrafts[axis]],
              ),
            ),
          },
        ],
      };
    }
    return recordPromotionFact(
      context,
      listingId,
      declaration,
      state,
      time.toISOString(),
      reference,
      { engagementKind: kind, nativePromotionKey: nativeKey },
      promotionContext,
    );
  };
  return (
    <section>
      <PromotionTermsForm
        label={t('promotionObservation', language)}
        kind={kind}
        observation
        onSaved={setSaved}
        onSave={(declaration) => save(declaration, declaration.nativePromotionKey)}
        onUnknown={(nativeKey) => save(null, nativeKey)}
      >
        <label>
          {t('promotionKind', language)}
          <select
            required
            value={kind}
            onChange={(e) => {
              setKind(e.target.value);
            }}
          >
            <option value="">{t('undeclared', language)}</option>
            <option value="OFFICIAL_PROMOTION_PARTICIPATION">
              {t('promotionOfficialKind', language)}
            </option>
            <option value="SELLER_DIRECT_DISCOUNT">{t('promotionSellerKind', language)}</option>
          </select>
        </label>
        <label>
          {t('promotionParticipationState', language)}
          <select
            value={state}
            onChange={(e) => {
              const next = e.target.value;
              setState(next);
              if (next !== 'PARTICIPATING') {
                setOriginalAuthorityReference('');
                setOriginalAuthorityValidUntil('');
              }
            }}
          >
            <option value="UNKNOWN">{t('undeclared', language)}</option>
            <option value="PARTICIPATING">{t('promotionParticipating', language)}</option>
            <option value="NOT_PARTICIPATING">{t('promotionNotParticipating', language)}</option>
          </select>
        </label>
        <label>
          {t('promotionObservedAt', language)}
          <input
            required
            type="datetime-local"
            step="any"
            value={observedAt}
            onChange={(e) => {
              setObservedAt(e.target.value);
            }}
          />
        </label>
        <label>
          {t('promotionObservationReference', language)}
          <input
            required
            maxLength={512}
            value={reference}
            onChange={(e) => {
              setReference(e.target.value);
            }}
          />
        </label>
        <label>
          <input
            type="checkbox"
            checked={completeContext}
            onChange={(event) => {
              setCompleteContext(event.target.checked);
            }}
          />{' '}
          {t('promotionContextComplete', language)}
        </label>
        {completeContext && (
          <fieldset>
            <legend>{t('promotionContext', language)}</legend>
            <p>{t('promotionContextHelp', language)}</p>
            {[
              ['promotionCoverageStart', coverageStart, setCoverageStart],
              ['promotionCoverageEnd', coverageEnd, setCoverageEnd],
              ['promotionVerificationExpires', verificationExpiresAt, setVerificationExpiresAt],
              ['promotionEffectiveFrom', effectiveFrom, setEffectiveFrom],
              ['promotionEffectiveTo', effectiveTo, setEffectiveTo],
            ].map(([key, value, setter]) => (
              <label key={String(key)}>
                {t(key as Parameters<typeof t>[0], language)}
                <input
                  required
                  type="datetime-local"
                  step="any"
                  value={value as string}
                  onChange={(event) => {
                    (setter as (next: string) => void)(event.target.value);
                  }}
                />
              </label>
            ))}
            <label>
              {t('promotionNewTransactionsState', language)}
              <select
                required
                value={newTransactionsState}
                onChange={(event) => {
                  setNewTransactionsState(event.target.value);
                }}
              >
                <option value="">{t('undeclared', language)}</option>
                {['OPEN', 'STOPPED', 'UNKNOWN'].map((value) => (
                  <option key={value} value={value}>
                    {t(
                      value === 'OPEN'
                        ? 'promotionTransactionsOpen'
                        : value === 'STOPPED'
                          ? 'promotionTransactionsStopped'
                          : 'promotionContextUnknown',
                      language,
                    )}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('promotionResidualState', language)}
              <select
                required
                value={residualObligationState}
                onChange={(event) => {
                  setResidualObligationState(event.target.value);
                }}
              >
                <option value="">{t('undeclared', language)}</option>
                {['OUTSTANDING', 'CLEARED', 'UNKNOWN'].map((value) => (
                  <option key={value} value={value}>
                    {t(
                      value === 'OUTSTANDING'
                        ? 'promotionResidualOutstanding'
                        : value === 'CLEARED'
                          ? 'promotionResidualCleared'
                          : 'promotionContextUnknown',
                      language,
                    )}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('promotionOriginalAuthority', language)}
              <input
                required={state === 'PARTICIPATING'}
                disabled={state !== 'PARTICIPATING'}
                maxLength={512}
                value={originalAuthorityReference}
                onChange={(event) => {
                  setOriginalAuthorityReference(event.target.value);
                }}
              />
            </label>
            <label>
              {t('promotionOriginalAuthorityUntil', language)}
              <input
                required={state === 'PARTICIPATING'}
                disabled={state !== 'PARTICIPATING'}
                type="datetime-local"
                step="any"
                value={originalAuthorityValidUntil}
                onChange={(event) => {
                  setOriginalAuthorityValidUntil(event.target.value);
                }}
              />
            </label>
            <fieldset>
              <legend>{t('promotionAxisDemands', language)}</legend>
              {PROMOTION_AXIS_CODES.map((axis) => {
                const draft = axisDrafts[axis];
                const partlyEntered = [draft.value, draft.unitCode, draft.evidenceReference].some(
                  (value) => value.trim() !== '',
                );
                const update = (change: Partial<PromotionAxisDraft>): void => {
                  setAxisDrafts((current) => ({
                    ...current,
                    [axis]: { ...current[axis], ...change },
                  }));
                };
                return (
                  <fieldset key={axis}>
                    <legend>{labelFor('allowanceAxis', axis, language)}</legend>
                    <label>
                      {t('promotionAxisValue', language)}
                      <input
                        required={partlyEntered}
                        inputMode="decimal"
                        maxLength={32}
                        value={draft.value}
                        onChange={(event) => {
                          update({ value: event.target.value });
                        }}
                      />
                    </label>
                    <label>
                      {t('promotionAxisUnit', language)}
                      <input
                        required={partlyEntered}
                        maxLength={32}
                        value={draft.unitCode}
                        onChange={(event) => {
                          update({ unitCode: event.target.value });
                        }}
                      />
                    </label>
                    <label>
                      {t('promotionAxisEvidence', language)}
                      <input
                        required={partlyEntered}
                        maxLength={512}
                        value={draft.evidenceReference}
                        onChange={(event) => {
                          update({ evidenceReference: event.target.value });
                        }}
                      />
                    </label>
                  </fieldset>
                );
              })}
            </fieldset>
          </fieldset>
        )}
      </PromotionTermsForm>
      {saved !== undefined && (
        <p role="status">
          {t('promotionObservationSaved', language)}: {saved}
        </p>
      )}
    </section>
  );
}

export function PromotionDeclaration({
  context,
  actionId,
  digest,
}: {
  readonly context: ConsoleRequest;
  readonly actionId: string;
  readonly digest: string | undefined;
}): React.JSX.Element {
  const { language } = useLanguage();
  const [response, setResponse] = useState<{
    context: ConsoleRequest;
    value: PromotionTermsView;
  }>();
  const requestSequence = useRef(0);
  const answer =
    response?.context === context && response.value.actionId === actionId
      ? response.value
      : undefined;
  const [failure, setFailure] = useState<ConsoleFailure>();
  const terms = answer?.terms;
  return (
    <section aria-label={t('promotionDeclaration', language)}>
      <p>
        {t('promotionDeclarationIdentity', language)}:{' '}
        {digest ?? t('promotionTermsMissing', language)}
      </p>
      <button
        type="button"
        onClick={() => {
          // Clear old disclosed terms before requesting the current authorization projection.
          setResponse(undefined);
          const sequence = ++requestSequence.current;
          setFailure(undefined);
          void fetchPromotionTerms(context, actionId).then((outcome) => {
            if (sequence !== requestSequence.current) return;
            if (outcome.ok) setResponse({ context, value: outcome.value });
            else setFailure(outcome.failure);
          });
        }}
      >
        {t('promotionReadTerms', language)}
      </button>
      {failure !== undefined && <ListingProblem failure={failure} />}
      {answer !== undefined && !answer.fullDisclosure && (
        <p>{t('promotionTermsRestricted', language)}</p>
      )}
      {answer?.fullDisclosure && terms === undefined && (
        <p>{t('promotionTermsMissing', language)}</p>
      )}
      {terms !== undefined && (
        <>
          <PromotionTermsDetails terms={terms} />
        </>
      )}
    </section>
  );
}

/** The same full declaration is used by viewing and structured independent review. */
export function PromotionTermsDetails({
  terms,
}: {
  readonly terms: PromotionTerms;
}): React.JSX.Element {
  const { language } = useLanguage();
  return (
    <>
      <dl>
        <dt>{t('promotionNativeKey', language)}</dt>
        <dd>{terms.nativePromotionKey}</dd>
        <dt>{t('evidence', language)}</dt>
        <dd>{terms.termsEvidenceReference}</dd>
        <dt>{t('promotionPriceFreeze', language)}</dt>
        <dd>
          <YesNo value={terms.priceFreeze} />
        </dd>
        <dt>{t('promotionAutoParticipation', language)}</dt>
        <dd>
          <YesNo value={terms.autoParticipation} />
        </dd>
      </dl>
      {(['terms', 'obligations'] as const).map((key) => (
        <table key={key}>
          <caption>
            {t(key === 'terms' ? 'promotionTerms' : 'promotionObligations', language)}
          </caption>
          <thead>
            <tr>
              <th>{t('promotionFieldName', language)}</th>
              <th>{t('promotionFieldValue', language)}</th>
            </tr>
          </thead>
          <tbody>
            {Object.entries(terms[key]).map(([name, value]) => (
              <tr key={name}>
                <th>{name}</th>
                <td>{value}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ))}
    </>
  );
}
