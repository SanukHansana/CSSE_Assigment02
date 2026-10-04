import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import * as RN from 'react-native';
import { AppNavigation, MobileTabs } from '../src/components/layout/AppNavigation';
import { HomeScreen } from '../src/features/dmc/screens/HomeScreen';
import { ReporterScreen } from '../src/features/reports/ReporterScreen';
import { OfficerScreen } from '../src/features/reports/OfficerScreen';
import { ReliefScreen } from '../src/features/relief/ReliefScreen';
import { WarningScreen } from '../src/features/warnings/WarningScreen';
import { RescueScreen } from '../src/features/rescue/RescueScreen';
import { CommunityScreen } from '../src/features/community/CommunityScreen';
import { DmcDashboard } from '../src/features/dmc/screens/DmcDashboard';
import { useAuth } from '../src/application/AuthContext';
import { router } from 'expo-router';
jest.mock('../src/application/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('../src/components/maps/MapPicker', () => ({
  MapPicker: () => <div>Location picker</div>,
}));
const setSession = jest.fn();
let session;
beforeEach(() => {
  jest.clearAllMocks();
  RN.useWindowDimensions.mockReturnValue({ width: 390, height: 844, scale: 1, fontScale: 1 });
  session = { accessToken: 'token', user: { displayName: 'User', roles: ['CITIZEN'] } };
  useAuth.mockImplementation(() => ({ session, setSession }));
});
afterEach(() => {
  cleanup();
  RN.useWindowDimensions.mockReturnValue({ width: 1280, height: 900, scale: 1, fontScale: 1 });
});
test.each([
  ['CITIZEN', 'My Needs'],
  ['COMMUNITY_VOLUNTEER', 'Community Support'],
  ['DUTY_OFFICER', 'Review Reports'],
  ['DMC_OFFICER', 'Rescue Operations'],
])('phone tabs retain %s workspaces and account menu logs out', (role, link) => {
  session.user.roles = [role];
  render(
    <>
      <AppNavigation />
      <MobileTabs />
    </>,
  );
  expect(screen.getByRole('link', { name: link })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Log out' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Account menu' }));
  expect(screen.getByText('User')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Log out' }));
  expect(setSession).toHaveBeenCalledWith(null);
  expect(router.replace).toHaveBeenCalledWith('/login');
});
test('guest mobile account menu and tabs open login/registration', () => {
  session = null;
  render(
    <>
      <AppNavigation />
      <MobileTabs />
    </>,
  );
  expect(screen.getByRole('link', { name: 'Sign in' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Account menu' }));
  fireEvent.click(screen.getByRole('link', { name: 'Create account →' }));
  expect(screen.queryByRole('link', { name: 'Create account →' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Account menu' }));
  fireEvent.click(screen.getByRole('link', { name: 'Sign in →' }));
  expect(screen.queryByRole('link', { name: 'Sign in →' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Account menu' }));
  fireEvent.click(screen.getByRole('link', { name: 'Check service connection →' }));
  expect(screen.queryByRole('link', { name: 'Check service connection →' })).toBeNull();
});
test('admin retains desktop navigation at phone width', () => {
  session.user.roles = ['ADMIN'];
  render(
    <>
      <AppNavigation />
      <MobileTabs />
    </>,
  );
  expect(screen.getByRole('link', { name: 'Officer Approvals' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Account menu' })).toBeNull();
});
test('home mobile disclosure expands and collapses flow', () => {
  render(<HomeScreen />);
  expect(screen.queryByText('Describe the hazard')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'How reporting works +' }));
  expect(screen.getByText('Describe the hazard')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Hide reporting flow −' }));
  expect(screen.queryByText('Describe the hazard')).toBeNull();
});
test.each([
  [ReliefScreen, 'Relief Resources & Shelters'],
  [WarningScreen, 'Issue & Broadcast Disaster Warnings'],
  [RescueScreen, 'Coordinate Rescue Operations'],
  [OfficerScreen, 'Ground Reports'],
])('phone %p renders touch-sized controls', (Component, title) => {
  session.user.roles = [Component === OfficerScreen ? 'DUTY_OFFICER' : 'DMC_OFFICER'];
  render(<Component session={session} onSignOut={setSession} />);
  expect(screen.getAllByRole('button').length).toBeGreaterThan(1);
  expect(screen.getAllByRole('heading').length).toBeGreaterThan(0);
});
test('citizen, volunteer, login and registration remain available on phone', () => {
  render(<ReporterScreen />);
  expect(screen.getByRole('button', { name: 'Save as Draft' })).toBeTruthy();
  cleanup();
  session.user.roles = ['COMMUNITY_VOLUNTEER'];
  render(<CommunityScreen />);
  expect(screen.getByRole('button', { name: 'My Offers' })).toBeTruthy();
  cleanup();
  session = null;
  render(<ReporterScreen authMode="register" />);
  expect(screen.getByRole('button', { name: 'Register' })).toBeTruthy();
});
test('DMC dashboard cards keep their workflows on phone', () => {
  session.user.roles = ['DMC_OFFICER'];
  render(<DmcDashboard />);
  expect(screen.getAllByRole('link', { name: 'Open workspace →' })).toHaveLength(3);
});
