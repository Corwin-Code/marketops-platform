import { presentEvidence } from './evidencePresentation';

/** What a chip needs in order to say what it means. */
export interface EvidenceChipProps {
  /** The state the API sent. */
  readonly state: string;
  /** What the state is about, for the accessible name. */
  readonly of: string;
}

/**
 * One evidence state, rendered so it cannot be mistaken for another.
 *
 * The tone is on the element as data rather than only in a colour, so a test
 * and a screen reader can both tell a blocked measure from a confirmed one. A
 * state nobody taught this console about renders as an explicit "unrecognised"
 * chip rather than as nothing: an operator seeing a blank where a state belongs
 * would reasonably read it as "fine".
 */
export function EvidenceChip({ state, of }: EvidenceChipProps): React.JSX.Element {
  const presentation = presentEvidence(state);
  if (presentation === undefined) {
    return (
      <span data-evidence-tone="unknown" data-evidence-state={state} title={state}>
        {of}: unrecognised state
      </span>
    );
  }
  return (
    <span
      data-evidence-tone={presentation.tone}
      data-evidence-state={state}
      data-write-grade={presentation.writeGrade ? 'yes' : 'no'}
      title={presentation.explanation}
    >
      {of}: {presentation.label}
    </span>
  );
}
