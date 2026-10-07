import { Pressable, StyleSheet, Text } from 'react-native';
export function Action({
  title,
  onPress,
  disabled = false,
  secondary = false,
  danger = false,
}: {
  title: string;
  onPress: () => void;
  disabled?: boolean;
  secondary?: boolean;
  danger?: boolean;
}) {
  const destructive = danger || /reject|cancel|close incident/i.test(title);
  const positive = /approv|verif|complete|resolved|confirm submission/i.test(title) && !destructive;
  const quiet =
    secondary ||
    /^(previous|next|back|reload|refresh|load|new |clear|sign out|map|open.+map)/i.test(title);
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        s.button,
        quiet && s.quiet,
        positive && s.positive,
        destructive && s.danger,
        (disabled || pressed) && s.dimmed,
      ]}
    >
      <Text style={[s.label, quiet && !positive && !destructive && s.quietLabel]}>{title}</Text>
    </Pressable>
  );
}
const s = StyleSheet.create({
  button: {
    backgroundColor: '#173F5F',
    paddingHorizontal: 20,
    paddingVertical: 13,
    minHeight: 46,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'transparent',
  },
  quiet: { backgroundColor: '#F5F8FA', borderColor: '#D9E4EA' },
  positive: { backgroundColor: '#087F70' },
  danger: { backgroundColor: '#B54747' },
  dimmed: { opacity: 0.48 },
  label: { color: '#FFFFFF', fontWeight: '700', fontSize: 14 },
  quietLabel: { color: '#28475D' },
});
