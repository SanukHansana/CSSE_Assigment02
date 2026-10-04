import { mobileStyles, useMobileLayout } from './mobileLayout';
import type { PropsWithChildren } from 'react';
import { ScrollView, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { theme } from '../../theme/theme';

export function Screen({ children }: PropsWithChildren) {
  const mobile = useMobileLayout();
  return (
    <SafeAreaView edges={mobile ? ['left', 'right'] : undefined} style={styles.container}>
      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={[styles.content, mobile && mobileStyles.content]}
      >
        {children}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: theme.colors.background },
  content: { flexGrow: 1, padding: theme.spacing.lg, gap: theme.spacing.lg },
});
