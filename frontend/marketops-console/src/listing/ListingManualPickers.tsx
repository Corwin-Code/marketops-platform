import { Flex, Form, Input, Tag, Typography } from 'antd';
import type { FormRule } from 'antd';
import type { ReactNode } from 'react';
import type { ConsoleRequest } from '../api/console';
import type {
  DescriptionObservationSummary,
  DisplayObservationSummary,
  ListingPersonRole,
  PromotionObservationSummary,
} from '../api/listingConversion';
import { fetchListingPeople } from '../api/listingConversion';
import { actions } from '../i18n/zh/common';
import { pickerText } from '../i18n/zh/listingManual';
import { ActionModal, PickOrType, idRules, isUuid, useRemote } from '../ui';
import type { PickOption } from '../ui';
import { Code, InstantPicker, When } from './ListingCommon';

/** Form rule: an instant that has already happened. */
export const notInFutureRule: FormRule = {
  validator: (_: unknown, value: string | undefined) =>
    value === undefined || value === '' || Date.parse(value) <= Date.now()
      ? Promise.resolve()
      : Promise.reject(new Error(pickerText.notInFuture)),
};

/** The trimmed value of an optional identifier field, or nothing. */
export function optionalId(value: string | undefined): string | undefined {
  const trimmed = value?.trim() ?? '';
  return trimmed === '' ? undefined : trimmed;
}

/** A store-time instant as a form control. */
export function InstantField({
  value,
  onChange,
  id,
}: {
  readonly value?: string | undefined;
  readonly onChange?: ((iso: string | undefined) => void) | undefined;
  readonly id?: string | undefined;
}): React.JSX.Element {
  return (
    <span id={id}>
      <InstantPicker
        value={value ?? ''}
        onChange={(iso) => {
          onChange?.(iso === '' ? undefined : iso);
        }}
      />
    </span>
  );
}

/** A colleague picked by name for one role, or typed by identifier. */
export function PersonField({
  context,
  role,
  targetId,
  needsTarget,
  value,
  onChange,
  id,
}: {
  readonly context: ConsoleRequest;
  readonly role: ListingPersonRole;
  /** The action (executor) or listing (steward) the person acts on. */
  readonly targetId: string | undefined;
  /** Shown while there is no target yet. */
  readonly needsTarget: string;
  readonly value?: string | undefined;
  readonly onChange?: ((value: string | undefined) => void) | undefined;
  readonly id?: string | undefined;
}): React.JSX.Element {
  const target = isUuid(targetId) ? targetId.trim() : undefined;
  const people = useRemote(target === undefined ? undefined : `${role}:${target}`, () =>
    fetchListingPeople(
      context,
      role,
      role === 'MANUAL_EXECUTOR' ? { actionId: target ?? '' } : { listingId: target ?? '' },
    ),
  );
  const options: PickOption[] = (people.value ?? []).map((person) => ({
    value: person.userId,
    label: `${person.displayName}${person.self ? pickerText.self : ''}`,
    search: person.displayName,
  }));
  return (
    <PickOrType
      id={id}
      value={value}
      onChange={onChange}
      options={options}
      loading={people.loading}
      unavailable={people.failed}
      disabled={target === undefined}
      {...(target === undefined ? { placeholder: needsTarget } : {})}
    />
  );
}

/** Small secondary text inside an option. */
function Minor({ children }: { readonly children: ReactNode }): React.JSX.Element {
  return (
    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
      {children}
    </Typography.Text>
  );
}

function NotIndependent(): React.JSX.Element {
  return (
    <Tag color="error" title={pickerText.notIndependentHelp} style={{ marginInlineEnd: 0 }}>
      {pickerText.notIndependent}
    </Tag>
  );
}

function recordedBy(
  observation: { readonly recordedByUserId: string | undefined },
  excluded: ReadonlySet<string>,
): boolean {
  return observation.recordedByUserId !== undefined && excluded.has(observation.recordedByUserId);
}

/** Why an observation cannot be chosen here, or nothing when it can. */
export type EvidenceBlock<T> = (observation: T) => string | undefined;

/** The reason a listed observation is not selectable, shown in its option. */
function Blocked({ reason }: { readonly reason: string }): React.JSX.Element {
  return (
    <Tag color="error" style={{ marginInlineEnd: 0 }}>
      {reason}
    </Tag>
  );
}

/** The approved target text digest and the text digest before the change. */
export interface DescriptionDigests {
  readonly target: string | undefined;
  readonly prior: string | undefined;
}

/**
 * Description observations, marked as matching the target, matching the prior
 * text, or differing from both; an observation the caller blocks is disabled
 * with its reason.
 */
