import { ReloadOutlined } from '@ant-design/icons';
import { Button, Flex, Segmented, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { AvailabilityCase, CaseView, Page } from '../api/availability';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { fetchAvailabilityCases } from '../api/availability';
import { actions, codeLabel } from '../i18n';
import {
  CASE_VIEW_LABELS,
  LANE_LABELS,
  ROLE_LABELS,
  availabilityText,
} from '../i18n/zh/availability';
import { caseActionText, caseListText as text, clockText } from '../i18n/zh/availabilityCases';
import {
  CodeTag,
  EmptyState,
  FailureAlert,
  LoadingState,
  SectionCard,
  VariantName,
  usePageParam,
  useSearchParam,
  useSearchParamsPatch,
} from '../ui';
import { AvailabilityCaseDrawer } from './AvailabilityCaseDrawer';
import { EscalateModal, RecordActionModal } from './CaseActions';
import type { CaseAction } from './CaseActions';
import { CaseStateTag, DueClock, ExceptionTag } from './CaseCells';
import { presentCaseState } from './casePresentation';
import { ExceptionDecision } from './ExceptionDecision';
import { ExceptionRequestDrawer } from './ExceptionRequestDrawer';
import { causeLabel, subjectChannelLabel } from './riskPresentation';
import { LANE_COLORS } from './tagColors';

/** What the case panel needs in order to load itself. */
export interface AvailabilityCasesProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The instant the deadlines are judged against. */
  readonly now?: Date;
}

const VIEWS: readonly CaseView[] = ['LIVE', 'ESCALATED', 'EXCEPTION_PENDING', 'ALL'];
const PAGE_SIZE = 20;

/** Address-bar keys of the cases page. */
const VIEW_PARAM = 'view';
const PAGE_PARAM = 'page';
const VARIANT_PARAM = 'variant';
const CASE_PARAM = 'case';
const TAB_PARAM = 'ctab';
const EXCEPTION_PARAM = 'exception';

/** A view read from the address bar; anything else is the live work. */
function readView(raw: string | undefined): CaseView {
  return VIEWS.find((view) => view === raw) ?? 'LIVE';
}

type Cases =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly page: Page<AvailabilityCase> }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/** An action being taken, by whichever surface asked for it. */
interface Acting {
  readonly action: CaseAction;
  readonly governed: AvailabilityCase;
}

/**
 * Who owns each availability failure, and what they have actually done.
 *
 * The two clocks are shown separately because they are separate obligations:
 * an action recorded on time and an outcome nobody ever verified is a specific
 * failure with a specific owner, and a combined "overdue" badge would hide it.
 * A case under a governed acceptance shows its paused clock, never "overdue".
 *
 * There is no acknowledgement control anywhere on this page. The action stage
 * takes a named action and the artefact behind it; escalating and asking for a
 * risk acceptance each say what they do and need a reason. Every row and the
 * detail offer exactly the actions the server offers this person.
 */
