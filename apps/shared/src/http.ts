// Server addresses. Screens are served by the Java server (or the Vite dev proxy), so everything is same-origin.
// configureServer() lets tests and tools point at another server.

let base: string | null = null;

/** Point every request and the socket at another server, like "http://127.0.0.1:8080". */
export function configureServer(baseUrl: string | null): void {
  base = baseUrl ? baseUrl.replace(/\/$/, '') : null;
}

function origin(): string {
  if (base) return base;
  if (typeof window !== 'undefined' && window.location?.origin && window.location.origin !== 'null') {
    return window.location.origin;
  }
  return 'http://localhost:8080';
}

/** An absolute http(s) URL for an /api path. */
export function apiUrl(path: string): string {
  return new URL(path, origin() + '/').toString();
}

/** The ws(s) URL for a socket path. */
export function wsUrl(path: string): string {
  const url = new URL(path, origin() + '/');
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.toString();
}

/** A JSON GET; throws HttpError with the server's reason on a non-2xx answer. */
export async function getJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(apiUrl(path), { credentials: 'include', ...init });
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new HttpError(response.status, body?.reason_code ?? 'HTTP_' + response.status, body?.reason_text ?? text);
  }
  return body as T;
}

/** A JSON POST (or other method) with a body. */
export async function sendJson<T>(path: string, body: unknown, method = 'POST'): Promise<T> {
  return getJson<T>(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export class HttpError extends Error {
  constructor(readonly status: number, readonly reasonCode: string, readonly reasonText: string) {
    super(reasonText || reasonCode);
  }
}
