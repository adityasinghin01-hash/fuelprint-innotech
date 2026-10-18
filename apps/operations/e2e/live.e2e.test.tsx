// Headless end-to-end check against a real server in fake mode (tools/test-e2e.sh starts it and sets FP_E2E_URL).
// The real App renders in jsdom as if the server had served it, and must see live ticks with the drone moving.
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { App } from '../src/App';

const server = process.env.FP_E2E_URL;

describe.skipIf(!server)('operations skeleton against the fake server', () => {
  it('goes live, shows the replayed run and sees the drone fly in the ticks', async () => {
    render(<App />);
    await waitFor(() => expect(screen.getByTestId('live-badge').textContent).toMatch(/Live/), { timeout: 15000 });
    await waitFor(() => expect(screen.getByTestId('run-state').textContent).toMatch(/SC[123]-/), { timeout: 15000 });
    await waitFor(() => expect(screen.getByTestId('sim-time').textContent).toMatch(/\d+\.\d s/), { timeout: 15000 });
    const simTime = () => parseFloat((screen.getByTestId('sim-time').textContent ?? '').replace(/[^0-9.]/g, ''));
    const height = () => parseFloat((screen.getByTestId('drone').textContent ?? '').match(/height ([0-9.]+) m/)?.[1] ?? 'NaN');
    // The drone leaves the ground in every scenario...
    await waitFor(() => expect(height()).toBeGreaterThan(1), { timeout: 60000, interval: 200 });
    // ...and keeps moving while the clock runs: the screen follows the ticks.
    const t0 = simTime();
    const h0 = height();
    await waitFor(() => {
      expect(simTime()).toBeGreaterThan(t0 + 1);
      expect(height()).not.toBe(h0);
    }, { timeout: 30000, interval: 200 });
    await waitFor(() => expect(screen.getByTestId('cursor').textContent).toMatch(/Last event: \d+/), { timeout: 15000 });
  }, 90000);
});