export function AvailabilityCases({
  context,
  now = new Date(),
}: AvailabilityCasesProps): React.JSX.Element {
  const [rawView] = useSearchParam(VIEW_PARAM);
  const [page, setPage] = usePageParam(PAGE_PARAM);
  const [variant] = useSearchParam(VARIANT_PARAM);
  const [caseId] = useSearchParam(CASE_PARAM);
  const [tab] = useSearchParam(TAB_PARAM);
  const [exceptionId] = useSearchParam(EXCEPTION_PARAM);
  const patch = useSearchParamsPatch();
  const view = readView(rawView);
  const [cases, setCases] = useState<Cases>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  const [fetching, setFetching] = useState(true);
  const [acting, setActing] = useState<Acting | undefined>(undefined);

  useEffect(() => {
    let live = true;
    setFetching(true);
    void fetchAvailabilityCases(context, {
      view,
      productVariantId: variant,
      limit: PAGE_SIZE,
      offset: (page - 1) * PAGE_SIZE,
    }).then((outcome) => {
      if (!live) return;
      setFetching(false);
      setCases(
        outcome.ok
          ? { kind: 'loaded', page: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, view, variant, page, generation]);

  const loaded = cases.kind === 'loaded' ? cases.page : undefined;
  // A page past the end goes back to the last page instead of an empty list.
  useEffect(() => {
    const total = loaded?.total ?? 0;
    if (loaded?.items.length === 0 && page > 1 && total > 0) {
      setPage(Math.max(1, Math.ceil(total / PAGE_SIZE)));
    }
  }, [loaded, page, setPage]);

  const changed = (): void => {
    setGeneration((value) => value + 1);
  };

  const act = (action: CaseAction, governed: AvailabilityCase): void => {
    setActing({ action, governed });
  };
  const done = (): void => {
    setActing(undefined);
    changed();
  };
  const stopActing = (): void => {
    setActing(undefined);
  };

  const open = (id: string): void => {
    patch({ [CASE_PARAM]: id, [TAB_PARAM]: undefined });
  };

  // The name of the filtered variant, read from any row that carries it.
  const filtered = loaded?.items.find(
    (item) => item.subject?.productVariantId === variant,
  )?.subject;

  const toolbar = (
    <Flex gap={8} wrap align="center" justify="flex-end">
      {variant === undefined ? null : (
        <Tag
          closable
          closeIcon
          onClose={() => {
            patch({ [VARIANT_PARAM]: undefined, [PAGE_PARAM]: undefined });
          }}
          aria-label={text.clearVariantFilter}
        >
          {text.variantFilter(filtered?.displayName ?? filtered?.skuCode ?? variant.slice(0, 8))}
        </Tag>
      )}
      <Segmented<string>
        aria-label={text.viewLabel}
        value={view}
        options={VIEWS.map((code) => ({ value: code, label: codeLabel(CASE_VIEW_LABELS, code) }))}
        onChange={(value) => {
          patch({ [VIEW_PARAM]: value === 'LIVE' ? undefined : value, [PAGE_PARAM]: undefined });
        }}
      />
      <Button icon={<ReloadOutlined />} aria-label={text.refreshLabel} onClick={changed}>
        {actions.refresh}
      </Button>
    </Flex>
  );

  // A session that has ended is one condition about the whole session, reported
  // once by the session surface rather than by every panel.
  if (cases.kind === 'failed' && cases.failure.kind === 'unauthenticated') {
    return (
      <section aria-label={availabilityText.casesTitle} data-state="signed-out">
        <SectionCard title={availabilityText.casesTitle} />
      </section>
    );
  }

  const total =
    loaded === undefined
      ? 0
      : (loaded.total ??
        (page - 1) * PAGE_SIZE + loaded.items.length + (loaded.items.length >= PAGE_SIZE ? 1 : 0));

  const columns: TableColumnsType<AvailabilityCase> = [
    {
      key: 'product',
      title: text.columnProduct,
      width: 240,
      render: (_, governed) =>
        governed.subject === undefined ? (
          <Typography.Text type="secondary">—</Typography.Text>
        ) : (
          <VariantName
            identity={governed.subject}
            productVariantId={governed.subject.productVariantId}
            extra={subjectChannelLabel(governed.subject)}
          />
        ),
    },
    {
      key: 'cause',
      title: text.columnCause,
      render: (_, governed) => causeLabel(governed.causeCode),
    },
    {
      key: 'severity',
      title: text.columnSeverity,
      render: (_, governed) => (
        <CodeTag labels={LANE_LABELS} code={governed.severity} colors={LANE_COLORS} />
      ),
    },
    {
      key: 'state',
      title: text.columnState,
      render: (_, governed) => <CaseStateTag governed={governed} />,
    },
    {
      key: 'role',
      title: text.columnRole,
      render: (_, governed) => <CodeTag labels={ROLE_LABELS} code={governed.accountableRoleCode} />,
    },
    {
      key: 'due',
      title: text.columnDue,
      render: (_, governed) => (
        <Flex vertical gap={4} align="flex-start">
          <Space size={4}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {clockText.action}
            </Typography.Text>
            <DueClock governed={governed} now={now} clock="action" />
          </Space>
          <Space size={4}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {clockText.outcome}
            </Typography.Text>
            <DueClock governed={governed} now={now} clock="outcome" />
          </Space>
        </Flex>
      ),
    },
    {
      key: 'counts',
      title: text.columnCounts,
      render: (_, governed) => (
        <Typography.Text
          {...(governed.reopenCount > 0 || governed.escalationLevel > 0
            ? { type: 'danger' as const }
            : { type: 'secondary' as const })}
          style={{ whiteSpace: 'nowrap' }}
        >
          {text.counts(governed.reopenCount, governed.escalationLevel)}
        </Typography.Text>
      ),
    },
    {
      key: 'exception',
      title: text.columnException,
      render: (_, governed) =>
        governed.openException === null ? (
          <Typography.Text type="secondary">—</Typography.Text>
        ) : (
          <span
            role="button"
            tabIndex={0}
            style={{ cursor: 'pointer' }}
            onClick={(event) => {
              event.stopPropagation();
              if (governed.openException !== null) {
                patch({ [EXCEPTION_PARAM]: governed.openException.id });
              }
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && governed.openException !== null) {
                event.stopPropagation();
                patch({ [EXCEPTION_PARAM]: governed.openException.id });
              }
            }}
          >
            <ExceptionTag open={governed.openException} />
          </span>
        ),
    },
    {
      key: 'actions',
      title: text.columnActions,
      render: (_, governed) => (
        <Space
          size={4}
          wrap
          onClick={(event) => {
            event.stopPropagation();
          }}
        >
          {ROW_ACTIONS.filter((action) => governed.allowedActions.includes(action)).map(
            (action) => (
              <Button
                key={action}
                size="small"
                {...(action === 'RECORD_ACTION' ? { type: 'primary' as const } : {})}
                onClick={() => {
                  act(action, governed);
                }}
              >
                {ROW_ACTION_LABELS[action]}
              </Button>
            ),
          )}
        </Space>
      ),
    },
  ];

  const empty =
    variant !== undefined
      ? text.emptyVariant
      : view === 'ESCALATED'
        ? text.emptyEscalated
        : view === 'EXCEPTION_PENDING'
          ? text.emptyPending
          : view === 'ALL'
            ? text.emptyAll
            : availabilityText.casesEmpty;

  return (
    <section aria-label={availabilityText.casesTitle} data-state={cases.kind}>
      <SectionCard title={availabilityText.casesTitle} extra={toolbar}>
        {cases.kind === 'failed' && <FailureAlert failure={cases.failure} />}
        {cases.kind === 'loading' && <LoadingState />}
        {loaded?.items.length === 0 && !(page > 1 && (loaded.total ?? 0) > 0) && (
          <EmptyState description={empty} />
        )}
        {loaded !== undefined && loaded.items.length > 0 && (
          <Table<AvailabilityCase>
            size="middle"
            rowKey="id"
            columns={columns}
            dataSource={[...loaded.items]}
            scroll={{ x: 'max-content' }}
            loading={fetching}
            pagination={{
              current: page,
              pageSize: PAGE_SIZE,
              total,
              showSizeChanger: false,
              hideOnSinglePage: true,
              showTotal: () =>
                loaded.total === undefined ? text.totalUnknown : text.total(loaded.total),
              onChange: (next) => {
                setPage(next);
              },
            }}
            onRow={(governed) =>
              ({
                'data-case-state': governed.state,
                'data-case-tone': presentCaseState(governed.state).tone,
                'data-severity': governed.severity,
                onClick: () => {
                  open(governed.id);
                },
                onKeyDown: (event: React.KeyboardEvent<HTMLElement>) => {
                  if (event.key === 'Enter' && event.target === event.currentTarget) {
                    open(governed.id);
                  }
                },
                tabIndex: 0,
                'aria-label': text.openCase(
                  governed.subject?.displayName ?? causeLabel(governed.causeCode),
                ),
                style: { cursor: 'pointer' },
              }) as React.HTMLAttributes<HTMLElement>
            }
          />
        )}
      </SectionCard>
      <AvailabilityCaseDrawer
        context={context}
        caseId={caseId}
        tab={tab}
        onTab={(next) => {
          patch({ [TAB_PARAM]: next === 'journal' ? undefined : next });
        }}
        onClose={() => {
          patch({ [CASE_PARAM]: undefined, [TAB_PARAM]: undefined });
        }}
        onAct={act}
        onOpenException={(id) => {
          patch({ [EXCEPTION_PARAM]: id });
        }}
        generation={generation}
        now={now}
      />
      <ExceptionDecision
        context={context}
        exceptionId={exceptionId}
        onClose={() => {
          patch({ [EXCEPTION_PARAM]: undefined });
        }}
        onChanged={changed}
      />
      <RecordActionModal
        context={context}
        governed={acting?.action === 'RECORD_ACTION' ? acting.governed : undefined}
        onClose={stopActing}
        onDone={done}
      />
      <EscalateModal
        context={context}
        governed={acting?.action === 'ESCALATE' ? acting.governed : undefined}
        onClose={stopActing}
        onDone={done}
      />
      <ExceptionRequestDrawer
        context={context}
        governed={acting?.action === 'REQUEST_EXCEPTION' ? acting.governed : undefined}
        onClose={stopActing}
        onDone={done}
      />
    </section>
  );
}

/** Row actions in the order they are offered, the usual next step first. */
const ROW_ACTIONS: readonly CaseAction[] = ['RECORD_ACTION', 'ESCALATE', 'REQUEST_EXCEPTION'];

const ROW_ACTION_LABELS: Readonly<Record<CaseAction, string>> = {
  RECORD_ACTION: caseActionText.recordAction,
  ESCALATE: caseActionText.escalate,
  REQUEST_EXCEPTION: caseActionText.requestException,
};
