import { useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest, ConsoleOutcome } from '../api/console';
import {
  fetchPromotionTerms,
  prepareAction,
  recordPromotionFact,
  type PromotionTerms,
  type PromotionTermsView,
} from '../api/listingConversion';
import { ListingProblem, YesNo } from './ListingCommon';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

interface TermRow {
  name: string;
  value: string;
}

/** One exact declaration form for preparation and independently entered observations. */
function PromotionTermsForm({
  label,
  kind,
  onSave,
  onSaved,
  onUnknown,
  observation = false,
  children,
}: {
  readonly label: string;
  readonly kind: string;
  readonly onSave: (terms: PromotionTerms) => Promise<ConsoleOutcome<string>>;
  readonly onSaved: (id: string) => void;
  readonly onUnknown?: (nativeKey: string) => Promise<ConsoleOutcome<string>>;
  readonly observation?: boolean;
  readonly children?: React.ReactNode;
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
      <h4>{t(observation ? 'promotionObservation' : 'promotionDeclaration', language)}</h4>
      {children}
      <p>{t(observation ? 'promotionObservationHelp' : 'promotionDeclarationHelp', language)}</p>
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
  return (
    <PromotionTermsForm
      label={candidateId}
      kind={kind}
      onSaved={onPrepared}
      onSave={async (terms) => {
        const outcome = await prepareAction(
          context,
          candidateId,
          'MANUAL',
          '',
          undefined,
          undefined,
          terms,
        );
        return outcome.ok ? { ok: true, value: outcome.value.id } : outcome;
      }}
    />
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
    return recordPromotionFact(
      context,
      listingId,
      declaration,
      state,
      time.toISOString(),
      reference,
      { engagementKind: kind, nativePromotionKey: nativeKey },
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
              setState(e.target.value);
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
      )}
    </section>
  );
}
