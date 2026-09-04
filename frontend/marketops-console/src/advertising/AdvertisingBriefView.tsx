import { useEffect, useState } from 'react';
import {
  fetchAdvertisingBrief,
  fetchAdvertisingBriefHistory,
  fetchLatestAdvertisingBrief,
} from '../api/console';
import type { AdvertisingBrief } from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { AdvertisingProblem } from './AdvertisingQueue';

/** What the brief surface needs in order to load itself. */
export interface AdvertisingBriefViewProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** Daily action brief or weekly evidence review. */
  readonly briefKind: string;
  /**
   * The period, as the calendar names it.
   *
   * Left out to ask for the newest published period instead. The console does
   * not work one out: the reporting timezone and cut minute belong to the
   * owner's calendar, and a browser deriving a period from its own clock would
   * be inventing the schedule.
   */
  readonly periodKey?: string;
}

/**
 * One published brief, and every earlier reading of the same period.
 *
 * Two things this page refuses to do. It does not show only the newest reading:
 * somebody acted on what was published on the day, and a page that quietly
 * replaced it would make that decision impossible to understand afterwards. And
 * it does not hide a topic that found nothing — a section that vanished when
 * empty would make &ldquo;we looked and there was nothing&rdquo; and &ldquo;we
 * never looked&rdquo; the same page.
 *
 * Every line links to one canonical row. Nothing here is a figure the brief
 * arrived at itself, which is why a reader who doubts a number should follow the
 * link rather than argue with the report.
 */
export function AdvertisingBriefView({
  context,
  briefKind,
  periodKey,
}: AdvertisingBriefViewProps): React.JSX.Element {
  const [brief, setBrief] = useState<AdvertisingBrief | undefined>(undefined);
  const [history, setHistory] = useState<readonly AdvertisingBrief[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [nothingPublished, setNothingPublished] = useState(false);

  useEffect(() => {
    let active = true;
    // Read through a call so the two checks either side of the await are both
    // real: a plain boolean narrows to true after the first one.
    const stopped = (): boolean => !active;
    const wanted =
      periodKey === undefined
        ? fetchLatestAdvertisingBrief(context, briefKind)
        : fetchAdvertisingBrief(context, briefKind, periodKey);
    void wanted.then(async (latest) => {
      if (stopped()) {
        return;
      }
      if (!latest.ok) {
        // Nothing published yet is an answer, not a fault: a calendar with no
        // operating day for the period deliberately publishes nothing.
        if (latest.failure.kind === 'refused' && latest.failure.status === 404) {
          setNothingPublished(true);
          setFailure(undefined);
          return;
        }
        setFailure(latest.failure);
        return;
      }
      // The period the server named, never one this browser worked out.
      const readings = await fetchAdvertisingBriefHistory(
        context,
        briefKind,
        latest.value.periodKey,
      );
      if (stopped()) {
        return;
      }
      if (!readings.ok) {
        setFailure(readings.failure);
        return;
      }
      setBrief(latest.value);
      setHistory(readings.value);
      setNothingPublished(false);
      setFailure(undefined);
    });
    return () => {
      active = false;
    };
  }, [context, briefKind, periodKey]);

  if (failure !== undefined) {
    return <AdvertisingProblem failure={failure} />;
  }
  if (nothingPublished) {
    return (
      <section aria-label="Advertising brief" data-state="none">
        <h2>Advertising brief</h2>
        <p>
          No reading has been published for this kind yet. A period the calendar does not name as an
          operating day publishes nothing, deliberately.
        </p>
      </section>
    );
  }
  if (brief === undefined || history === undefined) {
    return (
      <section aria-label="Advertising brief" data-state="loading">
        <h2>Advertising brief</h2>
        <p>Loading the published reading.</p>
      </section>
    );
  }

  return (
    <section
      aria-label="Advertising brief"
      data-state="loaded"
      data-brief-kind={brief.briefKind}
      data-revision={brief.revisionNo}
    >
      <h2>
        {brief.briefKind === 'DAILY_ACTION_BRIEF' ? 'Daily action brief' : 'Weekly evidence review'}{' '}
        — {brief.periodKey}
      </h2>
      <p>
        Facts cut at {brief.asOf}, published {brief.publishedAt}. The cut is not the render time: a
        report published late still describes the cut it names.
      </p>
      {brief.restatement ? (
        <p role="status" data-restatement="true">
          This is reading {brief.revisionNo}. It supersedes an earlier one because{' '}
          {brief.adjustmentReason ?? 'the facts underneath the period were restated'}
          {brief.lateFactReference === undefined ? '' : ` (${brief.lateFactReference})`}. The
          earlier reading is below, unchanged.
        </p>
      ) : null}
      {brief.gapCodes.length === 0 ? null : (
        <ul aria-label="What this reading could not establish">
          {brief.gapCodes.map((gap) => (
            <li key={gap}>{gap}</li>
          ))}
        </ul>
      )}

      {brief.sections.map((section) => (
        <section
          key={section.sectionCode}
          aria-label={section.sectionCode}
          data-section={section.sectionCode}
          data-coverage={section.coverageState}
        >
          <h3>{section.sectionCode.replaceAll('_', ' ').toLowerCase()}</h3>
          {section.complete ? null : (
            <p data-blocked="true">
              {section.summaryNote ?? 'this topic was not fully covered'}
              {section.blockerCodes.length === 0 ? '' : ` — ${section.blockerCodes.join(', ')}`}
            </p>
          )}
          {section.items.length === 0 ? (
            <p>
              {section.complete
                ? 'Nothing fell into this topic for the period.'
                : 'No canonical source for this topic.'}
            </p>
          ) : (
            <ul>
              {section.items.map((item) => (
                <li
                  key={`${item.subjectKind}:${item.referenceId}`}
                  data-subject={item.subjectKind}
                  data-value-state={item.valueState}
                >
                  {item.subjectKind} {item.referenceId}
                  {item.lane === undefined ? null : <> — {item.lane}</>}
                  {item.causeCode === undefined ? null : <> — {item.causeCode}</>}
                  {' — '}
                  {item.valueState === 'AVAILABLE'
                    ? `${String(item.numericValue)}${
                        item.currencyCode === undefined ? '' : ` ${item.currencyCode}`
                      }`
                    : item.valueState.toLowerCase().replaceAll('_', ' ')}
                </li>
              ))}
            </ul>
          )}
        </section>
      ))}

      {history.length <= 1 ? null : (
        <section aria-label="Earlier readings" data-readings={history.length}>
          <h3>Earlier readings</h3>
          <p>
            What was published before, kept as it was published. A reading is never edited once
            somebody could have acted on it.
          </p>
          <ol>
            {history.map((reading) => (
              <li key={reading.id} data-revision={reading.revisionNo}>
                Reading {reading.revisionNo} ({reading.revisionKind.toLowerCase()}), cut at{' '}
                {reading.asOf}, published {reading.publishedAt}
                {reading.lateFactReference === undefined
                  ? ''
                  : ` after ${reading.lateFactReference}`}
              </li>
            ))}
          </ol>
        </section>
      )}
    </section>
  );
}
