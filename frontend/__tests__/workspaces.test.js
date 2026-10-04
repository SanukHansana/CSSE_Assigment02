import React from 'react';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { ReliefScreen } from '../src/features/relief/ReliefScreen';
import { WarningScreen } from '../src/features/warnings/WarningScreen';
import { RescueScreen } from '../src/features/rescue/RescueScreen';
import { AdminScreen } from '../src/features/admin/AdminScreen';
import { DmcDashboard } from '../src/features/dmc/screens/DmcDashboard';
import { request, ApiError } from '../src/features/reports/api';
import { useAuth } from '../src/application/AuthContext';
jest.mock('../src/features/reports/api', () => ({
  ...jest.requireActual('../src/features/reports/api'),
  request: jest.fn(),
}));
jest.mock('../src/application/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('../src/components/maps/MapPicker', () => ({
  MapPicker: ({ onSelect, disabled }) => (
    <button disabled={disabled} onClick={() => onSelect?.(7, 80)}>
      Choose map point
    </button>
  ),
}));
const session = { accessToken: 'token', user: { displayName: 'Officer', roles: ['DMC_OFFICER'] } };
const onSignOut = jest.fn();
const resource = {
  id: 'r',
  version: 0,
  name: 'Water',
  type: 'WATER',
  location: 'Colombo',
  availableQuantity: 100,
  unit: 'bottles',
  updatedBy: 'Officer',
  updatedAt: '2026-10-13T12:00:00Z',
  history: [],
  allocations: [],
};
const shelter = {
  id: 's',
  version: 0,
  name: 'School',
  location: 'Colombo',
  capacity: 100,
  occupancy: 20,
  status: 'OPEN',
  updatedBy: 'Officer',
  updatedAt: '2026-10-13T12:00:00Z',
  history: [],
};
const incident = {
  id: 'i',
  version: 0,
  reference: 'INC-1',
  type: 'FLOOD_RESCUE',
  priority: 'HIGH',
  description: 'Flood rescue',
  location: 'Colombo',
  latitude: 7,
  longitude: 80,
  peopleNeedingAssistance: 5,
  status: 'OPEN',
  updatedBy: 'Officer',
  updatedAt: '2026-10-13T12:00:00Z',
  assignments: [],
  history: [],
};
const team = {
  id: 't',
  version: 0,
  name: 'Team One',
  specialization: 'FLOOD_RESCUE',
  location: 'Colombo',
  latitude: 7,
  longitude: 80,
  status: 'AVAILABLE',
  activeIncidentId: null,
  updatedAt: '2026-10-13T12:00:00Z',
};
const warning = {
  id: 'w',
  version: 0,
  reference: 'WAR-1',
  hazard: 'Flood',
  level: 'HIGH',
  affectedArea: 'Colombo',
  message: 'Move away',
  instructions: 'Higher ground',
  channels: ['SMS'],
  validUntil: '2099-10-13T12:00:00Z',
  status: 'DRAFT',
  createdBy: 'Officer',
  createdAt: '2026-10-13T12:00:00Z',
  deliveries: [],
  history: [],
};
const click = (name) => fireEvent.click(screen.getByRole('button', { name }));
const fill = (name, value) => fireEvent.change(screen.getByLabelText(name), { target: { value } });
async function idle(name) {
  await waitFor(() => expect(screen.getByRole('button', { name }).disabled).toBe(false));
}
beforeEach(() => {
  jest.clearAllMocks();
  request.mockReset();
  useAuth.mockReturnValue({ session, setSession: onSignOut });
});
afterEach(cleanup);
function mount(Component, role = 'DMC_OFFICER') {
  return render(
    <Component
      session={{ ...session, user: { ...session.user, roles: [role] } }}
      onSignOut={onSignOut}
    />,
  );
}
async function edit(value, refresh = 'Refresh List') {
  request.mockResolvedValueOnce({ items: [value], totalPages: 2, totalElements: 1 });
  click(refresh);
  await screen.findByRole('button', { name: 'View / Edit' });
  request.mockResolvedValueOnce(value);
  click('View / Edit');
  await screen.findByRole('button', {
    name: refresh === 'Refresh Warning List' ? 'Reload Saved Warning' : 'Reload Saved Record',
  });
}
test('relief creates, edits and reloads resource; required/numeric fields reject invalid input', async () => {
  mount(ReliefScreen);
  click('Create Record');
  await screen.findByText('Name and location are required.');
  fill('Resource name', 'Water');
  fill('Storage location', 'Colombo');
  click('Create Record');
  await screen.findByText(/Quantity must/);
  fill('Available quantity', '100');
  click('Create Record');
  await screen.findByText('Type and unit are required.');
  fill('Resource type', 'WATER');
  fill('Unit (bottles, packs, etc.)', 'bottles');
  request.mockResolvedValueOnce(resource);
  click('Create Record');
  await screen.findByText(/Saved successfully/);
  expect(request.mock.calls.at(-1)[3]).toMatchObject({ availableQuantity: 100 });
  request.mockResolvedValueOnce({ ...resource, version: 1 });
  click('Save Changes');
  await idle('Save Changes');
  expect(request.mock.calls.at(-1)[2]).toBe('PUT');
  request.mockResolvedValueOnce({ ...resource, version: 2 });
  click('Reload Saved Record');
  await idle('Reload Saved Record');
  click('New Record / Clear Form');
  expect(screen.getByLabelText('Resource name').value).toBe('');
});
test('resource allocation selects shelter, validates input and records coordination', async () => {
  mount(ReliefScreen);
  await edit(resource);
  click('Confirm Allocation');
  await screen.findByText(/valid whole quantity/);
  fill('Allocation quantity', '5');
  click('Confirm Allocation');
  await screen.findByText('Select a destination shelter.');
  request.mockResolvedValueOnce({ items: [shelter], totalPages: 2 });
  click('Load Destination Shelters');
  await screen.findByText(/School/);
  fireEvent.click(screen.getByText(/School/));
  click('Confirm Allocation');
  await screen.findByText('Enter a short reason or note.');
  fill('Coordination note', 'Emergency supplies');
  request.mockResolvedValueOnce({
    ...resource,
    version: 1,
    availableQuantity: 95,
    allocations: [
      {
        requestId: 'a',
        shelterName: 'School',
        quantity: 5,
        note: 'Emergency supplies',
        officer: 'Officer',
        at: resource.updatedAt,
      },
    ],
  });
  click('Confirm Allocation');
  await screen.findByText(/Coordination saved/);
  expect(request.mock.calls.at(-1)[3]).toMatchObject({
    shelterId: 's',
    quantity: 5,
    expectedVersion: 0,
  });
});
test('shelter occupancy changes preserve server version', async () => {
  mount(ReliefScreen);
  click('Shelters');
  await edit(shelter);
  fill('Occupancy change', '-3');
  fill('Coordination note', 'Departures');
  request.mockResolvedValueOnce({ ...shelter, version: 1, occupancy: 17 });
  click('Save Occupancy Change');
  await screen.findByText(/Coordination saved/);
  expect(request.mock.calls.at(-1)[3]).toMatchObject({ change: -3, expectedVersion: 0 });
  click('New Record / Clear Form');
  fill('Shelter name', 'School');
  fill('Location / district', 'Colombo');
  fill('Capacity', '0');
  fill('Current occupancy', '0');
  click('Create Record');
  await screen.findByText(/Capacity must/);
  fill('Capacity', '10');
  request.mockResolvedValueOnce(shelter);
  click('Create Record');
  await screen.findByText(/Saved successfully/);
});
test('rescue creates incident using map point and validates people', async () => {
  mount(RescueScreen);
  click('Create Record');
  await screen.findByText('Enter location and valid coordinates.');
  fill('Location / address', 'Colombo');
  click('Choose map point');
  click('Create Record');
  await screen.findByText(/positive whole number/);
  fill('Incident description', 'Flood rescue');
  fill('People requiring assistance', '5');
  request.mockResolvedValueOnce(incident);
  click('Create Record');
  await screen.findByRole('button', { name: 'Save Changes' });
  expect(request.mock.calls.at(-1)[3]).toMatchObject({
    latitude: 7,
    longitude: 80,
    peopleNeedingAssistance: 5,
  });
  request.mockResolvedValueOnce({ ...incident, version: 1 });
  click('Save Changes');
  await idle('Save Changes');
  request.mockResolvedValueOnce({ ...incident, version: 2 });
  click('Reload Saved Record');
  await idle('Reload Saved Record');
  click('New Record / Clear');
  expect(screen.getByLabelText('Incident description').value).toBe('');
});
test('rescue team creation and deployed record presentation', async () => {
  mount(RescueScreen);
  click('Rescue Teams');
  fill('Location / address', 'Colombo');
  fill('Latitude', '7');
  fill('Longitude', '80');
  click('Create Record');
  await screen.findByText('Enter a team name.');
  fill('Team name', 'Team One');
  click('UNAVAILABLE');
  request.mockResolvedValueOnce({ ...team, status: 'UNAVAILABLE' });
  click('Create Record');
  await screen.findByRole('button', { name: 'Save Changes' });
  expect(request.mock.calls.at(-1)[3]).toMatchObject({ status: 'UNAVAILABLE', name: 'Team One' });
  request.mockResolvedValueOnce({
    items: [{ ...team, status: 'DEPLOYED', activeIncidentId: 'i' }],
    totalPages: 1,
    totalElements: 1,
  });
  click('Refresh List');
  await screen.findByRole('button', { name: 'View / Edit' });
  request.mockResolvedValueOnce({ ...team, status: 'DEPLOYED', activeIncidentId: 'i' });
  click('View / Edit');
  await idle('Refresh List');
  expect(screen.getByRole('button', { name: 'Save Changes' }).disabled).toBe(true);
});
test('warning saves validated draft and shows preview before simulated broadcast', async () => {
  mount(WarningScreen);
  click('Save Draft');
  await screen.findByText(/Complete the warning/);
  for (const [name, value] of [
    ['Hazard / official assessment summary', 'Flood'],
    ['Affected area / district', 'Colombo'],
    ['Warning message', 'Move away'],
    ['Instructions / safety advice', 'Higher ground'],
  ])
    fill(name, value);
  fill('Valid until (ISO date/time)', 'bad');
  click('Save Draft');
  await screen.findByText(/future expiry/);
  fill('Valid until (ISO date/time)', warning.validUntil);
  click('LOW');
  fireEvent.click(screen.getByRole('checkbox', { name: 'Push notification' }));
  request.mockResolvedValueOnce({
    ...warning,
    level: 'LOW',
    channels: ['SMS', 'PUSH_NOTIFICATION'],
  });
  click('Review Preview');
  await screen.findByRole('button', { name: 'Broadcast Warning (Simulation)' });
  click('Broadcast Warning (Simulation)');
  request.mockResolvedValueOnce({
    ...warning,
    version: 1,
    status: 'BROADCAST',
    deliveries: [
      { channel: 'SMS', status: 'SENT', attempts: 1, lastAttemptAt: resource.updatedAt },
    ],
  });
  click('Confirm Action');
  await screen.findByText(/No real notifications/);
  expect(request.mock.calls.at(-1)[0]).toBe('/api/dmc/warnings/w/broadcast');
});
test('issued warning cancellation requires reason', async () => {
  mount(WarningScreen);
  await edit({ ...warning, status: 'ISSUED' }, 'Refresh Warning List');
  click('Review Preview');
  await screen.findByRole('button', { name: 'Cancel Warning' });
  click('Cancel Warning');
  click('Confirm Action');
  await screen.findByText('Provide a cancellation reason.');
  fill('Cancellation reason', 'Situation safe');
  request.mockResolvedValueOnce({ ...warning, status: 'CANCELLED', version: 1 });
  click('Confirm Action');
  await screen.findByText(/Warning cancelled/);
  expect(request.mock.calls.at(-1)[3].reason).toBe('Situation safe');
});
test.each([
  [ReliefScreen, 'Refresh List'],
  [RescueScreen, 'Refresh List'],
  [WarningScreen, 'Refresh Warning List'],
  [AdminScreen, 'Refresh Applications'],
])('%p handles authorization and connection failures', async (Component, button) => {
  mount(Component, Component === AdminScreen ? 'ADMIN' : 'DMC_OFFICER');
  request.mockRejectedValueOnce(new ApiError('Expired', 401));
  click(button);
  await waitFor(() => expect(onSignOut).toHaveBeenCalled());
  request.mockRejectedValueOnce(new Error('Database offline'));
  click(button);
  await screen.findByText('Database offline');
  request.mockResolvedValueOnce({ items: [], totalPages: 0, totalElements: 0 });
  click(button);
  await idle(button);
});
test('admin review stays inside applicant card and requires note', async () => {
  mount(AdminScreen, 'ADMIN');
  const applicant = {
    id: 'a',
    displayName: 'Applicant',
    email: 'a@example.test',
    roles: [],
    requestedRole: 'DUTY_OFFICER',
    approvalStatus: 'PENDING',
  };
  request.mockResolvedValueOnce({ items: [applicant], totalPages: 2, totalElements: 1 });
  click('Refresh Applications');
  await screen.findByRole('button', { name: 'Review Application' });
  click('Review Application');
  expect(screen.getByRole('button', { name: 'Confirm Approval' }).disabled).toBe(true);
  fill('Verification note for Applicant', 'Identity verified');
  request
    .mockResolvedValueOnce({})
    .mockResolvedValueOnce({ items: [], totalPages: 0, totalElements: 0 });
  click('Confirm Approval');
  await screen.findByText(/Applicant is approved/);
  expect(request.mock.calls.at(-2)).toEqual([
    '/api/dmc/admin/applications/a/decision',
    'token',
    'POST',
    { approved: true, note: 'Identity verified' },
  ]);
  click('Rejected');
  request.mockResolvedValueOnce({
    items: [{ ...applicant, approvalStatus: 'REJECTED', approvalNote: 'Not verified' }],
    totalPages: 1,
    totalElements: 1,
  });
  click('Refresh Applications');
  await screen.findByText(/Review note: Not verified/);
  expect(screen.queryByRole('button', { name: 'Review Application' })).toBeNull();
});
test('dashboard exposes verified evidence and handles empty/error results', async () => {
  render(<DmcDashboard />);
  request.mockResolvedValueOnce([]);
  click('Load Verified Reports');
  await screen.findByText(/No verified reports/);
  request.mockResolvedValueOnce([
    { id: 'r', reference: 'HR-1', description: 'Flood', location: { areaLabel: 'Colombo' } },
  ]);
  click('Load Verified Reports');
  await screen.findByText('HR-1');
  request.mockRejectedValueOnce(new ApiError('Expired', 401));
  click('Load Verified Reports');
  await waitFor(() => expect(onSignOut).toHaveBeenCalledWith(null));
  request.mockRejectedValueOnce(new Error('Offline'));
  click('Load Verified Reports');
  await screen.findByText('Offline');
});

