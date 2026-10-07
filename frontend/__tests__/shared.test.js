import React from 'react';
import {
  render,
  screen,
  fireEvent,
  waitFor,
  cleanup,
  act,
  renderHook,
} from '@testing-library/react';
import { AuthProvider, useAuth } from '../src/application/AuthContext';
import { AppNavigation } from '../src/components/layout/AppNavigation';
import { DmcScreen } from '../src/features/dmc/screens/DmcScreen';
import { HomeScreen } from '../src/features/dmc/screens/HomeScreen';
import { useDmcStatus } from '../src/features/dmc/hooks/useDmcStatus';
import { Action } from '../src/components/ui/Action';
import { router } from 'expo-router';
function Login({ role, status }) {
  const { setSession } = useAuth();
  return (
    <button
      onClick={() =>
        setSession({
          accessToken: 'token',
          user: { displayName: 'Test User', roles: role ? [role] : [], approvalStatus: status },
        })
      }
    >
      Set account
    </button>
  );
}
afterEach(() => {
  cleanup();
  jest.useRealTimers();
});
test('auth hook requires provider and sessions persist across child screens', () => {
  const error = jest.spyOn(console, 'error').mockImplementation(() => {});
  expect(() => renderHook(() => useAuth())).toThrow('AuthProvider is required.');
  error.mockRestore();
  const { result } = renderHook(() => useAuth(), { wrapper: AuthProvider });
  expect(result.current.session).toBeNull();
  act(() => result.current.setSession({ accessToken: 't', user: { roles: ['CITIZEN'] } }));
  expect(result.current.session.accessToken).toBe('t');
});
test.each([
  ['CITIZEN', 'Citizen', 'My Needs', undefined],
  ['COMMUNITY_VOLUNTEER', 'Community Volunteer', 'Community Support', undefined],
  ['DUTY_OFFICER', 'Duty Officer', 'Review Reports', undefined],
  ['DMC_OFFICER', 'DMC Officer', 'Rescue Operations', undefined],
  ['ADMIN', 'Administrator', 'Officer Approvals', undefined],
  [null, 'Awaiting Approval', null, 'PENDING'],
  [null, 'Application Rejected', null, 'REJECTED'],
])('navigation reflects %s and logout removes account', (role, label, link, status) => {
  render(
    <AuthProvider>
      <Login role={role} status={status} />
      <AppNavigation />
    </AuthProvider>,
  );
  expect(screen.getByRole('link', { name: 'Sign in' })).toBeTruthy();
  fireEvent.click(screen.getByText('Set account'));
  expect(screen.getByText(label)).toBeTruthy();
  if (link) expect(screen.getByRole('link', { name: link })).toBeTruthy();
  expect(screen.queryByRole('link', { name: 'Sign in' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Log out' }));
  expect(router.replace).toHaveBeenCalledWith('/login');
  expect(screen.getByRole('link', { name: 'Sign in' })).toBeTruthy();
});
test('home exposes community support and primary modules', () => {
  render(<HomeScreen />);
  expect(screen.getByText(/Community support/)).toBeTruthy();
  expect(screen.getAllByRole('link').length).toBeGreaterThan(3);
});
test('connection screen renders loading, validated success and error', async () => {
  let resolve;
  const service = {
    getStatus: jest.fn().mockImplementationOnce(() => new Promise((r) => (resolve = r))),
  };
  render(<DmcScreen service={service} />);
  fireEvent.click(screen.getByRole('button', { name: 'Check connection' }));
  expect(screen.getByRole('button').disabled).toBe(true);
  await act(async () => resolve({ application: 'DMC', status: 'UP' }));
  await screen.findByText('DMC: UP');
  service.getStatus.mockRejectedValueOnce(new Error('Offline'));
  fireEvent.click(screen.getByRole('button', { name: 'Check connection' }));
  await screen.findByText('Offline');
});
test('request timeout aborts and unmount aborts active request', async () => {
  jest.useFakeTimers();
  let reject;
  const service = {
    getStatus: jest.fn(
      (signal) =>
        new Promise((resolve, r) => {
          reject = r;
          signal.addEventListener('abort', () => r(new Error('abort')));
        }),
    ),
  };
  const { result, unmount } = renderHook(() => useDmcStatus(service));
  act(() => {
    void result.current.refresh();
  });
  await act(async () => jest.advanceTimersByTime(10000));
  expect(result.current.state).toEqual({
    phase: 'error',
    message: 'The request timed out. Check your connection and try again.',
  });
  act(() => {
    void result.current.refresh();
  });
  const signal = service.getStatus.mock.calls.at(-1)[0];
  unmount();
  expect(signal.aborted).toBe(true);
});
test('new request supersedes older result and supports unknown error', async () => {
  const pending = [];
  const service = {
    getStatus: jest.fn(() => new Promise((resolve, reject) => pending.push({ resolve, reject }))),
  };
  const { result } = renderHook(() => useDmcStatus(service));
  act(() => {
    void result.current.refresh();
    void result.current.refresh();
  });
  await act(async () => pending[0].resolve({ application: 'old', status: 'UP' }));
  expect(result.current.state.phase).toBe('loading');
  await act(async () => pending[1].reject('Offline'));
  expect(result.current.state.phase).toBe('error');
});
test('action handles pressed, destructive and secondary button states', () => {
  const click = jest.fn();
  const { rerender } = render(<Action title="Verify" onPress={click} />);
  fireEvent.mouseDown(screen.getByRole('button'));
  fireEvent.click(screen.getByRole('button'));
  expect(click).toHaveBeenCalledTimes(1);
  rerender(<Action title="Reject" disabled danger onPress={click} />);
  fireEvent.click(screen.getByRole('button'));
  expect(click).toHaveBeenCalledTimes(1);
  rerender(<Action title="Back" secondary onPress={click} />);
  expect(screen.getByRole('button', { name: 'Back' })).toBeTruthy();
});
