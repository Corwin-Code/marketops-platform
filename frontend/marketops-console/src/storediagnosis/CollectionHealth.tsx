import { Alert, Button, Flex } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import type { ConsoleRequest } from '../api/console';
import type { CollectionJob, DataCollection } from '../api/dataCollection';
import { fetchDataCollection } from '../api/dataCollection';
import { codeLabel } from '../i18n';
import { DATASET_LABELS } from '../i18n/zh/dataCollection';
import { collectionHealthText as text } from '../i18n/zh/storeDiagnosis';
import { ROUTES } from '../layout/navigation';
import { DateTime } from '../ui';

/** How long before a credential or verification evidence lapses that the home page warns. */
const CREDENTIAL_WARNING_DAYS = 14;
const EVIDENCE_WARNING_DAYS = 7;
const DAY_MILLIS = 24 * 60 * 60 * 1000;

interface Problem {
  readonly key: string;
  readonly type: 'error' | 'warning';
  readonly message: React.ReactNode;
}

function label(job: CollectionJob): string {
  return codeLabel(DATASET_LABELS, job.datasetKind);
}

function within(instant: string, days: number, now: number): boolean {
  return new Date(instant).getTime() - now <= days * DAY_MILLIS;
}

/** What stops or will soon stop scheduled collection, most urgent first. */
function problems(data: DataCollection, now: number): Problem[] {
  if (data.policy === null || !data.schedulerEnabled) return [];
  const found: Problem[] = [];
  const blocked = data.jobs.filter((job) => job.liveRun?.state === 'BLOCKED');
  const refused = blocked.filter(
    (job) => job.liveRunLastAnswer === 'HTTP 401' || job.liveRunLastAnswer === 'HTTP 403',
  );
  if (refused.length > 0) {
    found.push({
      key: 'refused',
      type: 'error',
      message: text.keyRefused(refused.map(label).join('、'), refused[0]?.liveRunLastAnswer ?? ''),
    });
  }
  const otherBlocked = blocked.filter((job) => !refused.includes(job));
  if (otherBlocked.length > 0) {
    found.push({
      key: 'blocked',
      type: 'error',
      message: text.blocked(
        otherBlocked
          .map((job) =>
            job.liveRunLastAnswer === null
              ? label(job)
              : `${label(job)}（${job.liveRunLastAnswer}）`,
          )
          .join('、'),
      ),
    });
  }
  const unverified = data.jobs.filter((job) => job.evidenceValidUntil === null);
  if (unverified.length > 0) {
    found.push({
      key: 'unverified',
      type: 'error',
      message: text.evidenceLapsed(unverified.map(label).join('、')),
    });
  }
  const expiring = data.jobs.filter(
    (job) =>
      job.evidenceValidUntil !== null && within(job.evidenceValidUntil, EVIDENCE_WARNING_DAYS, now),
  );
  const firstExpiring = expiring
    .map((job) => job.evidenceValidUntil ?? '')
    .sort()
    .at(0);
  if (firstExpiring !== undefined) {
    found.push({
      key: 'expiring',
      type: 'warning',
      message: (
        <>
          {text.evidenceExpiring(expiring.length)}
          <DateTime value={firstExpiring} />
        </>
      ),
    });
  }
  if (data.credentialExpiresAt === null) {
    found.push({ key: 'credential', type: 'error', message: text.credentialMissing });
  } else if (within(data.credentialExpiresAt, CREDENTIAL_WARNING_DAYS, now)) {
    found.push({
      key: 'credential',
      type: 'warning',
      message: (
        <>
          {text.credentialExpiring}
          <DateTime value={data.credentialExpiresAt} />
        </>
      ),
    });
  }
  return found;
}

/**
 * What stops scheduled collection, shown above the store diagnosis so that a withdrawn key or lapsed
 * evidence is noticed the day it happens rather than when the numbers go stale. Silent when all is
 * well or when the page cannot tell.
 */
export function CollectionHealth({
  context,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}): React.JSX.Element | null {
  const navigate = useNavigate();
  const [found, setFound] = useState<readonly Problem[]>([]);
  useEffect(() => {
    let live = true;
    void fetchDataCollection(context, storeId).then((outcome) => {
      if (live && outcome.ok) setFound(problems(outcome.value, Date.now()));
    });
    return () => {
      live = false;
    };
  }, [context, storeId]);

  if (found.length === 0) return null;
  const severe = found.some((problem) => problem.type === 'error');
  return (
    <Alert
      type={severe ? 'error' : 'warning'}
      showIcon
      title={severe ? text.titleStopped : text.titleAttention}
      description={
        <Flex vertical gap={4}>
          {found.map((problem) => (
            <span key={problem.key}>{problem.message}</span>
          ))}
        </Flex>
      }
      action={
        <Button
          size="small"
          onClick={() => {
            void navigate(ROUTES.dataCollection);
          }}
        >
          {text.open}
        </Button>
      }
    />
  );
}
