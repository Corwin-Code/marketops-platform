import { useEffect, useState } from 'react';
import { fetchDiagnosis } from '../api/console';
import type { ConsoleFailure, ConsoleRequest, SubjectDiagnosis } from '../api/console';
import { ValueCell } from '../state/ValueCell';

/** What the diagnosis view needs in order to load itself. */
export interface SubjectDiagnosisProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The listing variant being examined. */
  readonly subjectId: string;
  /** Store it sits on. */
  readonly storeId: string;
  /** Called when the operator goes back to the work list. */
  readonly onBack: () => void;
}

/**
 * Everything known about one listing variant, and what the rules concluded.
 *
 * Metrics and findings are shown together because a number without the rule
 * that read it invites an operator to draw their own conclusion, and a finding
 * without its inputs cannot be checked. The evidence identifiers are rendered
 * so a person can ask for the source record behind any figure.
 *
 * A rule that could not answer is shown as prominently as one that triggered.
 * Silence about a rule that declined is how a data problem gets mistaken for a
 * clean bill of health.
 */
export function SubjectDiagnosisView({
  context,
  subjectId,
  storeId,
  onBack,
}: SubjectDiagnosisProps): React.JSX.Element {
  const [diagnosis, setDiagnosis] = useState<SubjectDiagnosis | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchDiagnosis(context, subjectId, storeId).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setDiagnosis(outcome.value);
        setFailure(undefined);
      } else {
        setDiagnosis(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, subjectId, storeId]);

  return (
    <section aria-label="Subject diagnosis" data-subject={subjectId}>
      <h2>Listing variant {subjectId}</h2>
      <button type="button" onClick={onBack}>
        Back to the work list
      </button>

      {failure !== undefined && (
        <p role="alert">The diagnosis could not be loaded ({failure.kind}). Nothing was changed.</p>
      )}

      {diagnosis !== undefined && (
        <>
          <section aria-label="Canonical values" data-state="metrics">
            <h3>What the platform measured</h3>
            <div className="value-grid">
              {Object.entries(diagnosis.metrics).map(([code, metric]) => (
                <ValueCell
                  key={code}
                  label={code}
                  value={metric.numericValue}
                  currencyCode={metric.currencyCode}
                  valueState={metric.valueState}
                  confidenceState={metric.confidenceState}
                  freshnessSeconds={metric.freshnessSeconds}
                />
              ))}
            </div>
          </section>

          <section aria-label="Rule outcomes" data-state="findings">
            <h3>What the rules concluded</h3>
            <ul>
              {diagnosis.findings.map((finding) => (
                <li
                  key={finding.findingId}
                  data-rule={finding.ruleCode}
                  data-outcome={finding.outcome}
                  data-blocks-write={finding.blocksExecution}
                >
                  <strong>{finding.ruleCode}</strong>
                  <span> — {describe(finding.outcome, finding.severity)}</span>
                  {finding.outcome === 'DECLINED' && finding.declineReason !== null && (
                    <span> ({finding.declineReason})</span>
                  )}
                  {finding.blocksExecution && (
                    <span data-testid="blocks-write"> This blocks a platform write.</span>
                  )}
                  {Object.keys(finding.detail).length > 0 && (
                    <dl>
                      {Object.entries(finding.detail).map(([name, value]) => (
                        <div key={name}>
                          <dt>{name}</dt>
                          <dd>{value}</dd>
                        </div>
                      ))}
                    </dl>
                  )}
                  <p className="evidence-refs">
                    Evidence: {finding.metricValueIds.join(', ') || 'none recorded'}
                  </p>
                </li>
              ))}
            </ul>
          </section>
        </>
      )}
    </section>
  );
}

/** Say what a rule concluded in words rather than in a code. */
function describe(outcome: string, severity: string): string {
  switch (outcome) {
    case 'TRIGGERED':
      return `triggered at ${severity.toLowerCase()} severity`;
    case 'CLEAR':
      return 'evaluated and found nothing';
    default:
      return 'could not answer';
  }
}
