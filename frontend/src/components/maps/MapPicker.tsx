import { useMemo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { WebView } from 'react-native-webview';
import { mapDocument, selection } from './mapDocument';
import type { MapPickerProps } from './mapDocument';
export function MapPicker({ latitude, longitude, disabled = false, onSelect }: MapPickerProps) {
  const [zoom, setZoom] = useState(13);
  const readonly = disabled || !onSelect;
  const html = useMemo(
    () => mapDocument(latitude, longitude, readonly, zoom),
    [latitude, longitude, readonly, zoom],
  );
  return (
    <View style={s.wrapper}>
      <View style={s.map}>
        <WebView
          applicationNameForUserAgent="DMC-DisasterReportingPortal/1.0"
          source={{ html }}
          originWhitelist={['*']}
          onShouldStartLoadWithRequest={(request) =>
            request.url === 'about:blank' || request.url.startsWith('data:text/html')
          }
          onMessage={(event) => {
            if (readonly) return;
            try {
              const point = selection(JSON.parse(event.nativeEvent.data));
              if (point) {
                setZoom(point.zoom);
                onSelect?.(point.latitude, point.longitude);
              }
            } catch {
              /* Ignore messages that are not valid map selections. */
            }
          }}
        />
      </View>
      <Text style={s.caption}>
        {readonly ? 'Stored location' : 'Tap the map or drag the pin to select a location.'}{' '}
        Internet required; GPS and coordinate entry remain available.
      </Text>
    </View>
  );
}
const s = StyleSheet.create({
  wrapper: { gap: 9 },
  map: { height: 290, overflow: 'hidden', borderRadius: 12 },
  caption: { fontSize: 12, lineHeight: 19, color: '#61798A' },
});
