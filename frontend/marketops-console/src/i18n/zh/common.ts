/**
 * Chinese text shared by the shell and the display atoms.
 *
 * Domains keep their own wording next to their own code; only phrases that more
 * than one screen needs live here, so one word is not translated two ways.
 */

/** Verbs on buttons and links. */
export const actions = {
  refresh: '刷新',
  back: '返回',
  submit: '提交',
  cancel: '取消',
  confirm: '确认',
  save: '保存',
  view: '查看',
  close: '关闭',
  retry: '重试',
  loadMore: '加载更多',
  previousPage: '上一页',
  nextPage: '下一页',
  signIn: '登录',
  signOut: '退出登录',
  copy: '复制',
  expand: '展开',
  collapse: '收起',
} as const;

/** Words describing what a screen or a value is doing. */
export const states = {
  loading: '加载中…',
  empty: '暂无数据',
  unknown: '未知',
  yes: '是',
  no: '否',
  blocked: '已阻断',
  executable: '可执行',
  notAvailable: '暂无',
  failed: '失败',
  succeeded: '成功',
} as const;

/** Labels used by the display atoms themselves. */
export const terms = {
  technicalDetails: '技术详情',
  correlationId: '追踪编号',
  rawCode: '原始代码',
  beijingTime: '北京时间',
  utc: 'UTC',
  unrecognized: '未识别',
} as const;

/** A number of records, e.g. `12 条`. */
export function count(n: number): string {
  return `${String(n)} 条`;
}

/** A refusal whose reason has no Chinese label, named by its HTTP status. */
export function refusedWithStatus(status: number): string {
  return `请求被拒绝（HTTP ${String(status)}）`;
}
