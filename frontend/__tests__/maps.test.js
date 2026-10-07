import React from 'react';
import { render, screen, waitFor, act, cleanup } from '@testing-library/react';
import { MapPicker } from '../src/components/maps/MapPicker.web';
import { MapPicker as NativeMap } from '../src/components/maps/MapPicker.tsx';
import * as L from 'leaflet';
let mockWebViewProps;
jest.mock('react-native-webview', () => ({
  WebView: (props) => {
    mockWebViewProps = props;
    return <div data-testid="native-map" />;
  },
}));
jest.mock('leaflet', () => {
  const map = {
    setView: jest.fn().mockReturnThis(),
    on: jest.fn(),
    panTo: jest.fn(),
    remove: jest.fn(),
  };
  const marker = {
    addTo: jest.fn().mockReturnThis(),
    on: jest.fn(),
    setLatLng: jest.fn(),
    getLatLng: jest.fn(() => ({ lat: 7, lng: 540 })),
  };
  const tiles = { addTo: jest.fn().mockReturnThis(), on: jest.fn() };
  return {
    map: jest.fn(() => map),
    marker: jest.fn(() => marker),
    tileLayer: jest.fn(() => tiles),
    icon: jest.fn(() => ({})),
  };
});
beforeEach(() => jest.clearAllMocks());
afterEach(cleanup);
test('web map supports click and drag, updates marker and releases resources', async () => {
  const select = jest.fn();
  const { rerender, unmount } = render(<MapPicker latitude="7" longitude="80" onSelect={select} />);
  await waitFor(() => expect(L.map).toHaveBeenCalled());
  const map = L.map.mock.results[0].value;
  const marker = L.marker.mock.results[0].value;
  await act(async () =>
    map.on.mock.calls.find(([name]) => name === 'click')[1]({ latlng: { lat: 8, lng: 540 } }),
  );
  expect(select).toHaveBeenCalledWith(8, -180);
  act(() => marker.on.mock.calls.find(([name]) => name === 'dragend')[1]());
  expect(select).toHaveBeenCalledWith(7, -180);
  rerender(<MapPicker latitude="8" longitude="81" onSelect={select} />);
  await waitFor(() => expect(map.panTo).toHaveBeenCalledWith([8, 81]));
  unmount();
  expect(map.remove).toHaveBeenCalled();
});
test('tile failure is readable and clears on load; readonly map never registers click', async () => {
  render(<MapPicker disabled latitude="7" longitude="80" />);
  await waitFor(() => expect(L.tileLayer).toHaveBeenCalled());
  const tiles = L.tileLayer.mock.results[0].value;
  act(() => tiles.on.mock.calls.find(([name]) => name === 'tileerror')[1]());
  expect(screen.getByText(/Map tiles could not load/)).toBeTruthy();
  act(() => tiles.on.mock.calls.find(([name]) => name === 'load')[1]());
  expect(screen.queryByText(/Map tiles could not load/)).toBeNull();
  expect(L.map.mock.results[0].value.on).not.toHaveBeenCalled();
  expect(screen.getByText(/Stored location/)).toBeTruthy();
});
test.each([
  [undefined, undefined],
  ['', ''],
  ['NaN', '80'],
  ['7', 'NaN'],
  ['-91', '80'],
  ['91', '80'],
  ['7', '-181'],
  ['7', '181'],
])('invalid %p,%p uses safe default point', async (latitude, longitude) => {
  render(<MapPicker latitude={latitude} longitude={longitude} onSelect={() => {}} />);
  await waitFor(() => expect(L.map).toHaveBeenCalled());
  expect(L.map.mock.results[0].value.setView).toHaveBeenCalledWith([6.9271, 79.8612], 13);
});
test('map initialization failure falls back to GPS/coordinate entry', async () => {
  L.map.mockImplementationOnce(() => {
    throw new Error('Map unavailable');
  });
  render(<MapPicker />);
  await screen.findByText('Map unavailable. Use GPS or coordinate entry.');
});
test('native bridge validates messages and blocks external navigation', () => {
  const select = jest.fn();
  render(<NativeMap onSelect={select} />);
  expect(mockWebViewProps.onShouldStartLoadWithRequest({ url: 'about:blank' })).toBe(true);
  expect(mockWebViewProps.onShouldStartLoadWithRequest({ url: 'data:text/html,map' })).toBe(true);
  expect(mockWebViewProps.onShouldStartLoadWithRequest({ url: 'https://external.test' })).toBe(
    false,
  );
  act(() => mockWebViewProps.onMessage({ nativeEvent: { data: 'bad json' } }));
  act(() => mockWebViewProps.onMessage({ nativeEvent: { data: '{}' } }));
  expect(select).not.toHaveBeenCalled();
  act(() =>
    mockWebViewProps.onMessage({
      nativeEvent: {
        data: JSON.stringify({ type: 'dmc-map-select', latitude: 7, longitude: 80, zoom: 15 }),
      },
    }),
  );
  expect(select).toHaveBeenCalledWith(7, 80);
  expect(mockWebViewProps.source.html).toContain('],15)');
});
test('native readonly ignores even valid messages', () => {
  const select = jest.fn();
  render(<NativeMap disabled onSelect={select} />);
  act(() =>
    mockWebViewProps.onMessage({
      nativeEvent: {
        data: JSON.stringify({ type: 'dmc-map-select', latitude: 7, longitude: 80, zoom: 13 }),
      },
    }),
  );
  expect(select).not.toHaveBeenCalled();
});
