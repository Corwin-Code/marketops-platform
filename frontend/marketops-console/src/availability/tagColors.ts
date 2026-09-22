import type { TagColor } from '../ui/CodeTag';

/**
 * Tag colours for the availability code families.
 *
 * Green is reserved for settled good news, gold for anything waiting or
 * qualified, red for blocked, failed or breached, blue for work in progress.
 * A code without an entry stays neutral rather than borrowing a meaning.
 */

type Colors = Readonly<Record<string, TagColor>>;

export const LANE_COLORS: Colors = {
  HEALTHY: 'success',
  WATCH: 'warning',
  HIGH: 'volcano',
  CRITICAL: 'error',
  REVIEW: 'warning',
  UNRESOLVED: 'warning',
};

export const EVIDENCE_COLORS: Colors = {
  CONFIRMED: 'success',
  OPERATIONAL: 'success',
  PROVISIONAL: 'warning',
  CARRIED_FORWARD: 'warning',
  DATA_BLOCKED: 'error',
  POLICY_BLOCKED: 'error',
  CONFLICTED: 'error',
  STALE: 'error',
  UNKNOWN: 'default',
};

export const RISK_CONFIDENCE_COLORS: Colors = {
  HIGH: 'success',
  MEDIUM: 'warning',
  LOW: 'warning',
  UNUSABLE: 'error',
};

export const PROFIT_LANE_COLORS: Colors = {
  CONFIRMED_ELIGIBLE: 'success',
  OPERATIONAL_ELIGIBLE: 'success',
  PROVISIONAL: 'warning',
  PROFIT_DATA_BLOCKED: 'error',
  NOT_PROFITABLE: 'default',
  PROFIT_UNKNOWN: 'default',
};

export const WINDOW_ELIGIBILITY_COLORS: Colors = {
  ELIGIBLE: 'success',
  LOW_SAMPLE: 'warning',
  CENSORED: 'warning',
  OUTLIER_REVIEW: 'warning',
  WINDOW_CONFLICT: 'error',
  DATA_BLOCKED: 'error',
};

export const CASE_STATE_COLORS: Colors = {
  OPEN: 'warning',
  ASSIGNED: 'processing',
  IN_PROGRESS: 'processing',
  ACTION_RECORDED: 'processing',
  VERIFYING: 'processing',
  VERIFIED_SUCCESS: 'success',
  REOPENED: 'error',
  ESCALATED: 'error',
  REWORK_REQUIRED: 'error',
  ACCEPTED_RISK: 'warning',
  CANCELLED: 'default',
};

export const VERIFICATION_OUTCOME_COLORS: Colors = {
  VERIFIED: 'success',
  CONTINUING: 'warning',
  FAILED: 'error',
  REGRESSED: 'error',
};

export const EXCEPTION_STATE_COLORS: Colors = {
  REQUESTED: 'warning',
  AUTHORITY_BLOCKED: 'error',
  ACTIVE: 'success',
  REJECTED: 'error',
  EXPIRED: 'default',
  INVALIDATED: 'error',
  WITHDRAWN: 'default',
};

export const INBOUND_STATUS_COLORS: Colors = {
  DRAFT: 'default',
  REQUESTED: 'warning',
  SUPPLIER_CONFIRMED: 'processing',
  IN_TRANSIT: 'processing',
  RECEIVED: 'success',
  CANCELLED: 'default',
  OVERDUE: 'error',
  CONFLICTED: 'error',
  UNKNOWN: 'default',
};

export const POLICY_STATUS_COLORS: Colors = {
  ACTIVE: 'success',
  RETIRED: 'default',
  CANCELLED: 'default',
  SUPERSEDED: 'default',
};
