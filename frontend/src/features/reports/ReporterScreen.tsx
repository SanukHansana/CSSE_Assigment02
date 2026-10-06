import { WarningScreen } from '../warnings/WarningScreen';
import { ReliefScreen } from '../relief/ReliefScreen';
import { OfficerScreen } from './OfficerScreen';
import { Link } from 'expo-router';
import * as ImagePicker from 'expo-image-picker';
import * as Location from 'expo-location';
import { useEffect, useState } from 'react';
import {
  Image,
  Linking,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useWindowDimensions,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { ApiError, request, uploadPhoto } from './api';
import type { Report, ReportPage, Session } from './api';
import { env } from '../../config/env';

const base = '/api/dmc/ground-reports';
const hazards = ['FLOODING', 'RISING_RIVER_LEVEL', 'BLOCKED_ROAD', 'LANDSLIDE_CRACK'];
const label = (value: string) => value.toLowerCase().replaceAll('_', ' ');
function Action({
  title,
  onPress,
  disabled = false,
  secondary = false,
}: {
  title: string;
  onPress: () => void;
  disabled?: boolean;
  secondary?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled}
      accessibilityState={{ disabled }}
      onPress={onPress}
      style={({ pressed }) => [
        s.button,
        secondary && s.secondary,
        (disabled || pressed) && { opacity: 0.5 },
      ]}
    >
      <Text style={[s.buttonText, secondary && s.secondaryText]}>{title}</Text>
    </Pressable>
  );
}
function Field({
  title,
  value,
  onChange,
  multiline = false,
  password = false,
  limit,
  numeric = false,
}: {
  title: string;
  value: string;
  onChange: (value: string) => void;
  multiline?: boolean;
  password?: boolean;
  limit?: number;
  numeric?: boolean;
}) {
  return (
    <View style={s.field}>
      <Text style={s.label}>{title}</Text>
      <TextInput
        accessibilityLabel={title}
        value={value}
        onChangeText={onChange}
        multiline={multiline}
        secureTextEntry={password}
        maxLength={limit}
        autoCapitalize="none"
        keyboardType={numeric ? 'numbers-and-punctuation' : 'default'}
        style={[s.input, multiline && s.multiline]}
      />
    </View>
  );
}
export function ReporterScreen({
  workspace = 'reports',
}: {
  workspace?: 'reports' | 'relief' | 'warnings';
}) {
  const wide = useWindowDimensions().width >= 900;
  const [session, setSession] = useState<Session | null>(null);
  const [register, setRegister] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [tab, setTab] = useState<'form' | 'mine'>('form');
  const [report, setReport] = useState<Report | null>(null);
  const [hazard, setHazard] = useState('FLOODING');
  const [description, setDescription] = useState('');
  const [latitude, setLatitude] = useState('');
  const [longitude, setLongitude] = useState('');
  const [area, setArea] = useState('');
  const [photo, setPhoto] = useState<ImagePicker.ImagePickerAsset | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [reports, setReports] = useState<ReportPage>({ items: [], totalPages: 0 });
  const [confirm, setConfirm] = useState(false);
  const reporterRole = session?.user.roles.some((role) =>
    ['CITIZEN', 'COMMUNITY_VOLUNTEER'].includes(role),
  );
  const readonly = report !== null && report.status !== 'DRAFT';
  const token = session?.accessToken;
  const savedPhotoUri = report?.photo
    ? Platform.OS === 'web'
      ? preview
      : `${env.apiBaseUrl}${report.photo.viewUrl}`
    : null;

  // Evidence is private; web images must be fetched with the bearer token first.
  useEffect(() => {
    let active = true;
    let objectUrl: string | undefined;
    if (Platform.OS === 'web' && token && report?.photo) {
      const url = `${env.apiBaseUrl}${report.photo.viewUrl}`;

      void fetch(url, { headers: { Authorization: `Bearer ${token}` } })
        .then(async (response) => {
          if (!response.ok)
            throw new Error('Photo unavailable. Reload the report or sign in again.');
          const blob = await response.blob();
          if (active) {
            objectUrl = URL.createObjectURL(blob);
            setPreview(objectUrl);
          }
        })
        .catch(() => {
          if (active) setError('Saved photo could not be loaded.');
        });
    }
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [token, report]);

  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError('');
    setMessage('');
    try {
      await work();
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) {
        setSession(null);
        setReport(null);
        setPhoto(null);
        setPreview(null);
        setReports({ items: [], totalPages: 0 });
        setError('Your session expired. Sign in again.');
      } else
        setError(
          cause instanceof Error
            ? cause.message
            : 'Unable to connect. Check your connection and try again.',
        );
    } finally {
      setBusy(false);
    }
  }
  function open(value: Report) {
    setPreview(null);
    setReport(value);
    setHazard(value.hazardType || 'FLOODING');
    setDescription(value.description || '');
    setLatitude(value.location ? String(value.location.latitude) : '');
    setLongitude(value.location ? String(value.location.longitude) : '');
    setArea(value.location?.areaLabel || '');
    setPhoto(null);
    setConfirm(false);
    setTab('form');
  }
  function newReport() {
    setReport(null);
    setHazard('FLOODING');
    setDescription('');
    setLatitude('');
    setLongitude('');
    setArea('');
    setPhoto(null);
    setPreview(null);
    setConfirm(false);
    setError('');
    setMessage('');
    setTab('form');
  }
  async function authenticate() {
    if (register) {
      await request('/api/dmc/auth/register', undefined, 'POST', {
        email,
        displayName: name,
        password,
      });
      setRegister(false);
      setPassword('');
      setMessage('Account created. Sign in with your email and password.');
      return;
    }
    const result = await request<Session>('/api/dmc/auth/login', undefined, 'POST', {
      email,
      password,
    });
    setSession(result);
    setPassword('');
    newReport();
  }
  function location() {
    if (!latitude.trim() && !longitude.trim()) return null;
    const lat = Number(latitude);
    const lon = Number(longitude);
    if (
      !latitude.trim() ||
      !longitude.trim() ||
      !Number.isFinite(lat) ||
      !Number.isFinite(lon) ||
      lat < -90 ||
      lat > 90 ||
      lon < -180 ||
      lon > 180
    )
      throw new Error('Enter valid latitude (-90 to 90) and longitude (-180 to 180).');
    return { latitude: lat, longitude: lon, areaLabel: area };
  }
  async function save() {
    const body = { hazardType: hazard, description, location: location() };
    let saved = report
      ? await request<Report>(`${base}/${report.id}/draft`, token, 'PATCH', {
          ...body,
          expectedVersion: report.version,
        })
      : await request<Report>(`${base}/drafts`, token, 'POST', {
          ...body,
          source: Platform.OS === 'web' ? 'WEB_PORTAL' : 'MOBILE_APP',
        });
    setReport(saved); // Keep the acknowledged draft even if the following upload fails.
    if (photo && token) {
      saved = await uploadPhoto(saved, photo, token);
      setReport(saved);
      setPhoto(null);
    }
    return saved;
  }
  async function pick() {
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      quality: 1,
    });
    if (result.canceled) return;
    const selected = result.assets[0];
    if (selected.fileSize && selected.fileSize > 5 * 1024 * 1024)
      throw new Error('Choose a photo smaller than 5 MB.');
    if (selected.mimeType && !['image/jpeg', 'image/png'].includes(selected.mimeType))
      throw new Error('Choose a JPEG or PNG photo.');
    setPhoto(selected);
    setConfirm(false);
  }
  async function gps() {
    const permission = await Location.requestForegroundPermissionsAsync();
    if (!permission.granted)
      throw new Error(
        'Location access was denied. You can enter coordinates manually. Your previous location is retained.',
      );
    const result = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
    setLatitude(String(result.coords.latitude));
    setLongitude(String(result.coords.longitude));
    setConfirm(false);
    setMessage('Current location captured. Check that it matches the hazard location.');
  }
  async function load(nextPage = page) {
    const result = await request<ReportPage>(`${base}/mine?page=${nextPage}&size=10`, token);
    setReports(result);
    setPage(nextPage);
    setTab('mine');
  }
  async function reviewSubmit() {
    if (!description.trim() || !location() || !(photo || report?.photo))
      throw new Error('Add a description, location, and photo before submitting.');
    await save();
    setConfirm(true);
    setMessage('Draft saved. Review the details below, then confirm submission.');
  }

  if (session && workspace === 'warnings')
    return (
      <WarningScreen
        session={session}
        onSignOut={() => {
          setSession(null);
          newReport();
        }}
      />
    );
  if (session && workspace === 'relief') {
    return (
      <ReliefScreen
        session={session}
        onSignOut={() => {
          setSession(null);
          newReport();
        }}
      />
    );
  }
  if (session?.user.roles.includes('DUTY_OFFICER')) {
    return (
      <OfficerScreen
        session={session}
        onSignOut={() => {
          setSession(null);
          newReport();
          setReports({ items: [], totalPages: 0 });
        }}
      />
    );
  }
  return (
    <SafeAreaView style={s.root}>
      <ScrollView contentContainerStyle={s.scroll} keyboardShouldPersistTaps="handled">
        <View style={s.header}>
          <Link href="/" style={s.brand}>
            ◈ DMC <Text style={s.brandSub}>Disaster Reporting Portal</Text>
          </Link>
          <Text style={s.headerText}>
            {session
              ? session.user.displayName
              : workspace === 'relief'
                ? 'Relief coordination'
                : 'Ground reporting'}
          </Text>
        </View>
        <View style={s.content}>
          <View style={s.navigation}>
            <Link href="/" style={s.link}>
              Home
            </Link>
            <Text style={s.muted}>
              /{' '}
              {workspace === 'warnings'
                ? 'Disaster Warnings'
                : workspace === 'relief'
                  ? 'Relief Resources & Shelters'
                  : 'Ground Hazard Reports'}
            </Text>
            {session && (
              <Action
                title="Sign out"
                secondary
                disabled={busy}
                onPress={() => {
                  setSession(null);
                  newReport();
                  setReports({ items: [], totalPages: 0 });
                }}
              />
            )}
          </View>
          <Text accessibilityRole="header" style={s.title}>
            {!session
              ? register
                ? 'Create your account'
                : 'Welcome back'
              : tab === 'mine'
                ? 'My Reports'
                : readonly
                  ? 'Ground Hazard Report Details'
                  : 'Submit Ground Hazard Report'}
          </Text>
          <Text style={s.muted}>
            {session
              ? 'Share observations and evidence for Duty Officer review.'
              : 'Sign in to access your DMC workspace.'}
          </Text>
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
          {!session ? (
            <View style={[s.card, s.auth]}>
              {register && <Field title="Full name" value={name} onChange={setName} limit={100} />}
              <Field title="Email" value={email} onChange={setEmail} />
              <Field title="Password" value={password} onChange={setPassword} password />
              {register && (
                <Text style={s.muted}>
                  Use a password with 12–72 characters. Registration creates a citizen account.
                </Text>
              )}
              <Action
                title={busy ? 'Please wait…' : register ? 'Register' : 'Sign in'}
                disabled={busy || !email || !password || (register && !name.trim())}
                onPress={() => void run(authenticate)}
              />
              <Action
                title={register ? 'Already registered? Sign in' : 'New here? Create an account'}
                secondary
                disabled={busy}
                onPress={() => {
                  setRegister(!register);
                  setError('');
                  setMessage('');
                }}
              />
            </View>
          ) : !reporterRole ? (
            <View style={s.card}>
              <Text style={s.heading}>Officer account</Text>
              <Text style={s.muted}>
                Citizen and volunteer accounts submit reports. The Duty Officer review workspace
                will be added in the second commit.
              </Text>
            </View>
          ) : (
            <>
              <View style={s.actions}>
                <Action title="New Report" disabled={busy} onPress={newReport} />
                <Action
                  title="My Reports"
                  secondary
                  disabled={busy}
                  onPress={() => void run(() => load(0))}
                />
              </View>
              {tab === 'mine' ? (
                <View style={s.card}>
                  <Text style={s.heading}>Your ground reports</Text>
                  {reports.items.length === 0 && (
                    <Text style={s.muted}>No reports yet. Create your first report.</Text>
                  )}
                  {reports.items.map((item) => (
                    <Pressable
                      key={item.id}
                      accessibilityRole="button"
                      disabled={busy}
                      onPress={() =>
                        void run(async () =>
                          open(await request<Report>(`${base}/${item.id}`, token)),
                        )
                      }
                      style={s.reportRow}
                    >
                      <View style={{ flex: 1, gap: 8 }}>
                        <Text style={s.label}>{item.reference}</Text>
                        <Text style={s.muted}>
                          {item.hazardType ? label(item.hazardType) : 'Incomplete draft'} ·{' '}
                          {item.location?.areaLabel || 'Location not set'}
                        </Text>
                      </View>
                      <Text style={s.status}>{label(item.status)}</Text>
                    </Pressable>
                  ))}
                  <View style={s.actions}>
                    <Action
                      title="Previous"
                      secondary
                      disabled={busy || page === 0}
                      onPress={() => void run(() => load(page - 1))}
                    />
                    <Text style={s.muted}>Page {page + 1}</Text>
                    <Action
                      title="Next"
                      secondary
                      disabled={busy || page + 1 >= reports.totalPages}
                      onPress={() => void run(() => load(page + 1))}
                    />
                  </View>
                </View>
              ) : (
                <>
                  {report && (
                    <View style={s.actions}>
                      <Text style={s.label}>{report.reference}</Text>
                      <Text style={s.status}>{label(report.status)}</Text>
                      <Action
                        title="Reload saved report"
                        secondary
                        disabled={busy}
                        onPress={() =>
                          void run(async () =>
                            open(await request<Report>(`${base}/${report.id}`, token)),
                          )
                        }
                      />
                    </View>
                  )}
                  <View style={[s.columns, wide && { flexDirection: 'row' }]}>
                    <View style={s.mainColumn}>
                      <View style={s.card}>
                        <Text style={s.heading}>Reported condition</Text>
                        <Text style={s.label}>Hazard type *</Text>
                        <View style={s.actions}>
                          {hazards.map((value) => (
                            <Pressable
                              key={value}
                              accessibilityRole="button"
                              accessibilityState={{
                                selected: hazard === value,
                                disabled: readonly || busy || confirm,
                              }}
                              disabled={readonly || busy || confirm}
                              onPress={() => setHazard(value)}
                              style={[s.chip, hazard === value && s.selected]}
                            >
                              <Text style={hazard === value ? s.selectedText : s.muted}>
                                {label(value)}
                              </Text>
                            </Pressable>
                          ))}
                        </View>
                        {readonly || confirm ? (
                          <Text style={s.body}>{description}</Text>
                        ) : (
                          <Field
                            title="Describe what you observed *"
                            value={description}
                            onChange={setDescription}
                            multiline
                            limit={500}
                          />
                        )}
                        <Text style={s.counter}>{description.length}/500</Text>
                      </View>
                      <View style={s.card}>
                        <Text style={s.heading}>Photo evidence *</Text>
                        {(photo?.uri || savedPhotoUri) && (
                          <Image
                            accessibilityLabel="Hazard photo evidence"
                            source={{
                              uri: photo?.uri || savedPhotoUri!,
                              ...(Platform.OS !== 'web' && !photo
                                ? { headers: { Authorization: `Bearer ${token}` } }
                                : {}),
                            }}
                            style={s.photo}
                            resizeMode="cover"
                          />
                        )}
                        <Text style={s.muted}>
                          {photo?.fileName ||
                            report?.photo?.originalFilename ||
                            'Choose a clear JPEG or PNG photo, up to 5 MB.'}
                        </Text>
                        {!readonly && !confirm && (
                          <View style={s.actions}>
                            <Action
                              title={photo || report?.photo ? 'Replace photo' : 'Browse Photos'}
                              secondary
                              disabled={busy}
                              onPress={() => void run(pick)}
                            />
                            {(photo || report?.photo) && (
                              <Action
                                title="Remove"
                                secondary
                                disabled={busy}
                                onPress={() =>
                                  void run(async () => {
                                    if (report?.photo)
                                      setReport(
                                        await request<Report>(
                                          `${base}/${report.id}/photo`,
                                          token,
                                          'DELETE',
                                          undefined,
                                          { 'If-Match': `"${report.version}"` },
                                        ),
                                      );
                                    setPhoto(null);
                                  })
                                }
                              />
                            )}
                          </View>
                        )}
                      </View>
                    </View>
                    <View style={s.sideColumn}>
                      <View style={s.card}>
                        <Text style={s.heading}>Hazard location *</Text>
                        {readonly || confirm ? (
                          <Text style={s.body}>
                            {latitude}, {longitude}
                            {'\n'}
                            {area}
                          </Text>
                        ) : (
                          <>
                            <Field
                              title="Latitude"
                              value={latitude}
                              onChange={setLatitude}
                              numeric
                            />
                            <Field
                              title="Longitude"
                              value={longitude}
                              onChange={setLongitude}
                              numeric
                            />
                            <Field
                              title="Area / district"
                              value={area}
                              onChange={setArea}
                              limit={200}
                            />
                            <Action
                              title="Use Current Location"
                              secondary
                              disabled={busy}
                              onPress={() => void run(gps)}
                            />
                          </>
                        )}
                        <Text style={s.muted}>
                          Use the hazard location. If GPS fails, the previous coordinates are kept.
                        </Text>
                        {latitude && longitude && (
                          <Action
                            title="Open location in map"
                            secondary
                            disabled={busy}
                            onPress={() =>
                              void run(async () => {
                                const point = location();
                                if (point)
                                  await Linking.openURL(
                                    `https://www.openstreetmap.org/?mlat=${point.latitude}&mlon=${point.longitude}#map=16/${point.latitude}/${point.longitude}`,
                                  );
                              })
                            }
                          />
                        )}
                      </View>
                      <View style={s.card}>
                        <Text style={s.heading}>Reporting information</Text>
                        <Text style={s.label}>{session.user.displayName}</Text>
                        <Text style={s.muted}>
                          {session.user.roles.includes('COMMUNITY_VOLUNTEER')
                            ? 'Community volunteer'
                            : 'Citizen'}
                        </Text>
                        <Text style={s.note}>
                          Reports must be verified by a Duty Officer before they can support an
                          official hazard assessment.
                        </Text>
                        <Text style={s.muted}>
                          An internet connection is required to save. Unsaved changes remain on this
                          screen only.
                        </Text>
                      </View>
                    </View>
                  </View>
                  {readonly ? (
                    <View style={s.card}>
                      <Text style={s.heading}>Report activity</Text>
                      {report.history.map((event) => (
                        <Text key={event.id} style={s.body}>
                          {label(event.status)} · {new Date(event.occurredAt).toLocaleString()}
                        </Text>
                      ))}
                    </View>
                  ) : (
                    <View style={s.actions}>
                      {confirm ? (
                        <>
                          <Action
                            title="Back to editing"
                            secondary
                            disabled={busy}
                            onPress={() => {
                              setConfirm(false);
                              setMessage('');
                            }}
                          />
                          <Action
                            title="Confirm & Submit Report"
                            disabled={busy}
                            onPress={() =>
                              void run(async () => {
                                if (!report) return;
                                const saved = await request<Report>(
                                  `${base}/${report.id}/submit`,
                                  token,
                                  'POST',
                                  { expectedVersion: report.version },
                                );
                                open(saved);
                                setMessage(
                                  'Report submitted successfully for Duty Officer review.',
                                );
                              })
                            }
                          />
                        </>
                      ) : (
                        <>
                          <Action
                            title={busy ? 'Please wait…' : 'Save as Draft'}
                            secondary
                            disabled={busy}
                            onPress={() =>
                              void run(async () => {
                                await save();
                                setMessage('Draft saved successfully.');
                              })
                            }
                          />
                          <Action
                            title="Review & Submit Report"
                            disabled={busy}
                            onPress={() => void run(reviewSubmit)}
                          />
                        </>
                      )}
                    </View>
                  )}
                </>
              )}
            </>
          )}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}
