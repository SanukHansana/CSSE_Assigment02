import type { HttpClient } from './HttpClient';

export class HttpError extends Error {
  constructor(public readonly status: number) {
    super(`Request failed (HTTP ${status}).`);
    this.name = 'HttpError';
  }
}

/** Fetch adapter. JSON remains unknown until the feature validates its shape. */
export function createFetchHttpClient(baseUrl: string): HttpClient {
  const normalizedBaseUrl = baseUrl.replace(/\/+$/, '');

  return {
    async get(path, signal) {
      const response = await fetch(`${normalizedBaseUrl}${path}`, {
        headers: { Accept: 'application/json' },
        signal,
      });

      if (!response.ok) {
        throw new HttpError(response.status);
      }

      return response.json() as Promise<unknown>;
    },
  };
}
