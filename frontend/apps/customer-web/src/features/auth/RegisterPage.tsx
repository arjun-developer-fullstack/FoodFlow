import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router';
import { ApiError } from '../../shared/api/problem';
import { TextField } from '../../shared/ui/TextField';
import { errorMessage } from './errors';
import { useRegister } from './hooks';
import { PASSWORD_MIN, registerSchema, type RegisterValues } from './schemas';

const SERVER_FIELDS = ['fullName', 'email', 'password'] as const;

export function RegisterPage() {
  const signUp = useRegister();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: { fullName: '', email: '', password: '', confirmPassword: '' },
  });

  const onSubmit = handleSubmit(({ fullName, email, password }) =>
    signUp.mutate(
      { fullName, email, password },
      {
        onError: (error) => {
          if (!(error instanceof ApiError)) {
            return;
          }
          if (error.code === 'EMAIL_ALREADY_REGISTERED') {
            setError('email', { message: errorMessage(error) });
          }
          for (const violation of error.fieldErrors) {
            const field = SERVER_FIELDS.find((name) => name === violation.field);
            if (field) {
              setError(field, { message: violation.message });
            }
          }
        },
      },
    ),
  );

  const generalError = signUp.isError && !(signUp.error instanceof ApiError && signUp.error.code === 'EMAIL_ALREADY_REGISTERED');

  return (
    <section className="card auth-card" aria-labelledby="register-title">
      <h1 id="register-title">Create your account</h1>
      <p className="muted">Order from the best restaurants around you.</p>

      {generalError && (
        <div className="alert" role="alert">
          {errorMessage(signUp.error)}
        </div>
      )}

      <form noValidate onSubmit={onSubmit}>
        <TextField label="Full name" autoComplete="name" error={errors.fullName?.message} {...register('fullName')} />
        <TextField label="E-mail" type="email" autoComplete="email" error={errors.email?.message} {...register('email')} />
        <TextField
          label="Password"
          type="password"
          autoComplete="new-password"
          hint={`At least ${PASSWORD_MIN} characters.`}
          error={errors.password?.message}
          {...register('password')}
        />
        <TextField
          label="Confirm password"
          type="password"
          autoComplete="new-password"
          error={errors.confirmPassword?.message}
          {...register('confirmPassword')}
        />
        <button type="submit" className="primary" disabled={signUp.isPending}>
          {signUp.isPending ? 'Creating account…' : 'Create account'}
        </button>
      </form>

      <p className="muted">
        Already have an account? <Link to="/login">Sign in</Link>
      </p>
    </section>
  );
}
