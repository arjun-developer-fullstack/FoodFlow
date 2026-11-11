import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { renderApp } from '../../test/renderApp';
import { PROFILE, problem, server, TOKEN } from '../../test/server';
import { useSession } from './session';

describe('session bootstrap and route protection', () => {
  it('sends anonymous visitors of a protected page to sign-in', async () => {
    const { router } = renderApp('/');

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('restores the session from the refresh cookie on page load', async () => {
    server.use(http.post('/api/v1/auth/refresh', () => HttpResponse.json(TOKEN)));

    renderApp('/');

    expect(await screen.findByRole('heading', { name: 'Hi, Jane Doe!' })).toBeInTheDocument();
    expect(screen.getByText('Signed in as jane@example.com')).toBeInTheDocument();
  });

  it("does not show the previous user's cached profile to the next user", async () => {
    server.use(
      http.post('/api/v1/auth/refresh', () => HttpResponse.json(TOKEN)),
      http.post('/api/v1/auth/logout', () => new HttpResponse(null, { status: 204 })),
      http.post('/api/v1/auth/login', () => HttpResponse.json({ ...TOKEN, accessToken: 'access-token-2' })),
      http.get('/api/v1/users/me', ({ request }) =>
        HttpResponse.json(
          request.headers.get('Authorization') === 'Bearer access-token-2'
            ? { ...PROFILE, fullName: 'Bob Smith', email: 'bob@example.com' }
            : PROFILE,
        ),
      ),
    );
    const { user } = renderApp('/');
    await user.click(await screen.findByRole('button', { name: 'Sign out' }));

    await user.type(await screen.findByLabelText('E-mail'), 'bob@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct horse battery staple');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Hi, Bob Smith!' })).toBeInTheDocument();
    expect(screen.queryByText(/Jane Doe/)).not.toBeInTheDocument();
  });

  it('signs out on the server and returns to the sign-in page', async () => {
    let logoutCalled = false;
    server.use(
      http.post('/api/v1/auth/refresh', () => HttpResponse.json(TOKEN)),
      http.post('/api/v1/auth/logout', () => {
        logoutCalled = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user, router } = renderApp('/');

    await user.click(await screen.findByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(logoutCalled).toBe(true);
  });
});

describe('session restore during an outage', () => {
  const UNAVAILABLE = /FoodFlow is temporarily unavailable/;

  it('explains the outage instead of showing sign-in, keeps the session and restores it on retry', async () => {
    let serviceUp = false;
    let refreshCalls = 0;
    let logoutCalled = false;
    server.use(
      http.post('/api/v1/auth/refresh', () => {
        refreshCalls += 1;
        return serviceUp
          ? HttpResponse.json(TOKEN)
          : problem(503, 'SERVICE_UNAVAILABLE', 'The service is temporarily unavailable.');
      }),
      http.post('/api/v1/auth/logout', () => {
        logoutCalled = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user, router } = renderApp('/');

    expect(await screen.findByRole('alert')).toHaveTextContent(UNAVAILABLE);
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/'); // not redirected to /login
    expect(logoutCalled).toBe(false); // nothing revoked
    expect(useSession.getState().status).toBe('unavailable');

    serviceUp = true;
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('heading', { name: 'Hi, Jane Doe!' })).toBeInTheDocument();
    expect(refreshCalls).toBe(2);
    expect(logoutCalled).toBe(false);
  });

  it.each([
    ['the network is down', () => HttpResponse.error()],
    ['the gateway rate-limits the refresh', () => new HttpResponse(null, { status: 429 })],
  ])('also shows the notice when %s', async (_reason, response) => {
    server.use(http.post('/api/v1/auth/refresh', response));

    renderApp('/');

    expect(await screen.findByRole('alert')).toHaveTextContent(UNAVAILABLE);
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('sends a visitor without a session to sign-in once the service is back', async () => {
    let serviceUp = false;
    server.use(
      http.post('/api/v1/auth/refresh', () =>
        serviceUp
          ? problem(401, 'INVALID_REFRESH_TOKEN', 'Sign in again.')
          : problem(503, 'SERVICE_UNAVAILABLE', 'The service is temporarily unavailable.'),
      ),
    );
    const { user, router } = renderApp('/');
    await screen.findByRole('alert');

    serviceUp = true;
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('still treats an invalid refresh token (401) as signed out, without the outage notice', async () => {
    renderApp('/'); // default handler: 401 INVALID_REFRESH_TOKEN

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByText(UNAVAILABLE)).not.toBeInTheDocument();
  });
});

describe('sign in', () => {
  it('signs in and returns to the originally requested page', async () => {
    let submitted: unknown;
    server.use(
      http.post('/api/v1/auth/login', async ({ request }) => {
        submitted = await request.json();
        return HttpResponse.json(TOKEN);
      }),
    );
    const { user } = renderApp('/');

    await user.type(await screen.findByLabelText('E-mail'), 'jane@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct horse battery staple');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Hi, Jane Doe!' })).toBeInTheDocument();
    expect(submitted).toEqual({ email: 'jane@example.com', password: 'correct horse battery staple' });
  });

  it('shows a generic message for wrong credentials', async () => {
    server.use(http.post('/api/v1/auth/login', () => problem(401, 'INVALID_CREDENTIALS', 'Invalid e-mail or password.')));
    const { user } = renderApp('/login');

    await user.type(await screen.findByLabelText('E-mail'), 'jane@example.com');
    await user.type(screen.getByLabelText('Password'), 'wrong');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('The e-mail address or password is incorrect.');
  });

  it('explains a temporary lockout', async () => {
    server.use(http.post('/api/v1/auth/login', () => problem(423, 'ACCOUNT_LOCKED', 'Locked.')));
    const { user } = renderApp('/login');

    await user.type(await screen.findByLabelText('E-mail'), 'jane@example.com');
    await user.type(screen.getByLabelText('Password'), 'whatever');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/locked for a few minutes/);
  });

  it('validates input before calling the API', async () => {
    const { user } = renderApp('/login');

    await user.type(await screen.findByLabelText('E-mail'), 'not-an-email');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Enter a valid e-mail address.')).toBeInTheDocument();
    expect(screen.getByText('Enter your password.')).toBeInTheDocument();
    expect(screen.getByLabelText('E-mail')).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('registration', () => {
  it('creates a customer account and signs in', async () => {
    let submitted: unknown;
    server.use(
      http.post('/api/v1/auth/register', async ({ request }) => {
        submitted = await request.json();
        return HttpResponse.json(TOKEN, { status: 201 });
      }),
    );
    const { user } = renderApp('/register');

    await user.type(await screen.findByLabelText('Full name'), 'Jane Doe');
    await user.type(screen.getByLabelText('E-mail'), 'jane@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct horse battery staple');
    await user.type(screen.getByLabelText('Confirm password'), 'correct horse battery staple');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByRole('heading', { name: 'Hi, Jane Doe!' })).toBeInTheDocument();
    expect(submitted).toEqual({
      fullName: 'Jane Doe',
      email: 'jane@example.com',
      password: 'correct horse battery staple',
      accountType: 'CUSTOMER',
    });
  });

  it('enforces the password policy and confirmation locally', async () => {
    const { user } = renderApp('/register');

    await user.type(await screen.findByLabelText('Password'), 'short');
    await user.type(screen.getByLabelText('Confirm password'), 'different');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText(/Use at least 12 characters/)).toBeInTheDocument();
    expect(screen.getByText('The passwords do not match.')).toBeInTheDocument();
  });

  it('attaches a duplicate e-mail error to the e-mail field', async () => {
    server.use(
      http.post('/api/v1/auth/register', () => problem(409, 'EMAIL_ALREADY_REGISTERED', 'Already exists.')),
    );
    const { user } = renderApp('/register');

    await user.type(await screen.findByLabelText('Full name'), 'Jane Doe');
    await user.type(screen.getByLabelText('E-mail'), 'jane@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct horse battery staple');
    await user.type(screen.getByLabelText('Confirm password'), 'correct horse battery staple');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() =>
      expect(screen.getByLabelText('E-mail')).toHaveAccessibleDescription(
        'An account with this e-mail address already exists.',
      ),
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
