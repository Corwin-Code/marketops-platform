import type { ConsoleConfig } from '../config';
import { HealthShell } from '../health/HealthShell';
import { pageDescriptions, pages } from '../i18n/zh/shell';
import { Page } from './Page';

/** The platform-state panel, for a signed-in operator. */
export function SystemStatusPage({
  config,
  fetchImpl,
}: {
  readonly config: ConsoleConfig;
  readonly fetchImpl?: typeof fetch | undefined;
}): React.JSX.Element {
  return (
    <HealthShell
      config={config}
      variant="full"
      frame={(refresh, content) => (
        <Page
          title={pages.systemStatus}
          description={pageDescriptions.systemStatus}
          extra={refresh}
        >
          {content}
        </Page>
      )}
      {...(fetchImpl === undefined ? {} : { fetchImpl })}
    />
  );
}
