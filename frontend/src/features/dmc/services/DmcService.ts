import type { DmcStatus } from '../types/DmcStatus';

export interface DmcService {
  getStatus(signal?: AbortSignal): Promise<DmcStatus>;
}
