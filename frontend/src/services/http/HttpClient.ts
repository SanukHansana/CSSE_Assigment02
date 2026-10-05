/** Consumers depend on this small contract, rather than the fetch implementation. */
export interface HttpClient {
  get(path: string, signal?: AbortSignal): Promise<unknown>;
}
