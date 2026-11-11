import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { installSessionHooks, resetSession } from '../features/auth/session';
import { server } from './server';

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
  installSessionHooks();
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  resetSession();
});

afterAll(() => server.close());
