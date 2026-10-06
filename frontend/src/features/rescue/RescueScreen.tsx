import { RescueOperations } from './RescueOperations';
import { Link } from 'expo-router';
import { useState } from 'react';
import {
  Linking,
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
export interface Assignment {
  id: string;
  teamId: string;
  teamName: string;
  status: string;
  note: string;
  officer: string;
  assignedAt: string;
  updatedAt: string;
}
export interface Incident {
  id: string;
  version: number;
  reference: string;
  type: string;
  priority: string;
  description: string;
  location: string;
  latitude: number;
  longitude: number;
  peopleNeedingAssistance: number;
  status: string;
  updatedBy: string;
  updatedAt: string;
  assignments?: Assignment[];
  history: { id: string; detail: string; officer: string; at: string }[];
}
export interface Team {
  id: string;
  version: number;
  name: string;
  specialization: string;
  location: string;
  latitude: number;
  longitude: number;
  status: string;
  activeIncidentId: string | null;
  updatedBy: string;
  updatedAt: string;
}
interface Page {
  items: (Incident | Team)[];
  totalPages: number;
  totalElements: number;
}
const types = ['FLOOD_RESCUE', 'MEDICAL_RESCUE', 'STRUCTURAL_RESCUE', 'OTHER'];
const initial = {
  name: '',
  type: 'FLOOD_RESCUE',
  priority: 'HIGH',
  description: '',
  location: '',
  latitude: '',
  longitude: '',
  peopleNeedingAssistance: '',
  status: 'AVAILABLE',
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
export function RescueScreen({ session, onSignOut }: { session: Session; onSignOut: () => void }) {
  const wide = useWindowDimensions().width >= 900;
  const [tab, setTab] = useState<'incidents' | 'teams'>('incidents');
  const [selected, setSelected] = useState<Incident | Team | null>(null);
  const [form, setForm] = useState(initial);
  const [result, setResult] = useState<Page>({ items: [], totalPages: 0, totalElements: 0 });
  const [page, setPage] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const base = `/api/dmc/rescue/${tab}`;
  const readonly =
    selected !== null &&
    ('reference' in selected ? selected.status !== 'OPEN' : !!selected.activeIncidentId);
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
  function open(item: Incident | Team) {
    setSelected(item);
    setForm({
      ...initial,
      location: item.location,
      latitude: String(item.latitude),
      longitude: String(item.longitude),
      ...('reference' in item
        ? {
            type: item.type,
            priority: item.priority,
            description: item.description,
            peopleNeedingAssistance: String(item.peopleNeedingAssistance),
          }
        : { name: item.name, type: item.specialization, status: item.status }),
    });
  }
  function switchTab(next: 'incidents' | 'teams') {
    setTab(next);
    setSelected(null);
    setForm(initial);
    setLoaded(false);
    setResult({ items: [], totalPages: 0, totalElements: 0 });
    setPage(0);
    setError('');
    setMessage('');
  }
  async function load(next = 0) {
    setResult(await request<Page>(`${base}?page=${next}&size=10`, session.accessToken));
    setPage(next);
    setLoaded(true);
  }
  async function save() {
    const latitude = Number(form.latitude),
      longitude = Number(form.longitude);
    if (
      !form.location.trim() ||
      !form.latitude.trim() ||
      !form.longitude.trim() ||
      !Number.isFinite(latitude) ||
      latitude < -90 ||
      latitude > 90 ||
      !Number.isFinite(longitude) ||
      longitude < -180 ||
      longitude > 180
    )
      throw new Error('Enter location and valid coordinates.');
    const common = {
      location: form.location.trim(),
      latitude,
      longitude,
      ...(selected ? { expectedVersion: selected.version } : {}),
    };
    let body;
    if (tab === 'incidents') {
      const people = Number(form.peopleNeedingAssistance);
      if (!form.description.trim() || !Number.isSafeInteger(people) || people < 1)
        throw new Error(
          'Enter a description and positive whole number of people requiring assistance.',
        );
      body = {
        ...common,
        type: form.type,
        priority: form.priority,
        description: form.description.trim(),
        peopleNeedingAssistance: people,
      };
    } else {
      if (!form.name.trim()) throw new Error('Enter a team name.');
      body = { ...common, name: form.name.trim(), specialization: form.type, status: form.status };
    }
    open(
      await request<Incident | Team>(
        selected ? `${base}/${selected.id}` : base,
        session.accessToken,
        selected ? 'PUT' : 'POST',
        body,
      ),
    );
    setMessage('Saved successfully. Refresh the list for updated totals.');
  }
  const fields: [keyof typeof initial, string, number][] = [
    ...(tab === 'teams'
      ? [['name', 'Team name', 100] as [keyof typeof initial, string, number]]
      : [
          ['description', 'Incident description', 1000] as [keyof typeof initial, string, number],
          ['peopleNeedingAssistance', 'People requiring assistance', 12] as [
            keyof typeof initial,
            string,
            number,
          ],
        ]),
    ['location', 'Location / address', 200],
    ['latitude', 'Latitude', 25],
    ['longitude', 'Longitude', 25],
  ];
  return (
    <SafeAreaView style={s.root}>
      <View style={[s.shell, wide && { flexDirection: 'row' }]}>
        {wide && (
          <View style={s.sidebar}>
            <Text style={s.brand}>◈ DMC</Text>
            <Text style={s.light}>Emergency Response Portal</Text>
            <Link href="/" style={s.nav}>
              Dashboard
            </Link>
            <Text style={s.active}>Rescue Operations</Text>
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
            {tab === 'incidents' ? 'Rescue Incidents' : 'Rescue Teams'}
          </Text>
          <Text style={s.muted}>Record incidents and maintain team readiness.</Text>
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
                  title="Incidents"
                  disabled={busy || tab === 'incidents'}
                  onPress={() => switchTab('incidents')}
                />
                <Action
                  title="Rescue Teams"
                  disabled={busy || tab === 'teams'}
                  onPress={() => switchTab('teams')}
                />
                <Action
                  title="Refresh List"
                  disabled={busy}
                  onPress={() => void run(() => load())}
                />
              </View>
              <View style={[s.columns, wide && { flexDirection: 'row' }]}>
                <View style={[s.card, { flex: 3 }]}>
                  <Text style={s.heading}>
                    {loaded
                      ? `${result.totalElements} records`
                      : 'Press Refresh List to load records.'}
                  </Text>
                  {loaded && result.items.length === 0 && (
                    <Text style={s.muted}>No records yet. Add one with the form.</Text>
                  )}
                  {result.items.map((item) => (
                    <View key={item.id} style={s.record}>
                      <Text style={s.heading}>
                        {'reference' in item ? item.reference : item.name}
                      </Text>
                      <Text style={s.muted}>
                        {item.location} · {item.status}
                      </Text>
                      <Text style={s.body}>
                        {'reference' in item
                          ? `${item.type} · ${item.priority} · ${item.peopleNeedingAssistance} people`
                          : item.specialization}
                      </Text>
                      <Text style={s.muted}>
                        Updated by {item.updatedBy} · {new Date(item.updatedAt).toLocaleString()}
                      </Text>
                      <Action
                        title="View / Edit"
                        disabled={busy}
                        onPress={() =>
                          void run(async () =>
                            open(
                              await request<Incident | Team>(
                                `${base}/${item.id}`,
                                session.accessToken,
                              ),
                            ),
                          )
                        }
                      />
                    </View>
                  ))}
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
                <View style={[s.card, { flex: 2 }]}>
                  <Text style={s.heading}>
                    {selected ? 'View / Edit' : 'Create'}{' '}
                    {tab === 'incidents' ? 'incident' : 'team'}
                  </Text>
                  <Text style={s.body}>
                    {tab === 'incidents' ? 'Incident type' : 'Specialization'}
                  </Text>
                  <View style={s.row}>
                    {types.map((type) => (
                      <Pressable
                        key={type}
                        accessibilityRole="button"
                        accessibilityState={{ selected: form.type === type }}
                        disabled={busy || readonly}
                        onPress={() => setForm({ ...form, type })}
                        style={[s.chip, form.type === type && { backgroundColor: '#D9EAFD' }]}
                      >
                        <Text style={s.body}>{type.replaceAll('_', ' ')}</Text>
                      </Pressable>
                    ))}
                  </View>
                  <View style={s.row}>
                    {(tab === 'incidents'
                      ? ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
                      : ['AVAILABLE', 'UNAVAILABLE']
                    ).map((value) => (
                      <Pressable
                        key={value}
                        accessibilityRole="button"
                        accessibilityState={{
                          selected: (tab === 'incidents' ? form.priority : form.status) === value,
                        }}
                        disabled={busy || readonly}
                        onPress={() =>
                          setForm({ ...form, [tab === 'incidents' ? 'priority' : 'status']: value })
                        }
                        style={[
                          s.chip,
                          (tab === 'incidents' ? form.priority : form.status) === value && {
                            backgroundColor: '#D9EAFD',
                          },
                        ]}
                      >
                        <Text style={s.body}>{value}</Text>
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
                        multiline={key === 'description'}
                        keyboardType={
                          ['latitude', 'longitude', 'peopleNeedingAssistance'].includes(key)
                            ? 'numbers-and-punctuation'
                            : 'default'
                        }
                        style={[
                          s.input,
                          key === 'description' && { minHeight: 120, textAlignVertical: 'top' },
                        ]}
                      />
                    </View>
                  ))}
                  <Action
                    title={selected ? 'Save Changes' : 'Create Record'}
                    disabled={busy || readonly}
                    onPress={() => void run(save)}
                  />
                  <Action
                    title="New Record / Clear"
                    disabled={busy}
                    onPress={() => {
                      setSelected(null);
                      setForm(initial);
                      setError('');
                      setMessage('');
                    }}
                  />
                  {selected && (
                    <>
                      <Action
                        title="Reload Saved Record"
                        disabled={busy}
                        onPress={() =>
                          void run(async () =>
                            open(
                              await request<Incident | Team>(
                                `${base}/${selected.id}`,
                                session.accessToken,
                              ),
                            ),
                          )
                        }
                      />
                      <Action
                        title="Open Location in Map"
                        disabled={busy}
                        onPress={() =>
                          void run(async () => {
                            await Linking.openURL(
                              `https://www.openstreetmap.org/?mlat=${selected.latitude}&mlon=${selected.longitude}#map=16/${selected.latitude}/${selected.longitude}`,
                            );
                          })
                        }
                      />
                      {'reference' in selected && (
                        <>
                          <Text style={s.heading}>Incident history</Text>
                          {selected.history.map((event) => (
                            <Text key={event.id} style={s.body}>
                              {event.detail}
                              {'\n'}
                              {event.officer} · {new Date(event.at).toLocaleString()}
                            </Text>
                          ))}
                        </>
                      )}
                    </>
                  )}
                  {selected && 'reference' in selected && (
                    <RescueOperations
                      incident={selected}
                      token={session.accessToken}
                      busy={busy}
                      onUpdated={open}
                      run={run}
                    />
                  )}
                  <Text style={s.note}>
                    Operations record manual coordinator updates. No real emergency dispatch or live
                    GPS feed is connected.
                  </Text>
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
