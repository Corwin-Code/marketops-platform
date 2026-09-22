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
  summary: '正在查询平台状态。',
  action: '请等待第一次查询结果。',
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
          summary: '平台没有响应。',
          action: '请检查后端服务是否已启动，以及配置的地址能否访问。',
          usable: false,
        };
      case 'failing':
        return {
          name: 'failing',
          summary: `平台返回了错误状态（HTTP ${String(outcome.failure.status)}）。`,
          action: '请在后端日志中查找对应的记录。',
          usable: false,
        };
      case 'malformed':
        return {
          name: 'malformed',
          summary: '平台返回了控制台无法识别的内容。',
          action: '请确认控制台与后端来自同一个发布版本。',
          usable: false,
        };
    }
  }

  const status = outcome.value;

  if (status.database.status !== DATABASE_UP) {
    return {
      name: 'degraded',
      summary: '平台正在运行，但数据库没有响应。',
      action: '请检查数据库容器以及后端启动时使用的数据库凭据。',
      usable: false,
      status,
    };
  }

  if (status.migration.currentVersion === UNKNOWN_VERSION) {
    return {
      name: 'pendingMigration',
      summary: '平台正在运行，但没有报告已应用的数据结构版本。',
      action: '请确认数据库迁移已执行并完成。',
      usable: false,
      status,
    };
  }

  return {
    name: 'ready',
    summary: '平台运行正常，数据库响应正常。',
    action: '无需任何操作。',
    usable: true,
    status,
  };
}

/** How a state is coloured: a status dot and a result icon share one tone. */
export type HealthTone = 'processing' | 'success' | 'warning' | 'error';

/** The tone of each state; not-usable states are never shown as success. */
export const HEALTH_TONES: Readonly<Record<HealthStateName, HealthTone>> = {
  initialising: 'processing',
  ready: 'success',
  degraded: 'warning',
  pendingMigration: 'warning',
  unreachable: 'error',
  failing: 'error',
  malformed: 'error',
};

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