test.each([
  [ReliefScreen, 'Refresh List'],
  [RescueScreen, 'Refresh List'],
  [WarningScreen, 'Refresh Warning List'],
  [AdminScreen, 'Refresh Applications'],
])('pagination loads next and previous server page for %p', async (Component, refresh) => {
  mount(Component, Component === AdminScreen ? 'ADMIN' : 'DMC_OFFICER');
  request.mockResolvedValueOnce({ items: [], totalPages: 2, totalElements: 21 });
  click(refresh);
  await screen.findByRole('button', { name: 'Next' });
  request.mockResolvedValueOnce({ items: [], totalPages: 2, totalElements: 21 });
  click('Next');
  await idle('Previous');
  expect(request.mock.calls.at(-1)[0]).toContain('page=1');
  request.mockResolvedValueOnce({ items: [], totalPages: 2, totalElements: 21 });
  click('Previous');
  await idle('Next');
  expect(request.mock.calls.at(-1)[0]).toContain('page=0');
});
test('warning reload/new/back controls preserve acknowledged record', async () => {
  mount(WarningScreen);
  await edit(warning, 'Refresh Warning List');
  request.mockResolvedValueOnce({ ...warning, version: 1 });
  click('Reload Saved Warning');
  await idle('Reload Saved Warning');
  expect(request.mock.calls.at(-1)[0]).toBe('/api/dmc/warnings/w');
  request.mockResolvedValueOnce({ ...warning, version: 2 });
  click('Review Preview');
  await screen.findByRole('button', { name: 'Back to Details' });
  click('Back to Details');
  expect(screen.getByLabelText('Warning message').value).toBe('Move away');
  click('New Warning');
  expect(screen.getByLabelText('Warning message').value).toBe('');
});
test('resource and shelter tabs reset field values; shelter state is sent explicitly', async () => {
  mount(ReliefScreen);
  click('Shelters');
  fill('Shelter name', 'School');
  fill('Location / district', 'Colombo');
  fill('Capacity', '100');
  fill('Current occupancy', '20');
  click('CLOSED');
  request.mockResolvedValueOnce({ ...shelter, status: 'CLOSED' });
  click('Create Record');
  await idle('Save Changes');
  expect(request.mock.calls.at(-1)[3].status).toBe('CLOSED');
  click('Resources');
  expect(screen.getByLabelText('Resource name').value).toBe('');
});
test('rescue incident type and priority choices are included in save payload', async () => {
  mount(RescueScreen);
  click('Rescue Teams');
  click('Incidents');
  click('MEDICAL RESCUE');
  click('CRITICAL');
  fill('Incident description', 'Medical assistance');
  fill('People requiring assistance', '2');
  fill('Location / address', 'Colombo');
  fill('Latitude', '7');
  fill('Longitude', '80');
  request.mockResolvedValueOnce({ ...incident, type: 'MEDICAL_RESCUE', priority: 'CRITICAL' });
  click('Create Record');
  await idle('Save Changes');
  expect(request.mock.calls.at(-1)[3]).toMatchObject({
    type: 'MEDICAL_RESCUE',
    priority: 'CRITICAL',
  });
});

