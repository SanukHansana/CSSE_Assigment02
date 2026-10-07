import React, { useState } from 'react';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { RescueOperations } from '../src/features/rescue/RescueOperations';
import { request } from '../src/features/reports/api';
jest.mock('../src/features/reports/api', () => ({
  ...jest.requireActual('../src/features/reports/api'),
  request: jest.fn(),
}));
jest.mock('../src/components/maps/MapPicker', () => ({
  MapPicker: ({ onSelect }) => (
    <button onClick={() => onSelect?.(7, 80)}>Choose team map point</button>
  ),
}));
const initial = { id: 'i', version: 0, status: 'OPEN', assignments: [], history: [] };
const team = {
  id: 't',
  version: 3,
  name: 'Team One',
  status: 'AVAILABLE',
  location: 'Colombo',
  latitude: 7,
  longitude: 80,
  specialization: 'FLOOD_RESCUE',
};
const assignment = {
  id: 'a',
  teamId: 't',
  teamName: 'Team One',
  status: 'ASSIGNED',
  note: 'Dispatch',
  officer: 'Officer',
  assignedAt: '2026-10-13T12:00:00Z',
  updatedAt: '2026-10-13T12:00:00Z',
};
function Harness({ value = initial }) {
  const [incident, setIncident] = useState(value);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function run(work) {
    setBusy(true);
    setError('');
    try {
      await work();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <RescueOperations
        incident={incident}
        token="token"
        busy={busy}
        run={run}
        onUpdated={setIncident}
      />
      {error && <p role="alert">{error}</p>}
    </>
  );
}
const click = (name) => fireEvent.click(screen.getByRole('button', { name }));
const fill = (name, value) => fireEvent.change(screen.getByLabelText(name), { target: { value } });
beforeEach(() => jest.clearAllMocks());
afterEach(cleanup);
test('assign validates note/team, sends both versions, then records progress and closes', async () => {
  render(<Harness />);
  expect(screen.getByRole('button', { name: 'Assign Selected Team' }).disabled).toBe(true);
  request.mockResolvedValueOnce({
    items: [team, { ...team, id: 'busy', name: 'Busy team', status: 'DEPLOYED' }],
    totalPages: 2,
  });
  click('Load Rescue Teams');
  await screen.findAllByRole('button', { name: 'Select' });
  fireEvent.click(screen.getAllByRole('button', { name: 'Select' })[0]);
  click('Assign Team One');
  await screen.findByText('Enter a coordinator note or completion reason.');
  fill('Coordinator operation note', 'Dispatch');
  request.mockResolvedValueOnce({ ...initial, version: 1, assignments: [assignment] });
  click('Assign Team One');
  await screen.findByRole('button', { name: 'Mark En Route' });
  expect(request.mock.calls.at(-1)[3]).toMatchObject({
    teamId: 't',
    teamVersion: 3,
    expectedVersion: 0,
    note: 'Dispatch',
  });
  fill('Coordinator operation note', 'En route');
  fill('Team latitude', '7');
  click('Mark En Route');
  await screen.findByText('Supply both valid coordinates or leave both blank.');
  click('Choose team map point');
  fill('Team location address', 'Site');
  request.mockResolvedValueOnce({
    ...initial,
    version: 2,
    assignments: [{ ...assignment, status: 'EN_ROUTE' }],
  });
  click('Mark En Route');
  await screen.findByRole('button', { name: 'Mark On Site' });
  expect(request.mock.calls.at(-1)[3]).toMatchObject({
    latitude: 7,
    longitude: 80,
    location: 'Site',
    status: 'EN_ROUTE',
  });
  fill('Coordinator operation note', 'Arrived');
  request.mockResolvedValueOnce({
    ...initial,
    version: 3,
    assignments: [{ ...assignment, status: 'ON_SITE' }],
  });
  click('Mark On Site');
  await screen.findByRole('button', { name: 'Complete Assignment' });
  fill('Coordinator operation note', 'People rescued');
  request.mockResolvedValueOnce({
    ...initial,
    version: 4,
    assignments: [{ ...assignment, status: 'COMPLETED' }],
  });
  click('Complete Assignment');
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Mark Incident as Resolved' }).disabled).toBe(false),
  );
  fill('Coordinator operation note', 'Resolved');
  request.mockResolvedValueOnce({
    ...initial,
    version: 5,
    status: 'RESOLVED',
    assignments: [{ ...assignment, status: 'COMPLETED' }],
  });
  click('Mark Incident as Resolved');
  await screen.findByRole('button', { name: 'Close Incident' });
  fill('Coordinator operation note', 'Close');
  request.mockResolvedValueOnce({
    ...initial,
    version: 6,
    status: 'CLOSED',
    assignments: [{ ...assignment, status: 'COMPLETED' }],
  });
  click('Close Incident');
  await screen.findByText(/Incident closed/);
  expect(request.mock.calls.at(-1)[3].status).toBe('CLOSED');
});
test('uncertain cancellation preserves request ID and blocks changed input until reset', async () => {
  render(<Harness value={{ ...initial, version: 1, assignments: [assignment] }} />);
  fill('Coordinator operation note', 'Cancel');
  request.mockRejectedValueOnce(new Error('Offline'));
  click('Cancel Assignment');
  await screen.findByText('Offline');
  const first = request.mock.calls.at(-1)[3];
  fill('Coordinator operation note', 'Different');
  click('Cancel Assignment');
  await screen.findByText(/Retry the same input/);
  expect(request.mock.calls.at(-1)[3]).toBe(first);
  click('Acknowledge History / Reset Pending Action');
  request.mockResolvedValueOnce({
    ...initial,
    version: 2,
    assignments: [{ ...assignment, status: 'CANCELLED' }],
  });
  click('Cancel Assignment');
  await screen.findByText(/Team One · CANCELLED/);
  expect(request.mock.calls.at(-1)[3].requestId).not.toBe(first.requestId);
  expect(screen.getByRole('button', { name: 'Mark Incident as Resolved' }).disabled).toBe(true);
});
test('team pagination loads chosen page and clears old selection', async () => {
  render(<Harness />);
  request.mockResolvedValueOnce({ items: [team], totalPages: 2 });
  click('Load Rescue Teams');
  await screen.findByRole('button', { name: 'Select' });
  request.mockResolvedValueOnce({ items: [], totalPages: 2 });
  click('More Teams');
  await waitFor(() => expect(request.mock.calls.at(-1)[0]).toContain('page=1'));
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Previous Teams' }).disabled).toBe(false),
  );
  request.mockResolvedValueOnce({ items: [team], totalPages: 2 });
  click('Previous Teams');
  await screen.findByRole('button', { name: 'Select' });
});
