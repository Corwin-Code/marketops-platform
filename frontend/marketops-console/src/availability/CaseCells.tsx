import { Tag, Tooltip, Typography } from 'antd';
import type { AvailabilityCase, OpenException } from '../api/availability';
import { formatStoreDate } from '../format';
import { codeLabel } from '../i18n';
import {
  AUTHORITY_LEVEL_LABELS,
  CASE_STATE_LABELS,
  EXCEPTION_STATE_LABELS,
} from '../i18n/zh/availability';
import { clockText } from '../i18n/zh/availabilityCases';
import { CodeTag, DateTime } from '../ui';
import type { TagColor } from '../ui';
import { actionClock, dueTone, formatDuration, presentCaseState } from './casePresentation';
import { CASE_STATE_COLORS, EXCEPTION_STATE_COLORS } from './tagColors';

const DUE_COLORS: Readonly<Record<'overdue' | 'soon' | 'ok', TagColor>> = {
  overdue: 'error',
  soon: 'warning',
  ok: 'default',
};

const DUE_PREFIX: Readonly<Record<'overdue' | 'soon' | 'ok', string>> = {
  overdue: clockText.overdue,
  soon: clockText.soon,
  ok: clockText.due,
};

/** A case state with what it means on hover. */
export function CaseStateTag({
  governed,
}: {
  readonly governed: AvailabilityCase;
}): React.JSX.Element {
  const state = presentCaseState(governed.state);
  return (
    <Tooltip title={state.explanation}>
      <span data-case-state={governed.state} data-case-tone={state.tone}>
        <CodeTag labels={CASE_STATE_LABELS} code={governed.state} colors={CASE_STATE_COLORS} />
      </span>
    </Tooltip>
  );
}

/**
 * One of a case's two clocks.
 *
 * The action clock and the outcome clock are separate obligations with
 * separate owners, so each gets its own tag and they are never merged. A paused
 * action clock shows what was left when it paused rather than a stale deadline
 * judged against now, which would call a case under a governed acceptance
 * overdue.
 */
export function DueClock({
  governed,
  now,
  clock,
}: {
  readonly governed: AvailabilityCase;
  readonly now: Date;
  readonly clock: 'action' | 'outcome';
}): React.JSX.Element {
  if (clock === 'action') {
    const action = actionClock(governed, now);
    if (action.kind === 'paused') {
      return (
        <Tooltip title={clockText.pausedHelp}>
          <Tag color="default" data-due="paused">
            {clockText.paused}
            {action.remainingMillis === null
              ? null
              : ` · ${clockText.remaining(formatDuration(action.remainingMillis))}`}
          </Tag>
        </Tooltip>
      );
    }
    return (
      <DeadlineTag dueAt={action.dueAt} tone={action.tone} pending={clockText.outcomePending} />
    );
  }
  return (
    <DeadlineTag
      dueAt={governed.outcomeDueAt}
      tone={dueTone(governed.outcomeDueAt, now)}
      pending={clockText.outcomePending}
    />
  );
}

function DeadlineTag({
  dueAt,
  tone,
  pending,
}: {
  readonly dueAt: string | null;
  readonly tone: 'none' | 'overdue' | 'soon' | 'ok';
  readonly pending: string;
}): React.JSX.Element {
  if (tone === 'none') {
    return (
      <Typography.Text type="secondary" data-due={tone}>
        {dueAt === null ? pending : <DateTime value={dueAt} />}
      </Typography.Text>
    );
  }
  return (
    <Tag color={DUE_COLORS[tone]} data-due={tone}>
      {DUE_PREFIX[tone]} <DateTime value={dueAt} />
    </Tag>
  );
}

/** The acceptance occupying a case: its state, the authority it needs, and its end. */
export function ExceptionTag({ open }: { readonly open: OpenException }): React.JSX.Element {
  const color = EXCEPTION_STATE_COLORS[open.state];
  return (
    <Tag {...(color === undefined ? {} : { color })} data-exception-state={open.state}>
      {codeLabel(EXCEPTION_STATE_LABELS, open.state)}
      {open.requiredAuthority === ''
        ? null
        : ` · ${codeLabel(AUTHORITY_LEVEL_LABELS, open.requiredAuthority)}`}
      {open.state === 'ACTIVE' && open.expiresAt !== null
        ? ` · ${clockText.until(formatStoreDate(open.expiresAt))}`
        : null}
    </Tag>
  );
}
