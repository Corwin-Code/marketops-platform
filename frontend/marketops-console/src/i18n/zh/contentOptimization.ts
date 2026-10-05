/**
 * Chinese text for a listing's content optimization in the store diagnosis drawer: what Ozon's
 * content rating finds missing, what the card says now, and the model's Russian drafts.
 */

/** A count with thousands separators, as the rest of the console writes counts. */
function count(value: number): string {
  return value.toLocaleString('zh-CN');
}

export const contentText = {
  title: '内容优化',
  hint: '数据来自每天采集的 Ozon 商品目录和内容评分。AI 草稿只供审核，生成草稿不会改动商品；要改描述，在下方“修改描述并写入 Ozon”改定后由 Owner 确认写入。标题和属性仍需在 Ozon 卖家后台修改。',
  none: '还没有采集到这个商品的内容，下次每日采集后显示。',
  observedAt: '采集于',

  ratingTitle: 'Ozon 内容评分组',
  ratingNone: '还没有采集到评分组。',
  groupScore: (rating: string): string => `${rating} / 100`,
  groupWeight: (weight: string): string => `占总分 ${weight}%`,
  groupFull: '满分',
  groupGap: '有缺口',
  currentBracket: '当前档位',
  nextBracket: (points: string): string => `下一档（${points} 分）`,
  optional: '可选提升',
  improveTitle: 'Ozon 点名要补填的属性',
  improveAtLeast: (count: number): string => `补齐其中至少 ${String(count)} 项即可提升本组评分`,
  improveHint: '属性名是 Ozon 的俄语原名；请在卖家后台商品编辑页的对应字段填写。',
  filled: '已填',
  notFilled: '未填',

  cardTitle: '商品卡现状',
  description: '描述',
  descriptionNone: '没有描述',
  descriptionLength: (length: number): string => `${count(length)} 字符`,
  descriptionTooLong: '描述过长，未保存全文',
  richContent: '富内容',
  richYes: (length: number): string => `有（${count(length)} 字符）`,
  richNo: '无',
  images: '图片',
  imagesCount: (count: number): string => `${String(count)} 张`,
  attributes: '已填属性',
  attributesCount: (count: number): string => `${String(count)} 个`,
  showDescription: '展开描述全文',

  draftsTitle: 'AI 俄语草稿',
  draftsHint:
    '按 Ozon 评分缺口和买家搜索词，生成俄语标题、描述和属性建议。只使用商品卡和评分里已有的事实，数据里没有的属性会列为不确定项。',
  draftsNone: '还没有生成过这个商品的内容草稿。',
  draftsGenerate: '生成俄语草稿',
  draftsRegenerate: '重新生成',
  draftsNotice:
    'AI 草稿只供参考：描述草稿核对后可填入下方“修改描述并写入 Ozon”，由 Owner 确认后写入；标题和属性建议仍需在 Ozon 卖家后台修改，Ozon 审核通过后生效。',
  draftFields: { TITLE: '标题草稿', DESCRIPTION: '描述草稿', ATTRIBUTE: '属性建议' } as Readonly<
    Record<string, string>
  >,
  draftAttribute: (name: string): string => `属性建议：${name}`,
  draftLength: (length: number): string => `${count(length)} 字符`,
  draftCurrentLength: (length: number): string => `现为 ${count(length)} 字符`,
  draftAdvice: '其他建议',
  coverage: '搜索词覆盖（按词干近似匹配）',
  coverageNow: (covered: number, total: number): string =>
    `现有内容覆盖 ${String(covered)}/${String(total)}`,
  coverageDraft: (covered: number, total: number): string =>
    `草稿覆盖 ${String(covered)}/${String(total)}`,
  coverageNone: '这个商品还没有搜索词数据。',
  expectedEffect: '预期效果：',
  risk: '风险：',
  facts: '依据',
  unknowns: '不确定项（需要你补充的事实）',
  nextEvidence: '去哪里找：',
} as const;

/** Ozon's content rating groups, as its seller back office names them. */
export const RATING_GROUP_LABELS: Readonly<Record<string, string>> = {
  media: '图片与视频',
  text: '文字描述',
  other_attributes: '其他属性',
};

/** Ozon's content rating conditions (keys checked on the pilot store 2026-09-29). */
export const RATING_CONDITION_LABELS: Readonly<Record<string, string>> = {
  media_images_1_2: '图片 1–2 张',
  media_images_3_4: '图片 3–4 张',
  media_images_5_7: '图片 5–7 张',
  'media_images_8+': '图片 8 张以上',
  media_video_or_video_cover: '有视频或视频封面',
  text_annotation_100_chars: '描述不少于 100 字符',
  text_annotation_500_chars: '描述不少于 500 字符',
  text_rich: '有富内容',
  other__15_to_50_percent: '其他属性填写 15–50%',
  other__50_to_70_percent: '其他属性填写 50–70%',
  other_70_percent: '其他属性填写 70% 以上',
};

/**
 * Conditions that are brackets of one measure (how many images, how many attributes filled):
 * only the fulfilled bracket counts, and an unfulfilled lower bracket is not a gap.
 */
export const RATING_CONDITION_BRACKETS: Readonly<Record<string, string>> = {
  media_images_1_2: 'images',
  media_images_3_4: 'images',
  media_images_5_7: 'images',
  'media_images_8+': 'images',
  other__15_to_50_percent: 'attributes',
  other__50_to_70_percent: 'attributes',
  other_70_percent: 'attributes',
};
