// sendCommand: the only way a screen changes anything (AGREEMENT section 3).
import { apiUrl } from './http';
import type { Command, Receipt, Uuid } from './types';

export interface SendOptions {
  /** The run the command is for. */
  runId?: Uuid | null;
  /** Reuse this command id (for a retry the screen started itself). Default: a new UUID. */
  commandId?: Uuid;
  /** Network retries with the SAME command id (default 3). The server applies a command id once. */
  retries?: number;
  /** Milliseconds before the first retry; doubles each time (default 400). */
  retryDelayMs?: number;
}

/** A UUID v4 for a new command. */
export function newCommandId(): Uuid {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** A receipt made by the screen when the server could not be reached at all (nothing is known to be saved). */
function unreachable(commandId: Uuid, text: string): Receipt {
  return {
    receipt_id: newCommandId(), command_id: commandId, status: 'INTERRUPTED', reason_code: 'NO_CONNECTION',
    reason_text: text, record_refs: [], actor: 'this screen', automatic: false,
  };
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Sends one command and resolves with its receipt. A new UUID command id is made once; if the network fails or the
 * server answers 502/503/504 or SERVER_ERROR, the SAME id is sent again, so a command is never applied twice.
 * Never throws: when the server cannot be reached the receipt status is INTERRUPTED with reason NO_CONNECTION.
 */
export async function sendCommand<P extends object>(
  type: string,
  payload: P,
  expectedRevision: number | null = null,
  options: SendOptions = {},
): Promise<Receipt> {
  const command: Command<P> = {
    schema_version: 'fp/1',
    command_id: options.commandId ?? newCommandId(),
    command_type: type,
    run_id: options.runId ?? null,
    expected_revision: expectedRevision,
    payload,
  };
  const retries = options.retries ?? 3;
  let delay = options.retryDelayMs ?? 400;
  let last = 'The server did not answer.';
  for (let attempt = 0; attempt <= retries; attempt++) {
    if (attempt > 0) {
      await sleep(delay);
      delay *= 2;
    }
    try {
      const response = await fetch(apiUrl('/api/commands'), {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(command),
      });
      if ([502, 503, 504].includes(response.status)) {
        last = `The server is busy (HTTP ${response.status}).`;
        continue;
      }
      const text = await response.text();
      let receipt: Receipt;
      try {
        receipt = JSON.parse(text) as Receipt;
      } catch {
        return unreachable(command.command_id, `The server sent something that is not a receipt (HTTP ${response.status}).`);
      }
      if (receipt.reason_code === 'SERVER_ERROR' && attempt < retries) {
        last = receipt.reason_text ?? 'The server failed.';
        continue;
      }
      return receipt;
    } catch {
      last = 'No connection to the server.';
    }
  }
  return unreachable(command.command_id, `${last} Nothing is known to be saved; try again.`);
}

/** Plain words for a refused receipt, for the screen. */
export function receiptProblem(receipt: Receipt): string | null {
  if (receipt.status === 'APPLIED') return null;
  switch (receipt.reason_code) {
    case 'STALE_REVISION':
      return 'Someone already changed this. The screen shows the newer state now; check it and try again.';
    case 'NOT_ALLOWED':
      return receipt.reason_text ?? 'Your station is not allowed to do this.';
    default:
      return receipt.reason_text ?? `Refused (${receipt.reason_code ?? receipt.status}).`;
  }
}
