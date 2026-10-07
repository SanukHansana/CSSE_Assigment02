import React from 'react';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { CommunityScreen } from '../src/features/community/CommunityScreen';
import { request, ApiError } from '../src/features/reports/api';
import { useAuth } from '../src/application/AuthContext';
jest.mock('../src/features/reports/api', () => ({
  ...jest.requireActual('../src/features/reports/api'),
  request: jest.fn(),
}));
jest.mock('../src/application/AuthContext', () => ({ useAuth: jest.fn() }));
const setSession = jest.fn();
const need = (overrides = {}) => ({
  id: 'n1',
  version: 0,
  citizenName: 'Citizen',
  item: 'Water',
  category: 'WATER',
  quantity: 10,
  unit: 'bottles',
  description: 'Drinking water needed',
  area: 'Colombo',
  status: 'OPEN',
  received: 0,
  remainingToCommit: 10,
  mine: false,
  offers: [],
  history: [],
  ...overrides,
});
function mount(role = 'CITIZEN') {
  useAuth.mockReturnValue({
    session: role ? { accessToken: 'token', user: { roles: [role] } } : null,
    setSession,
  });
  return render(<CommunityScreen />);
}
const click = (name) => fireEvent.click(screen.getByRole('button', { name }));
beforeEach(() => {
  jest.clearAllMocks();
  request.mockReset();
});
afterEach(cleanup);
test('guest and staff have no citizen/volunteer actions', () => {
  mount(null);
  expect(screen.getByText('Sign in to request or offer support')).toBeTruthy();
  cleanup();
  mount('DMC_OFFICER');
  expect(screen.getByText(/approved Citizen and Community/)).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Post Need' })).toBeNull();
});
test('citizen validates and posts a need with numeric quantity', async () => {
  mount();
  click('Post Need');
  await screen.findByRole('alert');
  expect(request).not.toHaveBeenCalled();
  for (const [label, value] of [
    ['Item needed', 'Water'],
    ['Quantity needed', '10'],
    ['Unit (bottles, packs, etc.)', 'bottles'],
    ['Describe your need', 'Drinking water needed'],
    ['Area / district', 'Colombo'],
  ])
    fireEvent.change(screen.getByLabelText(label), { target: { value } });
  request.mockResolvedValueOnce(need({ mine: true }));
  click('Post Need');
  await screen.findByText(/Your need is posted/);
  expect(request).toHaveBeenCalledWith(
    '/api/dmc/community/needs',
    'token',
    'POST',
    expect.objectContaining({ quantity: 10, item: 'Water' }),
  );
  click('Back to Needs');
  expect(screen.getByLabelText('Item needed').value).toBe('');
});
async function open(role, value) {
  mount(role);
  request.mockResolvedValueOnce({ items: [value], totalPages: 2 });
  click('Refresh Needs');
  await screen.findByRole('button', { name: 'View Need' });
  request.mockResolvedValueOnce(value);
  click('View Need');
  await screen.findByRole('button', { name: 'Reload Need' });
}
test('volunteer offers own supplies and preserves request ID after uncertain failure', async () => {
  await open('COMMUNITY_VOLUNTEER', need());
  click('Offer Personal Resources');
  await screen.findByRole('alert');
  fireEvent.change(screen.getByLabelText('Personal resource quantity'), { target: { value: '3' } });
  fireEvent.change(screen.getByLabelText('Personal resource offer note'), {
    target: { value: 'My own bottles' },
  });
  request.mockRejectedValueOnce(new Error('Connection lost'));
  click('Offer Personal Resources');
  await screen.findByText('Connection lost');
  const first = request.mock.calls.at(-1)[3];
  request.mockResolvedValueOnce(
    need({
      version: 1,
      offers: [
        {
          id: 'o',
          mine: true,
          volunteerName: 'Volunteer',
          quantity: 3,
          note: 'My own bottles',
          status: 'OFFERED',
        },
      ],
    }),
  );
  click('Offer Personal Resources');
  await screen.findByText(/Support action recorded/);
  expect(request.mock.calls.at(-1)[3].requestId).toBe(first.requestId);
  expect(first).toMatchObject({ quantity: 3, expectedVersion: 0, note: 'My own bottles' });
});
test.each([
  ['ACCEPT', 'OFFERED', 'Accept Offer'],
  ['DECLINE', 'OFFERED', 'Decline Offer'],
  ['RECEIVE', 'DELIVERED', 'Confirm Receipt'],
  ['DELIVER', 'ACCEPTED', 'Mark Delivered'],
  ['WITHDRAW', 'OFFERED', 'Withdraw Offer'],
])('%s follows ownership and offer state', async (action, status, label) => {
  const citizen = ['ACCEPT', 'DECLINE', 'RECEIVE'].includes(action);
  const value = need({
    mine: citizen,
    offers: [
      {
        id: 'o1',
        mine: !citizen,
        volunteerName: 'Volunteer',
        quantity: 10,
        note: 'Supplies',
        status,
      },
    ],
  });
  await open(citizen ? 'CITIZEN' : 'COMMUNITY_VOLUNTEER', value);
  request.mockResolvedValueOnce({ ...value, version: 1 });
  click(label);
  await waitFor(() =>
    expect(request.mock.calls.at(-1)[3]).toMatchObject({ action, expectedVersion: 0 }),
  );
  expect(request.mock.calls.at(-1)[0]).toBe('/api/dmc/community/needs/n1/offers/o1/action');
});
test('citizen cancellation and detail refresh use server version', async () => {
  const value = need({ mine: true });
  await open('CITIZEN', value);
  request.mockResolvedValueOnce({ ...value, version: 1 });
  click('Reload Need');
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Reload Need' }).disabled).toBe(false),
  );
  request.mockResolvedValueOnce({ ...value, version: 2, status: 'CANCELLED' });
  click('Cancel Need');
  await screen.findByText('CANCELLED');
  expect(request.mock.calls.at(-1)[3]).toMatchObject({ expectedVersion: 1 });
});
test('expired session signs out; API error and unknown error remain readable', async () => {
  mount('COMMUNITY_VOLUNTEER');
  request.mockRejectedValueOnce(new ApiError('Expired', 401));
  click('Refresh Needs');
  await waitFor(() => expect(setSession).toHaveBeenCalledWith(null));
  request.mockRejectedValueOnce('offline');
  click('Refresh Needs');
  await screen.findByText(/Unable to connect/);
  request.mockResolvedValueOnce({ items: [], totalPages: 0 });
  click('Refresh Needs');
  await screen.findByText('No requests in this view yet.');
  click('My Offers');
  expect(screen.getByText('Refresh to load requests.')).toBeTruthy();
});
