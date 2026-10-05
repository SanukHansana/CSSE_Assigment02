import { StyleSheet, Text, View } from 'react-native';

import { Screen } from '../../../components/layout/Screen';
import { Button } from '../../../components/ui/Button';
import { theme } from '../../../theme/theme';
import { useDmcStatus } from '../hooks/useDmcStatus';
import type { DmcService } from '../services/DmcService';

interface DmcScreenProps {
  service: DmcService;
}

export function DmcScreen({ service }: DmcScreenProps) {
  const { state, refresh } = useDmcStatus(service);

  return (
    <Screen>
      <Text accessibilityRole="header" style={styles.title}>
        DMC
      </Text>
      <Text style={styles.subtitle}>Welcome to your DMC application.</Text>
      <View style={styles.card}>
        <Text style={styles.heading}>Backend connection</Text>
        <Text style={styles.body}>Check whether the DMC service is available.</Text>
        <View accessibilityLiveRegion="polite">
          {state.phase === 'success' && (
            <Text style={styles.body}>
              {state.data.application}: {state.data.status}
            </Text>
          )}
          {state.phase === 'error' && <Text style={styles.error}>{state.message}</Text>}
        </View>
        <Button
          label="Check connection"
          loading={state.phase === 'loading'}
          onPress={() => void refresh()}
        />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  title: { fontSize: 36, fontWeight: '700', color: theme.colors.text },
  subtitle: { fontSize: 17, color: theme.colors.muted },
  card: {
    padding: theme.spacing.lg,
    gap: theme.spacing.md,
    borderRadius: theme.radius.lg,
    borderWidth: 1,
    borderColor: theme.colors.border,
    backgroundColor: theme.colors.surface,
  },
  heading: { fontSize: 20, fontWeight: '600', color: theme.colors.text },
  body: { fontSize: 16, color: theme.colors.muted, lineHeight: 24 },
  error: { fontSize: 16, color: theme.colors.error },
});
