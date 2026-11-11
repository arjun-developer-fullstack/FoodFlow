import { errorMessage } from '../auth/errors';
import { useLogout, useProfile } from '../auth/hooks';

/** Signed-in landing page. Restaurant discovery replaces the placeholder in Phase 2. */
export function HomePage() {
  const profile = useProfile();
  const logout = useLogout();

  if (profile.isPending) {
    return (
      <p role="status" aria-live="polite">
        Loading your profile…
      </p>
    );
  }
  if (profile.isError) {
    return (
      <div className="alert" role="alert">
        {errorMessage(profile.error)}
      </div>
    );
  }

  return (
    <section className="card" aria-labelledby="home-title">
      <h1 id="home-title">Hi, {profile.data.fullName}!</h1>
      <p className="muted">Signed in as {profile.data.email}</p>
      <p>Restaurant discovery is coming soon.</p>
      <button type="button" onClick={() => logout.mutate()} disabled={logout.isPending}>
        {logout.isPending ? 'Signing out…' : 'Sign out'}
      </button>
    </section>
  );
}
