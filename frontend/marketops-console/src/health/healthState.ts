/**
 * The seven states the console can be in, and the rule that picks one.
 *
 * They are exhaustive and mutually exclusive by construction: every request
 * outcome maps to exactly one, so the shell never has to decide what to render
 * for a combination nobody considered.
 */
import type { MetaStatus, MetaStatusOutcome } from '../api/metaStatus';

/** Name of a state the console can be in. */
export type HealthStateName =
  | 'initialising'
  | 'ready'
  | 'degraded'
  | 'pendingMigration'
  | 'unreachable'
  | 'failing'
  | 'malformed';

/** Value the backend reports when its database answers. */
export const DATABASE_UP = 'UP';

/** Value the backend reports when its schema version cannot be determined. */
export const UNKNOWN_VERSION = 'UNKNOWN';

/** A state, with what the operator should be told about it. */
export interface HealthState {
  /** Which of the seven states this is. */
  readonly name: HealthStateName;
  /** One line describing what is true right now. */
  readonly summary: string;
  /** What an operator would do next. */
  readonly action: string;
  /** Whether the platform can be used in this state. */
  readonly usable: boolean;
  /** Metadata backing the state, when the backend answered. */
  readonly status?: MetaStatus;
}

/** The state before the first answer has arrived. */
export const INITIALISING: HealthState = {
  name: 'initialising',
  summary: 'Asking the platform for its status.',
  action: 'Wait for the first answer.',
  usable: false,
};

/**
 * Decide which state an outcome puts the console in.
 *
 * The order of the tests is the order of severity: a backend that did not
 * answer is reported as unreachable regardless of anything else, and a database
 * that is not up outranks an unknown schema version, because an operator who
 * fixes the connection will learn the version as a consequence.
 */
export function toHealthState(outcome: MetaStatusOutcome): HealthState {
  if (!outcome.ok) {
    switch (outcome.failure.kind) {
      case 'unreachable':
        return {
          name: 'unreachable',
          summary: 'The platform did not answer.',
          action: 'Check that the backend is running and reachable on the configured origin.',
          usable: false,
        };
      case 'failing':
        return {
          name: 'failing',
          summary: `The platform answered with status ${String(outcome.failure.status)}.`,
          action: 'Check the backend log for the correlated record.',
          usable: false,
        };
      case 'malformed':
        return {
          name: 'malformed',
          summary: 'The platform answered with something this console cannot read.',
          action: 'Check that the console and the backend are from the same release.',
          usable: false,
        };
    }
  }

  const status = outcome.value;

  if (status.database.status !== DATABASE_UP) {
    return {
      name: 'degraded',
      summary: 'The platform is running but its database is not answering.',
      action: 'Check the database container and the credentials the backend was started with.',
      usable: false,
      status,
    };
  }

  if (status.migration.currentVersion === UNKNOWN_VERSION) {
    return {
      name: 'pendingMigration',
      summary: 'The platform is running but reports no applied schema version.',
      action: 'Check that the migration ran and completed.',
      usable: false,
      status,
    };
  }

  return {
    name: 'ready',
    summary: 'The platform is running and its database is answering.',
    action: 'No action is needed.',
    usable: true,
    status,
  };
}

/** Every state name, in the order the shell documents them. */
export const HEALTH_STATE_NAMES: readonly HealthStateName[] = [
  'initialising',
  'ready',
  'degraded',
  'pendingMigration',
  'unreachable',
  'failing',
  'malformed',
];
