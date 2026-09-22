import { Card, Divider, Flex, Result, Spin, Typography, theme } from 'antd';
import { useEffect } from 'react';
import type { ReactNode } from 'react';
import type { ConsoleConfig } from '../config';
import { HealthShell } from '../health/HealthShell';
import { product, shell, signIn as text } from '../i18n/zh/shell';
import type { OidcSettings } from '../session/oidc';
import { SignIn } from '../session/SignIn';

/** What the signed-out screen needs. */
export interface SignedOutScreenProps {
  readonly config: ConsoleConfig;
  readonly fetchImpl?: typeof fetch | undefined;
  /** Identity provider settings; absent when this deployment has none. */
  readonly settings?: OidcSettings | undefined;
  /** Why the previous sign-in did not produce a session. */
  readonly problem?: string | undefined;
  /** Whether the provider has just returned and the code is being redeemed. */
  readonly completing?: boolean;
  /** Whether the previous session ran out rather than being ended by the operator. */
  readonly expired?: boolean;
}

/** The centred card shown before the signed-in frame exists. */
export function CenteredCard({
  children,
  maxWidth = 480,
}: {
  readonly children: ReactNode;
  readonly maxWidth?: number;
}): React.JSX.Element {
  const { token } = theme.useToken();
  return (
    <main
      aria-labelledby="console-heading"
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 16,
        boxSizing: 'border-box',
        background: token.colorBgLayout,
      }}
    >
      <Card style={{ width: '100%', maxWidth }}>
        <Flex vertical gap={16}>
          <div style={{ textAlign: 'center' }}>
            <Typography.Title level={1} id="console-heading" style={{ fontSize: 24, margin: 0 }}>
              {product.name}
            </Typography.Title>
            <Typography.Text type="secondary">{product.tagline}</Typography.Text>
          </div>
          {children}
        </Flex>
      </Card>
    </main>
  );
}

/**
 * What an unauthenticated visitor sees.
 *
 * The platform-state panel is deliberately available without a session: it says
 * only what the platform is and whether it is up, which is what an operator
 * needs before they can tell a sign-in problem from an outage. Nothing about a
 * store, a listing or a price is shown here.
 */
export function SignedOutScreen({
  config,
  fetchImpl,
  settings,
  problem,
  completing = false,
  expired = false,
}: SignedOutScreenProps): React.JSX.Element {
  useEffect(() => {
    document.title = product.name;
  }, []);

  let entry: React.JSX.Element;
  if (settings === undefined) {
    entry = (
      <section aria-label={text.consoleRegion} data-state="not-configured">
        <Result
          status="warning"
          title={text.notConfiguredTitle}
          subTitle={text.notConfiguredDescription}
          style={{ padding: '8px 0' }}
        />
      </section>
    );
  } else if (completing) {
    entry = (
      <Flex justify="center" align="center" gap={12} data-state="completing" role="status">
        <Spin />
        <Typography.Text>{shell.completingSignIn}</Typography.Text>
      </Flex>
    );
  } else {
    entry = (
      <SignIn
        settings={settings}
        expired={expired}
        {...(problem === undefined ? {} : { problem })}
      />
    );
  }

  return (
    <CenteredCard>
      {entry}
      <Divider style={{ margin: 0 }} />
      <HealthShell config={config} {...(fetchImpl === undefined ? {} : { fetchImpl })} />
    </CenteredCard>
  );
}
