import { LoginOutlined } from '@ant-design/icons';
import { Alert, Button, Flex, Typography } from 'antd';
import { useState } from 'react';
import { signIn as text } from '../i18n/zh/shell';
import { beginSignIn } from './oidc';
import type { OidcSettings } from './oidc';

/** What the sign-in screen needs. */
export interface SignInProps {
  /** Where to send the operator and what to ask for. */
  readonly settings: OidcSettings;
  /** How to leave this page, replaced in tests. */
  readonly navigate?: (url: string) => void;
  /** Why the previous attempt did not produce a session, when there was one. */
  readonly problem?: string;
}

/**
 * The way in.
 *
 * Nothing about the operating data is shown beside it — not a store name, not a
 * count. A console that showed even the shape of that data to somebody who has
 * not signed in is telling them something.
 */
export function SignIn({ settings, navigate, problem }: SignInProps): React.JSX.Element {
  const [starting, setStarting] = useState(false);
  const [startFailed, setStartFailed] = useState(false);
  const go =
    navigate ??
    ((url: string) => {
      window.location.assign(url);
    });
  return (
    <section aria-label={text.region} data-state="signed-out">
      <Flex vertical gap={12}>
        {problem !== undefined && (
          <Alert type="error" showIcon title={problem} role="alert" data-testid="sign-in-problem" />
        )}
        {startFailed && <Alert type="error" showIcon title={text.startFailed} role="alert" />}
        <Typography.Text type="secondary">{text.explanation}</Typography.Text>
        <Button
          type="primary"
          size="large"
          block
          icon={<LoginOutlined />}
          loading={starting}
          onClick={() => {
            setStarting(true);
            setStartFailed(false);
            void beginSignIn(settings).then(go, () => {
              setStarting(false);
              setStartFailed(true);
            });
          }}
        >
          {text.button}
        </Button>
      </Flex>
    </section>
  );
}
