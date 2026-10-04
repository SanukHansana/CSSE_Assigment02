import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { KeyboardAvoidingView, Platform, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AuthProvider } from '../application/AuthContext';
import { AppNavigation, MobileTabs } from '../components/layout/AppNavigation';
export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <AuthProvider>
        <StatusBar style="light" />
        <View style={{ flex: 1 }}>
          <AppNavigation />
          <KeyboardAvoidingView
            style={{ flex: 1 }}
            enabled={Platform.OS !== 'web'}
            behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
          >
            <Stack screenOptions={{ headerShown: false }} />
          </KeyboardAvoidingView>
          <MobileTabs />
        </View>
      </AuthProvider>
    </SafeAreaProvider>
  );
}
