import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  captureFeedbackSource,
  classifyFeedback,
  fetchFeedbackDetail,
  fetchFeedbackOverview,
} from '../api/listingConversion';
import type { ListingFeedbackDetail, ListingFeedbackOverview } from '../api/listingConversion';
import { ListingProblem, When } from './ListingCommon';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

export function ListingFeedbackPanel({
  context,
  listingId,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
}): React.JSX.Element {
  const { language } = useLanguage();
  const [from, setFrom] = useState(''),
    [to, setTo] = useState('');
  const [overview, setOverview] = useState<ListingFeedbackOverview | undefined>();
  const [detail, setDetail] = useState<ListingFeedbackDetail | undefined>();
  const [failure, setFailure] = useState<ConsoleFailure | undefined>();
  const [busy, setBusy] = useState(false);
  const [theme, setTheme] = useState(''),
    [reason, setReason] = useState('');
  const [qualification, setQualification] = useState('UNCERTAIN');
  const [rawObservationId, setRawObservationId] = useState('');
  const [itemPointer, setItemPointer] = useState('');
  const [identityPointer, setIdentityPointer] = useState('/id');
  const [listingPointer, setListingPointer] = useState('/listingId');
  const [textPointer, setTextPointer] = useState('/text');
  const [captured, setCaptured] = useState<string>();
  const generation = useRef(0);
  useEffect(() => {
    generation.current += 1;
    setOverview(undefined);
    setDetail(undefined);
    setFailure(undefined);
    setBusy(false);
    return () => {
      generation.current += 1;
    };
  }, [context, listingId]);
  const qualificationLabel = (value: string): string =>
    value === 'CONFIRMED'
      ? t('feedbackConfirmed', language)
      : value === 'UNCERTAIN'
        ? t('feedbackUncertain', language)
        : value === 'CONFLICTED'
          ? t('feedbackConflicted', language)
          : value;

  async function loadOverview(): Promise<void> {
    const requestedGeneration = generation.current;
    setFailure(undefined);
    setOverview(undefined);
    const result = await fetchFeedbackOverview(
      context,
      listingId,
      new Date(from).toISOString(),
      new Date(to).toISOString(),
    );
    if (requestedGeneration !== generation.current) return;
    if (result.ok) setOverview(result.value);
    else setFailure(result.failure);
  }
  async function loadDetail(itemId: string): Promise<void> {
    const requestedGeneration = generation.current;
    setDetail(undefined);
    setFailure(undefined);
    const result = await fetchFeedbackDetail(context, listingId, itemId);
    if (requestedGeneration !== generation.current) return;
    if (result.ok) {
      setDetail(result.value);
      const latest = result.value.classifications.at(-1);
      setTheme(latest?.themeCode ?? '');
      setQualification(latest?.qualificationState ?? 'UNCERTAIN');
      setReason('');
    } else setFailure(result.failure);
  }

  return (
    <section aria-label={t('feedback', language)}>
      <h4>{t('feedback', language)}</h4>
      <p>{t('feedbackBoundary', language)}</p>
      {failure !== undefined && <ListingProblem failure={failure} />}
      <form
        onSubmit={(event) => {
          event.preventDefault();
          setBusy(true);
          setFailure(undefined);
          setCaptured(undefined);
          const requestedGeneration = generation.current;
          void captureFeedbackSource(context, listingId, {
            rawObservationId,
            itemPointer,
            identityPointer,
            listingPointer,
            textPointer,
          })
            .then(async (result) => {
              if (requestedGeneration !== generation.current) return;
              if (result.ok) {
                setCaptured(result.value);
                await loadDetail(result.value);
              } else setFailure(result.failure);
            })
            .finally(() => {
              setBusy(false);
            });
        }}
      >
        <fieldset disabled={busy}>
          <legend>{t('feedbackCapture', language)}</legend>
          <p>{t('feedbackCaptureHelp', language)}</p>
          <label>
            {t('feedbackRawObservation', language)}
            <input
              required
              value={rawObservationId}
              onChange={(event) => {
                setRawObservationId(event.target.value);
              }}
            />
          </label>
          <label>
            {t('feedbackItemPointer', language)}
            <input
              value={itemPointer}
              placeholder="/items/0"
              onChange={(event) => {
                setItemPointer(event.target.value);
              }}
            />
          </label>
          <label>
            {t('feedbackIdentityPointer', language)}
            <input
              required
              value={identityPointer}
              onChange={(event) => {
                setIdentityPointer(event.target.value);
              }}
            />
          </label>
          <label>
            {t('feedbackListingPointer', language)}
            <input
              required
              value={listingPointer}
              onChange={(event) => {
                setListingPointer(event.target.value);
              }}
            />
          </label>
          <label>
            {t('feedbackTextPointer', language)}
            <input
              required
              value={textPointer}
              onChange={(event) => {
                setTextPointer(event.target.value);
              }}
            />
          </label>
          <button type="submit">{t('feedbackCapture', language)}</button>
        </fieldset>
      </form>
      {captured === undefined ? null : (
        <p role="status">
          {t('feedbackCaptured', language)}: {captured}
        </p>
      )}
      <form
        onSubmit={(event) => {
          event.preventDefault();
          setBusy(true);
          setDetail(undefined);
          void loadOverview().finally(() => {
            setBusy(false);
          });
        }}
      >
        <fieldset disabled={busy}>
          <legend>{t('feedbackPeriod', language)}</legend>
          <label>
            {t('feedbackFrom', language)}
            <input
              type="datetime-local"
              required
              value={from}
              onChange={(event) => {
                setFrom(event.target.value);
              }}
            />
          </label>
          <label>
            {t('feedbackTo', language)}
            <input
              type="datetime-local"
              required
              value={to}
              onChange={(event) => {
                setTo(event.target.value);
              }}
            />
          </label>
          <button type="submit">{t('feedbackLoad', language)}</button>
        </fieldset>
      </form>
      {overview !== undefined && (
        <>
          <p>
            {t('feedbackSnapshot', language)}: <When value={overview.asOf} />
          </p>
          <ul>
            {overview.themes.map((value) => (
              <li key={`${value.themeCode}:${value.qualificationState}`}>
                {value.themeCode} · {qualificationLabel(value.qualificationState)} ·{' '}
                {value.mentionCount}
              </li>
            ))}
          </ul>
          {overview.items.length === 0 && <p>{t('nothing', language)}</p>}
          <p>
            {t('feedbackItemLimit', language)}: {overview.itemLimit}
          </p>
          <ul>
            {overview.items.map((item) => (
              <li key={item.id}>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => {
                    setBusy(true);
                    void loadDetail(item.id).finally(() => {
                      setBusy(false);
                    });
                  }}
                >
                  {item.id}
                </button>
                {' · '}
                <When value={item.observedAt} />
              </li>
            ))}
          </ul>
        </>
      )}
      {detail !== undefined && (
        <>
          <h5>{t('feedbackOriginal', language)}</h5>
          <p>
            {t('feedbackRaw', language)}: <code>{detail.original.rawObservationId}</code>
          </p>
          <p>
            {t('feedbackPointer', language)}: <code>{detail.original.originalPointer}</code>
          </p>
          <p>
            {t('feedbackDigest', language)}: <code>{detail.original.originalDigest}</code>
          </p>
          <p>
            {t('sourceTime', language)}: <When value={detail.original.observedAt} />
          </p>
          <p>
            {t('acquisitionTime', language)}: <When value={detail.original.acquiredAt} />
          </p>
          <h5>{t('feedbackHistory', language)}</h5>
          <ol aria-label={t('feedbackHistory', language)}>
            {detail.classifications.map((label) => (
              <li key={label.id}>
                #{label.revision} · {label.themeCode} ·{' '}
                {qualificationLabel(label.qualificationState)} · {label.reason}
                {' · '}
                {label.classifiedBy}
                {' · '}
                <When value={label.classifiedAt} />
              </li>
            ))}
          </ol>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              setBusy(true);
              setFailure(undefined);
              const itemId = detail.original.id;
              const requestedGeneration = generation.current;
              void classifyFeedback(context, listingId, itemId, {
                themeCode: theme,
                qualificationState: qualification,
                classifierVersion: 'HUMAN_V1',
                reason,
              })
                .then(async (result) => {
                  if (requestedGeneration !== generation.current) return;
                  if (result.ok) {
                    await Promise.all([loadOverview(), loadDetail(itemId)]);
                  } else setFailure(result.failure);
                })
                .finally(() => {
                  setBusy(false);
                });
            }}
          >
            <fieldset disabled={busy}>
              <legend>{t('feedbackCorrect', language)}</legend>
              <label>
                {t('feedbackTheme', language)}
                <input
                  required
                  pattern="[A-Z][A-Z0-9_]{1,62}"
                  value={theme}
                  onChange={(event) => {
                    setTheme(event.target.value);
                  }}
                />
              </label>
              <label>
                {t('feedbackQualification', language)}
                <select
                  value={qualification}
                  onChange={(event) => {
                    setQualification(event.target.value);
                  }}
                >
                  {['CONFIRMED', 'UNCERTAIN', 'CONFLICTED'].map((value) => (
                    <option key={value} value={value}>
                      {qualificationLabel(value)}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                {t('feedbackReason', language)}
                <textarea
                  required
                  maxLength={512}
                  value={reason}
                  onChange={(event) => {
                    setReason(event.target.value);
                  }}
                />
              </label>
              <button type="submit">{t('feedbackCorrect', language)}</button>
            </fieldset>
          </form>
        </>
      )}
    </section>
  );
}