test('Brevo warning preview validates recipients and confirms real email with explicit status', async () => {
  mount(WarningScreen);
  await edit({ ...warning, channels: ['EMAIL'] }, 'Refresh Warning List');
  request.mockResolvedValueOnce({ ...warning, channels: ['EMAIL'] });
  click('Review Preview');
  await screen.findByRole('button', { name: 'Broadcast Warning Email' });
  click('Broadcast Warning Email');
  click('Confirm Action');
  await screen.findByText(/Enter 1–50 valid recipient/);
  fill('Warning email recipients', 'a@example.test, b@example.test');
  const acknowledged = {
    ...warning,
    channels: ['EMAIL'],
    status: 'ISSUED',
    version: 2,
    emailDispatch: {
      requestId: 'email',
      recipients: ['a@example.test', 'b@example.test'],
      status: 'ACCEPTED',
      providerMessageId: 'provider-1',
      message: 'Accepted, not delivered',
    },
    deliveries: [
      { channel: 'EMAIL', status: 'ACCEPTED', attempts: 1, lastAttemptAt: resource.updatedAt },
    ],
  };
  request.mockResolvedValueOnce(acknowledged);
  click('Confirm Action');
  await screen.findByText('Email status: ACCEPTED. Accepted, not delivered');
  expect(request.mock.calls.at(-1)[0]).toBe('/api/dmc/warnings/w/email');
  expect(request.mock.calls.at(-1)[3]).toMatchObject({
    expectedVersion: 0,
    recipients: ['a@example.test', 'b@example.test'],
  });
  expect(screen.getByLabelText('Warning email recipients').readOnly).toBe(true);
});
test('known failed email retries saved recipients and displays uncertain outcome', async () => {
  mount(WarningScreen);
  const failed = {
    ...warning,
    channels: ['EMAIL'],
    status: 'ISSUED',
    version: 2,
    emailDispatch: {
      requestId: 'previous',
      recipients: ['a@example.test'],
      status: 'FAILED',
      providerMessageId: null,
      message: 'Rejected',
    },
    deliveries: [
      { channel: 'EMAIL', status: 'FAILED', attempts: 1, lastAttemptAt: resource.updatedAt },
    ],
  };
  await edit(failed, 'Refresh Warning List');
  click('Review Preview');
  await screen.findByRole('button', { name: 'Retry Failed Email' });
  click('Retry Failed Email');
  request.mockResolvedValueOnce({
    ...failed,
    version: 4,
    emailDispatch: { ...failed.emailDispatch, status: 'UNKNOWN', message: 'Check Brevo logs' },
    deliveries: [
      { channel: 'EMAIL', status: 'UNKNOWN', attempts: 2, lastAttemptAt: resource.updatedAt },
    ],
  });
  click('Confirm Action');
  await screen.findByText('Email status: UNKNOWN. Check Brevo logs');
  expect(request.mock.calls.at(-1)[3].recipients).toEqual(['a@example.test']);
  expect(screen.getByRole('button', { name: 'Retry Failed Email' }).disabled).toBe(true);
});
