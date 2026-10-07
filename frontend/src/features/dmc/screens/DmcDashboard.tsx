import { Link } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Screen } from '../../../components/layout/Screen';
import { Action } from '../../../components/ui/Action';
import { useAuth } from '../../../application/AuthContext';
import { request } from '../../reports/api';
import type { Report } from '../../reports/api';
export function DmcDashboard() {
  const { session, setSession } = useAuth();
  const [reports, setReports] = useState<Report[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const cards: ['/relief' | '/warnings' | '/rescue', string, string][] = [
    ['/relief', 'Relief resources & shelters', 'Allocate supplies and manage shelter capacity.'],
    ['/warnings', 'Disaster warnings', 'Compose warnings and track simulated delivery.'],
    ['/rescue', 'Rescue operations', 'Assign teams and coordinate incident progress.'],
  ];
  return (
    <Screen>
      <Text style={s.eyebrow}>OPERATIONS WORKSPACE</Text>
      <Text style={s.title}>Welcome, {session?.user.displayName}</Text>
      <Text style={s.body}>
        Coordinate your response from one workspace. Your DMC Officer account has access to the
        modules below.
      </Text>
      <View style={s.grid}>
        {cards.map(([href, title, body]) => (
          <View key={href} style={s.card}>
            <Text style={s.heading}>{title}</Text>
            <Text style={s.body}>{body}</Text>
            <Link href={href} style={s.link}>
              Open workspace →
            </Link>
          </View>
        ))}
      </View>
      <View style={s.card}>
        <Text style={s.heading}>Verified ground evidence</Text>
        <Text style={s.body}>
          Use verified reports as supporting information. Duty Officers handle verification; DMC
          Officers coordinate the response.
        </Text>
        <Action
          title="Load Verified Reports"
          disabled={busy}
          onPress={() => {
            setBusy(true);
            setError('');
            void request<Report[]>(
              '/api/dmc/ground-reports/verified-evidence?page=0&size=20',
              session?.accessToken,
            )
              .then((data) => {
                setReports(data);
                setLoaded(true);
              })
              .catch((cause) => {
                if (cause.status === 401) setSession(null);
                else setError(cause.message || 'Unable to load evidence.');
              })
              .finally(() => setBusy(false));
          }}
        />
        {error ? <Text style={s.error}>{error}</Text> : null}
        {loaded && reports.length === 0 ? (
          <Text style={s.body}>No verified reports available yet.</Text>
        ) : null}
        {reports.map((report) => (
          <View key={report.id} style={s.evidence}>
            <Text style={s.heading}>{report.reference}</Text>
            <Text style={s.body}>{report.description}</Text>
            <Text style={s.body}>{report.location?.areaLabel}</Text>
          </View>
        ))}
      </View>
    </Screen>
  );
}
const s = StyleSheet.create({
  eyebrow: { fontSize: 12, fontWeight: '700', letterSpacing: 2, color: '#087F70' },
  title: { fontSize: 32, fontWeight: '800', color: '#142D45' },
  body: { color: '#587083', fontSize: 15, lineHeight: 25 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: 18 },
  card: {
    flexGrow: 1,
    minWidth: 240,
    backgroundColor: '#FFFFFF',
    borderWidth: 1,
    borderColor: '#DFE7ED',
    borderRadius: 18,
    padding: 24,
    gap: 16,
  },
  heading: { fontSize: 20, fontWeight: '700', color: '#142D45' },
  link: { color: '#087F70', fontWeight: '700', paddingVertical: 12 },
  error: { color: '#B54747' },
  evidence: { borderTopWidth: 1, borderTopColor: '#DFE7ED', paddingTop: 16, gap: 8 },
});
