import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Badge, Button, Flex, Space, Steps, Tabs, Typography } from 'antd';
import type { StepsProps } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { DescriptionCommand, ListingAction } from '../api/listingConversion';
import {
  fetchAction,
  fetchDescriptionCommand,
  fetchDescriptionGate,
} from '../api/listingConversion';
import { detailText } from '../i18n/zh/listingActions';
import { t } from '../i18n/zh/listing';
import {
  LoadingState,
  SectionCard,
  SectionCollapse,
  TechnicalDetails,
  useSearchParam,
} from '../ui';
import { ActionEvaluation, CommandTimeline } from './ListingActionEvidence';
import {
  Code,
  Codes,
  Details,
  IdText,
  ListingName,
  ListingProblem,
  RussianText,
  When,
  YesNo,
  codeText,
} from './ListingCommon';
import { DecisionCard } from './ListingDecisionActions';
import { PromotionDeclaration } from './ListingPromotionTerms';
import { ListingPurposeBasisDetails } from './ListingPurposeBasisDetails';
import { ListingResponsibility } from './ListingResponsibility';

/** The ordinary lifecycle of an action, as steps. */
const LIFECYCLE = ['DRAFT', 'REVIEWED', 'APPROVED', 'LAUNCHED', 'VERIFIED', 'CLOSED'] as const;

function lifecycle(state: string): { current: number; status: StepsProps['status'] } {
  switch (state) {
    case 'APPROVED_NOT_LAUNCHABLE':
      return { current: 2, status: 'error' };
    case 'CANCELLED':
    case 'CONTAINED':
      return { current: 0, status: 'error' };
    case 'CLOSED':
      return { current: LIFECYCLE.length - 1, status: 'finish' };
    default: {
      const index = LIFECYCLE.indexOf(state as (typeof LIFECYCLE)[number]);
      return { current: index < 0 ? 0 : index, status: 'process' };
    }
  }
}

/** The action's position in its lifecycle; a stopped action says so in red. */
function ActionLifecycle({ state }: { readonly state: string }): React.JSX.Element {
  const { current, status } = lifecycle(state);
  const stopped = state === 'CANCELLED' || state === 'CONTAINED';
  return (
    <div data-state={state}>
      {stopped && (
        <Alert
          style={{ marginBottom: 12 }}
          type="error"
          showIcon
          title={`${t('actionStopped')}：${codeText('actionState', state)}`}
        />
      )}
      <Steps
        size="small"
        current={current}
        {...(status === undefined ? {} : { status })}
        items={LIFECYCLE.map((step, index) => ({
          title:
            index === 2 && state === 'APPROVED_NOT_LAUNCHABLE'
              ? codeText('actionState', 'APPROVED_NOT_LAUNCHABLE')
              : codeText('actionState', step),
        }))}
      />
    </div>
  );
}

/** The description command of a launched API action, with its gate. */
type CommandState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure }
  | {
      readonly kind: 'loaded';
      readonly command: DescriptionCommand;
      readonly gate: readonly string[] | undefined;
      readonly gateFailure: ConsoleFailure | undefined;
    };

/** Reads the command and then its gate; a failed gate read stays a failure, never "open". */
function useDescriptionCommand(
  context: ConsoleRequest,
  actionId: string,
  enabled: boolean,
  generation: number,
): CommandState | undefined {
  const [state, setState] = useState<CommandState | undefined>(undefined);
  useEffect(() => {
    if (!enabled) {
      setState(undefined);
      return;
    }
    let active = true;
    setState({ kind: 'loading' });
    void fetchDescriptionCommand(context, actionId).then((outcome) => {
      if (!active) return;
      if (!outcome.ok) {
        setState({ kind: 'failed', failure: outcome.failure });
        return;
      }
      const command = outcome.value;
      setState({ kind: 'loaded', command, gate: undefined, gateFailure: undefined });
      void fetchDescriptionGate(context, command.id).then((gate) => {
        if (!active) return;
        setState({
          kind: 'loaded',
          command,
          gate: gate.ok ? gate.value : undefined,
          gateFailure: gate.ok ? undefined : gate.failure,
        });
      });
    });
    return () => {
      active = false;
    };
  }, [context, actionId, enabled, generation]);
  return state;
}

const TAB_PARAM = 'atab';

export interface ActionDetailProps {
  readonly context: ConsoleRequest;
  readonly actionId: string;
  readonly onBack: () => void;
}

/**
 * One exact action: where it stands, what it rests on, the decision its state
 * allows, and below, its responsibility clock, promotion terms, evaluation and
 * write command in tabs that load when opened.
 */
