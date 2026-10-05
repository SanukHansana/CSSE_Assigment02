import { env } from '../config/env';
import { createDmcService } from '../features/dmc/services/createDmcService';
import { createFetchHttpClient } from '../services/http/createFetchHttpClient';

/** Composition root: swap adapters here without changing screens or business logic. */
const httpClient = createFetchHttpClient(env.apiBaseUrl);

export const services = {
  dmc: createDmcService(httpClient),
};
