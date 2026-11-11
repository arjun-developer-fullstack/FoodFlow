import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

export const TOKEN = {
  accessToken: 'access-token-1',
  tokenType: 'Bearer',
  expiresIn: 900,
  expiresAt: new Date(Date.now() + 900_000).toISOString(),
} as const;

export const PROFILE = {
  id: '0192f0c4-7d4e-7a2b-9c1d-0123456789ab',
  email: 'jane@example.com',
  fullName: 'Jane Doe',
  phone: null,
  roles: ['CUSTOMER'],
  emailVerified: false,
  createdAt: '2026-11-07T10:00:00Z',
};

export function problem(status: number, code: string, detail: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `https://problems.foodflow.com/${code.toLowerCase()}`, status, title: 'Error', detail, code, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** Default: no refresh cookie (anonymous visitor). Tests override handlers as needed. */
export const handlers = [
  http.post('/api/v1/auth/refresh', () => problem(401, 'INVALID_REFRESH_TOKEN', 'Sign in again.')),
  http.get('/api/v1/users/me', ({ request }) =>
    request.headers.get('Authorization') === `Bearer ${TOKEN.accessToken}`
      ? HttpResponse.json(PROFILE)
      : problem(401, 'UNAUTHENTICATED', 'A valid access token is required.'),
  ),
];

export const server = setupServer(...handlers);
