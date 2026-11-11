import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { REFRESH_LOCK, useSession } from '../../features/auth/session';
import { problem, server, TOKEN } from '../../test/server';
import { apiRequest } from './http';
import { ApiError } from './problem';

describe('apiRequest', () => {
  it('attaches the in-memory access token', async () => {
    useSession.getState().signIn(TOKEN);

    await expect(apiRequest('/api/v1/users/me')).resolves.toMatchObject({ email: 'jane@example.com' });
  });

  it('refreshes once on 401 and retries the original request', async () => {
    useSession.getState().signIn({ ...TOKEN, accessToken: 'expired-token' });
    let refreshCalls = 0;
    server.use(
      http.post('/api/v1/auth/refresh', () => {
        refreshCalls++;
        return HttpResponse.json(TOKEN);
      }),
    );

    const profile = await apiRequest<{ email: string }>('/api/v1/users/me');

    expect(profile.email).toBe('jane@example.com');
    expect(refreshCalls).toBe(1);
    expect(useSession.getState().accessToken).toBe(TOKEN.accessToken);
  });

  it('shares a single refresh between concurrent requests (refresh tokens are single-use)', async () => {
    useSession.getState().signIn({ ...TOKEN, accessToken: 'expired-token' });
    let refreshCalls = 0;
    server.use(
      http.post('/api/v1/auth/refresh', async () => {
        refreshCalls++;
        await new Promise((resolve) => setTimeout(resolve, 20));
        return HttpResponse.json(TOKEN);
      }),
    );

    await Promise.all([apiRequest('/api/v1/users/me'), apiRequest('/api/v1/users/me'), apiRequest('/api/v1/users/me')]);

    expect(refreshCalls).toBe(1);
  });

  it('keeps the user signed in when refresh fails temporarily (rate limit, outage)', async () => {
    useSession.getState().signIn({ ...TOKEN, accessToken: 'expired-token' });
    server.use(http.post('/api/v1/auth/refresh', () => new HttpResponse(null, { status: 429 })));

    await expect(apiRequest('/api/v1/users/me')).rejects.toMatchObject({ status: 401 });
    expect(useSession.getState().status).toBe('authenticated');
  });

  it('serializes refreshes across browser tabs with the Web Locks API', async () => {
    const lockNames: string[] = [];
    vi.stubGlobal('navigator', {
      ...navigator,
      locks: {
        request: (name: string, work: () => Promise<unknown>) => {
          lockNames.push(name);
          return work();
        },
      },
    });
    try {
      useSession.getState().signIn({ ...TOKEN, accessToken: 'expired-token' });
      server.use(http.post('/api/v1/auth/refresh', () => HttpResponse.json(TOKEN)));

      await apiRequest('/api/v1/users/me');

      expect(lockNames).toEqual([REFRESH_LOCK]);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('ends the session when the refresh token is no longer valid', async () => {
    useSession.getState().signIn({ ...TOKEN, accessToken: 'expired-token' });

    await expect(apiRequest('/api/v1/users/me')).rejects.toMatchObject({ status: 401, code: 'UNAUTHENTICATED' });
    expect(useSession.getState().status).toBe('anonymous');
    expect(useSession.getState().accessToken).toBeNull();
  });

  it('exposes problem-detail codes, field errors and the correlation id', async () => {
    server.use(
      http.post('/api/v1/auth/register', () =>
        problem(400, 'VALIDATION_FAILED', 'Invalid fields', {
          correlationId: 'corr-12345678',
          errors: [{ field: 'email', message: 'must be a well-formed email address' }],
        }),
      ),
    );

    const error = await apiRequest('/api/v1/auth/register', { method: 'POST', body: {}, authenticated: false }).catch(
      (e: unknown) => e,
    );

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 400, code: 'VALIDATION_FAILED', correlationId: 'corr-12345678' });
    expect((error as ApiError).fieldErrors).toEqual([{ field: 'email', message: 'must be a well-formed email address' }]);
  });

  it('maps a gateway 429 without a body to RATE_LIMITED', async () => {
    server.use(http.post('/api/v1/auth/login', () => new HttpResponse(null, { status: 429 })));

    await expect(
      apiRequest('/api/v1/auth/login', { method: 'POST', body: {}, authenticated: false }),
    ).rejects.toMatchObject({ status: 429, code: 'RATE_LIMITED' });
  });

  it('reports unreachable servers as NETWORK_ERROR', async () => {
    server.use(http.get('/api/v1/users/me', () => HttpResponse.error()));

    await expect(apiRequest('/api/v1/users/me')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' });
  });
});
