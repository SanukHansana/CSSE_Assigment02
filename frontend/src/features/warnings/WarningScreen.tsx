import { Link } from 'expo-router';
import { useState } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useWindowDimensions,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { ApiError, request } from '../reports/api';
import type { Session } from '../reports/api';

interface Warning {
  id: string;
  version: number;
  reference: string;
  hazard: string;
  level: string;
  affectedArea: string;
  message: string;
  instructions: string;
  channels: string[];
  validUntil: string;
  status: string;
  createdBy: string;
  createdAt: string;
}
interface Page {
  items: Warning[];
  totalPages: number;
}
const blank = () => ({
  hazard: '',
  level: 'HIGH',
  affectedArea: '',
  message: '',
  instructions: '',
  channels: ['SMS'],
  validUntil: new Date(Date.now() + 6 * 60 * 60 * 1000).toISOString(),
});
const channelNames: Record<string, string> = {
  SMS: 'SMS',
  PUSH_NOTIFICATION: 'Push notification',
  AUDIBLE_ALERT: 'Audible alert',
};
function Action({
  title,
  onPress,
  disabled = false,
}: {
  title: string;
  onPress: () => void;
  disabled?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [s.button, (pressed || disabled) && { opacity: 0.5 }]}
    >
      <Text style={s.buttonText}>{title}</Text>
    </Pressable>
  );
}
export function WarningScreen({ session, onSignOut }: { session: Session; onSignOut: () => void }) {
  const wide = useWindowDimensions().width >= 900;
  const [form, setForm] = useState(blank);
  const [selected, setSelected] = useState<Warning | null>(null);
  const [preview, setPreview] = useState(false);
  const [result, setResult] = useState<Page>({ items: [], totalPages: 0 });
  const [loaded, setLoaded] = useState(false);
  const [page, setPage] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const readonly = selected !== null && selected.status !== 'DRAFT';
  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError('');
    setMessage('');
    try {
      await work();
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) onSignOut();
      else setError(cause instanceof Error ? cause.message : 'Connection failed.');
    } finally {
      setBusy(false);
    }
  }
  function open(warning: Warning) {
    setSelected(warning);
    setForm({
      hazard: warning.hazard,
      level: warning.level,
      affectedArea: warning.affectedArea,
      message: warning.message,
      instructions: warning.instructions,
      channels: warning.channels,
      validUntil: warning.validUntil,
    });
    setPreview(false);
  }
  async function load(next = 0) {
    setResult(await request<Page>(`/api/dmc/warnings?page=${next}&size=10`, session.accessToken));
    setPage(next);
    setLoaded(true);
  }
  async function save(showPreview = false) {
    if (
      !form.hazard.trim() ||
      !form.affectedArea.trim() ||
      !form.message.trim() ||
      !form.instructions.trim() ||
      !form.channels.length
    )
      throw new Error('Complete the warning details and select at least one channel.');
    const expiry = new Date(form.validUntil);
    if (!Number.isFinite(expiry.getTime()) || expiry.getTime() <= Date.now())
      throw new Error('Enter a future expiry using ISO date/time.');
    const saved = await request<Warning>(
      selected ? `/api/dmc/warnings/${selected.id}` : '/api/dmc/warnings',
      session.accessToken,
      selected ? 'PUT' : 'POST',
      {
        ...form,
        validUntil: expiry.toISOString(),
        ...(selected ? { expectedVersion: selected.version } : {}),
      },
    );
    open(saved);
    setPreview(showPreview);
    setMessage('Warning draft saved. No notifications have been sent.');
  }
  const fields: [
    keyof Pick<typeof form, 'hazard' | 'affectedArea' | 'message' | 'instructions' | 'validUntil'>,
    string,
    number,
  ][] = [
    ['hazard', 'Hazard / official assessment summary', 200],
    ['affectedArea', 'Affected area / district', 200],
    ['message', 'Warning message', 500],
    ['instructions', 'Instructions / safety advice', 300],
    ['validUntil', 'Valid until (ISO date/time)', 40],
  ];
  return (
    <SafeAreaView style={s.root}>
      <View style={[s.shell, wide && { flexDirection: 'row' }]}>
        {wide && (
          <View style={s.sidebar}>
            <Text style={s.brand}>◈ DMC</Text>
            <Text style={s.light}>Disaster Management Centre</Text>
            <Link href="/" style={s.nav}>
              Dashboard
            </Link>
            <Text style={s.active}>Warnings</Text>
            <View style={{ flex: 1 }} />
            <Text style={s.light}>
              {session.user.displayName}
              {'\n'}DMC Officer
            </Text>
          </View>
        )}
        <ScrollView
          style={{ flex: 1 }}
          contentContainerStyle={s.content}
          keyboardShouldPersistTaps="handled"
        >
          <View style={s.row}>
            <Link href="/" style={s.link}>
              ← Home
            </Link>
            <Text style={s.muted}>{session.user.displayName}</Text>
            <Action title="Sign out" disabled={busy} onPress={onSignOut} />
          </View>
          <Text accessibilityRole="header" style={s.title}>
            {preview ? 'Preview Warning' : 'Compose Warning'}
          </Text>
          <Text style={s.muted}>Create and review an official warning draft.</Text>
          {!session.user.roles.includes('DMC_OFFICER') ? (
            <Text style={s.error}>A DMC Officer account is required.</Text>
          ) : (
            <>
              {error ? (
                <Text accessibilityRole="alert" style={s.error}>
                  {error}
                </Text>
              ) : null}
              {message ? (
                <Text accessibilityLiveRegion="polite" style={s.success}>
                  {message}
                </Text>
              ) : null}
              <View style={s.row}>
                <Action
                  title="New Warning"
                  disabled={busy}
                  onPress={() => {
                    setSelected(null);
                    setForm(blank());
                    setPreview(false);
                    setError('');
                    setMessage('');
                  }}
                />
                <Action
                  title="Refresh Warning List"
                  disabled={busy}
                  onPress={() => void run(() => load())}
                />
                {selected && (
                  <Action
                    title="Reload Saved Warning"
                    disabled={busy}
                    onPress={() =>
                      void run(async () =>
                        open(
                          await request<Warning>(
                            `/api/dmc/warnings/${selected.id}`,
                            session.accessToken,
                          ),
                        ),
                      )
                    }
                  />
                )}
              </View>
              <View style={[s.columns, wide && { flexDirection: 'row' }]}>
                <View style={[s.card, { flex: 3 }]}>
                  {preview ? (
                    <>
                      <View
                        style={[
                          s.previewHeader,
                          { backgroundColor: form.level === 'HIGH' ? '#FEEEEE' : '#FFF7E8' },
                        ]}
                      >
                        <Text style={s.heading}>
                          ⚠ {form.hazard} ({form.level})
                        </Text>
                      </View>
                      <Text style={s.body}>Area: {form.affectedArea}</Text>
                      <Text style={s.heading}>Message</Text>
                      <Text style={s.body}>{form.message}</Text>
                      <Text style={s.heading}>Instructions</Text>
                      <Text style={s.body}>{form.instructions}</Text>
                      <Text style={s.body}>
                        Channels: {form.channels.map((channel) => channelNames[channel]).join(', ')}
                      </Text>
                      <Text style={s.muted}>
                        Expires: {new Date(form.validUntil).toLocaleString()}
                      </Text>
                      <Text style={s.muted}>
                        Created by: {selected?.createdBy} ·{' '}
                        {selected?.createdAt ? new Date(selected.createdAt).toLocaleString() : ''}
                      </Text>
                      <Action
                        title="Back to Details"
                        disabled={busy}
                        onPress={() => setPreview(false)}
                      />
                      <Text style={s.note}>
                        Broadcasting is added in the next step. This is a saved draft; no recipients
                        have been notified.
                      </Text>
                    </>
                  ) : (
                    <>
                      <Text style={s.heading}>
                        {selected ? selected.reference : 'New disaster warning'} {selected?.status}
                      </Text>
                      <View style={s.row}>
                        {['LOW', 'MEDIUM', 'HIGH'].map((level) => (
                          <Pressable
                            key={level}
                            accessibilityRole="button"
                            accessibilityState={{ selected: form.level === level }}
                            disabled={busy || readonly}
                            onPress={() => setForm({ ...form, level })}
                            style={[s.chip, form.level === level && { backgroundColor: '#D9EAFD' }]}
                          >
                            <Text style={s.body}>{level}</Text>
                          </Pressable>
                        ))}
                      </View>
                      {fields.map(([key, title, max]) => (
                        <View key={key} style={{ gap: 8 }}>
                          <Text style={s.body}>{title} *</Text>
                          <TextInput
                            accessibilityLabel={title}
                            value={form[key]}
                            onChangeText={(value) => setForm({ ...form, [key]: value })}
                            editable={!busy && !readonly}
                            maxLength={max}
                            multiline={key === 'message' || key === 'instructions'}
                            style={[
                              s.input,
                              (key === 'message' || key === 'instructions') && {
                                minHeight: 120,
                                textAlignVertical: 'top',
                              },
                            ]}
                          />
                          {(key === 'message' || key === 'instructions') && (
                            <Text style={s.muted}>
                              {form[key].length}/{max}
                            </Text>
                          )}
                        </View>
                      ))}
                      <Text style={s.heading}>Delivery channels</Text>
                      <View style={s.row}>
                        {Object.entries(channelNames).map(([channel, title]) => (
                          <Pressable
                            key={channel}
                            accessibilityRole="checkbox"
                            accessibilityState={{ checked: form.channels.includes(channel) }}
                            disabled={busy || readonly}
                            onPress={() =>
                              setForm({
                                ...form,
                                channels: form.channels.includes(channel)
                                  ? form.channels.filter((value) => value !== channel)
                                  : [...form.channels, channel],
                              })
                            }
                            style={[
                              s.chip,
                              form.channels.includes(channel) && { backgroundColor: '#D9EAFD' },
                            ]}
                          >
                            <Text style={s.body}>
                              {form.channels.includes(channel) ? '✓ ' : ''}
                              {title}
                            </Text>
                          </Pressable>
                        ))}
                      </View>
                      <View style={s.row}>
                        <Action
                          title="Save Draft"
                          disabled={busy || readonly}
                          onPress={() => void run(() => save())}
                        />
                        <Action
                          title="Review Preview"
                          disabled={busy}
                          onPress={() => (readonly ? setPreview(true) : void run(() => save(true)))}
                        />
                      </View>
                      <Text style={s.note}>
                        Warnings are prepared by an officer. Ground reports do not automatically
                        issue warnings. Channel delivery is not implemented in this step.
                      </Text>
                    </>
                  )}
                </View>
                <View style={[s.card, { flex: 2 }]}>
                  <Text style={s.heading}>Warning directory</Text>
                  {!loaded ? (
                    <Text style={s.muted}>Press Refresh Warning List to load.</Text>
                  ) : result.items.length === 0 ? (
                    <Text style={s.muted}>No saved warnings yet.</Text>
                  ) : (
                    result.items.map((item) => (
                      <View key={item.id} style={s.record}>
                        <Text style={s.heading}>{item.hazard}</Text>
                        <Text style={s.muted}>
                          {item.affectedArea} · {item.level} · {item.status}
                        </Text>
                        <Action
                          title="View / Edit"
                          disabled={busy}
                          onPress={() =>
                            void run(async () =>
                              open(
                                await request<Warning>(
                                  `/api/dmc/warnings/${item.id}`,
                                  session.accessToken,
                                ),
                              ),
                            )
                          }
                        />
                      </View>
                    ))
                  )}
                  {loaded && (
                    <View style={s.row}>
                      <Action
                        title="Previous"
                        disabled={busy || page === 0}
                        onPress={() => void run(() => load(page - 1))}
                      />
                      <Text style={s.muted}>Page {page + 1}</Text>
                      <Action
                        title="Next"
                        disabled={busy || page + 1 >= result.totalPages}
                        onPress={() => void run(() => load(page + 1))}
                      />
                    </View>
                  )}
                </View>
              </View>
            </>
          )}
        </ScrollView>
      </View>
    </SafeAreaView>
  );
}
const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#F5F7FB' },
  shell: { flex: 1 },
  sidebar: { width: 230, backgroundColor: '#06234D', padding: 22, gap: 24 },
  brand: { fontSize: 28, fontWeight: '700', color: '#FFFFFF' },
  light: { color: '#C5D9EF', lineHeight: 24 },
  nav: { color: '#FFFFFF', fontSize: 16, paddingVertical: 14 },
  active: { color: '#FFFFFF', backgroundColor: '#005BEA', padding: 16, borderRadius: 8 },
  content: { padding: 24, gap: 20, width: '100%', maxWidth: 1300, alignSelf: 'center' },
  title: { fontSize: 28, fontWeight: '700', color: '#09274F' },
  heading: { fontSize: 19, fontWeight: '700', color: '#09274F' },
  body: { color: '#253B56', fontSize: 15, lineHeight: 24 },
  muted: { color: '#52647B', fontSize: 14, lineHeight: 22 },
  link: { color: '#005BEA', fontSize: 15 },
  row: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 12 },
  columns: { gap: 20 },
  card: {
    backgroundColor: '#FFFFFF',
    borderWidth: 1,
    borderColor: '#DFE5EF',
    borderRadius: 14,
    padding: 20,
    gap: 16,
  },
  record: { borderBottomWidth: 1, borderBottomColor: '#DFE5EF', paddingVertical: 18, gap: 12 },
  input: {
    borderWidth: 1,
    borderColor: '#CBD5E1',
    borderRadius: 8,
    minHeight: 48,
    padding: 13,
    fontSize: 16,
    color: '#253B56',
  },
  button: {
    backgroundColor: '#005BEA',
    padding: 14,
    minHeight: 48,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonText: { color: '#FFFFFF', fontSize: 14, fontWeight: '600' },
  chip: { backgroundColor: '#F1F4F8', padding: 12, minHeight: 44, borderRadius: 8 },
  previewHeader: { padding: 20, borderRadius: 8 },
  error: {
    color: '#B42318',
    backgroundColor: '#FEEEEE',
    padding: 14,
    lineHeight: 22,
    borderRadius: 8,
  },
  success: {
    color: '#087A4B',
    backgroundColor: '#EAF8F1',
    padding: 14,
    lineHeight: 22,
    borderRadius: 8,
  },
  note: {
    color: '#795A21',
    backgroundColor: '#FFF7E8',
    padding: 14,
    lineHeight: 22,
    borderRadius: 8,
  },
});
