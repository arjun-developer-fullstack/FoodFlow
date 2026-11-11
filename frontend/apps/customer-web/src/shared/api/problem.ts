/** RFC 9457 problem details as returned by every FoodFlow service and the gateway. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  correlationId?: string;
  errors?: FieldViolation[];
}

export interface FieldViolation {
  field: string;
  message: string;
}

export const NETWORK_ERROR = 'NETWORK_ERROR';
export const RATE_LIMITED = 'RATE_LIMITED';

/** A failed API call with the server's stable error code (e.g. INVALID_CREDENTIALS). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldViolation[];
  readonly correlationId: string | undefined;

  constructor(status: number, code: string, message: string, fieldErrors: FieldViolation[] = [], correlationId?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
    this.correlationId = correlationId;
  }
}

const CODE_BY_STATUS: Record<number, string> = {
  400: 'BAD_REQUEST',
  401: 'UNAUTHENTICATED',
  403: 'ACCESS_DENIED',
  404: 'NOT_FOUND',
  409: 'CONFLICT',
  429: RATE_LIMITED,
};

/** Builds an {@link ApiError} from any failed response, including ones without a JSON body. */
export async function toApiError(response: Response): Promise<ApiError> {
  const correlationId = response.headers.get('X-Correlation-Id') ?? undefined;
  let problem: ProblemDetail = {};
  const contentType = response.headers.get('Content-Type') ?? '';
  if (contentType.includes('json')) {
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      problem = {};
    }
  }
  const code = problem.code ?? CODE_BY_STATUS[response.status] ?? (response.status >= 500 ? 'SERVER_ERROR' : 'REQUEST_FAILED');
  const message = problem.detail ?? problem.title ?? `Request failed with status ${response.status}`;
  return new ApiError(response.status, code, message, problem.errors ?? [], problem.correlationId ?? correlationId);
}
