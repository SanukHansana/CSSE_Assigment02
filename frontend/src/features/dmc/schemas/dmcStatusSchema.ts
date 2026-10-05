import type { DmcStatus } from '../types/DmcStatus';

/** Validate external data at the boundary; TypeScript alone cannot validate JSON. */
export function parseDmcStatus(value: unknown): DmcStatus {
  if (
    typeof value !== 'object' ||
    value === null ||
    !('status' in value) ||
    typeof value.status !== 'string' ||
    !('application' in value) ||
    typeof value.application !== 'string'
  ) {
    throw new Error('The DMC API returned an unexpected response.');
  }

  return { status: value.status, application: value.application };
}
