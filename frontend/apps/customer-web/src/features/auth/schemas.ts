import { z } from 'zod';

/** Mirrors user-service RegisterRequest/LoginRequest validation so users get instant feedback. */
export const PASSWORD_MIN = 12;
export const PASSWORD_MAX = 128;

const email = z.string().trim().min(1, 'Enter your e-mail address.').max(254).pipe(z.email('Enter a valid e-mail address.'));

export const loginSchema = z.object({
  email,
  password: z.string().min(1, 'Enter your password.').max(PASSWORD_MAX),
});

export const registerSchema = z
  .object({
    fullName: z.string().trim().min(1, 'Enter your name.').max(100, 'Use at most 100 characters.'),
    email,
    password: z
      .string()
      .min(PASSWORD_MIN, `Use at least ${PASSWORD_MIN} characters. A short phrase works well.`)
      .max(PASSWORD_MAX, `Use at most ${PASSWORD_MAX} characters.`),
    confirmPassword: z.string(),
  })
  .refine((values) => values.password === values.confirmPassword, {
    path: ['confirmPassword'],
    message: 'The passwords do not match.',
  });

export type LoginValues = z.infer<typeof loginSchema>;
export type RegisterValues = z.infer<typeof registerSchema>;
