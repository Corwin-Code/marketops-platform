import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { recordNativeScope } from '../api/listingConversion';
import { ListingProblem } from './ListingCommon';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

/** Capture the source's actual enumeration; never populate it from the currently mapped subset. */
export function NativeScopeObservationForm({
  context,
  listingId,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
}): React.JSX.Element {
  const { language } = useLanguage();
  const form = useRef<HTMLFormElement>(null);
  const sequence = useRef(0);
  const [pending, setPending] = useState(false);
  const [receipt, setReceipt] = useState<string>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    sequence.current += 1;
    form.current?.reset();
    setReceipt(undefined);
    setFailure(undefined);
    setPending(false);
    return () => {
      sequence.current += 1;
    };
  }, [context, listingId]);
  return (
    <form
      ref={form}
      aria-label={t('nativeScopeCapture', language)}
      onSubmit={(event) => {
        event.preventDefault();
        const fields = new FormData(event.currentTarget);
        const value = (key: string) => {
          const field = fields.get(key);
          return typeof field === 'string' ? field : '';
        };
        const current = ++sequence.current;
        setReceipt(undefined);
        setFailure(undefined);
        setPending(true);
        void recordNativeScope(context, listingId, {
          scopeKind: value('scopeKind'),
          nativeScopeKey: value('nativeScopeKey'),
          nativeVariantKeys: value('members')
            .split(/\r?\n/)
            .filter((key) => key !== ''),
          coverageState: value('coverageState'),
          expectedMemberCount:
            value('expectedCount') === '' ? null : Number(value('expectedCount')),
          continuationReference: value('continuation') === '' ? null : value('continuation'),
          sourceReference: value('source'),
          scopeBasisReference: value('basis'),
          observedAt: new Date(value('observedAt')).toISOString(),
          verificationExpiresAt: new Date(value('expiresAt')).toISOString(),
        }).then((outcome) => {
          if (current !== sequence.current) return;
          setPending(false);
          if (outcome.ok) setReceipt(outcome.value);
          else setFailure(outcome.failure);
        });
      }}
    >
      <h4>{t('nativeScopeCapture', language)}</h4>
      <p>{t('nativeScopeExplanation', language)}</p>
      <fieldset disabled={pending}>
        <label>
          {t('nativeScopeKind', language)}
          <select name="scopeKind" required defaultValue="">
            <option value="">—</option>
            <option value="WHOLE_LISTING">{t('nativeWholeListing', language)}</option>
            <option value="NATIVE_VARIANT">{t('nativeSingleVariant', language)}</option>
          </select>
        </label>
        <label>
          {t('nativeScopeKey', language)}
          <input name="nativeScopeKey" required maxLength={512} />
        </label>
        <label>
          {t('nativeMemberKeys', language)}
          <textarea name="members" maxLength={2101248} />
        </label>
        <label>
          {t('nativeCoverage', language)}
          <select name="coverageState" required defaultValue="">
            <option value="">—</option>
            <option value="COMPLETE">{t('nativeComplete', language)}</option>
            <option value="PARTIAL">{t('nativePartial', language)}</option>
            <option value="UNKNOWN">{t('nativeUnknown', language)}</option>
          </select>
        </label>
        <label>
          {t('nativeExpectedCount', language)}
          <input name="expectedCount" type="number" min={0} max={4096} step={1} />
        </label>
        <label>
          {t('nativeContinuation', language)}
          <input name="continuation" maxLength={512} />
        </label>
        <label>
          {t('nativeScopeSource', language)}
          <input name="source" required maxLength={512} />
        </label>
        <label>
          {t('nativeScopeBasis', language)}
          <input name="basis" required maxLength={512} />
        </label>
        <label>
          {t('nativeScopeObserved', language)}
          <input name="observedAt" type="datetime-local" required />
        </label>
        <label>
          {t('nativeScopeExpires', language)}
          <input name="expiresAt" type="datetime-local" required />
        </label>
        <button type="submit">{t('nativeScopeCapture', language)}</button>
      </fieldset>
      {failure !== undefined && <ListingProblem failure={failure} />}
      {receipt !== undefined && (
        <p role="status">
          {t('nativeScopeSaved', language)} {receipt}
        </p>
      )}
    </form>
  );
}
