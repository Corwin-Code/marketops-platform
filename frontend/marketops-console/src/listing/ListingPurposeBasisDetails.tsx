import { Typography } from 'antd';
import type { ListingPurposeBasis } from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { Details, SubTitle, When } from './ListingCommon';

function Lines({ values }: { readonly values: readonly string[] }): React.JSX.Element {
  return (
    <ul style={{ margin: 0, paddingInlineStart: 18 }}>
      {values.map((value, index) => (
        <li key={index}>
          <Typography.Text>{value}</Typography.Text>
        </li>
      ))}
    </ul>
  );
}

/**
 * The evidence a purpose rests on and the conditions for using it. Pass
 * `heading={false}` where the surrounding section already names it.
 */
export function ListingPurposeBasisDetails({
  basis,
  heading = true,
}: {
  readonly basis: ListingPurposeBasis;
  readonly heading?: boolean;
}): React.JSX.Element {
  return (
    <section aria-label={t('purposeBasis')}>
      {heading && <SubTitle>{t('purposeBasis')}</SubTitle>}
      <Details
        column={{ xs: 1, md: 2 }}
        items={[
          {
            key: 'evidence',
            label: t('purposeEvidence'),
            children: <Typography.Text copyable>{basis.evidenceReference}</Typography.Text>,
          },
          {
            key: 'until',
            label: t('purposeUseUntil'),
            children: <When value={basis.useUntil} />,
          },
          {
            key: 'use',
            label: t('purposeUseConditions'),
            span: 'filled',
            children: <Lines values={basis.useConditions} />,
          },
          {
            key: 'end',
            label: t('purposeEndConditions'),
            span: 'filled',
            children: <Lines values={basis.endConditions} />,
          },
        ]}
      />
    </section>
  );
}
