import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { DmcScreen } from '../features/dmc/screens/DmcScreen';
import { services } from './services';

/** Application entry point; dependencies are assembled outside the UI. */
export function App() {
  return (
    <SafeAreaProvider>
      <StatusBar style="dark" />
      <DmcScreen service={services.dmc} />
    </SafeAreaProvider>
  );
}
