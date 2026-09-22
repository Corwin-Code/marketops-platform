import { Tag, Tooltip } from 'antd';
import { presentationOf } from '../state/confidence';
import type { ConfidenceState, Presentation, ValueState } from '../state/confidence';
import type { TagColor } from './CodeTag';

/** The states that decide how much weight a figure can carry. */
export interface ConfidenceTagProps {
  readonly valueState?: ValueState;
  readonly confidenceState?: ConfidenceState;
}

const TONE_COLORS: Readonly<Record<Presentation['tone'], TagColor>> = {
  confirmed: 'success',
  qualified: 'warning',
  absent: 'default',
};

/**
 * The confidence qualifier of a figure, as a tag.
 *
 * The label is visible text rather than only a colour, and the tone is on the
 * element as data so a check can assert nothing doubtful looks confirmed.
 */
export function ConfidenceTag({
  valueState = 'AVAILABLE',
  confidenceState,
}: ConfidenceTagProps): React.JSX.Element {
  const presentation = presentationOf(valueState, confidenceState);
  return (
    <Tooltip title={presentation.explanation}>
      <Tag color={TONE_COLORS[presentation.tone]} data-tone={presentation.tone}>
        {presentation.label}
      </Tag>
    </Tooltip>
  );
}
