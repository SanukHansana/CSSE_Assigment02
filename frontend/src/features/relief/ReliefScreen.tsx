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

interface Resource {
  id: string;
  version: number;
  name: string;
  type: string;
  location: string;
  availableQuantity: number;
  unit: string;
  updatedBy: string;
  updatedAt: string;
}
interface Shelter {
  id: string;
  version: number;
  name: string;
  location: string;
  capacity: number;
  occupancy: number;
  status: string;
  updatedBy: string;
  updatedAt: string;
}
interface Page {
  items: (Resource | Shelter)[];
  totalPages: number;
  totalElements: number;
}
const initial = {
  name: '',
  type: '',
  location: '',
  availableQuantity: '',
  unit: '',
  capacity: '',
  occupancy: '',
  status: 'OPEN',
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
      disabled={disabled}
      accessibilityState={{ disabled }}
      onPress={onPress}
      style={({ pressed }) => [s.button, (pressed || disabled) && { opacity: 0.5 }]}
    >
      <Text style={s.buttonText}>{title}</Text>
    </Pressable>
  );
}
export function ReliefScreen({ session, onSignOut }: { session: Session; onSignOut: () => void }) {
  const wide = useWindowDimensions().width >= 900;
  const [tab, setTab] = useState<'resources' | 'shelters'>('resources');
  const [result, setResult] = useState<Page>({ items: [], totalPages: 0, totalElements: 0 });
  const [loaded, setLoaded] = useState(false);
  const [page, setPage] = useState(0);
  const [form, setForm] = useState(initial);
  const [selected, setSelected] = useState<Resource | Shelter | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const allowed = session.user.roles.includes('DMC_OFFICER');
  const base = `/api/dmc/relief/${tab}`;
  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError('');
    setMessage('');
    try {
      await work();
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) onSignOut();
      else setError(cause instanceof Error ? cause.message : 'Connection failed. Try again.');
    } finally {
      setBusy(false);
    }
  }
  async function load(next = page) {
    const data = await request<Page>(`${base}?page=${next}&size=10`, session.accessToken);
    setResult(data);
    setLoaded(true);
    setPage(next);
  }
  function edit(item: Resource | Shelter) {
    setSelected(item);
    setForm({
      ...initial,
      name: item.name,
      location: item.location,
      ...('availableQuantity' in item
        ? { type: item.type, availableQuantity: String(item.availableQuantity), unit: item.unit }
        : {
            capacity: String(item.capacity),
            occupancy: String(item.occupancy),
            status: item.status,
          }),
    });
    setMessage('');
  }
  function switchTab(next: 'resources' | 'shelters') {
    setTab(next);
    setSelected(null);
    setForm(initial);
    setResult({ items: [], totalPages: 0, totalElements: 0 });
    setLoaded(false);
    setPage(0);
    setError('');
    setMessage('');
  }
  async function save() {
    if (!form.name.trim() || !form.location.trim())
      throw new Error('Name and location are required.');
    function integer(value: string, title: string, minimum = 0) {
      const number = Number(value);
      if (!value.trim() || !Number.isSafeInteger(number) || number < minimum)
        throw new Error(`${title} must be a whole number of at least ${minimum}.`);
      return number;
    }
    const common = {
      name: form.name.trim(),
      location: form.location.trim(),
      ...(selected ? { expectedVersion: selected.version } : {}),
    };
    const body =
      tab === 'resources'
        ? {
            ...common,
            type: form.type.trim(),
            unit: form.unit.trim(),
            availableQuantity: integer(form.availableQuantity, 'Quantity'),
          }
        : {
            ...common,
            capacity: integer(form.capacity, 'Capacity', 1),
            occupancy: integer(form.occupancy, 'Occupancy'),
            status: form.status,
          };
    if (tab === 'resources' && (!form.type.trim() || !form.unit.trim()))
      throw new Error('Type and unit are required.');
    const saved = await request<Resource | Shelter>(
      selected ? `${base}/${selected.id}` : base,
      session.accessToken,
      selected ? 'PUT' : 'POST',
      body,
    );
    edit(saved);
    setMessage('Saved successfully. Refresh the list to see the latest records.');
  }
  const fields: [keyof typeof initial, string, boolean][] =
    tab === 'resources'
      ? [
          ['name', 'Resource name', false],
          ['type', 'Resource type', false],
          ['location', 'Storage location', false],
          ['availableQuantity', 'Available quantity', true],
          ['unit', 'Unit (bottles, packs, etc.)', false],
        ]
      : [
          ['name', 'Shelter name', false],
          ['location', 'Location / district', false],
          ['capacity', 'Capacity', true],
          ['occupancy', 'Current occupancy', true],
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
            <Text style={s.active}>Resources & Shelters</Text>
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
            Relief Resources & Shelters
          </Text>
          <Text style={s.muted}>Manage available supplies and shelter capacity.</Text>
          {!allowed ? (
            <Text style={s.error}>
              A DMC Officer account is required. Public registration creates citizen accounts.
            </Text>
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
                  title="Resources"
                  disabled={busy || tab === 'resources'}
                  onPress={() => switchTab('resources')}
                />
                <Action
                  title="Shelters"
                  disabled={busy || tab === 'shelters'}
                  onPress={() => switchTab('shelters')}
                />
                <Action
                  title="Refresh List"
                  disabled={busy}
                  onPress={() => void run(() => load(0))}
                />
              </View>
              <View style={[s.columns, wide && { flexDirection: 'row' }]}>
                <View style={[s.card, { flex: 3 }]}>
                  <Text style={s.heading}>
                    {tab === 'resources' ? 'Available resources' : 'Shelter directory'}
                  </Text>
                  <Text style={s.muted}>
                    {loaded
                      ? `${result.totalElements} records`
                      : 'Press Refresh List to load records.'}
                  </Text>
                  {loaded && result.items.length === 0 && (
                    <Text style={s.muted}>No records yet. Add one using the form.</Text>
                  )}
                  {result.items.map((item) => (
                    <View key={item.id} style={s.record}>
                      <Text style={s.heading}>{item.name}</Text>
                      <Text style={s.muted}>{item.location}</Text>
                      {'availableQuantity' in item ? (
                        <Text style={s.body}>
                          {item.type} · {item.availableQuantity} {item.unit}
                        </Text>
                      ) : (
                        <>
                          <Text style={s.badge}>{item.status}</Text>
                          <Text style={s.body}>
                            {item.occupancy} / {item.capacity} occupants ·{' '}
                            {item.capacity - item.occupancy} places free
                          </Text>
                        </>
                      )}
                      <Text style={s.muted}>
                        Updated by {item.updatedBy} · {new Date(item.updatedAt).toLocaleString()}
                      </Text>
                      <Action
                        title="View / Edit"
                        disabled={busy}
                        onPress={() =>
                          void run(async () =>
                            edit(
                              await request<Resource | Shelter>(
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
                    {selected ? 'Edit' : 'Add'} {tab === 'resources' ? 'resource' : 'shelter'}
                  </Text>
                  {fields.map(([key, title, numeric]) => (
                    <View key={key} style={{ gap: 8 }}>
                      <Text style={s.body}>{title} *</Text>
                      <TextInput
                        accessibilityLabel={title}
                        editable={!busy}
                        value={form[key]}
                        onChangeText={(value) => setForm({ ...form, [key]: value })}
                        keyboardType={numeric ? 'number-pad' : 'default'}
                        maxLength={
                          numeric
                            ? 12
                            : key === 'location'
                              ? 200
                              : key === 'type'
                                ? 60
                                : key === 'unit'
                                  ? 30
                                  : 100
                        }
                        style={s.input}
                      />
                    </View>
                  ))}
                  {tab === 'shelters' && (
                    <View style={s.row}>
                      {['OPEN', 'FULL', 'CLOSED'].map((value) => (
                        <Pressable
                          key={value}
                          accessibilityRole="button"
                          accessibilityState={{ selected: form.status === value }}
                          disabled={busy}
                          onPress={() => setForm({ ...form, status: value })}
                          style={[s.chip, form.status === value && { backgroundColor: '#D9EAFD' }]}
                        >
                          <Text style={s.body}>{value}</Text>
                        </Pressable>
                      ))}
                    </View>
                  )}
                  <Action
                    title={busy ? 'Saving…' : selected ? 'Save Changes' : 'Create Record'}
                    disabled={busy}
                    onPress={() => void run(save)}
                  />
                  <Action
                    title="New Record / Clear Form"
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
                      <Text style={s.muted}>
                        Version {selected.version}. If another officer changes this record, reload
                        before saving.
                      </Text>
                      <Action
                        title="Reload Saved Record"
                        disabled={busy}
                        onPress={() =>
                          void run(async () =>
                            edit(
                              await request<Resource | Shelter>(
                                `${base}/${selected.id}`,
                                session.accessToken,
                              ),
                            ),
                          )
                        }
                      />
                    </>
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
  badge: {
    backgroundColor: '#EDF4FF',
    color: '#005BEA',
    padding: 8,
    borderRadius: 8,
    alignSelf: 'flex-start',
  },
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
});
