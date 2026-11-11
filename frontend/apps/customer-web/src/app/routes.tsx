import type { RouteObject } from 'react-router';
import { HomePage } from '../features/account/HomePage';
import { GuestOnly, RequireAuth } from '../features/auth/guards';
import { LoginPage } from '../features/auth/LoginPage';
import { RegisterPage } from '../features/auth/RegisterPage';
import { AppLayout, NotFoundPage } from './AppLayout';

export const routes: RouteObject[] = [
  {
    element: <AppLayout />,
    children: [
      { path: '/login', element: <GuestOnly><LoginPage /></GuestOnly> },
      { path: '/register', element: <GuestOnly><RegisterPage /></GuestOnly> },
      { element: <RequireAuth />, children: [{ path: '/', element: <HomePage /> }] },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
