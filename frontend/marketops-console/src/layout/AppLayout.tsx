import {
  ClockCircleOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {
  App as AntApp,
  Avatar,
  Breadcrumb,
  Button,
  Dropdown,
  Flex,
  Layout,
  Menu,
  Tag,
  Tooltip,
  Typography,
  theme,
} from 'antd';
import type { MenuProps } from 'antd';
import { Suspense, useEffect, useMemo, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router';
import {
  environmentLabel,
  isProductionEnvironment,
  product,
  shell as text,
  shortId,
} from '../i18n/zh/shell';
import { LoadingState } from '../ui';
import { NAV_GROUPS, placeRoute } from './navigation';

/** How long before expiry the operator is warned. */
export const SESSION_WARNING_MS = 5 * 60_000;

/** What the frame needs to know about the operator and the deployment. */
export interface AppLayoutProps {
  /** Operator-facing name, when the provider supplied one. */
  readonly displayName: string | undefined;
  /** Store whose work is being shown. */
  readonly storeId: string;
  /** Configured environment name. */
  readonly environment: string;
  /** When the session stops being accepted. */
  readonly expiresAt: number;
  /** The instant the session clock was last read. */
  readonly now: number;
  /** Called when the operator signs out. */
  readonly onSignOut: () => void;
}

/** Menu entries built once from the navigation table. */
const MENU_ITEMS: NonNullable<MenuProps['items']> = NAV_GROUPS.map((group) => ({
  key: group.key,
  icon: group.icon,
  label: group.label,
  children: group.items.map((item) => ({ key: item.path, label: item.label })),
}));

/**
 * The signed-in frame: business-area menu on the left, store and operator on
 * top, breadcrumb above the page.
 *
 * The menu follows the route rather than holding its own selection, so a link,
 * a back button and a typed URL all leave the same entry highlighted.
 */
export function AppLayout({
  displayName,
  storeId,
  environment,
  expiresAt,
  now,
  onSignOut,
}: AppLayoutProps): React.JSX.Element {
  const location = useLocation();
  const navigate = useNavigate();
  const { token } = theme.useToken();
  const { notification } = AntApp.useApp();
  const place = useMemo(() => placeRoute(location.pathname), [location.pathname]);

  const [collapsed, setCollapsed] = useState(false);
  const [narrow, setNarrow] = useState(false);
  const [openKeys, setOpenKeys] = useState<string[]>(
    place.groupKey === undefined ? [] : [place.groupKey],
  );

  // Opening a page from elsewhere opens its group too; groups the operator
  // opened by hand stay open.
  useEffect(() => {
    const groupKey = place.groupKey;
    if (groupKey !== undefined) {
      setOpenKeys((keys) => (keys.includes(groupKey) ? keys : [...keys, groupKey]));
    }
  }, [place.groupKey]);

  const remainingMs = expiresAt - now;
  const expiring = remainingMs <= SESSION_WARNING_MS;
  const minutesLeft = Math.max(1, Math.ceil(remainingMs / 60_000));

  useEffect(() => {
    if (expiring) {
      notification.warning({
        key: 'session-expiry',
        title: text.sessionExpiringTitle,
        description: text.sessionExpiringDescription(minutesLeft),
        duration: 10,
      });
    }
    // Shown once when the warning window opens; the header tag keeps counting,
    // so the minutes are deliberately not a dependency.
  }, [expiring, notification]);

  const userMenu: MenuProps = {
    items: [
      ...(narrow
        ? [
            {
              key: 'store',
              disabled: true,
              label: `${text.store} ${shortId(storeId)} · ${environmentLabel(environment)}`,
            },
            { type: 'divider' as const },
          ]
        : []),
      {
        key: 'sign-out',
        icon: <LogoutOutlined />,
        label: text.signOut,
        danger: true,
      },
    ],
    onClick: ({ key }) => {
      if (key === 'sign-out') {
        notification.destroy('session-expiry');
        onSignOut();
      }
    },
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        breakpoint="lg"
        collapsedWidth={narrow ? 0 : 64}
        onBreakpoint={(broken) => {
          setNarrow(broken);
          setCollapsed(broken);
        }}
        trigger={null}
        width={220}
        style={
          narrow && !collapsed
            ? { position: 'fixed', height: '100vh', zIndex: 20 }
            : { position: 'sticky', top: 0, height: '100vh', overflow: 'auto' }
        }
      >
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#fff',
            fontWeight: 600,
            fontSize: collapsed ? 14 : 18,
            letterSpacing: 0.5,
          }}
        >
          {collapsed ? 'MO' : 'MarketOps'}
        </div>
        <nav aria-label={text.mainNavigation}>
          <Menu
            theme="dark"
            mode="inline"
            items={MENU_ITEMS}
            selectedKeys={place.menuPath === undefined ? [] : [place.menuPath]}
            {...(collapsed ? {} : { openKeys })}
            onOpenChange={(keys) => {
              // A collapsed menu reports its groups as closed; that is not the
              // operator closing them, so the expanded state is kept.
              if (!collapsed) {
                setOpenKeys(keys);
              }
            }}
            onClick={({ key }) => {
              void navigate(key);
              if (narrow) {
                setCollapsed(true);
              }
            }}
          />
        </nav>
      </Layout.Sider>
      {narrow && !collapsed && (
        <div
          aria-hidden
          onClick={() => {
            setCollapsed(true);
          }}
          style={{ position: 'fixed', inset: 0, background: token.colorBgMask, zIndex: 19 }}
        />
      )}
      <Layout>
        <Layout.Header
          aria-label={text.header}
          style={{
            background: token.colorBgContainer,
            position: 'sticky',
            top: 0,
            zIndex: 10,
            padding: '0 16px',
            borderBottom: `1px solid ${token.colorBorderSecondary}`,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
          }}
        >
          <Flex align="center" gap={8} style={{ minWidth: 0 }}>
            <Button
              type="text"
              aria-label={collapsed ? text.expandMenu : text.collapseMenu}
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => {
                setCollapsed(!collapsed);
              }}
            />
            <Typography.Text strong ellipsis style={{ fontSize: 16 }}>
              {product.name}
            </Typography.Text>
          </Flex>
          <Flex align="center" gap={12} aria-label={text.session} role="group">
            {expiring && (
              <Tag color="warning" icon={<ClockCircleOutlined />} data-state="expiring">
                {text.sessionExpiringTag(minutesLeft)}
              </Tag>
            )}
            {!narrow && (
              <Flex align="center" gap={4}>
                <Typography.Text type="secondary">{text.store}</Typography.Text>
                <Tooltip title={storeId}>
                  <Typography.Text
                    code
                    copyable={{ text: storeId, tooltips: [undefined, text.storeCopied] }}
                    data-store-id={storeId}
                  >
                    {shortId(storeId)}
                  </Typography.Text>
                </Tooltip>
                <Tag
                  color={isProductionEnvironment(environment) ? 'red' : 'blue'}
                  data-environment={environment}
                  style={{ marginInlineEnd: 0 }}
                >
                  {environmentLabel(environment)}
                </Tag>
              </Flex>
            )}
            <Dropdown menu={userMenu} trigger={['click']} placement="bottomRight">
              <Button type="text" aria-label={text.signedInAs}>
                <Avatar size="small" icon={<UserOutlined />} />
                <Typography.Text ellipsis style={{ maxWidth: 160 }}>
                  {displayName ?? text.anonymousUser}
                </Typography.Text>
              </Button>
            </Dropdown>
          </Flex>
        </Layout.Header>
        <Layout.Content style={{ padding: narrow ? 16 : 24 }}>
          <nav aria-label={text.breadcrumb}>
            <Breadcrumb
              style={{ marginBottom: 12 }}
              items={place.crumbs.map((crumb) => ({
                key: crumb.label,
                title:
                  crumb.path === undefined ? (
                    crumb.label
                  ) : (
                    <Link to={crumb.path}>{crumb.label}</Link>
                  ),
              }))}
            />
          </nav>
          <main>
            <Suspense fallback={<LoadingState />}>
              <Outlet />
            </Suspense>
          </main>
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
