import { useQueryClient } from '@tanstack/react-query';
import { useEffect, type ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import { refreshSession, useSession } from './session';

/**
 * Restores the session on page load from the HttpOnly refresh cookie (the access token is not
 * persisted). Renders nothing route-specific until the session status is known.
 */
export function SessionBootstrap({ children }: { children: ReactNode }) {
  const status = useSession((state) => state.status);
  const queryClient = useQueryClient();

  // Never show one user's cached data to the next: drop the cache whenever the user signs in
  // or the session ends (explicit sign-out, expiry, or revocation).
  useEffect(
    () =>
      useSession.subscribe((state, previous) => {
        if (state.status !== previous.status) {
          queryClient.clear();
        }
      }),
    [queryClient],
  );

  useEffect(() => {
    if (status === 'unknown') {
      void refreshSession();
    }
  }, [status]);

  if (status === 'unknown') {
    return (
      <div className="splash" role="status" aria-live="polite">
        Loading FoodFlow…
      </div>
    );
  }
  if (status === 'unavailable') {
    return <ServiceUnavailableNotice onRetry={() => useSession.getState().retryRestore()} />;
  }
  return children;
}

/**
 * Shown when the session cannot be restored because FoodFlow is temporarily unreachable. Nobody is signed
 * out: the (HttpOnly, so unreadable here) refresh cookie is kept, and "Try again" restores the session once
 * the service is back. Visitors without a session simply reach the sign-in page after the retry.
 */
function ServiceUnavailableNotice({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="splash">
      <div className="alert" role="alert">
        <strong>FoodFlow is temporarily unavailable.</strong> Please try again in a moment. If you were signed
        in, you will stay signed in.
      </div>
      <button type="button" className="primary" onClick={onRetry}>
        Try again
      </button>
    </div>
  );
}

/** Protects child routes: anonymous users are sent to /login and returned afterwards. */
export function RequireAuth() {
  const status = useSession((state) => state.status);
  const location = useLocation();
  if (status !== 'authenticated') {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  return <Outlet />;
}

/** Sign-in/sign-up pages are pointless once signed in. */
export function GuestOnly({ children }: { children: ReactNode }) {
  const status = useSession((state) => state.status);
  if (status === 'authenticated') {
    return <Navigate to="/" replace />;
  }
  return children;
}