const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#F5F7FB' },
  scroll: { flexGrow: 1 },
  header: {
    backgroundColor: '#06234D',
    padding: 24,
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 16,
  },
  brand: { color: '#FFFFFF', fontSize: 24, fontWeight: '700' },
  brandSub: { fontSize: 13 },
  headerText: { color: '#D4E3F5', fontSize: 15 },
  content: { padding: 24, gap: 20, width: '100%', maxWidth: 1250, alignSelf: 'center' },
  navigation: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 14 },
  title: { fontSize: 30, fontWeight: '700', color: '#09274F' },
  link: { color: '#005BEA', fontSize: 15 },
  muted: { fontSize: 14, lineHeight: 22, color: '#52647B' },
  body: { fontSize: 16, lineHeight: 25, color: '#253B56' },
  card: {
    backgroundColor: '#FFFFFF',
    borderWidth: 1,
    borderColor: '#DFE5EF',
    borderRadius: 14,
    padding: 22,
    gap: 16,
  },
  auth: { width: '100%', maxWidth: 480, alignSelf: 'center' },
  heading: { fontSize: 19, fontWeight: '700', color: '#09274F' },
  label: { fontSize: 14, fontWeight: '600', color: '#253B56' },
  field: { gap: 8 },
  input: {
    borderWidth: 1,
    borderColor: '#CBD5E1',
    borderRadius: 8,
    padding: 13,
    minHeight: 48,
    fontSize: 16,
    color: '#172338',
    backgroundColor: '#FFFFFF',
  },
  multiline: { minHeight: 135, textAlignVertical: 'top' },
  button: {
    backgroundColor: '#005BEA',
    padding: 14,
    borderRadius: 8,
    minHeight: 48,
    justifyContent: 'center',
    alignItems: 'center',
  },
  buttonText: { color: '#FFFFFF', fontWeight: '600', fontSize: 15 },
  secondary: { backgroundColor: '#FFFFFF', borderWidth: 1, borderColor: '#CBD5E1' },
  secondaryText: { color: '#005BEA' },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 10, alignItems: 'center' },
  columns: { gap: 20 },
  mainColumn: { flex: 2, gap: 20 },
  sideColumn: { flex: 1, gap: 20 },
  chip: {
    padding: 10,
    borderRadius: 8,
    backgroundColor: '#F1F4F8',
    minHeight: 44,
    justifyContent: 'center',
  },
  selected: { backgroundColor: '#005BEA' },
  selectedText: { color: '#FFFFFF', fontSize: 14 },
  counter: { textAlign: 'right', color: '#64748B', fontSize: 12 },
  photo: { width: '100%', height: 220, borderRadius: 8 },
  note: {
    backgroundColor: '#FFF7E8',
    padding: 14,
    borderRadius: 8,
    color: '#795A21',
    fontSize: 14,
    lineHeight: 22,
  },
  error: {
    color: '#B42318',
    backgroundColor: '#FEEEEE',
    padding: 14,
    borderRadius: 8,
    lineHeight: 22,
  },
  success: {
    color: '#087A4B',
    backgroundColor: '#EAF8F1',
    padding: 14,
    borderRadius: 8,
    lineHeight: 22,
  },
  reportRow: {
    flexDirection: 'row',
    gap: 12,
    paddingVertical: 18,
    borderBottomWidth: 1,
    borderBottomColor: '#E5EAF1',
    alignItems: 'center',
  },
  status: {
    color: '#005BEA',
    backgroundColor: '#EDF4FF',
    padding: 8,
    borderRadius: 8,
    fontSize: 12,
    fontWeight: '700',
  },
});
