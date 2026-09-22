import type { ListingPurposeBasis } from '../api/listingConversion';
import { When } from './ListingCommon';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

export function ListingPurposeBasisDetails({
  basis,
}: {
  readonly basis: ListingPurposeBasis;
}): React.JSX.Element {
  const { language } = useLanguage();
  return (
    <section aria-label={t('purposeBasis', language)}>
      <h4>{t('purposeBasis', language)}</h4>
      <dl>
        <dt>{t('evidence', language)}</dt>
        <dd>{basis.evidenceReference}</dd>
        <dt>{t('purposeUseUntil', language)}</dt>
        <dd>
          <When value={basis.useUntil} />
        </dd>
      </dl>
      <h5>{t('purposeUseConditions', language)}</h5>
      <ul>
        {basis.useConditions.map((value, index) => (
          <li key={index}>{value}</li>
        ))}
      </ul>
      <h5>{t('purposeEndConditions', language)}</h5>
      <ul>
        {basis.endConditions.map((value, index) => (
          <li key={index}>{value}</li>
        ))}
      </ul>
    </section>
  );
}
