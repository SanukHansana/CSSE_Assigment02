import React from 'react';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { ReporterScreen } from '../src/features/reports/ReporterScreen';
import { OfficerScreen } from '../src/features/reports/OfficerScreen';
import { request, uploadPhoto, ApiError } from '../src/features/reports/api';
import { useAuth } from '../src/application/AuthContext';
import * as ImagePicker from 'expo-image-picker';
import * as Location from 'expo-location';
jest.mock('../src/features/reports/api', () => ({
  ...jest.requireActual('../src/features/reports/api'),
  request: jest.fn(),
  uploadPhoto: jest.fn(),
}));
jest.mock('../src/application/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('expo-image-picker', () => ({ launchImageLibraryAsync: jest.fn() }));
jest.mock('expo-location', () => ({
  requestForegroundPermissionsAsync: jest.fn(),
  getCurrentPositionAsync: jest.fn(),
  Accuracy: { Balanced: 3 },
}));
jest.mock('../src/components/maps/MapPicker', () => ({
  MapPicker: ({ onSelect }) => <button onClick={() => onSelect?.(7, 80)}>Choose map point</button>,
}));
const setSession = jest.fn();
const session = { accessToken: 'token', user: { displayName: 'Citizen', roles: ['CITIZEN'] } };
const report = {
  id: 'r',
  reference: 'HR-1',
  version: 0,
  status: 'DRAFT',
  hazardType: 'FLOODING',
  description: 'Flooded road',
  location: { latitude: 7, longitude: 80, areaLabel: 'Colombo' },
  reporter: { displayName: 'Citizen', type: 'CITIZEN' },
  photo: null,
  history: [],
};
const check = {
  descriptionSufficientlyDetailed: true,
  photoRelevant: true,
  gpsCorrespondsToArea: true,
  reportingTimeReasonable: true,
};
const click = (name) => fireEvent.click(screen.getByRole('button', { name }));
const fill = (name, value) => fireEvent.change(screen.getByLabelText(name), { target: { value } });
const idle = async (name) =>
  waitFor(() => expect(screen.getByRole('button', { name }).disabled).toBe(false));
function mount(value = session, props = {}) {
  useAuth.mockReturnValue({ session: value, setSession });
  return render(<ReporterScreen {...props} />);
}
beforeEach(() => {
  jest.clearAllMocks();
  request.mockReset();
  global.fetch = jest.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['photo']) });
  URL.createObjectURL = jest.fn(() => 'blob:saved');
  URL.revokeObjectURL = jest.fn();
});
afterEach(cleanup);
test('login and public registration send intended role and clear password', async () => {
  mount(null);
  fill('Email', 'citizen@example.test');
  fill('Password', 'short');
  request.mockResolvedValueOnce(session);
  click('Sign in');
  await waitFor(() => expect(setSession).toHaveBeenCalledWith(session));
  cleanup();
  mount(null, { authMode: 'register' });
  fill('Full name', 'Volunteer');
  fill('Email', 'v@example.test');
  fill('Password', 'short');
  click('COMMUNITY VOLUNTEER');
  request.mockResolvedValueOnce({});
  click('Register');
  await screen.findByText('Account created. Sign in with your email and password.');
  expect(request.mock.calls.at(-1)[3]).toMatchObject({
    requestedRole: 'COMMUNITY_VOLUNTEER',
    password: 'short',
  });
  expect(screen.getByLabelText('Password').value).toBe('');
});
test('officer registration explains approval and pending account refreshes effective role', async () => {
  mount(null, { authMode: 'register' });
  click('DMC OFFICER');
  fill('Email', 'o@example.test');
  fill('Full name', 'Officer');
  fill('Password', 'short');
  request.mockResolvedValueOnce({});
  click('Register');
  await screen.findByText(/Officer application created/);
  cleanup();
  const pending = {
    ...session,
    user: {
      displayName: 'Officer',
      roles: [],
      requestedRole: 'DMC_OFFICER',
      approvalStatus: 'PENDING',
    },
  };
  mount(pending);
  request.mockResolvedValueOnce({
    ...pending.user,
    roles: ['DMC_OFFICER'],
    approvalStatus: 'APPROVED',
  });
  click('Refresh Approval Status');
  await waitFor(() => expect(setSession).toHaveBeenCalled());
  expect(request.mock.calls.at(-1)[0]).toBe('/api/dmc/auth/me');
});
test('citizen saves draft, edits it and resets form', async () => {
  mount();
  fill('Describe what you observed *', 'Flooded road');
  click('Choose map point');
  fill('Area / district', 'Colombo');
  request.mockResolvedValueOnce(report);
  click('Save as Draft');
  await screen.findByText(/Draft saved/);
  expect(request.mock.calls.at(-1)[3].location).toEqual({
    latitude: 7,
    longitude: 80,
    areaLabel: 'Colombo',
  });
  request.mockResolvedValueOnce({ ...report, version: 1 });
  click('Save as Draft');
  await idle('Save as Draft');
  expect(request.mock.calls.at(-1)[2]).toBe('PATCH');
  click('New Report');
  expect(screen.getByLabelText('Describe what you observed *').value).toBe('');
});
test('report submission requires evidence and saves before confirmation', async () => {
  mount();
  click('Review & Submit Report');
  await screen.findByText(/Add a description/);
  fill('Describe what you observed *', 'Flooded road');
  click('Choose map point');
  ImagePicker.launchImageLibraryAsync.mockResolvedValueOnce({
    canceled: false,
    assets: [{ uri: 'blob:photo', mimeType: 'image/jpeg', fileSize: 100, fileName: 'photo.jpg' }],
  });
  click('Browse Photos');
  await screen.findByRole('button', { name: 'Remove' });
  request.mockResolvedValueOnce(report);
  const withPhoto = {
    ...report,
    version: 1,
    photo: { originalFilename: 'photo.jpg', viewUrl: '/photo' },
  };
  uploadPhoto.mockResolvedValueOnce(withPhoto);
  click('Review & Submit Report');
  await screen.findByRole('button', { name: 'Confirm & Submit Report' });
  expect(uploadPhoto).toHaveBeenCalledWith(
    report,
    expect.objectContaining({ uri: 'blob:photo' }),
    'token',
  );
  request.mockResolvedValueOnce({ ...withPhoto, version: 2, status: 'SUBMITTED' });
  click('Confirm & Submit Report');
  await idle('New Report');
  expect(request.mock.calls.at(-1)[0]).toBe('/api/dmc/ground-reports/r/submit');
});
test('GPS rejection keeps manual input and valid GPS updates coordinates', async () => {
  mount();
  fill('Latitude', '6.9');
  Location.requestForegroundPermissionsAsync.mockResolvedValueOnce({ granted: false });
  click('Use Current Location');
  await screen.findByText(/Location access was denied/);
  expect(screen.getByLabelText('Latitude').value).toBe('6.9');
  Location.requestForegroundPermissionsAsync.mockResolvedValueOnce({ granted: true });
  Location.getCurrentPositionAsync.mockResolvedValueOnce({
    coords: { latitude: 7, longitude: 80 },
  });
  click('Use Current Location');
  await screen.findByText(/Current location captured/);
  expect(screen.getByLabelText('Longitude').value).toBe('80');
});
test.each([
  { fileSize: 6 * 1024 * 1024, mimeType: 'image/jpeg', error: 'Choose a photo smaller than 5 MB.' },
  { fileSize: 5, mimeType: 'image/gif', error: 'Choose a JPEG or PNG photo.' },
])('invalid evidence is rejected before upload %p', async ({ error, ...asset }) => {
  mount();
  ImagePicker.launchImageLibraryAsync.mockResolvedValueOnce({ canceled: false, assets: [asset] });
  click('Browse Photos');
  await screen.findByText(error);
  expect(uploadPhoto).not.toHaveBeenCalled();
});
test('cancelled photo picker, remove evidence, invalid coordinates and unauthenticated error', async () => {
  mount();
  ImagePicker.launchImageLibraryAsync.mockResolvedValueOnce({ canceled: true });
  click('Browse Photos');
  await idle('Browse Photos');
  fill('Latitude', '91');
  fill('Longitude', '80');
  click('Save as Draft');
  await screen.findByText(/Enter valid latitude/);
  fill('Latitude', '7');
  request.mockRejectedValueOnce(new ApiError('Expired', 401));
  click('Save as Draft');
  await waitFor(() => expect(setSession).toHaveBeenCalledWith(null));
});
test('my reports fetches paginated items and opens saved terminal report', async () => {
  mount();
  request.mockResolvedValueOnce({
    items: [
      {
        ...report,
        status: 'REJECTED',
        verification: {
          reference: 'RV',
          officerDisplayName: 'Duty',
          decision: 'REJECTED',
          comments: 'Unclear',
          rejectionReason: 'Wrong location',
          decidedAt: '2026-10-13T12:00:00Z',
          checklist: check,
        },
      },
    ],
    totalPages: 2,
  });
  click('My Reports');
  await screen.findByText('HR-1');
  request.mockResolvedValueOnce({
    ...report,
    status: 'REJECTED',
    verification: {
      reference: 'RV',
      officerDisplayName: 'Duty',
      decision: 'REJECTED',
      comments: 'Unclear',
      rejectionReason: 'Wrong location',
      decidedAt: '2026-10-13T12:00:00Z',
      checklist: check,
    },
  });
  fireEvent.click(screen.getByText('HR-1'));
  await screen.findByText('Report activity');
  expect(screen.queryByRole('button', { name: 'Save as Draft' })).toBeNull();
});
async function officer(value) {
  render(
    <OfficerScreen
      session={{ ...session, user: { roles: ['DUTY_OFFICER'] } }}
      onSignOut={setSession}
    />,
  );
  request.mockResolvedValueOnce({ items: [value], totalPages: 2 });
  click('Search / Refresh');
  await screen.findByRole('button', { name: 'View Details' });
  request.mockResolvedValueOnce(value);
  click('View Details');
  await screen.findByRole('button', { name: 'Back to Reports' });
}
test('duty officer starts review and saves credibility checklist', async () => {
  await officer({ ...report, status: 'SUBMITTED' });
  const reviewing = {
    ...report,
    version: 1,
    status: 'UNDER_REVIEW',
    review: {
      officerDisplayName: 'Duty',
      startedAt: '2026-10-13T12:00:00Z',
      comments: '',
      checklist: check,
    },
  };
  request.mockResolvedValueOnce(reviewing);
  click('Start Review');
  await screen.findByRole('button', { name: 'Save Review' });
  fill('Duty Officer comments', 'Credible');
  request.mockResolvedValueOnce({ ...reviewing, version: 2 });
  click('Save Review');
  await idle('Save Review');
  expect(request.mock.calls.at(-1)[3]).toMatchObject({
    expectedVersion: 1,
    comments: 'Credible',
    checklist: check,
  });
});
test.each(['VERIFIED', 'REJECTED'])(
  'duty officer records %s decision with required rejection reason',
  async (decision) => {
    const reviewing = {
      ...report,
      status: 'UNDER_REVIEW',
      review: {
        checklist: check,
        comments: 'Credible',
        officerDisplayName: 'Duty',
        startedAt: '2026-10-13T12:00:00Z',
      },
    };
    await officer(reviewing);
    click(decision === 'VERIFIED' ? 'Verify Report' : 'Reject Report');
    if (decision === 'REJECTED') {
      expect(screen.getByRole('button', { name: 'Confirm Decision' }).disabled).toBe(true);
      fill('Rejection reason', 'Wrong location');
    }
    request.mockResolvedValueOnce({
      ...reviewing,
      status: decision,
      verification: {
        reference: 'RV-1',
        officerDisplayName: 'Duty',
        decision,
        comments: 'Credible',
        rejectionReason: decision === 'REJECTED' ? 'Wrong location' : null,
        decidedAt: '2026-10-13T12:00:00Z',
        checklist: check,
      },
    });
    click('Confirm Decision');
    await screen.findByText(
      decision === 'VERIFIED'
        ? 'Verification recorded successfully.'
        : 'Rejection recorded successfully.',
    );
    expect(request.mock.calls.at(-1)[3].decision).toBe(decision);
    click('Back to Reports');
    expect(screen.getByRole('button', { name: 'Search / Refresh' })).toBeTruthy();
  },
);
test('officer queue error signs out expired session and reports conflict', async () => {
  render(<OfficerScreen session={session} onSignOut={setSession} />);
  request.mockRejectedValueOnce(new ApiError('Expired', 401));
  click('Search / Refresh');
  await waitFor(() => expect(setSession).toHaveBeenCalled());
  request.mockRejectedValueOnce(new ApiError('Stale', 409));
  click('Search / Refresh');
  await screen.findByText(/Reload the saved report/);
  request.mockResolvedValueOnce({ items: [], totalPages: 0 });
  click('Search / Refresh');
  await idle('Search / Refresh');
});

