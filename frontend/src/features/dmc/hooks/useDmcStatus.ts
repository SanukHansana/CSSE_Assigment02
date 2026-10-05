import { useCallback, useEffect, useRef, useState } from 'react';

import { getErrorMessage } from '../../../utils/getErrorMessage';
import type { DmcService } from '../services/DmcService';
import type { DmcStatus } from '../types/DmcStatus';

type DmcState =
  | { phase: 'idle' }
  | { phase: 'loading' }
  | { phase: 'success'; data: DmcStatus }
  | { phase: 'error'; message: string };

/** Owns request lifecycle. Screens only render state and invoke actions. */
export function useDmcStatus(service: DmcService) {
  const [state, setState] = useState<DmcState>({ phase: 'idle' });
  const activeRequest = useRef<AbortController | null>(null);

  useEffect(() => {
    return () => {
      activeRequest.current?.abort();
      activeRequest.current = null;
    };
  }, [service]);

  const refresh = useCallback(async () => {
    activeRequest.current?.abort();
    const controller = new AbortController();
    activeRequest.current = controller;
    setState({ phase: 'loading' });
    const timeout = setTimeout(() => controller.abort(), 10000);

    try {
      const data = await service.getStatus(controller.signal);
      if (activeRequest.current === controller && !controller.signal.aborted) {
        setState({ phase: 'success', data });
      }
    } catch (error: unknown) {
      if (activeRequest.current === controller) {
        setState({
          phase: 'error',
          message: controller.signal.aborted
            ? 'The request timed out. Check your connection and try again.'
            : getErrorMessage(error),
        });
      }
    } finally {
      clearTimeout(timeout);
      if (activeRequest.current === controller) {
        activeRequest.current = null;
      }
    }
  }, [service]);

  return { state, refresh };
}
