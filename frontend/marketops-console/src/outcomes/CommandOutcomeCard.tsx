import { Flex, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import type { ConsoleRequest } from '../api/console';
import type { CommandOutcome } from '../api/outcomes';
import { fetchCommandOutcome } from '../api/outcomes';
import { outcomesText as text } from '../i18n/zh/outcomes';
import { ROUTES } from '../layout/navigation';
import { SectionCard } from '../ui';
import { ReadingDetails, progressText } from './OutcomeParts';

/** The effect of one price command, once it succeeded and is followed (P10). */
export function CommandOutcomeCard({
  context,
  commandId,
}: {
  readonly context: ConsoleRequest;
  readonly commandId: string;
}): React.JSX.Element | null {
  const [outcome, setOutcome] = useState<CommandOutcome | undefined>(undefined);

  useEffect(() => {
    let live = true;
    void fetchCommandOutcome(context, commandId).then((answer) => {
      if (live && answer.ok) setOutcome(answer.value);
    });
    return () => {
      live = false;
    };
  }, [context, commandId]);

  if (outcome === undefined) return null;
  const action = outcome.action;
  return (
    <SectionCard
      title={text.commandTitle}
      extra={<Link to={ROUTES.pricingOutcomes}>{text.openOutcomes}</Link>}
    >
      {action === null ? (
        <Typography.Text type="secondary">{text.commandNotFollowed}</Typography.Text>
      ) : (
        <Flex vertical gap={12}>
          <Typography.Text>{progressText(action)}</Typography.Text>
          {[action.finalReading, action.preliminary]
            .filter((reading) => reading !== null)
            .map((reading) => (
              <ReadingDetails
                key={reading.stage}
                reading={reading}
                currencyCode={action.currencyCode}
              />
            ))}
        </Flex>
      )}
    </SectionCard>
  );
}
