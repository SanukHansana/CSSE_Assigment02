import { ActivityIndicator, Pressable, StyleSheet, Text } from 'react-native';

import { theme } from '../../theme/theme';

interface ButtonProps {
  label: string;
  onPress: () => void;
  loading?: boolean;
}

/** Presentational component: no feature logic or API dependencies. */
export function Button({ label, onPress, loading = false }: ButtonProps) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: loading, busy: loading }}
      disabled={loading}
      onPress={onPress}
      style={({ pressed }) => [styles.button, (pressed || loading) && styles.dimmed]}
    >
      {loading ? (
        <ActivityIndicator color={theme.colors.surface} />
      ) : (
        <Text style={styles.label}>{label}</Text>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: 48,
    backgroundColor: theme.colors.primary,
    borderRadius: theme.radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    padding: theme.spacing.md,
  },
  dimmed: { opacity: 0.65 },
  label: { color: theme.colors.surface, fontSize: 16, fontWeight: '600' },
});
