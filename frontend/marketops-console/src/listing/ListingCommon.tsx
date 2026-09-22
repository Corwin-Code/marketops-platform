import type { ConsoleFailure } from '../api/console';
import { labelFor, useLanguage } from './i18n/language';
import { t } from './i18n/ui';

/** One failure, said in the operator's language and with the backend's own detail. */
export function ListingProblem({
  failure,
}: {
  readonly failure: ConsoleFailure;
}): React.JSX.Element {
  const { language } = useLanguage();
  const detail = ((): string => {
    switch (failure.kind) {
      case 'unauthenticated':
        return t('failureUnauthenticated', language);
      case 'step-up-required':
        return t('failureStepUp', language);
      case 'forbidden':
        return t('failureForbidden', language);
      case 'refused':
        return `${t('failureRefused', language)}: ${String(failure.status)} ${failure.detail}`;
      case 'unreachable':
        return `${t('failureUnreachable', language)}: ${failure.detail}`;
      case 'malformed':
        return `${t('failureMalformed', language)}: ${failure.detail}`;
    }
  })();
  return (
    <p role="alert" data-failure={failure.kind}>
      {t('failure', language)} — {detail}
    </p>
  );
}

/** A backend code, shown by its label with the code itself as the title. */
export function Code({
  family,
  code,
}: {
  readonly family: string;
  readonly code: string | null | undefined;
}): React.JSX.Element {
  const { language } = useLanguage();
  return (
    <span data-family={family} data-code={code ?? ''} title={code ?? ''}>
      {labelFor(family, code, language)}
    </span>
  );
}

/** A yes / no / undeclared answer. */
export function YesNo({ value }: { readonly value: boolean | undefined }): React.JSX.Element {
  const { language } = useLanguage();
  return (
    <span>
      {value === undefined
        ? t('undeclared', language)
        : value
          ? t('yes', language)
          : t('no', language)}
    </span>
  );
}

/** A timestamp as the backend sent it; the moment is the fact, the format is not. */
export function When({ value }: { readonly value: string | undefined }): React.JSX.Element {
  return <time dateTime={value ?? ''}>{value ?? '—'}</time>;
}
