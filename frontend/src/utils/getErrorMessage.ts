/** Convert unknown thrown values into a safe, predictable UI message. */
export function getErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Something went wrong. Please try again.';
}
