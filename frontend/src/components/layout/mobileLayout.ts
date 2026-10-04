import { StyleSheet, useWindowDimensions } from 'react-native';
/** Phone presentation only; desktop keeps its existing layout. */
export function useMobileLayout() {
  return useWindowDimensions().width < 700;
}
export const mobileStyles = StyleSheet.create({
  content: { padding: 16, paddingBottom: 28, gap: 16 },
  title: { fontSize: 25, lineHeight: 32, fontWeight: '800' },
  card: { padding: 16, borderRadius: 20, gap: 14, flex: 0, minWidth: 0 },
  columns: { gap: 16 },
  column: { flex: 0, gap: 16 },
  photo: { height: 190, borderRadius: 14 },
  input: { borderRadius: 12, minHeight: 50, fontSize: 16 },
  row: { gap: 8 },
  record: {
    backgroundColor: '#F4F8FA',
    padding: 14,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#DFE7ED',
    marginBottom: 10,
  },
  actions: { alignItems: 'stretch' },
});
