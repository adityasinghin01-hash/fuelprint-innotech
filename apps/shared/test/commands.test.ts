import { afterEach, describe, expect, it, vi } from 'vitest';

import { newCommandId, receiptProblem, sendCommand } from '../src/commands';
import { configureServer } from '../src/http';
import type { Receipt } from '../src/types';

const receipt = (commandId: string, extra: Partial<Receipt> = {}): Receipt => ({
  receipt_id: 'r', command_id: commandId, status: 'APPLIED', reason_code: null, reason_text: null, record_refs: [],
  actor: 'supervisor@laptop-1', automatic: false, ...extra,
});

afterEach(() => {
  vi.unstubAllGlobals();
  configureServer(null);
});

describe('sendCommand', () => {
  it('sends the contract command and retries with the SAME command id after network failures', async () => {
    configureServer('http://host:8080');
    const bodies: Record<string, unknown>[] = [];
    let calls = 0;
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      expect(url).toBe('http://host:8080/api/commands');
      bodies.push(JSON.parse(String(init.body)));
      calls++;
      if (calls < 3) throw new TypeError('network down');
      return new Response(JSON.stringify(receipt(bodies[0].command_id as string)), { status: 200 });
    }));
    const result = await sendCommand('approve_mission', { mission_id: 'm1', dependency_hash: 'h' }, 7,
      { runId: 'run-1', retryDelayMs: 1 });
    expect(result.status).toBe('APPLIED');
    expect(bodies).toHaveLength(3);
    expect(new Set(bodies.map((b) => b.command_id)).size).toBe(1);
    expect(bodies[0]).toEqual({
      schema_version: 'fp/1', command_id: bodies[0].command_id, command_type: 'approve_mission', run_id: 'run-1',
      expected_revision: 7, payload: { mission_id: 'm1', dependency_hash: 'h' },
    });
    expect(String(bodies[0].command_id)).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  it('retries a busy server and a SERVER_ERROR, but returns a refusal at once', async () => {
    const statuses = [503, 500, 200];
    let i = 0;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      const id = JSON.parse(String(init.body)).command_id;
      const status = statuses[i++];
      if (status === 503) return new Response('busy', { status });
      if (status === 500) return new Response(JSON.stringify(receipt(id, { status: 'REJECTED', reason_code: 'SERVER_ERROR' })), { status });
      return new Response(JSON.stringify(receipt(id, { status: 'REJECTED', reason_code: 'STALE_REVISION' })), { status });
    }));
    const result = await sendCommand('reject_mission', { mission_id: 'm' }, 2, { retryDelayMs: 1 });
    expect(i).toBe(3);
    expect(result.reason_code).toBe('STALE_REVISION');
    expect(receiptProblem(result)).toMatch(/already changed/);
  });

  it('never throws: an unreachable server gives an INTERRUPTED receipt', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('offline');
    }));
    const result = await sendCommand('pause_run', { run_id: 'r' }, null, { retries: 1, retryDelayMs: 1 });
    expect(result.status).toBe('INTERRUPTED');
    expect(result.reason_code).toBe('NO_CONNECTION');
    expect(result.automatic).toBe(false);
  });

  it('makes a fresh UUID v4 per command', () => {
    expect(newCommandId()).not.toBe(newCommandId());
  });
});
