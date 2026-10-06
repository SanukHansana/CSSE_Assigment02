import { useState } from 'react';
import { Linking, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { request } from '../reports/api';
import type { Incident, Team } from './RescueScreen';
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
      style={({ pressed }) => [s.button, (disabled || pressed) && { opacity: 0.5 }]}
    >
      <Text style={s.buttonText}>{title}</Text>
    </Pressable>
  );
}
export function RescueOperations({
  incident,
  token,
  busy,
  onUpdated,
  run,
}: {
  incident: Incident;
  token: string;
  busy: boolean;
  onUpdated: (incident: Incident) => void;
  run: (work: () => Promise<void>) => Promise<void>;
}) {
  const [teams, setTeams] = useState<Team[]>([]);
  const [team, setTeam] = useState<Team | null>(null);
  const [teamPage, setTeamPage] = useState(0);
  const [teamPages, setTeamPages] = useState(0);
  const [note, setNote] = useState('');
  const [latitude, setLatitude] = useState('');
  const [longitude, setLongitude] = useState('');
  const [location, setLocation] = useState('');
  const [operation, setOperation] = useState<{
    id: string;
    signature: string;
    incidentId: string;
  } | null>(null);
  const assignments = incident.assignments || [];
  const active = assignments.filter((item) => !['COMPLETED', 'CANCELLED'].includes(item.status));
  const completed = assignments.filter((item) => item.status === 'COMPLETED');
  async function loadTeams(next = 0) {
    const result = await request<{ items: Team[]; totalPages: number }>(
      `/api/dmc/rescue/teams?page=${next}&size=10`,
      token,
    );
    setTeams(result.items);
    setTeamPage(next);
    setTeamPages(result.totalPages);
  }
  async function perform(
    action: 'assign' | 'progress' | 'resolve' | 'close',
    assignmentId?: string,
    status?: string,
  ) {
    if (!note.trim()) throw new Error('Enter a coordinator note or completion reason.');
    if (action === 'assign' && !team) throw new Error('Select an available team.');
    let body: Record<string, unknown> = { expectedVersion: incident.version, note: note.trim() };
    let path = `/api/dmc/rescue/incidents/${incident.id}`;
    if (action === 'assign') {
      path += '/assignments';
      body = { ...body, teamId: team!.id, teamVersion: team!.version };
    }
    if (action === 'progress') {
      path += `/assignments/${assignmentId}/progress`;
      body.status = status;
      if (latitude.trim() || longitude.trim()) {
        const lat = Number(latitude),
          lon = Number(longitude);
        if (
          !latitude.trim() ||
          !longitude.trim() ||
          !Number.isFinite(lat) ||
          lat < -90 ||
          lat > 90 ||
          !Number.isFinite(lon) ||
          lon < -180 ||
          lon > 180
        )
          throw new Error('Supply both valid coordinates or leave both blank.');
        body.latitude = lat;
        body.longitude = lon;
      }
      if (location.trim()) body.location = location.trim();
    }
    if (action === 'resolve' || action === 'close') {
      path += '/status';
      body.status = action === 'resolve' ? 'RESOLVED' : 'CLOSED';
    }
    const signature = JSON.stringify([
      path,
      { ...body, expectedVersion: undefined, teamVersion: undefined },
    ]);
    if (operation && operation.incidentId === incident.id && operation.signature !== signature)
      throw new Error(
        'Retry the same input, or use Acknowledge / Reset after inspecting history for the pending action.',
      );
    const requestId =
      operation && operation.incidentId === incident.id
        ? operation.id
        : `${Date.now()}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
    setOperation({ id: requestId, signature, incidentId: incident.id });
    const saved = await request<Incident>(path, token, 'POST', { ...body, requestId });
    onUpdated(saved);
    setOperation(null);
    setNote('');
    setTeam(null);
  }
  return (
    <View style={s.panel}>
      <Text style={s.heading}>Dispatch & Operation Monitoring</Text>
      <Text style={s.muted}>
        {incident.status} · {active.length} active assignments · {completed.length} completed
      </Text>
      <Text style={s.note}>
        Stored, manually confirmed updates only. No live GPS, route estimates, or real dispatch
        messages.
      </Text>
      <TextInput
        accessibilityLabel="Coordinator operation note"
        placeholder="Coordinator note / completion reason (required)"
        value={note}
        onChangeText={setNote}
        editable={!busy}
        maxLength={500}
        multiline
        style={s.input}
      />
      {incident.status === 'OPEN' && (
        <>
          <Action
            title="Load Rescue Teams"
            disabled={busy}
            onPress={() => void run(() => loadTeams())}
          />
          {teams.map((item) => (
            <View key={item.id} style={s.team}>
              <Text style={s.body}>
                {item.name} · {item.specialization.replaceAll('_', ' ')} · {item.status}
              </Text>
              <Text style={s.muted}>
                {item.location} · last confirmed {new Date(item.updatedAt).toLocaleString()}
              </Text>
              <View style={s.row}>
                <Action
                  title={team?.id === item.id ? 'Selected' : 'Select'}
                  disabled={busy || item.status !== 'AVAILABLE' || !!item.activeIncidentId}
                  onPress={() => setTeam(item)}
                />
                <Action
                  title="Map"
                  disabled={busy}
                  onPress={() =>
                    void run(async () => {
                      await Linking.openURL(
                        `https://www.openstreetmap.org/?mlat=${item.latitude}&mlon=${item.longitude}#map=16/${item.latitude}/${item.longitude}`,
                      );
                    })
                  }
                />
              </View>
            </View>
          ))}
          {teamPages > 1 && (
            <View style={s.row}>
              <Action
                title="Previous Teams"
                disabled={busy || teamPage === 0}
                onPress={() => void run(() => loadTeams(teamPage - 1))}
              />
              <Action
                title="More Teams"
                disabled={busy || teamPage + 1 >= teamPages}
                onPress={() => void run(() => loadTeams(teamPage + 1))}
              />
            </View>
          )}
          <Action
            title={team ? `Assign ${team.name}` : 'Assign Selected Team'}
            disabled={busy || !team}
            onPress={() => void run(() => perform('assign'))}
          />
        </>
      )}
      <Text style={s.heading}>Rescue assignments</Text>
      {assignments.length === 0 && <Text style={s.muted}>No assignments recorded.</Text>}
      {assignments.map((item) => (
        <View key={item.id} style={s.team}>
          <Text style={s.heading}>
            {item.teamName} · {item.status}
          </Text>
          <Text style={s.body}>{item.note}</Text>
          <Text style={s.muted}>
            {item.officer} · {new Date(item.updatedAt).toLocaleString()}
          </Text>
          {incident.status === 'OPEN' && !['COMPLETED', 'CANCELLED'].includes(item.status) && (
            <View style={s.row}>
              {[
                { from: 'ASSIGNED', to: 'EN_ROUTE', label: 'Mark En Route' },
                { from: 'EN_ROUTE', to: 'ON_SITE', label: 'Mark On Site' },
                { from: 'ON_SITE', to: 'COMPLETED', label: 'Complete Assignment' },
              ]
                .filter((value) => value.from === item.status)
                .map((value) => (
                  <Action
                    key={value.to}
                    title={value.label}
                    disabled={busy}
                    onPress={() => void run(() => perform('progress', item.id, value.to))}
                  />
                ))}
              <Action
                title="Cancel Assignment"
                disabled={busy}
                onPress={() => void run(() => perform('progress', item.id, 'CANCELLED'))}
              />
            </View>
          )}
        </View>
      ))}
      {active.length > 0 && incident.status === 'OPEN' && (
        <>
          <Text style={s.muted}>
            Optional last-confirmed team location for the next progress update:
          </Text>
          <TextInput
            accessibilityLabel="Team location address"
            placeholder="Team location"
            value={location}
            onChangeText={setLocation}
            editable={!busy}
            maxLength={200}
            style={s.input}
          />
          <TextInput
            accessibilityLabel="Team latitude"
            placeholder="Latitude"
            value={latitude}
            onChangeText={setLatitude}
            editable={!busy}
            keyboardType="numbers-and-punctuation"
            style={s.input}
          />
          <TextInput
            accessibilityLabel="Team longitude"
            placeholder="Longitude"
            value={longitude}
            onChangeText={setLongitude}
            editable={!busy}
            keyboardType="numbers-and-punctuation"
            style={s.input}
          />
        </>
      )}
      {incident.status === 'OPEN' && (
        <>
          <Text style={s.note}>
            Resolve only after at least one rescue assignment is completed and no active assignments
            remain.
          </Text>
          <Action
            title="Mark Incident as Resolved"
            disabled={busy || active.length > 0 || completed.length === 0}
            onPress={() => void run(() => perform('resolve'))}
          />
        </>
      )}
      {incident.status === 'RESOLVED' && (
        <Action
          title="Close Incident"
          disabled={busy}
          onPress={() => void run(() => perform('close'))}
        />
      )}{' '}
      {incident.status === 'CLOSED' && (
        <Text style={s.success}>✓ Incident closed. All required rescue operations have ended.</Text>
      )}
      {operation && operation.incidentId === incident.id && (
        <>
          <Text style={s.note}>
            Pending request {operation.id}. Retry the same values after a connection failure. Reload
            and inspect incident history before resetting.
          </Text>
          <Action
            title="Acknowledge History / Reset Pending Action"
            disabled={busy}
            onPress={() => setOperation(null)}
          />
        </>
      )}
    </View>
  );
}
const s = StyleSheet.create({
  panel: { gap: 16, borderTopWidth: 1, borderTopColor: '#DFE5EF', paddingTop: 20 },
  heading: { fontSize: 18, fontWeight: '700', color: '#09274F' },
  body: { fontSize: 15, color: '#253B56', lineHeight: 24 },
  muted: { fontSize: 14, color: '#52647B', lineHeight: 22 },
  note: {
    backgroundColor: '#FFF7E8',
    color: '#795A21',
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
  button: {
    backgroundColor: '#005BEA',
    padding: 14,
    minHeight: 48,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonText: { color: '#FFFFFF', fontSize: 14, fontWeight: '600' },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  team: { gap: 12, paddingVertical: 16, borderBottomWidth: 1, borderBottomColor: '#DFE5EF' },
  input: {
    borderWidth: 1,
    borderColor: '#CBD5E1',
    borderRadius: 8,
    padding: 13,
    minHeight: 48,
    color: '#253B56',
    fontSize: 16,
  },
});
