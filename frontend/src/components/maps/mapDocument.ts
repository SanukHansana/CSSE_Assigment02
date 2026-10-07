export interface MapPickerProps {
  latitude?: string;
  longitude?: string;
  disabled?: boolean;
  onSelect?: (latitude: number, longitude: number) => void;
}
export function selection(
  value: unknown,
): { latitude: number; longitude: number; zoom: number } | null {
  if (!value || typeof value !== 'object') return null;
  const data = value as Record<string, unknown>;
  if (
    data.type !== 'dmc-map-select' ||
    typeof data.latitude !== 'number' ||
    typeof data.longitude !== 'number' ||
    typeof data.zoom !== 'number' ||
    !Number.isFinite(data.latitude) ||
    !Number.isFinite(data.longitude) ||
    data.latitude < -90 ||
    data.latitude > 90 ||
    data.longitude < -180 ||
    data.longitude > 180
  )
    return null;
  return {
    latitude: data.latitude,
    longitude: data.longitude,
    zoom: Math.min(18, Math.max(2, data.zoom)),
  };
}
export function mapDocument(
  latitude: string | undefined,
  longitude: string | undefined,
  disabled: boolean,
  zoom: number,
) {
  const lat = Number(latitude),
    lon = Number(longitude);
  const selected = Boolean(
    latitude?.trim() &&
    longitude?.trim() &&
    Number.isFinite(lat) &&
    Number.isFinite(lon) &&
    lat >= -90 &&
    lat <= 90 &&
    lon >= -180 &&
    lon <= 180,
  );
  const point = selected ? [lat, lon] : [6.9271, 79.8612];
  return `<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css"><style>html,body,#map{height:100%;width:100%;margin:0}body{background:#e5edf1;font-family:sans-serif}.help{position:absolute;top:10px;left:52px;right:10px;z-index:500;background:white;padding:10px 12px;border-radius:8px;font-size:12px;color:#29495e;box-shadow:0 2px 8px #183e4c22;pointer-events:none}</style></head><body><div id="map"></div><div class="help">${disabled ? 'Reported location' : 'Click the map or drag the pin to choose a location'}</div><script src="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js"></script><script>
 (function(){if(!window.L){document.querySelector('.help').textContent='Map unavailable. Please use GPS or coordinate entry.';return;}var map=L.map('map').setView(${JSON.stringify(point)},${zoom});L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>'}).addTo(map);
 var marker=null;function notify(point){var data={type:'dmc-map-select',latitude:point.lat,longitude:point.lng,zoom:map.getZoom()};if(window.ReactNativeWebView){window.ReactNativeWebView.postMessage(JSON.stringify(data));}else{window.parent.postMessage(data,'*');}}
 function place(point){if(marker)marker.setLatLng(point);else{marker=L.marker(point,{draggable:${!disabled}}).addTo(map);if(${!disabled})marker.on('dragend',function(){notify(marker.getLatLng());});}}
 if(${selected})place(${JSON.stringify(point)});if(${!disabled})map.on('click',function(e){place(e.latlng);notify(e.latlng);});setTimeout(function(){map.invalidateSize();},100);
 })();</script></body></html>`;
}
