import { Alert, Button, Descriptions, Progress, Space, Steps, Typography } from 'antd';
import type { StepsProps } from 'antd';
import { DownloadOutlined, ExportOutlined, ReloadOutlined } from '@ant-design/icons';
import { useEffect, useRef, useState } from 'react';
import type { ConsoleRequest } from '../api/console';
import {
  downloadDiagnosticExport,
  fetchDiagnosticExport,
  submitDiagnosticExport,
} from '../api/diagnosticExport';
import type { ExportJob } from '../api/diagnosticExport';
import { codeLabel } from '../i18n';
import {
  EXPORT_FAILURE_LABELS,
  EXPORT_STATE_COLORS,
  EXPORT_STATE_LABELS,
  EXPORT_WINDOW_LABELS,
} from '../i18n/zh/pricing';
import { CodeTag, DateTime, SectionCard } from '../ui';

/** Where the job is on its way to a downloadable file. */
function exportSteps(job: ExportJob | undefined): {
  readonly current: number;
  readonly status: NonNullable<StepsProps['status']>;
} {
  switch (job?.state) {
    case undefined:
      return { current: 0, status: 'wait' };
    case 'QUEUED':
      return { current: 1, status: 'process' };
    case 'RUNNING':
      return { current: 2, status: 'process' };
    case 'SUCCEEDED':
      return { current: 3, status: 'finish' };
    case 'FAILED':
      return { current: 2, status: 'error' };
    case 'EXPIRED':
      return { current: 3, status: 'error' };
  }
}

