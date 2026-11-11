import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router';
import { TextField } from '../../shared/ui/TextField';
import { errorMessage } from './errors';
import { useLogin } from './hooks';
import { loginSchema, type LoginValues } from './schemas';

export function LoginPage() {
  const login = useLogin();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { email: '', password: '' } });

  return (
    <section className="card auth-card" aria-labelledby="login-title">
      <h1 id="login-title">Sign in</h1>
      <p className="muted">Welcome back! Hungry already?</p>

      {login.isError && (
        <div className="alert" role="alert">
          {errorMessage(login.error)}
        </div>
      )}

      <form noValidate onSubmit={handleSubmit((values) => login.mutate(values))}>
        <TextField label="E-mail" type="email" autoComplete="email" error={errors.email?.message} {...register('email')} />
        <TextField
          label="Password"
          type="password"
          autoComplete="current-password"
          error={errors.password?.message}
          {...register('password')}
        />
        <button type="submit" className="primary" disabled={login.isPending}>
          {login.isPending ? 'Signing in…' : 'Sign in'}
        </button>
      </form>

      <p className="muted">
        New to FoodFlow? <Link to="/register">Create an account</Link>
      </p>
    </section>
  );
}
