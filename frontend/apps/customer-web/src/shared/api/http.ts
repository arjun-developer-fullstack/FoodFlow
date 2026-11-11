import { ApiError, NETWORK_ERROR, toApiError } from './problem';

/** 'expired': the session is gone (401). 'unavailable': temporary failure (429, 5xx, network). */
export type RefreshResult = 'refreshed' | 'expired' | 'unavailable';

/**
 * Hooks the HTTP client uses to authenticate requests. Provided by the auth feature at startup
 * ({@link configureSession}), which keeps this module free of any dependency on it.
 */
export interface SessionHooks {
  getAccessToken(): string | null;
  /** Obtains a new access token via the refresh cookie. */
  refresh(): Promise<RefreshResult>;
  onSessionExpired(): void;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** Attach the access token and transparently refresh it once on 401. Default: true. */
  authenticated?: boolean;
  signal?: AbortSignal;
}

let session: SessionHooks | null = null;

export function configureSession(hooks: SessionHooks | null): void {
  session = hooks;
}

/** Resolves API paths against VITE_API_BASE_URL (cross-origin deployments) or the page origin. */
export function apiUrl(path: string): string {
  const base = import.meta.env.VITE_API_BASE_URL || window.location.origin;
  return new URL(path, base).toString();
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const authenticated = options.authenticated ?? true;
  let response = await send(path, options, authenticated);

  if (response.status === 401 && authenticated && session) {
    const result = await session.refresh();
    if (result === 'refreshed') {
      response = await send(path, options, authenticated);
    } else if (result === 'expired') {
      session.onSessionExpired();
    }
    // 'unavailable': keep the session; the caller receives this request's error
  }
  return parse<T>(response);
}

async function send(path: string, options: RequestOptions, authenticated: boolean): Promise<Response> {
  const headers = new Headers({ Accept: 'application/json, application/problem+json' });
  if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json');
  }
  const token = authenticated ? session?.getAccessToken() : null;
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  const init: RequestInit = {
    method: options.method ?? 'GET',
    headers,
    // The refresh-token cookie is HttpOnly + SameSite=Strict; 'include' also covers cross-origin API hosts.
    credentials: 'include',
  };
  if (options.body !== undefined) {
    init.body = JSON.stringify(options.body);
  }
  if (options.signal) {
    init.signal = options.signal;
  }
  try {
    return await fetch(apiUrl(path), init);
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    throw new ApiError(0, NETWORK_ERROR, 'The FoodFlow service could not be reached.');
  }
}

async function parse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw await toApiError(response);
  }
  if (response.status === 204 || response.headers.get('Content-Length') === '0') {
    return undefined as T;
  }
  return (await response.json()) as T;
}
