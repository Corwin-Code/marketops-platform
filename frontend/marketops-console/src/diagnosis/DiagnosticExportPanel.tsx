import { useEffect, useRef, useState } from 'react';
import type { ConsoleRequest } from '../api/console';
import {
  downloadDiagnosticExport,
  fetchDiagnosticExport,
  submitDiagnosticExport,
} from '../api/diagnosticExport';
import type { ExportJob } from '../api/diagnosticExport';

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
    setMessage('Queuing the export…');
    const result = await submitDiagnosticExport(context, storeId, key.current, controller.signal);
    if (controller.signal.aborted) return;
    setBusy(false);
    if (result.ok) {
      setJob(result.value);
      setMessage('Export queued.');
    } else {
      setFailed(true);
      setMessage('Export request failed. Retry uses the same request key.');
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
              ? 'Export ready. Download expires after one hour.'
              : result.value.state === 'FAILED'
                ? 'Export failed. No downloadable file was published.'
                : result.value.state === 'EXPIRED'
                  ? 'Export expired.'
                  : 'Preparing the export…',
          );
        } else {
          setFailed(true);
          setMessage('Cannot read export status. Check access and refresh status.');
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
      setMessage(`Export state: ${result.value.state}.`);
    } else {
      setFailed(true);
      setMessage('Cannot read export status. Check access and refresh status.');
    }
  };

  const download = async (): Promise<void> => {
    if (job === undefined) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setFailed(false);
    setMessage('Verifying export parts…');
    const result = await downloadDiagnosticExport(context, job, controller.signal, (parts) => {
      if (!controller.signal.aborted)
        setMessage(`Verified ${String(parts)} of ${String(job.completedParts)} parts.`);
    });
    if (controller.signal.aborted) return;
    setBusy(false);
    if (!result.ok) {
      setFailed(true);
      setMessage('Download refused or incomplete. No file was saved.');
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
    setMessage('Verified export sent to the browser download manager.');
  };

  return (
    <section aria-label="Diagnostic export">
      <h2>Export store diagnosis</h2>
      <p>
        Last 30 days: current store and listing metrics, findings and evidence references. Prepared
        in the background.
      </p>
      {job === undefined && (
        <button
          type="button"
          disabled={busy}
          onClick={() => {
            void begin();
          }}
        >
          Prepare export
        </button>
      )}
      {job !== undefined && (
        <p>
          Export state: {job.state}. Records: {String(job.rowCount)}.
        </p>
      )}
      {job?.state === 'SUCCEEDED' && (
        <button
          type="button"
          disabled={busy}
          onClick={() => {
            void download();
          }}
        >
          Download verified export
        </button>
      )}
      {job !== undefined && failed && (
        <button
          type="button"
          disabled={busy}
          onClick={() => {
            void refresh();
          }}
        >
          Refresh export status
        </button>
      )}
      {(job?.state === 'FAILED' || job?.state === 'EXPIRED') && (
        <button
          type="button"
          onClick={() => {
            key.current = undefined;
            setJob(undefined);
            setMessage('');
            setFailed(false);
          }}
        >
          New export
        </button>
      )}
      {message !== '' && <p role={failed ? 'alert' : 'status'}>{message}</p>}
      {busy && (
        <button
          type="button"
          onClick={() => {
            request.current?.abort();
            setBusy(false);
            setMessage('Stopped waiting. Background work may continue.');
          }}
        >
          Stop waiting
        </button>
      )}
    </section>
  );
}