export function ActionDetail({ context, actionId, onBack }: ActionDetailProps): React.JSX.Element {
  const [action, setAction] = useState<ListingAction | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [rawTab, setTab] = useSearchParam(TAB_PARAM);

  useEffect(() => {
    let active = true;
    void fetchAction(context, actionId).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setAction(outcome.value);
        setFailure(undefined);
      } else {
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, actionId, generation]);

  const refresh = (): void => {
    setGeneration((value) => value + 1);
  };

  const commandShown = action?.executionPath === 'API' && action.launch !== undefined;
  const command = useDescriptionCommand(context, actionId, commandShown, generation);

  return (
    <section aria-label={t('actions')} data-action={actionId}>
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <div>
          <Button icon={<ArrowLeftOutlined />} onClick={onBack}>
            {detailText.back}
          </Button>
        </div>
        {failure !== undefined && <ListingProblem failure={failure} />}
        {action === undefined && failure === undefined && <LoadingState rows={8} />}
        {action !== undefined && (
          <>
            <ActionHeader action={action} onRefresh={refresh} />
            <DecisionCard context={context} action={action} onChanged={refresh} />
            <LowerTabs
              context={context}
              action={action}
              command={command}
              activeKey={rawTab}
              onTab={(key) => {
                setTab(key);
              }}
            />
          </>
        )}
      </Space>
    </section>
  );
}

/** The action's identity, lifecycle, summary and the material it rests on. */
function ActionHeader({
  action,
  onRefresh,
}: {
  readonly action: ListingAction;
  readonly onRefresh: () => void;
}): React.JSX.Element {
  return (
    <SectionCard
      title={
        <Flex gap={12} align="center" wrap>
          <ListingName
            nativeListingKey={action.nativeListingKey}
            listingId={action.platformListingId}
          />
          <Space size={4} wrap>
            <Code family="actionState" code={action.state} />
            <Code family="executionPath" code={action.executionPath} />
            <Code family="materialityRoute" code={action.materialityRoute} />
          </Space>
        </Flex>
      }
      extra={
        <Button icon={<ReloadOutlined />} onClick={onRefresh}>
          {t('refresh')}
        </Button>
      }
      state={action.state}
    >
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <ActionLifecycle state={action.state} />
        <Details
          items={[
            {
              key: 'kind',
              label: t('actionKind'),
              children: codeText('actionKind', action.actionKind),
            },
            {
              key: 'purpose',
              label: t('actionPurpose'),
              children:
                action.purposeCode === undefined ? (
                  '—'
                ) : (
                  <Code family="actionPurpose" code={action.purposeCode} />
                ),
            },
            {
              key: 'affected',
              label: t('affectedSetShort'),
              children: (
                <Space size={4}>
                  <Code family="affectedSetState" code={action.affectedSetState} />
                  <Typography.Text type="secondary">
                    {detailText.variants(action.affectedVariantCount)}
                  </Typography.Text>
                </Space>
              ),
            },
            {
              key: 'kiz',
              label: t('kiz'),
              children: <YesNo value={action.kizMarkedDeclared} />,
            },
            {
              key: 'content',
              label: t('contentAxis'),
              children: <YesNo value={action.contentAxisMaterial} />,
            },
            {
              key: 'exposure',
              label: t('exposureAxis'),
              children: <YesNo value={action.exposureAxisMaterial} />,
            },
            {
              key: 'reviews',
              label: t('reviewer'),
              children:
                action.reviews.length === 0 ? (
                  '—'
                ) : (
                  <Space orientation="vertical" size={2}>
                    {action.reviews.map((review) => (
                      <Space key={review.id} size={4} wrap>
                        <Code family="reviewVerdict" code={review.verdict} />
                        <When value={review.reviewedAt} />
                      </Space>
                    ))}
                  </Space>
                ),
            },
            {
              key: 'launch',
              label: t('launchedAt'),
              children: <When value={action.launch?.launchedAt} />,
            },
            { key: 'version', label: t('version'), children: action.version },
            ...(action.bindingGaps.length === 0
              ? []
              : [
                  {
                    key: 'gaps',
                    label: t('bindingGaps'),
                    span: 'filled' as const,
                    children: <Codes family="bindingGap" codes={action.bindingGaps} />,
                  },
                ]),
          ]}
        />
        {action.restoresCommandId !== undefined && (
          <Alert
            type="info"
            showIcon
            title={t('restorationApproval')}
            description={
              <IdText label={t('restoresCommandShort')} value={action.restoresCommandId} />
            }
          />
        )}
        {(action.purposeBasis !== undefined || action.targetText !== undefined) && (
          <SectionCollapse
            size="small"
            items={[
              ...(action.purposeBasis === undefined
                ? []
                : [
                    {
                      key: 'basis',
                      title: t('purposeBasis'),
                      summary: detailText.purposeBasisSummary,
                      defaultOpen: true,
                      children: (
                        <ListingPurposeBasisDetails basis={action.purposeBasis} heading={false} />
                      ),
                    },
                  ]),
              ...(action.targetText === undefined
                ? []
                : [
                    {
                      key: 'target',
                      title: t('targetText'),
                      summary: detailText.targetTextSummary,
                      children: <RussianText value={action.targetText} />,
                    },
                  ]),
            ]}
          />
        )}
        <TechnicalDetails>
          <Space orientation="vertical" size={2}>
            <IdText label={t('actionId')} value={action.id} />
            <IdText label={t('platformListingId')} value={action.platformListingId} />
            <IdText label={t('author')} value={action.authorUserId} />
            {action.reviews.map((review) => (
              <IdText key={review.id} label={t('reviewer')} value={review.reviewerUserId} />
            ))}
            <IdText label={t('affectedSet')} value={action.affectedSetDigest} />
            <IdText label={t('currentDigest')} value={action.currentTextDigest} />
            <IdText label={t('targetDigest')} value={action.targetTextDigest} />
            <IdText
              label={t('calibrationVersion')}
              value={`${action.calibrationPackageId ?? '—'} · ${
                action.calibrationVersion === undefined ? '—' : String(action.calibrationVersion)
              }`}
            />
          </Space>
        </TechnicalDetails>
      </Space>
    </SectionCard>
  );
}

