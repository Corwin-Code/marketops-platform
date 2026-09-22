import {
  AlertOutlined,
  DollarOutlined,
  FundOutlined,
  SettingOutlined,
  ShopOutlined,
} from '@ant-design/icons';
import type { ReactNode } from 'react';
import { matchPath } from 'react-router';
import { areas, pages, shortId } from '../i18n/zh/shell';

/** Every path the signed-in console serves. */
export const ROUTES = {
  home: '/',
  pricingQueue: '/pricing/queue',
  subject: '/pricing/subjects/:subjectId',
  review: '/pricing/review',
  command: '/pricing/commands/:commandId',
  pricingExport: '/pricing/export',
  availabilityRisks: '/availability/risks',
  availabilityCases: '/availability/cases',
  availabilityAuthority: '/availability/authority',
  advertisingQueue: '/advertising/queue',
  advertisingCase: '/advertising/cases/:caseId',
  advertisingOperations: '/advertising/operations',
  advertisingDaily: '/advertising/briefs/daily',
  advertisingWeekly: '/advertising/briefs/weekly',
  listing: '/listing',
  listingAllowances: '/listing/allowances',
  listingCalibrations: '/listing/calibrations',
  systemStatus: '/system/status',
} as const;

/** Path of one subject's diagnosis. */
export function subjectPath(subjectId: string): string {
  return `/pricing/subjects/${encodeURIComponent(subjectId)}`;
}

/** Path of one command's timeline. */
export function commandPath(commandId: string): string {
  return `/pricing/commands/${encodeURIComponent(commandId)}`;
}

/** Path of one advertising case. */
export function advertisingCasePath(caseId: string): string {
  return `/advertising/cases/${encodeURIComponent(caseId)}`;
}

/** One menu entry. */
export interface NavItem {
  readonly path: string;
  readonly label: string;
}

/** One business area, shown as a menu group. */
export interface NavGroup {
  readonly key: string;
  readonly label: string;
  readonly icon: ReactNode;
  readonly items: readonly NavItem[];
}

/** The menu, grouped by business area, most important work first. */
export const NAV_GROUPS: readonly NavGroup[] = [
  {
    key: 'pricing',
    label: areas.pricing,
    icon: <DollarOutlined />,
    items: [
      { path: ROUTES.pricingQueue, label: pages.pricingQueue },
      { path: ROUTES.pricingExport, label: pages.pricingExport },
    ],
  },
  {
    key: 'availability',
    label: areas.availability,
    icon: <AlertOutlined />,
    items: [
      { path: ROUTES.availabilityRisks, label: pages.availabilityRisks },
      { path: ROUTES.availabilityCases, label: pages.availabilityCases },
      { path: ROUTES.availabilityAuthority, label: pages.availabilityAuthority },
    ],
  },
  {
    key: 'advertising',
    label: areas.advertising,
    icon: <FundOutlined />,
    items: [
      { path: ROUTES.advertisingQueue, label: pages.advertisingQueue },
      { path: ROUTES.advertisingOperations, label: pages.advertisingOperations },
      { path: ROUTES.advertisingDaily, label: pages.advertisingDaily },
      { path: ROUTES.advertisingWeekly, label: pages.advertisingWeekly },
    ],
  },
  {
    key: 'listing',
    label: areas.listing,
    icon: <ShopOutlined />,
    items: [
      { path: ROUTES.listing, label: pages.listing },
      { path: ROUTES.listingAllowances, label: pages.listingAllowances },
      { path: ROUTES.listingCalibrations, label: pages.listingCalibrations },
    ],
  },
  {
    key: 'system',
    label: areas.system,
    icon: <SettingOutlined />,
    items: [{ path: ROUTES.systemStatus, label: pages.systemStatus }],
  },
];

/** One breadcrumb step; a step with a path is a link. */
export interface Crumb {
  readonly label: string;
  readonly path?: string;
}

/** Where a path sits in the navigation. */
export interface RoutePlace {
  /** Menu group to open, when the path belongs to one. */
  readonly groupKey: string | undefined;
  /** Menu entry to highlight, when the path belongs to one. */
  readonly menuPath: string | undefined;
  readonly crumbs: readonly Crumb[];
}

/** Detail pages, placed under the menu entry they are opened from. */
const DETAIL_PAGES: readonly {
  readonly pattern: string;
  readonly param?: string;
  readonly label: string;
  readonly groupKey: string;
  readonly parent: NavItem;
}[] = [
  {
    pattern: ROUTES.subject,
    param: 'subjectId',
    label: pages.subject,
    groupKey: 'pricing',
    parent: { path: ROUTES.pricingQueue, label: pages.pricingQueue },
  },
  {
    pattern: ROUTES.review,
    label: pages.review,
    groupKey: 'pricing',
    parent: { path: ROUTES.pricingQueue, label: pages.pricingQueue },
  },
  {
    pattern: ROUTES.command,
    param: 'commandId',
    label: pages.command,
    groupKey: 'pricing',
    parent: { path: ROUTES.pricingQueue, label: pages.pricingQueue },
  },
  {
    pattern: ROUTES.advertisingCase,
    param: 'caseId',
    label: pages.advertisingCase,
    groupKey: 'advertising',
    parent: { path: ROUTES.advertisingQueue, label: pages.advertisingQueue },
  },
];

function groupLabel(key: string): string {
  return NAV_GROUPS.find((group) => group.key === key)?.label ?? '';
}

/**
 * Place a path in the navigation: which group is open, which entry is
 * highlighted, and the breadcrumb, with a detail page's id shortened.
 */
export function placeRoute(pathname: string): RoutePlace {
  for (const group of NAV_GROUPS) {
    for (const item of group.items) {
      if (matchPath({ path: item.path, end: true }, pathname) !== null) {
        return {
          groupKey: group.key,
          menuPath: item.path,
          crumbs: [{ label: group.label }, { label: item.label }],
        };
      }
    }
  }
  for (const detail of DETAIL_PAGES) {
    const match = matchPath({ path: detail.pattern, end: true }, pathname);
    if (match !== null) {
      const id = detail.param === undefined ? undefined : match.params[detail.param];
      return {
        groupKey: detail.groupKey,
        menuPath: detail.parent.path,
        crumbs: [
          { label: groupLabel(detail.groupKey) },
          { label: detail.parent.label, path: detail.parent.path },
          { label: id === undefined ? detail.label : `${detail.label} · ${shortId(id)}` },
        ],
      };
    }
  }
  return { groupKey: undefined, menuPath: undefined, crumbs: [{ label: pages.notFound }] };
}
