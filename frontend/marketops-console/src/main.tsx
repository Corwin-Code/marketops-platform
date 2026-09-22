import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
// Configures dayjs (Chinese locale, store time zone) before anything renders.
import './format/time';
import './global.css';

/** Theme shared by every screen, with a font stack that renders Chinese well. */
const THEME = {
  token: {
    colorPrimary: '#1677ff',
    borderRadius: 6,
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Segoe UI', Roboto, sans-serif",
  },
} as const;

/**
 * Mounts the console.
 *
 * A missing mount point is a failure of the page rather than of the
 * application, so it is raised immediately instead of leaving a blank screen.
 * The Ant Design app wrapper provides the context that message and modal need.
 */
const container = document.getElementById('root');

if (container === null) {
  throw new Error('the page is missing its mount point');
}

createRoot(container).render(
  <StrictMode>
    <ConfigProvider locale={zhCN} theme={THEME} button={{ autoInsertSpace: false }}>
      <AntApp>
        <App />
      </AntApp>
    </ConfigProvider>
  </StrictMode>,
);
