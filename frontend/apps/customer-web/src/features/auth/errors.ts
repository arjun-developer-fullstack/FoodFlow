import { ApiError, NETWORK_ERROR, RATE_LIMITED } from '../../shared/api/problem';

const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'The e-mail address or password is incorrect.',
  ACCOUNT_LOCKED: 'Too many failed sign-in attempts. Your account is locked for a few minutes.',
  ACCOUNT_SUSPENDED: 'This account is suspended. Please contact support.',
  EMAIL_ALREADY_REGISTERED: 'An account with this e-mail address already exists.',
  [RATE_LIMITED]: 'Too many attempts. Please wait a moment and try again.',
  [NETWORK_ERROR]: "We can't reach FoodFlow right now. Check your connection and try again.",
};

/** User-facing message for an error; unexpected failures include the support reference. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const known = MESSAGES[error.code];
    if (known) {
      return known;
    }
    if (error.status >= 500) {
      return error.correlationId
        ? `Something went wrong on our side. Reference: ${error.correlationId}`
        : 'Something went wrong on our side. Please try again.';
    }
    return error.message;
  }
  return 'Something went wrong. Please try again.';
}
