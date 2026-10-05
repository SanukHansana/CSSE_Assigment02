import { apiRoutes } from '../../../constants/apiRoutes';
import type { HttpClient } from '../../../services/http/HttpClient';
import { parseDmcStatus } from '../schemas/dmcStatusSchema';
import type { DmcService } from './DmcService';

/** Factory injects transport; the feature owns endpoint selection and validation. */
export function createDmcService(httpClient: HttpClient): DmcService {
  return {
    async getStatus(signal) {
      return parseDmcStatus(await httpClient.get(apiRoutes.dmc, signal));
    },
  };
}
