import { useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { Map, Marker } from 'leaflet';
import type { MapPickerProps } from './mapDocument';
function point(latitude?: string, longitude?: string): [number, number] | null {
  const lat = Number(latitude),
    lon = Number(longitude);
  return latitude?.trim() &&
    longitude?.trim() &&
    Number.isFinite(lat) &&
    Number.isFinite(lon) &&
    lat >= -90 &&
    lat <= 90 &&
    lon >= -180 &&
    lon <= 180
    ? [lat, lon]
    : null;
}
export function MapPicker({ latitude, longitude, disabled = false, onSelect }: MapPickerProps) {
  const container = useRef<HTMLDivElement>(null);
  const instance = useRef<Map | null>(null);
  const marker = useRef<Marker | null>(null);
  const moveMarker = useRef<((point: [number, number]) => void) | null>(null);
  const select = useRef(onSelect);
  const coordinates = useRef(point(latitude, longitude));
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const readonly = disabled || !onSelect;
  useEffect(() => {
    select.current = onSelect;
    coordinates.current = point(latitude, longitude);
  }, [onSelect, latitude, longitude]);
  useEffect(() => {
    let active = true;
    void import('leaflet')
      .then((L) => {
        if (!active || !container.current) return;
        const map = L.map(container.current).setView(coordinates.current || [6.9271, 79.8612], 13);
        instance.current = map;
        const icon = L.icon({
          iconUrl: 'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/images/marker-icon.png',
          iconRetinaUrl:
            'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/images/marker-icon-2x.png',
          shadowUrl: 'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/images/marker-shadow.png',
          iconSize: [25, 41],
          iconAnchor: [12, 41],
          shadowSize: [41, 41],
        });
        const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
          maxZoom: 19,
          attribution:
            '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap contributors</a>',
          referrerPolicy: 'strict-origin-when-cross-origin',
        }).addTo(map);
        tiles.on('tileerror', () => {
          if (active)
            setError('Map tiles could not load. You can still use GPS or enter coordinates.');
        });
        tiles.on('load', () => {
          if (active) setError('');
        });
        function place(lat: number, lon: number) {
          if (marker.current) marker.current.setLatLng([lat, lon]);
          else {
            marker.current = L.marker([lat, lon], { draggable: !readonly, icon }).addTo(map);
            if (!readonly)
              marker.current.on('dragend', () => {
                const position = marker.current!.getLatLng();
                select.current?.(position.lat, ((position.lng + 540) % 360) - 180);
              });
          }
        }
        moveMarker.current = (value) => place(...value);
        if (coordinates.current) place(...coordinates.current);
        if (!readonly)
          map.on('click', (event) => {
            const lon = ((event.latlng.lng + 540) % 360) - 180;
            place(event.latlng.lat, lon);
            select.current?.(event.latlng.lat, lon);
          });
        if (active) setReady(true);
      })
      .catch(() => {
        if (active) setError('Map unavailable. Use GPS or coordinate entry.');
      });
    return () => {
      active = false;
      instance.current?.remove();
      instance.current = null;
      marker.current = null;
      moveMarker.current = null;
    };
  }, [readonly]);
  useEffect(() => {
    const value = point(latitude, longitude);
    if (ready && value && instance.current) {
      instance.current.panTo(value);
      moveMarker.current?.(value);
    }
  }, [latitude, longitude, ready]);
  return (
    <View style={s.wrapper}>
      <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css" />
      <div
        ref={container}
        aria-label="Interactive location picker: click the map to choose a location"
        style={{
          height: 290,
          width: '100%',
          borderRadius: 12,
          overflow: 'hidden',
          background: '#E5EDF1',
          position: 'relative',
          zIndex: 0,
        }}
      />
      {error ? <Text style={s.error}>{error}</Text> : null}
      <Text style={s.caption}>
        {readonly
          ? 'Stored location'
          : 'Click the map or drag the pin. Coordinates update automatically.'}{' '}
        Internet required; GPS and coordinate entry remain available.
      </Text>
    </View>
  );
}
const s = StyleSheet.create({
  wrapper: { gap: 9 },
  caption: { fontSize: 12, lineHeight: 19, color: '#61798A' },
  error: { fontSize: 12, lineHeight: 19, color: '#A34C4C' },
});
