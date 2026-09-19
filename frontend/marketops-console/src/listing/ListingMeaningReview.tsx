import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  fetchMeaningReviewBasis,
  reviewAction,
  type MeaningAssessment,
  type MeaningReviewBasis,
} from '../api/listingConversion';
import { ListingPurposeBasisDetails } from './ListingPurposeBasisDetails';
import { ListingProblem } from './ListingCommon';
import { PromotionTermsDetails } from './ListingPromotionTerms';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

export function ListingMeaningReview({
  context,
  actionId,
  reason,
  onOutcome,
  approvalMode = false,
  onMaterialLoaded,
}: {
  readonly context: ConsoleRequest;
  readonly actionId: string;
  readonly reason: string;
  readonly onOutcome: (outcome: {
    readonly ok: boolean;
    readonly failure?: ConsoleFailure;
  }) => void;
  readonly approvalMode?: boolean;
  readonly onMaterialLoaded?: (basis: MeaningReviewBasis | undefined) => void;
}): React.JSX.Element {
  const { language } = useLanguage();
  const epoch = useRef(0);
  const [basis, setBasis] = useState<MeaningReviewBasis>();
  const [answers, setAnswers] = useState<MeaningAssessment['answers']>([]);
  const [reference, setReference] = useState('');
  const [complete, setComplete] = useState(false);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    epoch.current += 1;
    setBasis(undefined);
    setAnswers([]);
    setReference('');
    setComplete(false);
    setPending(false);
    setFailure(undefined);
    onMaterialLoaded?.(undefined);
    return () => {
      epoch.current += 1;
    };
  }, [context, actionId, onMaterialLoaded]);
  const qualified = basis?.ruleState === 'QUALIFIED' && basis.conditions.length > 0;
  const ready =
    qualified &&
    complete &&
    reference.trim() !== '' &&
    reason.trim() !== '' &&
    answers.length === basis.conditions.length &&
    answers.every((a) => a.state !== 'UNKNOWN' && a.reason.trim() !== '');
  return (
    <section
      aria-label={
        approvalMode
          ? language === 'ru'
            ? 'Материалы окончательного решения'
            : '最终批准材料'
          : t('meaningLoad', language)
      }
    >
      <p>
        {approvalMode
          ? language === 'ru'
            ? 'До решения прочитайте точный замороженный объект, проверку и текущие защиты.'
            : '作出决定前，请读取准确冻结对象、审核证据与当前经营保护。'
          : t('meaningHelp', language)}
      </p>
      <button
        type="button"
        disabled={pending}
        onClick={() => {
          const ticket = ++epoch.current;
          setPending(true);
          setBasis(undefined);
          setAnswers([]);
          setComplete(false);
          setReference('');
          setFailure(undefined);
          onMaterialLoaded?.(undefined);
          void fetchMeaningReviewBasis(context, actionId).then((outcome) => {
            if (ticket !== epoch.current) return;
            setPending(false);
            if (outcome.ok) {
              setBasis(outcome.value);
              onMaterialLoaded?.(outcome.value);
              setAnswers(
                outcome.value.conditions.map((c) => ({
                  code: c.code,
                  state: 'UNKNOWN',
                  reason: '',
                })),
              );
            } else {
              setFailure(outcome.failure);
              onMaterialLoaded?.(undefined);
            }
          });
        }}
      >
        {approvalMode
          ? language === 'ru'
            ? 'Прочитать материалы окончательного решения'
            : '读取最终批准材料'
          : t('meaningLoad', language)}
      </button>
      {failure !== undefined && <ListingProblem failure={failure} />}
      {basis !== undefined && (
        <>
          {basis.purposeBasis !== undefined && (
            <ListingPurposeBasisDetails basis={basis.purposeBasis} />
          )}
          {basis.currentDescription !== undefined && (
            <section
              aria-label={language === 'ru' ? 'Замороженное исходное описание' : '冻结前值证据'}
            >
              <h4>{language === 'ru' ? 'Замороженное исходное описание' : '冻结前值证据'}</h4>
              <p>
                {basis.currentDescription.observationId} · {basis.currentDescription.textDigest} ·{' '}
                {basis.currentDescription.languageCode} · {basis.currentDescription.sourceKind}
              </p>
              <p>
                {basis.currentDescription.observedAt} · {basis.currentDescription.acquiredAt}
              </p>
              <p>
                KIZ:{' '}
                {basis.currentDescription.kizMarkedDeclared === undefined
                  ? '—'
                  : String(basis.currentDescription.kizMarkedDeclared)}
              </p>
            </section>
          )}
          <section aria-label={language === 'ru' ? 'Полная область воздействия' : '完整影响集合'}>
            <h4>{language === 'ru' ? 'Полная область воздействия' : '完整影响集合'}</h4>
            <p>
              {basis.affectedSet.affectedSetId} · {basis.affectedSet.digest} ·{' '}
              {basis.affectedSet.state}
            </p>
            <p>
              {language === 'ru' ? 'Наблюдение исходной области:' : '原生范围观测：'}{' '}
              {basis.affectedSet.nativeScopeObservationId ?? '—'}
            </p>
            <h5>{language === 'ru' ? 'Варианты карточки' : 'Listing Variant 成员'}</h5>
            <ul>
              {basis.affectedSet.listingVariantIds.map((id) => (
                <li key={id}>{id}</li>
              ))}
            </ul>
            <h5>{language === 'ru' ? 'Варианты товара' : 'Product Variant 成员'}</h5>
            <ul>
              {basis.affectedSet.productVariantIds.map((id) => (
                <li key={id}>{id}</li>
              ))}
            </ul>
            {basis.affectedSet.identityLineage !== undefined && (
              <details>
                <summary>
                  {language === 'ru' ? 'Замороженная цепочка идентичности' : '冻结身份链'}
                </summary>
                <pre>{basis.affectedSet.identityLineage}</pre>
              </details>
            )}
          </section>
          {basis.reviewEvidence !== undefined && (
            <section aria-label={language === 'ru' ? 'Независимая проверка' : '独立审核证据'}>
              <h4>{language === 'ru' ? 'Независимая проверка' : '独立审核证据'}</h4>
              <p>
                {basis.reviewEvidence.reviewerUserId} · {basis.reviewEvidence.verdict} ·{' '}
                {basis.reviewEvidence.reviewedAt}
              </p>
              <p>{basis.reviewEvidence.reason}</p>
              <p>
                {basis.reviewEvidence.factsDigest} ·{' '}
                {basis.reviewEvidence.evaluationPlanDigest ??
                  basis.reviewEvidence.purposeBasisDigest ??
                  '—'}
              </p>
              <p>
                {language === 'ru' ? 'Ось содержания / воздействия:' : '内容轴／暴露轴：'}{' '}
                {String(basis.reviewEvidence.contentAxisMaterial)} /{' '}
                {String(basis.reviewEvidence.exposureAxisMaterial)} ·{' '}
                {basis.reviewEvidence.materialityRoute ?? '—'}
              </p>
              {basis.reviewEvidence.meaningAssessment !== undefined && (
                <pre>{basis.reviewEvidence.meaningAssessment}</pre>
              )}
              {basis.reviewEvidence.exposureEvidence !== undefined && (
                <pre>{basis.reviewEvidence.exposureEvidence}</pre>
              )}
            </section>
          )}
          <EvidenceTable
            title={language === 'ru' ? 'Текущая калибровка' : '当前校准复核'}
            evidence={basis.calibrationEvidence}
          />
          <EvidenceTable
            title={language === 'ru' ? 'Текущая существенность' : '当前材料性复核'}
            evidence={basis.materialityEvidence}
          />
          <EvidenceTable
            title={language === 'ru' ? 'Текущая защита бизнеса' : '当前经营保护'}
            evidence={basis.businessProtectionEvidence}
          />
          <details>
            <summary>{language === 'ru' ? 'Основание полномочий' : '审批权威依据'}</summary>
            <pre>{basis.authorityDocument}</pre>
          </details>
          {basis.applicableExperience.length > 0 && (
            <section aria-label={language === 'ru' ? 'Применимый опыт' : '适用经验'}>
              <h4>{language === 'ru' ? 'Применимый опыт' : '适用经验'}</h4>
              <ul>
                {basis.applicableExperience.map((item) => (
                  <li key={item.experienceApplicationId}>
                    {item.sourceActionId} · {item.sourceResultId} · {item.sourceNodeCode} /{' '}
                    {item.sourceStage} · r{item.sourceRevision} ·{' '}
                    {item.applicabilityEvidenceReference}
                  </li>
                ))}
              </ul>
            </section>
          )}
          {basis.currentText !== undefined && (
            <>
              <h4>{t('meaningCurrent', language)}</h4>
              <pre lang="ru">{basis.currentText}</pre>
            </>
          )}
          {basis.targetText !== undefined && (
            <>
              <h4>{t('meaningTarget', language)}</h4>
              <pre lang="ru">{basis.targetText}</pre>
            </>
          )}
          {basis.promotionTerms !== undefined && (
            <PromotionTermsDetails terms={basis.promotionTerms} />
          )}
          {basis.selectedSimulation !== undefined && (
            <section aria-label={language === 'ru' ? 'Выбранный расчёт' : '选定模拟'}>
              <h4>{language === 'ru' ? 'Выбранный расчёт' : '选定模拟'}</h4>
              <p>
                {language === 'ru'
                  ? 'Условный расчёт не даёт допуска к запуску. Проверьте исходные данные и пробелы в доказательствах.'
                  : '条件计算不授予启动资格。请核对输入依据及尚未确定的条件。'}
              </p>
              <p>
                {basis.selectedSimulation.periodStart ?? '—'} —{' '}
                {basis.selectedSimulation.periodEnd ?? '—'} ·{' '}
                {basis.selectedSimulation.currency ?? '—'}
              </p>
              <table>
                <thead>
                  <tr>
                    {(language === 'ru'
                      ? ['Сценарий', 'Количество', 'Выручка', 'Прибыль', 'Отсутствующие данные']
                      : ['情景', '数量', '净收入', '贡献利润', '缺失输入']
                    ).map((label) => (
                      <th key={label}>{label}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {basis.selectedSimulation.scenarios.map((scenario) => (
                    <tr key={scenario.code}>
                      <td>{scenario.code}</td>
                      <td>{scenario.quantity ?? '—'}</td>
                      <td>{scenario.netRevenue ?? '—'}</td>
                      <td>{scenario.contributionProfit ?? '—'}</td>
                      <td>{scenario.missingInputs.join(', ') || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p>
                {language === 'ru'
                  ? 'Условие прибыли / минимальное количество:'
                  : '利润参考条件／最低数量：'}{' '}
                {basis.selectedSimulation.referenceProfitLine ?? '—'} /{' '}
                {basis.selectedSimulation.inverseMinimumQuantity ??
                  (basis.selectedSimulation.inverseState === 'NO_SOLUTION'
                    ? language === 'ru'
                      ? 'Нет решения в поддерживаемом диапазоне'
                      : '支持范围内无解'
                    : language === 'ru'
                      ? 'Не определено'
                      : '无法判定')}
              </p>
              <details>
                <summary>
                  {language === 'ru' ? 'Исходные допущения и доказательства' : '原始假设与证据'}
                </summary>
                <p>
                  {basis.selectedSimulation.computedAt} · {basis.selectedSimulation.id}
                </p>
                <pre>{basis.selectedSimulation.inputEvidence}</pre>
              </details>
            </section>
          )}
          {!qualified && <p>{t('meaningUnqualified', language)}</p>}
          {approvalMode && (
            <ul>
              {basis.conditions.map((condition) => (
                <li key={condition.code}>
                  {condition.code} · {condition.axis} · {condition.condition}
                </li>
              ))}
            </ul>
          )}
          {!approvalMode &&
            basis.conditions.map((condition, index) => (
              <fieldset key={condition.code} disabled={pending}>
                <legend>{condition.condition}</legend>
                <label>
                  {condition.code}
                  <select
                    value={answers[index]?.state ?? 'UNKNOWN'}
                    onChange={(event) => {
                      const state = event.target
                        .value as MeaningAssessment['answers'][number]['state'];
                      setAnswers(answers.map((a, i) => (i === index ? { ...a, state } : a)));
                    }}
                  >
                    <option value="UNKNOWN">{t('meaningUnknown', language)}</option>
                    <option value="APPLIES">{t('meaningApplies', language)}</option>
                    <option value="DOES_NOT_APPLY">{t('meaningNotApplies', language)}</option>
                  </select>
                </label>
                <label>
                  {t('meaningReason', language)} {index + 1}
                  <textarea
                    maxLength={2000}
                    value={answers[index]?.reason ?? ''}
                    onChange={(event) => {
                      setAnswers(
                        answers.map((a, i) =>
                          i === index ? { ...a, reason: event.target.value } : a,
                        ),
                      );
                    }}
                  />
                </label>
              </fieldset>
            ))}
          {!approvalMode && (
            <label>
              {t('meaningReference', language)}
              <input
                disabled={pending}
                maxLength={512}
                value={reference}
                onChange={(event) => {
                  setReference(event.target.value);
                }}
              />
            </label>
          )}
          {!approvalMode && (
            <label>
              <input
                type="checkbox"
                disabled={pending}
                checked={complete}
                onChange={(event) => {
                  setComplete(event.target.checked);
                }}
              />
              {t('meaningComplete', language)}
            </label>
          )}
        </>
      )}
      {!approvalMode && (
        <button
          type="button"
          disabled={pending || !ready}
          onClick={() => {
            if (!ready) return;
            const ticket = ++epoch.current;
            setPending(true);
            void reviewAction(context, actionId, 'ATTESTED', reason, {
              model: 'LC_MEANING_REVIEW_1',
              basisDigest: basis.basisDigest,
              complete,
              evidenceReference: reference,
              answers,
            }).then((outcome) => {
              if (ticket !== epoch.current) return;
              setPending(false);
              onOutcome(outcome);
            });
          }}
        >
          {t('attest', language)}
        </button>
      )}
    </section>
  );
}

function EvidenceTable({
  title,
  evidence,
}: {
  readonly title: string;
  readonly evidence: Readonly<Record<string, string>>;
}): React.JSX.Element {
  return (
    <section aria-label={title}>
      <h4>{title}</h4>
      <dl>
        {Object.entries(evidence).map(([key, value]) => (
          <div key={key}>
            <dt>{key}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
