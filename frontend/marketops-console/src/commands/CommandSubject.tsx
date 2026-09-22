import { Flex, Typography } from 'antd';
import type { PriceCommand } from '../api/console';
import { commandText } from '../i18n/zh/pricingFollowUp';
import { SubjectName, subjectSubtitle } from '../ui';

/** Whether an identity carries a name an operator would recognise. */
function hasName(command: PriceCommand): boolean {
  const identity = command.identity;
  return [identity?.productName, identity?.variantName].some(
    (part) => part !== null && part !== undefined && part.trim() !== '',
  );
}

/** The product and variant a price command changes, as an operator knows it. */
export function commandSubjectName(command: PriceCommand): string {
  const identity = command.identity;
  if (identity !== undefined && hasName(command)) {
    return [identity.productName, identity.variantName]
      .filter((part): part is string => part !== null && part.trim() !== '')
      .filter((part, index, parts) => parts.indexOf(part) === index)
      .join(' · ');
  }
  return command.subjectId === undefined
    ? commandText.subjectMissing
    : `商品 ${command.subjectId.slice(0, 8)}`;
}

/**
 * The listing variant a price command changes.
 *
 * Either field may be missing while the backend catches up; the command's own
 * identifier is never shown in its place, because it names the command and an
 * operator would read it as the product.
 */
export function CommandSubject({
  command,
  strong = true,
}: {
  readonly command: PriceCommand;
  readonly strong?: boolean;
}): React.JSX.Element {
  if (command.subjectId !== undefined) {
    return (
      <SubjectName identity={command.identity} subjectId={command.subjectId} strong={strong} />
    );
  }
  const subtitle = subjectSubtitle(command.identity);
  return (
    <Flex vertical gap={0}>
      <Typography.Text
        strong={strong && hasName(command)}
        {...(hasName(command) ? {} : { type: 'secondary' as const })}
      >
        {commandSubjectName(command)}
      </Typography.Text>
      {subtitle !== undefined && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {subtitle}
        </Typography.Text>
      )}
    </Flex>
  );
}
