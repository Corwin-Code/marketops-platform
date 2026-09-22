import { Flex, Result, Typography } from 'antd';
import type { ConsoleConfigKey } from '../config';
import { configuration as text } from '../i18n/zh/shell';
import { CenteredCard } from './SignedOutScreen';

/**
 * Names missing settings without rendering or logging any configured value.
 *
 * Developer-facing: the keys are shown exactly as they are spelled in the
 * environment file so they can be copied.
 */
export function ConfigurationError({
  missingKeys,
}: {
  readonly missingKeys: readonly ConsoleConfigKey[];
}): React.JSX.Element {
  return (
    <CenteredCard maxWidth={560}>
      <section aria-label={text.region} data-state="configuration-error">
        <Result
          status="warning"
          title={text.title}
          subTitle={<span role="alert">{text.description}</span>}
          style={{ padding: '8px 0' }}
        >
          <Flex vertical gap={8}>
            <ul style={{ margin: 0, paddingInlineStart: 20 }}>
              {missingKeys.map((key) => (
                <li key={key}>
                  <Typography.Text code copyable>
                    {key}
                  </Typography.Text>
                </li>
              ))}
            </ul>
            <Typography.Text type="secondary">{text.hint}</Typography.Text>
          </Flex>
        </Result>
      </section>
    </CenteredCard>
  );
}