export function descriptionOptions(
  observations: readonly DescriptionObservationSummary[],
  digests: DescriptionDigests,
  excluded: ReadonlySet<string>,
  blocked?: EvidenceBlock<DescriptionObservationSummary>,
): PickOption[] {
  return observations.map((observation) => {
    const target = digests.target !== undefined && observation.textDigest === digests.target;
    const prior =
      !target && digests.prior !== undefined && observation.textDigest === digests.prior;
    const reason = blocked?.(observation);
    return {
      value: observation.observationId,
      search: `${observation.textPreview} ${observation.observationId}`,
      ...(reason === undefined ? {} : { disabled: true }),
      label: (
        <Flex gap={6} align="center" wrap={false} style={{ minWidth: 0 }}>
          <When value={observation.observedAt} />
          <Tag
            color={target ? 'success' : prior ? 'warning' : 'default'}
            style={{ marginInlineEnd: 0 }}
          >
            {target
              ? pickerText.matchesTarget
              : prior
                ? pickerText.matchesPrior
                : pickerText.differsFromTarget}
          </Tag>
          <Code family="factSourceKind" code={observation.sourceKind} />
          {reason === undefined ? (
            recordedBy(observation, excluded) && <NotIndependent />
          ) : (
            <Blocked reason={reason} />
          )}
          <Typography.Text lang="ru" ellipsis style={{ fontSize: 12, minWidth: 0 }}>
            {observation.textPreview}
          </Typography.Text>
        </Flex>
      ),
    };
  });
}

/** Buyer-side display observations; an observation the caller blocks is disabled. */
export function displayOptions(
  observations: readonly DisplayObservationSummary[],
  excluded: ReadonlySet<string>,
  blocked?: EvidenceBlock<DisplayObservationSummary>,
): PickOption[] {
  return observations.map((observation) => {
    const reason = blocked?.(observation);
    return {
      value: observation.observationId,
      search: `${observation.evidenceReference} ${observation.observationId}`,
      ...(reason === undefined ? {} : { disabled: true }),
      label: (
        <Flex gap={6} align="center" wrap={false} style={{ minWidth: 0 }}>
          <When value={observation.observedAt} />
          <Code family="displayState" code={observation.displayState} />
          <Code family="factSourceKind" code={observation.sourceKind} />
          {reason === undefined ? (
            recordedBy(observation, excluded) && <NotIndependent />
          ) : (
            <Blocked reason={reason} />
          )}
          <Minor>{observation.evidenceReference}</Minor>
        </Flex>
      ),
    };
  });
}

/**
 * Promotion observations, with an optional verdict per observation; an
 * observation the caller blocks is disabled with its reason.
 */
export function promotionOptions(
  observations: readonly PromotionObservationSummary[],
  excluded: ReadonlySet<string>,
  verdict?: (observation: PromotionObservationSummary) => ReactNode,
  blocked?: EvidenceBlock<PromotionObservationSummary>,
): PickOption[] {
  return observations.map((observation) => {
    const reason = blocked?.(observation);
    return {
      value: observation.observationId,
      search: `${observation.nativePromotionKey} ${observation.evidenceReference} ${observation.observationId}`,
      ...(reason === undefined ? {} : { disabled: true }),
      label: (
        <Flex gap={6} align="center" wrap={false} style={{ minWidth: 0 }}>
          <When value={observation.observedAt} />
          <Code family="contextCoverage" code={observation.contextCoverage} />
          <Tag
            color={observation.independentCurrent ? 'success' : 'default'}
            style={{ marginInlineEnd: 0 }}
          >
            {observation.independentCurrent ? pickerText.current : pickerText.notCurrent}
          </Tag>
          {reason === undefined ? (
            recordedBy(observation, excluded) && <NotIndependent />
          ) : (
            <Blocked reason={reason} />
          )}
          {verdict?.(observation)}
          <Minor>{observation.nativePromotionKey}</Minor>
        </Flex>
      ),
    };
  });
}

/**
 * Open a record by its identifier, for when the list that would offer it
 * cannot be read. The identifier is checked before anything is opened.
 */
export function IdLookup({
  label,
  title,
  fieldLabel,
  initial,
  onOpenId,
}: {
  readonly label: string;
  readonly title: string;
  readonly fieldLabel: string;
  readonly initial: string | undefined;
  readonly onOpenId: (id: string) => void;
}): React.JSX.Element {
  return (
    <ActionModal<{ id?: string }>
      trigger={{ label }}
      title={title}
      okText={actions.view}
      {...(initial === undefined ? {} : { initialValues: { id: initial } })}
      onSubmit={(values) => {
        onOpenId((values.id ?? '').trim());
        return Promise.resolve(undefined);
      }}
    >
      <Form.Item name="id" label={fieldLabel} rules={idRules(fieldLabel)}>
        <Input autoFocus placeholder={pickerText.typeId} />
      </Form.Item>
    </ActionModal>
  );
}
