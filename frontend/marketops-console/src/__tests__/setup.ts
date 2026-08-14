import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

/**
 * Test environment for the console.
 *
 * The rendered tree is torn down after every case. A tree left mounted keeps
 * its timers and its pending request, and the next case then observes a state
 * transition it did not cause.
 */
afterEach(() => {
  cleanup();
});
