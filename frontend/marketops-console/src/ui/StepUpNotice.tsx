import { Alert, Button } from 'antd';
import type { ReactNode } from 'react';
import { stepUp } from '../i18n/zh/common';
import { useReauthenticate } from '../session/reauthenticate';

/**
 * A sensitive action needs a recent sign-in, and this one is too old.
 *
 * Shown before the operator fills anything in, with a way to sign in again
 * when the deployment has an identity provider, so nobody writes a reason only
 * to have the action refused. The server still decides: this notice is advice
 * read from the sign-in time the server reported.
 */
export function StepUpNotice({
  title = stepUp.title,
  description = stepUp.help,
}: {
  readonly title?: ReactNode;
  readonly description?: ReactNode;
}): React.JSX.Element {
  const reauthenticate = useReauthenticate();
  return (
    <Alert
      type="warning"
      showIcon
      title={title}
      description={description}
      data-state="step-up-required"
      {...(reauthenticate === undefined
        ? {}
        : {
            action: (
              <Button
                size="small"
                onClick={() => {
                  void reauthenticate().catch(() => undefined);
                }}
              >
                {stepUp.action}
              </Button>
            ),
          })}
    />
  );
}
