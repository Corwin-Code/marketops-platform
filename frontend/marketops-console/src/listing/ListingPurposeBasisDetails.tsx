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

export function ListingPurposeBasisDetails({
  basis,
}: {
  readonly basis: ListingPurposeBasis;
}): React.JSX.Element {
  return (
    <section aria-label={t('purposeBasis')}>
      <SubTitle>{t('purposeBasis')}</SubTitle>
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
