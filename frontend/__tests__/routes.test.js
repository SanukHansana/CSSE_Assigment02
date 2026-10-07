import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import { AuthProvider } from '../src/application/AuthContext';
jest.mock('expo-status-bar', () => ({ StatusBar: () => null }));
afterEach(cleanup);
test.each([
  'index',
  'connection',
  'admin',
  'warnings',
  'rescue',
  'relief',
  'register',
  'ground-reports',
  'login',
  'community',
  '_layout',
])('Expo route %s renders its entry screen', (name) => {
  const Component = require('../src/app/' + name).default;
  const view = render(
    <AuthProvider>
      <Component />
    </AuthProvider>,
  );
  expect(view.container.textContent.length).toBeGreaterThan(0);
});
test('legacy application entry renders connection screen', () => {
  const { App } = require('../src/application/App');
  render(<App />);
  expect(screen.getByRole('button', { name: 'Check connection' })).toBeTruthy();
});
