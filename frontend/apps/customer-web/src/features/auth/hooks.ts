import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router';
import { authApi, type Credentials, type Registration } from './api';
import { useSession } from './session';

export const PROFILE_QUERY_KEY = ['profile'] as const;

/** Where to go after signing in: the page that redirected to /login, or home. */
function useReturnPath(): string {
  const location = useLocation();
  const from = (location.state as { from?: { pathname?: string; search?: string; hash?: string } } | null)?.from;
  if (!from?.pathname || from.pathname === '/login' || from.pathname === '/register') {
    return '/';
  }
  return `${from.pathname}${from.search ?? ''}${from.hash ?? ''}`;
}

export function useLogin() {
  const signIn = useSession((state) => state.signIn);
  const navigate = useNavigate();
  const returnPath = useReturnPath();
  return useMutation({
    mutationFn: (credentials: Credentials) => authApi.login(credentials),
    onSuccess: (token) => {
      signIn(token);
      void navigate(returnPath, { replace: true });
    },
  });
}

export function useRegister() {
  const signIn = useSession((state) => state.signIn);
  const navigate = useNavigate();
  return useMutation({
    mutationFn: (registration: Registration) => authApi.register(registration),
    onSuccess: (token) => {
      signIn(token);
      void navigate('/', { replace: true });
    },
  });
}

export function useLogout() {
  const signOut = useSession((state) => state.signOut);
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return useMutation({
    mutationFn: () => authApi.logout(),
    // Sign out locally even if the server call fails: the user asked to leave.
    onSettled: () => {
      signOut();
      queryClient.clear();
      void navigate('/login', { replace: true });
    },
  });
}

export function useProfile() {
  const authenticated = useSession((state) => state.status === 'authenticated');
  return useQuery({
    queryKey: PROFILE_QUERY_KEY,
    queryFn: ({ signal }) => authApi.profile(signal),
    enabled: authenticated,
    staleTime: 60_000,
  });
}