/** The command tab's label: its state, and a dot when the gate blocks or is unknown. */
function CommandTabLabel({
  command,
}: {
  readonly command: CommandState | undefined;
}): React.JSX.Element {
  const loaded = command?.kind === 'loaded' ? command : undefined;
  const blocked = loaded?.gate !== undefined && loaded.gate.length > 0;
  const unknown = loaded?.gateFailure !== undefined || command?.kind === 'failed';
  const label = (
    <Space size={6}>
      <span>{detailText.tabCommand}</span>
      {loaded !== undefined && <Code family="commandState" code={loaded.command.state} />}
    </Space>
  );
  if (!blocked && !unknown) return label;
  return (
    <Badge
      dot
      color={blocked ? 'red' : 'orange'}
      offset={[4, 0]}
      title={blocked ? detailText.gateBlockedDot : detailText.commandUnknown}
    >
      {label}
    </Badge>
  );
}

/** Secondary material of the action, one tab at a time, each loading when opened. */
function LowerTabs({
  context,
  action,
  command,
  activeKey,
  onTab,
}: {
  readonly context: ConsoleRequest;
  readonly action: ListingAction;
  readonly command: CommandState | undefined;
  readonly activeKey: string | undefined;
  readonly onTab: (key: string | undefined) => void;
}): React.JSX.Element {
  const items = [
    {
      key: 'responsibility',
      label: detailText.tabResponsibility,
      children: <ListingResponsibility context={context} actionId={action.id} />,
    },
    ...(action.actionKind === 'LISTING_PROMOTION_ACTION'
      ? [
          {
            key: 'promotion',
            label: detailText.tabPromotion,
            children: (
              <PromotionDeclaration
                key={action.id}
                context={context}
                actionId={action.id}
                digest={action.promotionTermsDigest}
              />
            ),
          },
        ]
      : []),
    ...(action.purposeCode !== 'DESCRIPTION_CORRECTION' &&
    action.purposeCode !== 'BOUNDED_EXPLORATION'
      ? [
          {
            key: 'evaluation',
            label: detailText.tabEvaluation,
            children: <ActionEvaluation context={context} action={action} />,
          },
        ]
      : []),
    ...(command === undefined
      ? []
      : [
          {
            key: 'command',
            label: <CommandTabLabel command={command} />,
            children:
              command.kind === 'loading' ? (
                <LoadingState rows={4} />
              ) : command.kind === 'failed' ? (
                <ListingProblem failure={command.failure} />
              ) : (
                <CommandTimeline
                  command={command.command}
                  gate={command.gate}
                  gateFailure={command.gateFailure}
                />
              ),
          },
        ]),
  ];
  const first = items[0]?.key ?? 'responsibility';
  const active = items.some((item) => item.key === activeKey) ? (activeKey ?? first) : first;
  return (
    <SectionCard>
      <Tabs
        aria-label={detailText.lowerTabs}
        activeKey={active}
        onChange={(key) => {
          onTab(key === first ? undefined : key);
        }}
        items={items}
      />
    </SectionCard>
  );
}
