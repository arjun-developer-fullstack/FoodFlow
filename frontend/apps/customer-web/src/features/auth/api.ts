import { apiRequest } from '../../shared/api/http';

/** Mirrors user-service AuthDtos.TokenResponse. The refresh token is only ever a cookie. */
export interface TokenResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  expiresAt: string;
}

/** Mirrors user-service UserDtos.UserProfileResponse. */
export interface UserProfile {
  id: string;
  email: string;
  fullName: string;
  phone: string | null;
  roles: string[];
  emailVerified: boolean;
  createdAt: string;
}

export interface Credentials {
  email: string;
  password: string;
}

export interface Registration extends Credentials {
  fullName: string;
}

export const authApi = {
  login: (credentials: Credentials) =>
    apiRequest<TokenResponse>('/api/v1/auth/login', { method: 'POST', body: credentials, authenticated: false }),

  register: (registration: Registration) =>
    apiRequest<TokenResponse>('/api/v1/auth/register', {
      method: 'POST',
      body: { ...registration, accountType: 'CUSTOMER' },
      authenticated: false,
    }),

  refresh: () => apiRequest<TokenResponse>('/api/v1/auth/refresh', { method: 'POST', authenticated: false }),

  logout: () => apiRequest<void>('/api/v1/auth/logout', { method: 'POST', authenticated: false }),

  profile: (signal?: AbortSignal) =>
    apiRequest<UserProfile>('/api/v1/users/me', signal ? { signal } : {}),
};