test('citizen pagination, draft reload, hazard choice and photo removal work', async () => {
  mount();
  request.mockResolvedValueOnce({ items: [], totalPages: 2 });
  click('My Reports');
  await screen.findByRole('button', { name: 'Next' });
  request.mockResolvedValueOnce({ items: [], totalPages: 2 });
  click('Next');
  await idle('Previous');
  expect(request.mock.calls.at(-1)[0]).toContain('page=1');
  request.mockResolvedValueOnce({ items: [], totalPages: 2 });
  click('Previous');
  await idle('Next');
  click('New Report');
  click('landslide crack');
  request.mockResolvedValueOnce({ ...report, hazardType: 'LANDSLIDE_CRACK' });
  click('Save as Draft');
  await idle('Save as Draft');
  request.mockResolvedValueOnce({ ...report, version: 1, hazardType: 'LANDSLIDE_CRACK' });
  click('Reload saved report');
  await idle('Reload saved report');
  ImagePicker.launchImageLibraryAsync.mockResolvedValueOnce({
    canceled: false,
    assets: [{ uri: 'blob:photo', fileName: 'photo.jpg' }],
  });
  click('Browse Photos');
  await screen.findByRole('button', { name: 'Remove' });
  click('Remove');
  await idle('Browse Photos');
  expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull();
});
