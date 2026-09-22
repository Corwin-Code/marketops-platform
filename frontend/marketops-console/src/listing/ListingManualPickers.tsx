import { Button, Flex, Form, Input, Select, Tag, Typography } from 'antd';
import type { FormRule } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { ConsoleOutcome, ConsoleRequest } from '../api/console';
import type {
  DescriptionObservationSummary,
  DisplayObservationSummary,
  ListingPersonRole,
  PromotionObservationSummary,
} from '../api/listingConversion';
import { fetchListingPeople } from '../api/listingConversion';
import { actions } from '../i18n/zh/common';
import { pickerText } from '../i18n/zh/listingManual';
import { ActionModal } from '../ui';
import { Code, InstantPicker, When } from './ListingCommon';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Whether a typed value is a well-formed identifier. */
export function isUuid(value: string | undefined): value is string {
  return value !== undefined && UUID.test(value.trim());
}

/** Form rules for an identifier that is picked or typed. */
export function idRules(label: string, required = true): FormRule[] {
  return [
    ...(required ? [{ required: true, whitespace: true, message: `请选择或填写${label}` }] : []),
    {
      validator: (_: unknown, value: string | undefined) =>
        value === undefined || value.trim() === '' || isUuid(value)
          ? Promise.resolve()
          : Promise.reject(new Error(pickerText.invalidId)),
    },
  ];
}

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

/** What a remote list read gives a picker. */
export interface Remote<T> {
  readonly value: T | undefined;
  readonly loading: boolean;
  readonly failed: boolean;
}

/**
 * One read for a picker, repeated whenever `key` changes. Without a key nothing
 * is read; a failed read is a fallback signal, never an error shown.
 */
export function useRemote<T>(
  key: string | undefined,
  load: () => Promise<ConsoleOutcome<T>>,
): Remote<T> {
  const loadRef = useRef(load);
  loadRef.current = load;
  const [state, setState] = useState<{
    readonly key: string | undefined;
    readonly value: T | undefined;
    readonly failed: boolean;
  }>({ key: undefined, value: undefined, failed: false });
  useEffect(() => {
    if (key === undefined) return;
    let live = true;
    void loadRef.current().then((outcome) => {
      if (!live) return;
      setState({ key, value: outcome.ok ? outcome.value : undefined, failed: !outcome.ok });
    });
    return () => {
      live = false;
    };
  }, [key]);
  const current = key !== undefined && state.key === key;
  return {
    value: current ? state.value : undefined,
    loading: key !== undefined && !current,
    failed: current && state.failed,
  };
}

/** One choice of a picker. */
export interface PickOption {
  readonly value: string;
  readonly label: ReactNode;
  /** Plain text the operator's search is matched against. */
  readonly search: string;
  readonly disabled?: boolean;
}

const MANUAL = '__manual__';

/**
 * Choose a record from a list, or type its identifier.
 *
 * The list is the normal way; typing is always offered as 「其他（手动输入）」
 * and becomes the only way when the list cannot be read, so an older backend
 * never blocks the task.
 */
export function PickOrType({
  value,
  onChange,
  id,
  options,
  loading,
  unavailable,
  disabled = false,
  placeholder,
}: {
  readonly value?: string | undefined;
  readonly onChange?: ((value: string | undefined) => void) | undefined;
  readonly id?: string | undefined;
  readonly options: readonly PickOption[];
  readonly loading: boolean;
  readonly unavailable: boolean;
  readonly disabled?: boolean;
  readonly placeholder?: string;
}): React.JSX.Element {
  const [manual, setManual] = useState(false);
  const known = options.some((option) => option.value === value);
  const empty = !loading && !unavailable && options.length === 0;
  const typing =
    !disabled &&
    (unavailable || empty || manual || (!loading && value !== undefined && value !== '' && !known));
  if (typing) {
    return (
      <Flex vertical gap={2}>
        <Input
          id={id}
          value={value ?? ''}
          placeholder={pickerText.typeId}
          allowClear
          onChange={(event) => {
            onChange?.(event.target.value === '' ? undefined : event.target.value);
          }}
        />
        {unavailable || empty ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {unavailable ? pickerText.listUnavailable : pickerText.listEmpty}
          </Typography.Text>
        ) : (
          <Button
            type="link"
            size="small"
            style={{ padding: 0, alignSelf: 'flex-start' }}
            onClick={() => {
              setManual(false);
              onChange?.(undefined);
            }}
          >
            {pickerText.backToList}
          </Button>
        )}
      </Flex>
    );
  }
  return (
    <Select<string>
      {...(id === undefined ? {} : { id })}
      value={value === undefined || value === '' ? null : value}
      loading={loading}
      disabled={disabled}
      allowClear
      placeholder={placeholder ?? pickerText.pick}
      showSearch={{ optionFilterProp: 'search' }}
      options={[
        ...options.map((option) => ({
          value: option.value,
          label: option.label,
          search: option.search,
          disabled: option.disabled === true,
        })),
        { value: MANUAL, label: pickerText.manualEntry, search: pickerText.manualEntry },
      ]}
      onChange={(next: string | undefined) => {
        if (next === MANUAL) {
          setManual(true);
          onChange?.(undefined);
        } else {
          onChange?.(next);
        }
      }}
    />
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
