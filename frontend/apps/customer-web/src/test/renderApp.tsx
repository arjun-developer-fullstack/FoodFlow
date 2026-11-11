import { QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { createQueryClient } from '../app/queryClient';
import { routes } from '../app/routes';
import { SessionBootstrap } from '../features/auth/guards';

/** Renders the real route tree at {@code path}, exactly as the app composes it. */
export function renderApp(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const queryClient = createQueryClient();
  const user = userEvent.setup();
  const view = render(
    <QueryClientProvider client={queryClient}>
      <SessionBootstrap>
        <RouterProvider router={router} />
      </SessionBootstrap>
    </QueryClientProvider>,
  );
  return { ...view, router, user };
}
