import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';

/**
 * Mounts the console.
 *
 * A missing mount point is a failure of the page rather than of the
 * application, so it is raised immediately instead of leaving a blank screen.
 */
const container = document.getElementById('root');

if (container === null) {
  throw new Error('the page is missing its mount point');
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
