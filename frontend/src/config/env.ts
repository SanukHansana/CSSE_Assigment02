import { Platform } from 'react-native';

// Android emulators reach the host through 10.0.2.2. Physical devices need its LAN IP.
const defaultApiBaseUrl =
  Platform.OS === 'android' ? 'http://10.0.2.2:8080' : 'http://localhost:8080';

export const env = {
  apiBaseUrl: process.env.EXPO_PUBLIC_API_BASE_URL || defaultApiBaseUrl,
};