/** Asynchronous store export; errors never produce a partially downloaded file. */
export function DiagnosticExportPanel({
  context,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}): React.JSX.Element {
  const [job, setJob] = useState<ExportJob>();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [failed, setFailed] = useState(false);
  const [verifiedParts, setVerifiedParts] = useState<number>();
  const key = useRef<string | undefined>(undefined);
  const request = useRef<AbortController | undefined>(undefined);
  useEffect(
    () => () => {
      request.current?.abort();
    },
    [context, storeId],
  );

  const begin = async (): Promise<void> => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    key.current ??= crypto.randomUUID();
    setBusy(true);
    setFailed(false);
    setMessage('正在提交导出请求…');
    const result = await submitDiagnosticExport(context, storeId, key.current, controller.signal);
    if (controller.signal.aborted) return;
    setBusy(false);
    if (result.ok) {
      setJob(result.value);
      setMessage('导出已排队。');
    } else {
      setFailed(true);
      setMessage('导出请求失败。重试会沿用同一个请求编号，不会重复导出。');
    }
  };

  useEffect(() => {
    if (job === undefined || !['QUEUED', 'RUNNING'].includes(job.state)) return;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      void fetchDiagnosticExport(context, job.id, controller.signal).then((result) => {
        if (controller.signal.aborted) return;
        if (result.ok && result.value.storeId === storeId) {
          setJob(result.value);
          setFailed(false);
          setMessage(
            result.value.state === 'SUCCEEDED'
              ? '导出已就绪，下载链接一小时后失效。'
              : result.value.state === 'FAILED'
                ? '导出失败，没有生成可下载的文件。'
                : result.value.state === 'EXPIRED'
                  ? '导出已过期。'
                  : '正在生成导出…',
          );
        } else {
          setFailed(true);
          setMessage('无法读取导出状态，请检查权限后刷新状态。');
        }
      });
    }, 2000);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [context, job, storeId]);

  const refresh = async (): Promise<void> => {
    if (job === undefined) return;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    const result = await fetchDiagnosticExport(context, job.id, controller.signal);
    if (controller.signal.aborted) return;
    setBusy(false);
    if (result.ok && result.value.storeId === storeId) {
      setJob(result.value);
      setFailed(false);
      setMessage(`导出状态：${codeLabel(EXPORT_STATE_LABELS, result.value.state)}。`);
    } else {
      setFailed(true);
      setMessage('无法读取导出状态，请检查权限后刷新状态。');
    }
  };

  const download = async (): Promise<void> => {
    if (job === undefined) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setFailed(false);
    setVerifiedParts(0);
    setMessage('正在校验导出分片…');
    const result = await downloadDiagnosticExport(context, job, controller.signal, (parts) => {
      if (!controller.signal.aborted) {
        setVerifiedParts(parts);
        setMessage(`已校验 ${String(parts)} / ${String(job.completedParts)} 个分片。`);
      }
    });
    if (controller.signal.aborted) return;
    setBusy(false);
    if (!result.ok) {
      setFailed(true);
      setVerifiedParts(undefined);
      setMessage('下载被拒绝或不完整，未保存任何文件。');
      return;
    }
    const url = URL.createObjectURL(result.value);
    const link = document.createElement('a');
    link.href = url;
    link.download = `diagnostic-${job.id}.ndjson`;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => {
      URL.revokeObjectURL(url);
    }, 1000);
    setMessage('已校验的导出文件已交给浏览器下载。');
  };

  const steps = exportSteps(job);
  const percent =
    verifiedParts === undefined || job === undefined || job.completedParts === 0
      ? undefined
      : Math.min(100, Math.round((verifiedParts / job.completedParts) * 100));

  const actionsBar = (
    <Space wrap>
      {job === undefined && (
        <Button
          type="primary"
          icon={<ExportOutlined />}
          loading={busy}
          disabled={busy}
          onClick={() => {
            void begin();
          }}
        >
          {failed ? '重试导出' : '准备导出'}
        </Button>
      )}
      {job?.state === 'SUCCEEDED' && (
        <Button
          type="primary"
          icon={<DownloadOutlined />}
          loading={busy}
          disabled={busy}
          onClick={() => {
            void download();
          }}
        >
          下载已校验的导出
        </Button>
      )}
      {job !== undefined && failed && (
        <Button
          icon={<ReloadOutlined />}
          disabled={busy}
          onClick={() => {
            void refresh();
          }}
        >
          刷新导出状态
        </Button>
      )}
      {(job?.state === 'FAILED' || job?.state === 'EXPIRED') && (
        <Button
          onClick={() => {
            key.current = undefined;
            setJob(undefined);
            setMessage('');
            setFailed(false);
            setVerifiedParts(undefined);
          }}
        >
          新建导出
        </Button>
      )}
      {busy && (
        <Button
          type="text"
          onClick={() => {
            request.current?.abort();
            setBusy(false);
            setMessage('已停止等待，后台任务可能仍在继续。');
          }}
        >
          停止等待
        </Button>
      )}
    </Space>
  );

  return (
    <section aria-label="诊断导出" data-state={job?.state ?? 'idle'}>
      <SectionCard title="导出店铺诊断" extra={actionsBar}>
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Text type="secondary">
            导出近 30 天的店铺与商品指标、规则结论和证据引用，在后台生成。
          </Typography.Text>
          <Steps
            size="small"
            current={steps.current}
            status={steps.status}
            items={[
              { title: '提交请求' },
              { title: '排队' },
              { title: '生成' },
              { title: job?.state === 'EXPIRED' ? '已过期' : '可下载' },
            ]}
          />
          {job !== undefined && (
            <Descriptions
              bordered
              size="small"
              column={{ xs: 1, md: 2, xl: 3 }}
              items={[
                {
                  key: 'state',
                  label: '导出状态',
                  children: (
                    <CodeTag
                      labels={EXPORT_STATE_LABELS}
                      code={job.state}
                      colors={EXPORT_STATE_COLORS}
                    />
                  ),
                },
                {
                  key: 'window',
                  label: '时间范围',
                  children: codeLabel(EXPORT_WINDOW_LABELS, job.window),
                },
                { key: 'rows', label: '记录数', children: String(job.rowCount) },
                { key: 'created', label: '创建时间', children: <DateTime value={job.createdAt} /> },
                {
                  key: 'snapshot',
                  label: '快照时间',
                  children: <DateTime value={job.snapshotAt} />,
                },
                { key: 'expires', label: '下载截止', children: <DateTime value={job.expiresAt} /> },
                ...(job.failureCode === null
                  ? []
                  : [
                      {
                        key: 'failure',
                        label: '失败原因',
                        children: (
                          <CodeTag
                            labels={EXPORT_FAILURE_LABELS}
                            code={job.failureCode}
                            colors={{ [job.failureCode]: 'error' }}
                          />
                        ),
                      },
                    ]),
              ]}
            />
          )}
          {percent !== undefined && (
            <Progress
              percent={percent}
              status={failed ? 'exception' : percent === 100 ? 'success' : 'active'}
              aria-label="分片校验进度"
            />
          )}
          {message !== '' && (
            <Alert
              type={failed ? 'error' : 'info'}
              showIcon
              role={failed ? 'alert' : 'status'}
              title={message}
            />
          )}
        </Space>
      </SectionCard>
    </section>
  );
}
