import { create } from 'zustand';
import { configureSession, type RefreshResult } from '../../shared/api/http';
import { ApiError } from '../../shared/api/problem';
import { authApi, type TokenResponse } from './api';

/**
 * - unknown: restoring the session on page load
 * - unavailable: restoring failed for a temporary reason (outage, rate limit, network); the refresh cookie
 *   is untouched, so retrying restores the session once FoodFlow is reachable again
 */
export type SessionStatus = 'unknown' | 'unavailable' | 'authenticated' | 'anonymous';

interface SessionState {
  status: SessionStatus;
  /** Kept in memory only: never written to localStorage/sessionStorage, so XSS cannot harvest it later. */
  accessToken: string | null;
  expiresAt: number | null;
  signIn(token: TokenResponse): void;
  signOut(): void;
  /** The initial restore could not reach FoodFlow. Nothing is revoked or cleared. */
  markUnavailable(): void;
  /** Tries the initial restore again (back to 'unknown', which the bootstrap picks up). */
  retryRestore(): void;
}

const initialState = { status: 'unknown' as SessionStatus, accessToken: null, expiresAt: null };

export const useSession = create<SessionState>()((set) => ({
  ...initialState,
  signIn: (token) => set({ status: 'authenticated', accessToken: token.accessToken, expiresAt: Date.parse(token.expiresAt) }),
  signOut: () => set({ status: 'anonymous', accessToken: null, expiresAt: null }),
  markUnavailable: () => set({ status: 'unavailable', accessToken: null, expiresAt: null }),
  retryRestore: () => set((state) => (state.status === 'unavailable' ? { status: 'unknown' } : state)),
}));

/** Test helper: back to the pre-bootstrap state. */
export function resetSession(): void {
  useSession.setState(initialState);
}

/** Web Locks name shared by all tabs of this origin. */
export const REFRESH_LOCK = 'foodflow-session-refresh';

let refreshInFlight: Promise<RefreshResult> | null = null;

/**
 * Exchanges the HttpOnly refresh cookie for a new access token.
 *
 * Refresh tokens are single-use, so refreshes are serialized within the tab (shared promise) and
 * across tabs (Web Locks API): a second tab waits, then refreshes with the cookie the first tab
 * just received instead of replaying the consumed one.
 *
 * Only a 401 ends the session. Rate limiting, server or network errors leave a signed-in user
 * signed in; the failed call reports the error and a later call can retry.
 */
export function refreshSession(): Promise<RefreshResult> {
  refreshInFlight ??= withCrossTabLock(async (): Promise<RefreshResult> => {
    try {
      useSession.getState().signIn(await authApi.refresh());
      return 'refreshed';
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        useSession.getState().signOut();
        return 'expired';
      }
      if (useSession.getState().status === 'unknown') {
        // Cannot restore the session right now. Do not pretend the user is signed out: the refresh cookie
        // is still valid, so explain the outage and let the user retry.
        useSession.getState().markUnavailable();
      }
      return 'unavailable';
    }
  }).finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

function withCrossTabLock<T>(work: () => Promise<T>): Promise<T> {
  const locks = typeof navigator === 'undefined' ? undefined : navigator.locks;
  return locks ? locks.request(REFRESH_LOCK, work) : work();
}

/** Connects the HTTP client to the session store. Call once at startup. */
export function installSessionHooks(): void {
  configureSession({
    getAccessToken: () => useSession.getState().accessToken,
    refresh: refreshSession,
    onSessionExpired: () => useSession.getState().signOut(),
  });
}
