import { Link } from 'expo-router';
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
import { env } from '../../config/env';
import { ApiError, request } from './api';
import type { Checklist, Report, ReportPage, Session } from './api';

const base = '/api/dmc/ground-reports';
const empty: Checklist = {
  descriptionSufficientlyDetailed: null,
  photoRelevant: null,
  gpsCorrespondsToArea: null,
  reportingTimeReasonable: null,
};
const checks: [keyof Checklist, string][] = [
  ['descriptionSufficientlyDetailed', 'Description is sufficiently detailed'],
  ['photoRelevant', 'Photo is relevant to the reported hazard'],
  ['gpsCorrespondsToArea', 'GPS location corresponds to the reported area'],
  ['reportingTimeReasonable', 'Reporting time is reasonable'],
];
const label = (value: string) => value.replaceAll('_', ' ');
function Action({
  title,
  onPress,
  disabled = false,
  danger = false,
}: {
  title: string;
  onPress: () => void;
  disabled?: boolean;
  danger?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        s.button,
        danger && { backgroundColor: '#B42318' },
        (disabled || pressed) && { opacity: 0.5 },
      ]}
    >
      <Text style={s.buttonText}>{title}</Text>
    </Pressable>
  );
}
export function OfficerScreen({ session, onSignOut }: { session: Session; onSignOut: () => void }) {
  const wide = useWindowDimensions().width >= 900;
  const [report, setReport] = useState<Report | null>(null);
  const [queue, setQueue] = useState<ReportPage>({ items: [], totalPages: 0 });
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('SUBMITTED');
  const [page, setPage] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [checklist, setChecklist] = useState<Checklist>(empty);
  const [comments, setComments] = useState('');
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState<'VERIFIED' | 'REJECTED' | null>(null);
  const [preview, setPreview] = useState<{ path: string; uri: string } | null>(null);
  const token = session.accessToken;
  const terminal = report?.status === 'VERIFIED' || report?.status === 'REJECTED';
  const editing = report?.status === 'UNDER_REVIEW' && !terminal;
  const passed = checks.every(([key]) => checklist[key] === true);
  useEffect(() => {
    let active = true;
    let objectUrl: string | undefined;
    if (Platform.OS === 'web' && report?.photo) {
      const path = report.photo.viewUrl;
      void fetch(`${env.apiBaseUrl}${path}`, { headers: { Authorization: `Bearer ${token}` } })
        .then(async (response) => {
          if (!response.ok) throw new ApiError('Photo could not be loaded.', response.status);
          const blob = await response.blob();
          if (active) {
            objectUrl = URL.createObjectURL(blob);
            setPreview({ path, uri: objectUrl });
          }
        })
        .catch((cause) => {
          if (active) {
            if (cause instanceof ApiError && cause.status === 401) onSignOut();
            else setError('Photo could not be loaded. Reload the report.');
          }
        });
    }
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [report, token, onSignOut]);
  const photoUri = report?.photo
    ? Platform.OS === 'web'
      ? preview?.path === report.photo.viewUrl
        ? preview.uri
        : null
      : `${env.apiBaseUrl}${report.photo.viewUrl}`
    : null;
  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError('');
    setMessage('');
    try {
      await work();
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) onSignOut();
      else
        setError(
          cause instanceof ApiError && cause.status === 409
            ? `${cause.message} Reload the saved report before continuing.`
            : cause instanceof Error
              ? cause.message
              : 'Connection failed. Try again.',
        );
    } finally {
      setBusy(false);
    }
  }
  function open(value: Report) {
    setReport(value);
    setChecklist(value.review?.checklist || empty);
    setComments(value.review?.comments || '');
    setReason('');
    setPending(null);
    setPreview(null);
  }
  async function load(next = 0) {
    const params = new URLSearchParams({ status, q: query, page: String(next), size: '10' });
    const result = await request<ReportPage>(`${base}/review-queue?${params}`, token);
    setQueue(result);
    setPage(next);
    setLoaded(true);
    setReport(null);
  }
  async function reload() {
    if (report) open(await request<Report>(`${base}/${report.id}/review/details`, token));
  }
  async function decide() {
    if (!report || !pending) return;
    if (pending === 'REJECTED' && !reason.trim())
      throw new Error('A rejection reason is required.');
    const saved = await request<Report>(`${base}/${report.id}/decision`, token, 'POST', {
      expectedVersion: report.version,
      decision: pending,
      checklist,
      comments,
      ...(pending === 'REJECTED' ? { rejectionReason: reason } : {}),
    });
    open(saved);
    setMessage(
      saved.status === 'VERIFIED'
        ? 'Verification recorded successfully.'
        : 'Rejection recorded successfully.',
    );
  }
  return (
    <SafeAreaView style={s.root}>
      <View style={[s.shell, wide && { flexDirection: 'row' }]}>
        {wide && (
          <View style={s.sidebar}>
            <Text style={s.brand}>◈ DMC</Text>
            <Text style={s.sidebarSub}>Disaster Reporting Portal</Text>
            <Link href="/" style={s.sidebarLink}>
              Dashboard
            </Link>
            <Text style={s.activeNav}>Ground Reports</Text>
            <View style={{ flex: 1 }} />
            <Text style={s.sidebarSub}>
              {session.user.displayName}
              {'\n'}Duty Officer
            </Text>
          </View>
        )}
        <ScrollView style={{ flex: 1 }} contentContainerStyle={s.content}>
          <View style={s.top}>
            <Link href="/" style={s.link}>
              DMC / Ground Reports
            </Link>
            <Text style={s.muted}>{session.user.displayName} · Duty Officer</Text>
            <Action title="Sign out" disabled={busy} onPress={onSignOut} />
          </View>
          <Text accessibilityRole="header" style={s.title}>
            {report ? 'Ground Hazard Report' : 'Ground Reports Review Queue'}
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
          {!report ? (
            <View style={s.card}>
              <Text style={s.heading}>Find reports to review</Text>
              <TextInput
                accessibilityLabel="Search reports"
                value={query}
                onChangeText={setQuery}
                editable={!busy}
                maxLength={100}
                placeholder="Search reference, reporter, or description"
                style={s.input}
              />
              <View style={s.actions}>
                {['SUBMITTED', 'UNDER_REVIEW', 'VERIFIED', 'REJECTED'].map((value) => (
                  <Pressable
                    key={value}
                    accessibilityRole="button"
                    accessibilityState={{ selected: status === value }}
                    disabled={busy}
                    onPress={() => {
                      setStatus(value);
                      setLoaded(false);
                    }}
                    style={[s.chip, status === value && { backgroundColor: '#005BEA' }]}
                  >
                    <Text style={{ color: status === value ? '#FFFFFF' : '#52647B' }}>
                      {label(value)}
                    </Text>
                  </Pressable>
                ))}
              </View>
              <Action
                title={busy ? 'Loading…' : 'Search / Refresh'}
                disabled={busy}
                onPress={() => void run(() => load())}
              />
              {!loaded ? (
                <Text style={s.muted}>Choose a status and press Search / Refresh.</Text>
              ) : queue.items.length === 0 ? (
                <Text style={s.muted}>No matching reports.</Text>
              ) : (
                queue.items.map((item) => (
                  <View key={item.id} style={s.row}>
                    <View style={{ flex: 1, gap: 8 }}>
                      <Text style={s.heading}>{item.reference}</Text>
                      <Text style={s.muted}>
                        {label(item.hazardType || 'UNKNOWN')} ·{' '}
                        {item.location?.areaLabel || 'Location unavailable'}
                      </Text>
                      <Text style={s.muted}>
                        {item.reporter?.displayName} · {label(item.status)}
                      </Text>
                    </View>
                    <Action
                      title="View Details"
                      disabled={busy}
                      onPress={() =>
                        void run(async () =>
                          open(await request<Report>(`${base}/${item.id}/review/details`, token)),
                        )
                      }
                    />
                  </View>
                ))
              )}
              {loaded && (
                <View style={s.actions}>
                  <Action
                    title="Previous"
                    disabled={busy || page === 0}
                    onPress={() => void run(() => load(page - 1))}
                  />
                  <Text style={s.muted}>Page {page + 1}</Text>
                  <Action
                    title="Next"
                    disabled={busy || page + 1 >= queue.totalPages}
                    onPress={() => void run(() => load(page + 1))}
                  />
                </View>
              )}
            </View>
          ) : (
            <>
              <View style={s.actions}>
                <Text style={s.heading}>{report.reference}</Text>
                <Text style={s.badge}>{label(report.status)}</Text>
                <Action
                  title="Back to Reports"
                  disabled={busy}
                  onPress={() => {
                    setReport(null);
                    setPending(null);
                  }}
                />
                <Action
                  title="Reload saved report"
                  disabled={busy}
                  onPress={() => void run(reload)}
                />
              </View>
              {report.status === 'VERIFIED' && (
                <View style={s.verified}>
                  <Text style={s.verifiedTitle}>✓ VERIFIED</Text>
                  <Text style={s.body}>
                    This report is available as supporting information for official hazard
                    assessment.
                  </Text>
                </View>
              )}
              {report.status === 'REJECTED' && (
                <Text style={s.error}>Rejected: {report.verification?.rejectionReason}</Text>
              )}
              <View style={[s.columns, wide && { flexDirection: 'row' }]}>
                <View style={s.main}>
                  <View style={s.card}>
                    <Text style={s.heading}>Hazard information</Text>
                    <Text style={s.body}>{label(report.hazardType || '')}</Text>
                    <Text style={s.muted}>
                      Reporter: {report.reporter?.displayName} / {report.reporter?.type}
                    </Text>
                    <Text style={s.muted}>
                      Submitted:{' '}
                      {report.submittedAt ? new Date(report.submittedAt).toLocaleString() : '—'}
                    </Text>
                    <Text style={s.body}>{report.description}</Text>
                  </View>
                  <View style={s.card}>
                    <Text style={s.heading}>Photo evidence</Text>
                    {photoUri ? (
                      <Image
                        source={{
                          uri: photoUri,
                          ...(Platform.OS !== 'web'
                            ? { headers: { Authorization: `Bearer ${token}` } }
                            : {}),
                        }}
                        accessibilityLabel="Reported hazard evidence"
                        style={s.photo}
                      />
                    ) : (
                      <Text style={s.muted}>Photo preview unavailable or loading.</Text>
                    )}
                    <Text style={s.muted}>{report.photo?.originalFilename}</Text>
                  </View>
                  <View style={s.card}>
                    <Text style={s.heading}>Reported location</Text>
                    <Text style={s.body}>{report.location?.areaLabel}</Text>
                    <Text style={s.muted}>
                      {report.location?.latitude}, {report.location?.longitude}
                    </Text>
                    {report.location && (
                      <Action
                        title="Open in Map"
                        disabled={busy}
                        onPress={() =>
                          void run(async () => {
                            const point = report.location!;
                            await Linking.openURL(
                              `https://www.openstreetmap.org/?mlat=${point.latitude}&mlon=${point.longitude}#map=16/${point.latitude}/${point.longitude}`,
                            );
                          })
                        }
                      />
                    )}
                  </View>
                  <View style={s.card}>
                    <Text style={s.heading}>Report activity</Text>
                    {report.history.map((event) => (
                      <Text key={event.id} style={s.body}>
                        ✓ {label(event.status)} · {new Date(event.occurredAt).toLocaleString()}
                      </Text>
                    ))}
                  </View>
                </View>
                <View style={s.side}>
                  {terminal ? (
                    <View style={s.card}>
                      <Text style={s.heading}>Verification details</Text>
                      <Text style={s.body}>Decision: {report.verification?.decision}</Text>
                      <Text style={s.body}>Officer: {report.verification?.officerDisplayName}</Text>
                      <Text style={s.muted}>
                        {report.verification?.decidedAt
                          ? new Date(report.verification.decidedAt).toLocaleString()
                          : '—'}
                      </Text>
                      <Text style={s.body}>
                        {report.verification?.comments || 'No comments recorded.'}
                      </Text>
                      <Text style={s.muted}>
                        Verification record: {report.verification?.reference}
                      </Text>
                      {checks.map(([key, title]) => (
                        <Text key={key} style={s.muted}>
                          {report.verification?.checklist[key] === true
                            ? '✓'
                            : report.verification?.checklist[key] === false
                              ? '✕'
                              : '—'}{' '}
                          {title}
                        </Text>
                      ))}
                    </View>
                  ) : (
                    <View style={s.card}>
                      <Text style={s.heading}>Verification decision</Text>
                      {report.status === 'SUBMITTED' ? (
                        <>
                          <Text style={s.muted}>
                            Viewing a report does not start its review. Claim it to begin.
                          </Text>
                          <Action
                            title="Start Review"
                            disabled={busy}
                            onPress={() =>
                              void run(async () =>
                                open(
                                  await request<Report>(
                                    `${base}/${report.id}/review/start`,
                                    token,
                                    'POST',
                                    { expectedVersion: report.version },
                                  ),
                                ),
                              )
                            }
                          />
                        </>
                      ) : (
                        <>
                          <Text style={s.muted}>
                            Assigned reviewer: {report.review?.officerDisplayName}. Only the
                            assigned officer can save or decide.
                          </Text>
                          {checks.map(([key, title]) => (
                            <View key={key} style={{ gap: 8 }}>
                              <Text style={s.body}>{title}</Text>
                              <View style={s.actions}>
                                {([true, false, null] as const).map((value) => (
                                  <Pressable
                                    key={String(value)}
                                    accessibilityRole="button"
                                    accessibilityState={{ selected: checklist[key] === value }}
                                    disabled={busy || !editing || pending !== null}
                                    onPress={() => setChecklist({ ...checklist, [key]: value })}
                                    style={[
                                      s.chip,
                                      checklist[key] === value && { backgroundColor: '#D9EAFD' },
                                    ]}
                                  >
                                    <Text style={s.muted}>
                                      {value === true
                                        ? '✓ Pass'
                                        : value === false
                                          ? '✕ Fail'
                                          : 'Not assessed'}
                                    </Text>
                                  </Pressable>
                                ))}
                              </View>
                            </View>
                          ))}
                          <Text style={s.heading}>Duty Officer comments</Text>
                          <TextInput
                            accessibilityLabel="Duty Officer comments"
                            value={comments}
                            onChangeText={setComments}
                            editable={!busy && editing && !pending}
                            multiline
                            maxLength={2000}
                            style={[s.input, { minHeight: 100 }]}
                          />
                          <Action
                            title="Save Review"
                            disabled={busy || !editing || !!pending}
                            onPress={() =>
                              void run(async () => {
                                open(
                                  await request<Report>(
                                    `${base}/${report.id}/review`,
                                    token,
                                    'PATCH',
                                    { expectedVersion: report.version, checklist, comments },
                                  ),
                                );
                                setMessage('Review saved.');
                              })
                            }
                          />
                          <Text style={s.note}>
                            All four checks must pass to verify. A rejection requires a reason.
                            Unverified reports cannot support official assessment.
                          </Text>
                          {!pending ? (
                            <View style={s.actions}>
                              <Action
                                title="Verify Report"
                                disabled={busy || !editing || !passed}
                                onPress={() => setPending('VERIFIED')}
                              />
                              <Action
                                title="Reject Report"
                                danger
                                disabled={busy || !editing}
                                onPress={() => setPending('REJECTED')}
                              />
                            </View>
                          ) : (
                            <View style={{ gap: 12 }}>
                              <Text style={s.heading}>
                                Confirm {pending === 'VERIFIED' ? 'verification' : 'rejection'}
                              </Text>
                              {pending === 'REJECTED' && (
                                <TextInput
                                  accessibilityLabel="Rejection reason"
                                  placeholder="Explain why this report is rejected"
                                  value={reason}
                                  onChangeText={setReason}
                                  editable={!busy}
                                  multiline
                                  maxLength={2000}
                                  style={[s.input, { minHeight: 100 }]}
                                />
                              )}
                              <Text style={s.muted}>
                                This decision is final and will be recorded with your name and the
                                current time.
                              </Text>
                              <Action
                                title="Confirm Decision"
                                danger={pending === 'REJECTED'}
                                disabled={busy || (pending === 'REJECTED' && !reason.trim())}
                                onPress={() => void run(decide)}
                              />
                              <Action
                                title="Cancel"
                                disabled={busy}
                                onPress={() => setPending(null)}
                              />
                            </View>
                          )}
                        </>
                      )}
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
  sidebar: { width: 240, backgroundColor: '#06234D', padding: 22, gap: 24 },
  brand: { color: '#FFFFFF', fontSize: 28, fontWeight: '700' },
  sidebarSub: { color: '#C5D9EF', fontSize: 14, lineHeight: 24 },
  sidebarLink: { color: '#FFFFFF', fontSize: 16, paddingVertical: 14 },
  activeNav: {
    color: '#FFFFFF',
    fontSize: 16,
    backgroundColor: '#005BEA',
    padding: 16,
    borderRadius: 10,
  },
  content: { padding: 24, gap: 20, maxWidth: 1300, width: '100%', alignSelf: 'center' },
  top: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  link: { color: '#005BEA', fontSize: 14 },
  title: { color: '#09274F', fontSize: 30, fontWeight: '700' },
  card: {
    padding: 20,
    borderWidth: 1,
    borderColor: '#DFE5EF',
    borderRadius: 14,
    backgroundColor: '#FFFFFF',
    gap: 16,
  },
  heading: { fontSize: 18, fontWeight: '700', color: '#09274F' },
  body: { color: '#253B56', fontSize: 15, lineHeight: 24 },
  muted: { color: '#52647B', fontSize: 14, lineHeight: 22 },
  input: {
    borderWidth: 1,
    borderColor: '#CBD5E1',
    borderRadius: 8,
    padding: 14,
    fontSize: 16,
    color: '#253B56',
    minHeight: 48,
  },
  button: {
    padding: 14,
    minHeight: 48,
    borderRadius: 8,
    backgroundColor: '#005BEA',
    justifyContent: 'center',
    alignItems: 'center',
  },
  buttonText: { color: '#FFFFFF', fontSize: 14, fontWeight: '600' },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 10, alignItems: 'center' },
  chip: { padding: 12, minHeight: 44, borderRadius: 8, backgroundColor: '#F1F4F8' },
  row: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 20,
    borderBottomWidth: 1,
    borderBottomColor: '#DFE5EF',
  },
  columns: { gap: 20 },
  main: { flex: 3, gap: 20 },
  side: { flex: 2, gap: 20 },
  photo: { width: '100%', height: 240, borderRadius: 8 },
  badge: {
    padding: 8,
    borderRadius: 8,
    color: '#005BEA',
    backgroundColor: '#EDF4FF',
    fontSize: 13,
  },
  error: {
    backgroundColor: '#FEEEEE',
    color: '#B42318',
    padding: 14,
    borderRadius: 8,
    lineHeight: 22,
  },
  success: {
    backgroundColor: '#EAF8F1',
    color: '#087A4B',
    padding: 14,
    borderRadius: 8,
    lineHeight: 22,
  },
  note: {
    backgroundColor: '#FFF7E8',
    color: '#795A21',
    padding: 14,
    borderRadius: 8,
    lineHeight: 22,
  },
  verified: {
    padding: 22,
    gap: 12,
    borderRadius: 12,
    backgroundColor: '#EAF8F1',
    borderWidth: 1,
    borderColor: '#9CD7B9',
  },
  verifiedTitle: { color: '#008F54', fontWeight: '800', fontSize: 30 },
});
