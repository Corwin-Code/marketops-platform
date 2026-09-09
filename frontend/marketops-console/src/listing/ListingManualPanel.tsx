import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { ManualPacket, PromotionEngagement } from '../api/listingConversion';
import {
  authorizeExit,
  fetchEngagements,
  fetchMyPackets,
  issuePacket,
  releaseEngagement,
  reportPacket,
  verifyPacket,
} from '../api/listingConversion';
import { Code, ListingProblem, When } from './ListingCommon';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

export interface ListingManualPanelProps {
  readonly context: ConsoleRequest;
}

/**
 * The manual execution path and simple promotion engagements.
 *
 * A packet carries exactly the approved material; the executor reports, and a
 * different person verifies. An engagement is entered on the platform's own
 * console and only recorded, exited and released here.
 */
export function ListingManualPanel({ context }: ListingManualPanelProps): React.JSX.Element {
  const { language } = useLanguage();
  const [packets, setPackets] = useState<readonly ManualPacket[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [issueAction, setIssueAction] = useState('');
  const [issueExecutor, setIssueExecutor] = useState('');
  const [operationTime, setOperationTime] = useState('');
  const [reportState, setReportState] = useState('APPLIED');
  const [note, setNote] = useState('');
  const [basis, setBasis] = useState('INDEPENDENT_HUMAN');
  const [managementMatch, setManagementMatch] = useState('MATCHED_TARGET');
  const [displayState, setDisplayState] = useState('DISPLAYED');
  const [listingId, setListingId] = useState('');
  const [engagements, setEngagements] = useState<readonly PromotionEngagement[] | undefined>(
    undefined,
  );
  const [exitReason, setExitReason] = useState('OWNER_DECISION');

  useEffect(() => {
    let active = true;
    void fetchMyPackets(context).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setPackets(outcome.value);
        setFailure(undefined);
      } else {
        setPackets(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, generation]);

  const settle = (outcome: { readonly ok: boolean; readonly failure?: ConsoleFailure }): void => {
    if (outcome.ok) {
      setFailure(undefined);
      setGeneration((value) => value + 1);
    } else if (outcome.failure !== undefined) {
      setFailure(outcome.failure);
    }
  };
  const loadEngagements = (): void => {
    void fetchEngagements(context, listingId).then((outcome) => {
      if (outcome.ok) {
        setEngagements(outcome.value);
        setFailure(undefined);
      } else setFailure(outcome.failure);
    });
  };

  return (
    <section
      aria-label={t('packets', language)}
      data-state={packets === undefined ? 'loading' : 'loaded'}
    >
      <h3>{t('packets', language)}</h3>
      {failure !== undefined && <ListingProblem failure={failure} />}
      <form
        aria-label={t('executor', language)}
        onSubmit={(event) => {
          event.preventDefault();
          void issuePacket(context, issueAction, issueExecutor).then(settle);
        }}
      >
        <label>
          {t('actions', language)}{' '}
          <input
            value={issueAction}
            onChange={(e) => {
              setIssueAction(e.target.value);
            }}
          />
        </label>
        <label>
          {t('executor', language)}{' '}
          <input
            value={issueExecutor}
            onChange={(e) => {
              setIssueExecutor(e.target.value);
            }}
          />
        </label>
        <button type="submit">{t('submit', language)}</button>
      </form>
      {packets === undefined && failure === undefined && <p>{t('loading', language)}</p>}
      {packets?.length === 0 && <p>{t('nothing', language)}</p>}
      {packets?.map((packet) => (
        <article key={packet.id} data-packet={packet.id} data-packet-state={packet.state}>
          <h4>
            {packet.nativeListingKey} · <Code family="packetState" code={packet.state} /> ·{' '}
            {t('expires', language)} <When value={packet.expiresAt} />
          </h4>
          {packet.targetText !== undefined && <pre lang="ru">{packet.targetText}</pre>}
          <ul>
            {packet.reports.map((report) => (
              <li key={report.id}>
                <Code family="reportState" code={report.reportState} /> {report.reporterUserId}{' '}
                {report.note}
              </li>
            ))}
            {packet.verifications.map((verification) => (
              <li key={verification.id}>
                <Code family="verificationBasis" code={verification.verificationBasis} />{' '}
                <Code family="managementMatch" code={verification.managementMatch} />{' '}
                <Code family="displayState" code={verification.displayState} />
              </li>
            ))}
          </ul>
          {packet.state === 'ISSUED' && (
            <form
              aria-label={`${t('report', language)} ${packet.id}`}
              onSubmit={(event) => {
                event.preventDefault();
                void reportPacket(context, packet.id, operationTime, reportState, note).then(
                  settle,
                );
              }}
            >
              <label>
                {t('operationTime', language)}{' '}
                <input
                  value={operationTime}
                  onChange={(e) => {
                    setOperationTime(e.target.value);
                  }}
                  placeholder="2026-09-09T10:00:00Z"
                />
              </label>
              <label>
                <Code family="reportState" code={reportState} />
                <select
                  value={reportState}
                  onChange={(e) => {
                    setReportState(e.target.value);
                  }}
                >
                  {['APPLIED', 'NOT_APPLIED', 'PARTIAL'].map((state) => (
                    <option key={state} value={state}>
                      {state}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                {t('note', language)}{' '}
                <input
                  value={note}
                  onChange={(e) => {
                    setNote(e.target.value);
                  }}
                />
              </label>
              <button type="submit">{t('report', language)}</button>
            </form>
          )}
          {packet.state === 'REPORTED' && (
            <form
              aria-label={`${t('verify', language)} ${packet.id}`}
              onSubmit={(event) => {
                event.preventDefault();
                void verifyPacket(
                  context,
                  packet.id,
                  basis,
                  managementMatch,
                  displayState,
                  note,
                ).then(settle);
              }}
            >
              <label>
                <Code family="verificationBasis" code={basis} />
                <select
                  value={basis}
                  onChange={(e) => {
                    setBasis(e.target.value);
                  }}
                >
                  <option value="INDEPENDENT_HUMAN">INDEPENDENT_HUMAN</option>
                  <option value="OFFICIAL_EVIDENCE">OFFICIAL_EVIDENCE</option>
                </select>
              </label>
              <label>
                <Code family="managementMatch" code={managementMatch} />
                <select
                  value={managementMatch}
                  onChange={(e) => {
                    setManagementMatch(e.target.value);
                  }}
                >
                  {['MATCHED_TARGET', 'MATCHED_PRIOR', 'DIFFERENT', 'UNKNOWN'].map((state) => (
                    <option key={state} value={state}>
                      {state}
                    </option>
                  ))}
                </select>
              </label>
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
                {t('note', language)}{' '}
                <input
                  value={note}
                  onChange={(e) => {
                    setNote(e.target.value);
                  }}
                />
              </label>
              <button type="submit">{t('verify', language)}</button>
            </form>
          )}
        </article>
      ))}

      <h3>{t('engagements', language)}</h3>
      <form
        aria-label={t('engagements', language)}
        onSubmit={(event) => {
          event.preventDefault();
          loadEngagements();
        }}
      >
        <label>
          {t('listing', language)}{' '}
          <input
            value={listingId}
            onChange={(e) => {
              setListingId(e.target.value);
            }}
          />
        </label>
        <button type="submit">{t('open', language)}</button>
      </form>
      {engagements?.length === 0 && <p>{t('nothing', language)}</p>}
      {engagements?.map((engagement) => (
        <article
          key={engagement.id}
          data-engagement={engagement.id}
          data-engagement-state={engagement.state}
        >
          <h4>
            <Code family="engagementKind" code={engagement.engagementKind} /> ·{' '}
            <Code family="engagementState" code={engagement.state} />
            {engagement.exitReasonCode !== undefined && (
              <>
                {' '}
                · <Code family="exitReason" code={engagement.exitReasonCode} />
              </>
            )}
          </h4>
          {engagement.state === 'ACTIVE' && (
            <>
              <label>
                <Code family="exitReason" code={exitReason} />
                <select
                  value={exitReason}
                  onChange={(e) => {
                    setExitReason(e.target.value);
                  }}
                >
                  {[
                    'MARGIN_BELOW_BOUND',
                    'RETURN_RATE_ABOVE_BOUND',
                    'SUPPLY_COVERAGE_LOST',
                    'PLATFORM_TERMS_CHANGED',
                    'OWNER_DECISION',
                  ].map((code) => (
                    <option key={code} value={code}>
                      {code}
                    </option>
                  ))}
                </select>
              </label>
              <button
                type="button"
                onClick={() => {
                  void authorizeExit(context, engagement.id, exitReason).then((outcome) => {
                    settle(outcome);
                    loadEngagements();
                  });
                }}
              >
                {t('exit', language)}
              </button>
            </>
          )}
          {(engagement.state === 'EXITING' || engagement.state === 'STOPPED') && (
            <button
              type="button"
              onClick={() => {
                void releaseEngagement(
                  context,
                  engagement.id,
                  engagement.state === 'EXITING'
                    ? 'NEW_TRANSACTIONS_STOPPED'
                    : 'OBLIGATIONS_CLEARED',
                ).then((outcome) => {
                  settle(outcome);
                  loadEngagements();
                });
              }}
            >
              {t('release', language)}
            </button>
          )}
        </article>
      ))}
    </section>
  );
}
