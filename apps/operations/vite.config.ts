// Dev server on 5173 with /api and /ws proxied to the Java server (see @fp/shared/vite). Built into dist/, which
// the Java server serves at /operations/.
import { defineConfig, mergeConfig } from 'vitest/config';

import { fpViteConfig } from '../shared/vite';

export default mergeConfig(fpViteConfig('operations'), defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.test.{ts,tsx}', 'e2e/**/*.test.{ts,tsx}'],
    environmentOptions: {
      // The end-to-end test talks to a real fake-mode server: the page must look as if that server served it.
      jsdom: { url: (process.env.FP_E2E_URL ?? 'http://localhost:5173') + '/operations/' },
    },
  },
}));
