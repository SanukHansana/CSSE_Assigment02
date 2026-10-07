import { request, uploadPhoto, ApiError } from '../src/features/reports/api';
import { createFetchHttpClient, HttpError } from '../src/services/http/createFetchHttpClient';
import { parseDmcStatus } from '../src/features/dmc/schemas/dmcStatusSchema';
import { createDmcService } from '../src/features/dmc/services/createDmcService';
import { getErrorMessage } from '../src/utils/getErrorMessage';
import { selection, mapDocument } from '../src/components/maps/mapDocument';
const response = (data, ok = true, status = 200) => ({
  ok,
  status,
  json: async () => data,
  blob: async () => new Blob(['photo'], { type: 'image/jpeg' }),
});
beforeEach(() => {
  global.fetch = jest.fn();
});
test('JSON transport sends credentials/body and propagates structured field errors', async () => {
  fetch.mockResolvedValueOnce(response({ id: 'x' }));
  await expect(request('/needs', 'token', 'POST', { quantity: 3 })).resolves.toEqual({ id: 'x' });
  expect(fetch.mock.calls[0][1]).toMatchObject({
    headers: { Authorization: 'Bearer token', 'Content-Type': 'application/json' },
    body: '{"quantity":3}',
  });
  fetch.mockResolvedValueOnce(
    response(
      {
        message: 'Invalid',
        fieldErrors: [{ field: 'quantity', message: 'positive' }, { field: 'note' }],
      },
      false,
      400,
    ),
  );
  await expect(request('/needs')).rejects.toThrow('Invalid (quantity: positive, note)');
  fetch.mockResolvedValueOnce(response({}, false, 503));
  await expect(request('/needs')).rejects.toBeInstanceOf(ApiError);
});
test('upload uses multipart and the report version without JSON content type', async () => {
  fetch.mockResolvedValueOnce(response({ version: 3 }));
  await uploadPhoto(
    { id: 'r', version: 2 },
    { file: new File(['photo'], 'test.jpg'), fileName: 'test.jpg' },
    'token',
  );
  expect(fetch.mock.calls[0][1].body).toBeInstanceOf(FormData);
  expect(fetch.mock.calls[0][1].headers['If-Match']).toBe('"2"');
  expect(fetch.mock.calls[0][1].headers['Content-Type']).toBeUndefined();
  fetch.mockResolvedValueOnce(response({})).mockResolvedValueOnce(response({}));
  await uploadPhoto({ id: 'r', version: 2 }, { uri: 'blob:photo' }, 'token');
  expect(fetch.mock.calls.at(-1)[1].body.get('file').name).toBe('evidence.jpg');
});
test('fetch adapter normalizes URL, supports abort and retains status on failure', async () => {
  const http = createFetchHttpClient('http://localhost:8080///');
  const signal = new AbortController().signal;
  fetch.mockResolvedValueOnce(response({ status: 'UP', application: 'DMC' }));
  expect(await http.get('/api/dmc', signal)).toEqual({ status: 'UP', application: 'DMC' });
  expect(fetch).toHaveBeenCalledWith(
    'http://localhost:8080/api/dmc',
    expect.objectContaining({ signal }),
  );
  fetch.mockResolvedValueOnce(response({}, false, 503));
  await expect(http.get('/api/dmc')).rejects.toEqual(new HttpError(503));
});
test.each([
  null,
  undefined,
  0,
  'UP',
  {},
  { status: 1, application: 'DMC' },
  { status: 'UP' },
  { status: 'UP', application: 3 },
])('schema rejects invalid external payload %p', (value) => {
  expect(() => parseDmcStatus(value)).toThrow(/unexpected/);
});
test('service validates server response and error presentation handles unknown values', async () => {
  const http = {
    get: jest.fn().mockResolvedValue({ status: 'UP', application: 'DMC', secret: 'discard' }),
  };
  const signal = new AbortController().signal;
  expect(await createDmcService(http).getStatus(signal)).toEqual({
    status: 'UP',
    application: 'DMC',
  });
  expect(getErrorMessage(new Error('offline'))).toBe('offline');
  expect(getErrorMessage('failure')).toBeTruthy();
});
test.each([
  null,
  0,
  {},
  { type: 'wrong', latitude: 0, longitude: 0, zoom: 3 },
  { type: 'dmc-map-select', latitude: '0', longitude: 0, zoom: 3 },
  { type: 'dmc-map-select', latitude: 0, longitude: '0', zoom: 3 },
  { type: 'dmc-map-select', latitude: 0, longitude: 0, zoom: '3' },
  { type: 'dmc-map-select', latitude: NaN, longitude: 0, zoom: 3 },
  { type: 'dmc-map-select', latitude: 0, longitude: Infinity, zoom: 3 },
  ...[-91, 91].map((latitude) => ({ type: 'dmc-map-select', latitude, longitude: 0, zoom: 3 })),
  ...[-181, 181].map((longitude) => ({ type: 'dmc-map-select', latitude: 0, longitude, zoom: 3 })),
])('map rejects malformed or out-of-range selection %p', (value) => {
  expect(selection(value)).toBeNull();
});
test('map accepts boundary coordinates and clamps zoom; HTML disables editing when readonly', () => {
  expect(selection({ type: 'dmc-map-select', latitude: 90, longitude: -180, zoom: 40 })).toEqual({
    latitude: 90,
    longitude: -180,
    zoom: 18,
  });
  expect(selection({ type: 'dmc-map-select', latitude: -90, longitude: 180, zoom: -1 }).zoom).toBe(
    2,
  );
  expect(mapDocument('7', '80', false, 10)).toContain('setView([7,80],10)');
  expect(mapDocument(undefined, undefined, true, 10)).toContain('Reported location');
  for (const [lat, lon] of [
    ['', ''],
    ['NaN', '80'],
    ['7', 'NaN'],
    ['-91', '80'],
    ['91', '80'],
    ['7', '-181'],
    ['7', '181'],
  ])
    expect(mapDocument(lat, lon, false, 10)).toContain('setView([6.9271,79.8612],10)');
});
