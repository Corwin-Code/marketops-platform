import { PromotionObservationForm } from './ListingPromotionTerms';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { ConversionMeasurement, ListingDetail, ListingHealth } from '../api/listingConversion';
import {
  fetchHealthQueue,
  fetchListingDetail,
  measureConversion,
  recomputeHealth,
  recordDescriptionFact,
  recordDisplayFact,
} from '../api/listingConversion';
import { Code, ListingProblem, When } from './ListingCommon';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

export interface ListingHealthPanelProps {
  readonly context: ConsoleRequest;
  readonly onPrepare: (listingId: string) => void;
}

const HEALTH_STATES = ['PASS', 'FAIL', 'UNKNOWN'] as const;

/**
 * Listing Health as three layers and never as a score.
 *
 * The queue is the product's own ordering of necessary-condition state; the
 * detail shows every condition with its evidence, the eligibility per purpose
 * and the opportunities by name. A measurement is shown with its ratio state
 * beside it, so "no visits" and "not yet mature" never read the same.
 */
export function ListingHealthPanel({
  context,
  onPrepare,
}: ListingHealthPanelProps): React.JSX.Element {
  const { language } = useLanguage();
  const [filter, setFilter] = useState<string | undefined>(undefined);
  const [rows, setRows] = useState<readonly ListingHealth[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [selected, setSelected] = useState<string | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchHealthQueue(context, filter).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setRows(outcome.value);
        setFailure(undefined);
      } else {
        setRows(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, filter]);

  if (selected !== undefined) {
    return (
      <ListingDetailView
        context={context}
        listingId={selected}
        onBack={() => {
          setSelected(undefined);
        }}
        onPrepare={onPrepare}
      />
    );
  }

  return (
    <section
      aria-label={t('healthQueue', language)}
      data-state={rows === undefined ? 'loading' : 'loaded'}
    >
      <h3>{t('healthQueue', language)}</h3>
      <p>{t('noScore', language)}</p>
      <fieldset>
        <legend>{t('healthNecessary', language)}</legend>
        <button
          type="button"
          aria-pressed={filter === undefined}
          onClick={() => {
            setFilter(undefined);
          }}
        >
          —
        </button>
        {HEALTH_STATES.map((state) => (
          <button
            key={state}
            type="button"
            aria-pressed={filter === state}
            onClick={() => {
              setFilter(state);
            }}
          >
            <Code family="healthState" code={state} />
          </button>
        ))}
      </fieldset>
      {failure !== undefined && <ListingProblem failure={failure} />}
      {rows === undefined && failure === undefined && <p>{t('loading', language)}</p>}
      {rows?.length === 0 && <p>{t('nothing', language)}</p>}
      {rows !== undefined && rows.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>{t('listing', language)}</th>
              <th>{t('healthNecessary', language)}</th>
              <th>{t('healthEligibility', language)}</th>
              <th>{t('healthOpportunities', language)}</th>
              <th>{t('computedAt', language)}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((health) => (
              <tr key={health.id} data-listing={health.platformListingId}>
                <td>{health.nativeListingKey}</td>
                <td>
                  <Code family="healthState" code={health.necessaryState} />
                </td>
                <td>
                  {Object.entries(health.eligibility).map(([purpose, state]) => (
                    <span key={purpose}>
                      {purpose}: <Code family="eligibility" code={state} />{' '}
                    </span>
                  ))}
                </td>
                <td>
                  {health.opportunities.map((opportunity) => (
                    <span key={opportunity.code}>
                      <Code family="opportunity" code={opportunity.code} />{' '}
                    </span>
                  ))}
                </td>
                <td>
                  <When value={health.computedAt} />
                </td>
                <td>
                  <button
                    type="button"
                    onClick={() => {
                      setSelected(health.platformListingId);
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

interface ListingDetailViewProps {
  readonly context: ConsoleRequest;
  readonly listingId: string;
  readonly onBack: () => void;
  readonly onPrepare: (listingId: string) => void;
}

function ListingDetailView({
  context,
  listingId,
  onBack,
  onPrepare,
}: ListingDetailViewProps): React.JSX.Element {
  const { language } = useLanguage();
  const [detail, setDetail] = useState<ListingDetail | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [descriptionText, setDescriptionText] = useState('');
  const [kiz, setKiz] = useState<'undeclared' | 'yes' | 'no'>('undeclared');
  const [displayState, setDisplayState] = useState('DISPLAYED');
  const [displayedText, setDisplayedText] = useState('');
  const [evidenceReference, setEvidenceReference] = useState('');
  const [windowStart, setWindowStart] = useState('');
  const [windowEnd, setWindowEnd] = useState('');
  const [retentionDays, setRetentionDays] = useState('14');
  const [evidencePath, setEvidencePath] = useState('DETAIL');
  const [message, setMessage] = useState<string | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchListingDetail(context, listingId).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setDetail(outcome.value);
        setFailure(undefined);
      } else {
        setDetail(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, listingId, generation]);

  const refresh = (): void => {
    setGeneration((value) => value + 1);
  };
  const report = (outcome: { readonly ok: boolean; readonly failure?: ConsoleFailure }): void => {
    if (outcome.ok) {
      setMessage(t('done', language));
      setFailure(undefined);
      refresh();
    } else if (outcome.failure !== undefined) {
      setFailure(outcome.failure);
    }
  };

  return (
    <section aria-label={t('listing', language)} data-listing={listingId}>
      <button type="button" onClick={onBack}>
        ← {t('healthQueue', language)}
      </button>
      {failure !== undefined && <ListingProblem failure={failure} />}
      {message !== undefined && <p role="status">{message}</p>}
      {detail === undefined && failure === undefined && <p>{t('loading', language)}</p>}
      {detail !== undefined && (
        <>
          <h3>
            {t('listing', language)} {detail.nativeListingKey} ({detail.platformCode})
          </h3>
          <button
            type="button"
            onClick={() => {
              void recomputeHealth(context, listingId).then(report);
            }}
          >
            {t('recompute', language)}
          </button>
          <button
            type="button"
            onClick={() => {
              onPrepare(listingId);
            }}
          >
            {t('candidates', language)}
          </button>
          {detail.health === undefined ? (
            <p>{t('nothing', language)}</p>
          ) : (
            <HealthLayers health={detail.health} />
          )}
          <Measurements measurements={detail.measurements} />
          <form
            aria-label={t('measure', language)}
            onSubmit={(event) => {
              event.preventDefault();
              void measureConversion(
                context,
                listingId,
                windowStart,
                windowEnd,
                Number(retentionDays),
                evidencePath,
              ).then(report);
            }}
          >
            <label>
              {t('window', language)}{' '}
              <input
                value={windowStart}
                onChange={(e) => {
                  setWindowStart(e.target.value);
                }}
                placeholder="2026-08-01T00:00:00Z"
              />
            </label>
            <label>
              →{' '}
              <input
                value={windowEnd}
                onChange={(e) => {
                  setWindowEnd(e.target.value);
                }}
                placeholder="2026-08-31T00:00:00Z"
              />
            </label>
            <label>
              {t('retentionDays', language)}
              <select
                value={retentionDays}
                onChange={(e) => {
                  setRetentionDays(e.target.value);
                }}
              >
                {['7', '14', '30'].map((days) => (
                  <option key={days} value={days}>
                    {days}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('evidencePath', language)}
              <select
                value={evidencePath}
                onChange={(e) => {
                  setEvidencePath(e.target.value);
                }}
              >
                {['DETAIL', 'OFFICIAL_SUMMARY'].map((path) => (
                  <option key={path} value={path}>
                    {path}
                  </option>
                ))}
              </select>
            </label>
            <button type="submit">{t('measure', language)}</button>
          </form>
          <PromotionObservationForm context={context} listingId={listingId} />
          <form
            aria-label={t('targetText', language)}
            onSubmit={(event) => {
              event.preventDefault();
              void recordDescriptionFact(
                context,
                listingId,
                descriptionText,
                kiz === 'undeclared' ? undefined : kiz === 'yes',
                '',
              ).then(report);
            }}
          >
            <label>
              {t('targetText', language)}
              <textarea
                value={descriptionText}
                onChange={(e) => {
                  setDescriptionText(e.target.value);
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
            <button type="submit">{t('submit', language)}</button>
          </form>
          <form
            aria-label={t('evidence', language)}
            onSubmit={(event) => {
              event.preventDefault();
              void recordDisplayFact(
                context,
                listingId,
                displayState,
                displayedText,
                evidenceReference,
              ).then(report);
            }}
          >
            <label>
              <Code family="displayState" code={displayState} />
              <select
                value={displayState}
                onChange={(e) => {
                  setDisplayState(e.target.value);
                }}
              >
                {['DISPLAYED', 'NOT_DISPLAYED', 'UNKNOWN'].map((state) => (
                  <option key={state} value={state}>
                    {state}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('targetText', language)}{' '}
              <input
                value={displayedText}
                onChange={(e) => {
                  setDisplayedText(e.target.value);
                }}
              />
            </label>
            <label>
              {t('evidence', language)}{' '}
              <input
                value={evidenceReference}
                onChange={(e) => {
                  setEvidenceReference(e.target.value);
                }}
              />
            </label>
            <button type="submit">{t('submit', language)}</button>
          </form>
        </>
      )}
    </section>
  );
}

function HealthLayers({ health }: { readonly health: ListingHealth }): React.JSX.Element {
  const { language } = useLanguage();
  return (
    <div data-health-version={health.healthVersion}>
      <h4>
        {t('healthNecessary', language)}: <Code family="healthState" code={health.necessaryState} />
      </h4>
      <ul>
        {health.necessaryConditions.map((condition) => (
          <li key={condition.code}>
            <Code family="healthCondition" code={condition.code} />:{' '}
            <Code family="healthState" code={condition.state} /> ({condition.evidenceReference})
          </li>
        ))}
      </ul>
      <h4>{t('healthEligibility', language)}</h4>
      <ul>
        {Object.entries(health.eligibility).map(([purpose, state]) => (
          <li key={purpose}>
            {purpose}: <Code family="eligibility" code={state} />
          </li>
        ))}
      </ul>
      <h4>{t('healthOpportunities', language)}</h4>
      {health.opportunities.length === 0 ? (
        <p>{t('nothing', language)}</p>
      ) : (
        <ul>
          {health.opportunities.map((opportunity) => (
            <li key={opportunity.code}>
              <Code family="opportunity" code={opportunity.code} /> ({opportunity.evidenceReference}
              )
            </li>
          ))}
        </ul>
      )}
      <p>
        {t('sourceTime', language)}: <When value={health.sourceTime} /> ·{' '}
        {t('acquisitionTime', language)}: <When value={health.acquisitionTime} /> ·{' '}
        {t('computedAt', language)}: <When value={health.computedAt} />
      </p>
    </div>
  );
}

function Measurements({
  measurements,
}: {
  readonly measurements: readonly ConversionMeasurement[];
}): React.JSX.Element {
  const { language } = useLanguage();
  return (
    <div>
      <h4>{t('measurements', language)}</h4>
      {measurements.length === 0 ? (
        <p>{t('nothing', language)}</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>{t('window', language)}</th>
              <th>{t('evidencePath', language)}</th>
              <th>{t('visits', language)}</th>
              <th>{t('retained', language)}</th>
              <th>{t('ratio', language)}</th>
              <th>{t('excludedDays', language)}</th>
              <th>{t('qualification', language)}</th>
            </tr>
          </thead>
          <tbody>
            {measurements.map((measurement) => (
              <tr key={measurement.id} data-ratio-state={measurement.ratioState}>
                <td>
                  <When value={measurement.windowStart} /> → <When value={measurement.windowEnd} />{' '}
                  ({measurement.retentionWindowDays})
                </td>
                <td>
                  <Code family="evidencePath" code={measurement.evidencePath} />
                </td>
                <td>{measurement.visitCount ?? '—'}</td>
                <td>{measurement.retainedPurchaseVisitCount ?? '—'}</td>
                <td>
                  {measurement.ratioState === 'DEFINED' ? (measurement.primaryRatio ?? '—') : null}{' '}
                  <Code family="ratioState" code={measurement.ratioState} />
                </td>
                <td>{measurement.excludedTransitionDays.join(', ')}</td>
                <td>
                  {measurement.qualificationReasonCodes.map((code) => (
                    <span key={code}>
                      <Code family="qualificationReason" code={code} />{' '}
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
