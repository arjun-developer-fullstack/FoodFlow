import { Link, Outlet } from 'react-router';

export function AppLayout() {
  return (
    <>
      <header className="app-header">
        <Link to="/" className="brand">
          FoodFlow
        </Link>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </>
  );
}

export function NotFoundPage() {
  return (
    <section className="card">
      <h1>Page not found</h1>
      <Link to="/">Back to FoodFlow</Link>
    </section>
  );
}
