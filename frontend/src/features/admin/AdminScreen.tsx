import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Action } from '../../components/ui/Action';
import { ApiError, request } from '../reports/api';
import type { Account, Session } from '../reports/api';
export function AdminScreen({ session, onSignOut }: { session: Session; onSignOut: () => void }) {
  const [items, setItems] = useState<Account[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [status, setStatus] = useState('PENDING');
  const [page, setPage] = useState(0);
  const [pages, setPages] = useState(0);
  const [total, setTotal] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError('');
    setMessage('');
    try {
      await work();
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) onSignOut();
      else setError(cause instanceof Error ? cause.message : 'Request failed.');
    } finally {
      setBusy(false);
    }
  }
  async function load(next = 0) {
    const result = await request<{ items: Account[]; totalPages: number; totalElements: number }>(
      `/api/dmc/admin/applications?status=${status}&page=${next}&size=20`,
      session.accessToken,
    );
    setItems(result.items);
    setPages(result.totalPages);
    setTotal(result.totalElements);
    setPage(next);
    setLoaded(true);
    setSelected(null);
  }
  async function decide(item: Account, approved: boolean) {
    if (!item.id || !note.trim()) throw new Error('Enter a verification note or rejection reason.');
    const decision = await request<Account>(
      `/api/dmc/admin/applications/${item.id}/decision`,
      session.accessToken,
      'POST',
      {
        approved,
        note: note.trim(),
      },
    );
    await load(page);
    setNote('');
    setMessage(
      (approved
        ? `${item.displayName} is approved. Officer access is now enabled.`
        : `${item.displayName} was rejected. Officer access remains blocked.`) +
        (decision.emailNotificationStatus
          ? ` Email notification: ${decision.emailNotificationStatus}.`
          : ''),
    );
  }
  return (
    <SafeAreaView style={s.root}>
      <ScrollView contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
        <View style={s.hero}>
          <View style={s.heroCopy}>
            <Text style={s.eyebrow}>ADMINISTRATION / ACCESS CONTROL</Text>
            <Text accessibilityRole="header" style={s.title}>
              Officer applications
            </Text>
            <Text style={s.description}>
              Review each applicant, confirm their eligibility, and grant the right access.
            </Text>
          </View>
          <View style={s.stat}>
            <Text style={s.statNumber}>{loaded ? String(total) : '—'}</Text>
            <Text style={s.statLabel}>{status.toLowerCase()} applications</Text>
          </View>
        </View>
        <View style={s.toolbar}>
          <View style={s.tabs}>
            {['PENDING', 'APPROVED', 'REJECTED'].map((value) => (
              <Pressable
                key={value}
                accessibilityRole="button"
                accessibilityState={{ selected: status === value }}
                disabled={busy}
                onPress={() => {
                  setStatus(value);
                  setSelected(null);
                  setLoaded(false);
                  setError('');
                }}
                style={[s.tab, status === value && s.activeTab]}
              >
                <Text style={[s.tabText, status === value && s.activeTabText]}>
                  {value[0] + value.slice(1).toLowerCase()}
                </Text>
              </Pressable>
            ))}
          </View>
          <Action
            title="Refresh Applications"
            disabled={busy}
            onPress={() => void run(() => load())}
          />
        </View>
        {message ? (
          <Text accessibilityLiveRegion="polite" style={s.success}>
            {message}
          </Text>
        ) : null}
        {error && !selected ? (
          <Text accessibilityRole="alert" style={s.error}>
            {error}
          </Text>
        ) : null}
        {!loaded ? (
          <View style={s.empty}>
            <Text style={s.heading}>Your review queue</Text>
            <Text style={s.body}>Choose a status and refresh to load applications.</Text>
          </View>
        ) : items.length === 0 ? (
          <View style={s.empty}>
            <Text style={s.heading}>All clear</Text>
            <Text style={s.body}>No {status.toLowerCase()} applications to show.</Text>
          </View>
        ) : (
          items.map((item) => (
            <View key={item.id} style={[s.card, selected === item.id && s.selectedCard]}>
              <View style={s.cardHeader}>
                <View style={s.person}>
                  <View style={s.avatar}>
                    <Text style={s.initials}>
                      {item.displayName
                        .split(' ')
                        .map((part) => part[0])
                        .slice(0, 2)
                        .join('')
                        .toUpperCase()}
                    </Text>
                  </View>
                  <View style={{ gap: 5 }}>
                    <Text style={s.heading}>{item.displayName}</Text>
                    <Text style={s.email}>{item.email}</Text>
                  </View>
                </View>
                <Text
                  style={[
                    s.badge,
                    item.approvalStatus === 'APPROVED' && s.approved,
                    item.approvalStatus === 'REJECTED' && s.rejected,
                  ]}
                >
                  {item.approvalStatus}
                </Text>
              </View>
              <View style={s.cardFooter}>
                <View style={{ gap: 6 }}>
                  <Text style={s.fieldLabel}>REQUESTED ACCESS</Text>
                  <Text style={s.role}>{item.requestedRole?.replaceAll('_', ' ')}</Text>
                </View>
                {item.approvalStatus === 'PENDING' && selected !== item.id && (
                  <Action
                    title="Review Application"
                    onPress={() => {
                      setSelected(item.id || null);
                      setNote('');
                      setError('');
                    }}
                    disabled={busy}
                  />
                )}
              </View>
              {item.approvalNote ? (
                <Text style={s.body}>Review note: {item.approvalNote}</Text>
              ) : null}
              {selected === item.id && item.approvalStatus === 'PENDING' && (
                <View style={s.review}>
                  <Text style={s.heading}>Confirm access for {item.displayName}</Text>
                  <Text style={s.body}>
                    Check the identity and eligibility of this applicant before confirming. Your
                    decision and note will be recorded. An email notification will be sent to{' '}
                    {item.email} if email delivery is enabled.
                  </Text>
                  <TextInput
                    accessibilityLabel={`Verification note for ${item.displayName}`}
                    placeholder="Verification note or rejection reason…"
                    value={note}
                    onChangeText={setNote}
                    editable={!busy}
                    multiline
                    maxLength={500}
                    style={s.input}
                  />
                  {error ? (
                    <Text accessibilityRole="alert" style={s.error}>
                      {error}
                    </Text>
                  ) : null}
                  <View style={s.reviewActions}>
                    <Action
                      title="Confirm Approval"
                      disabled={busy || !note.trim()}
                      onPress={() => void run(() => decide(item, true))}
                    />
                    <Action
                      title="Confirm Rejection"
                      danger
                      disabled={busy || !note.trim()}
                      onPress={() => void run(() => decide(item, false))}
                    />
                    <Action
                      title="Back"
                      secondary
                      disabled={busy}
                      onPress={() => {
                        setSelected(null);
                        setError('');
                      }}
                    />
                  </View>
                </View>
              )}
            </View>
          ))
        )}
        {loaded && pages > 1 && (
          <View style={s.pagination}>
            <Action
              title="Previous"
              secondary
              disabled={busy || page === 0}
              onPress={() => void run(() => load(page - 1))}
            />
            <Text style={s.body}>
              Page {page + 1} of {pages}
            </Text>
            <Action
              title="Next"
              secondary
              disabled={busy || page + 1 >= pages}
              onPress={() => void run(() => load(page + 1))}
            />
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}
const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#F3F6F8' },
  content: { padding: 28, gap: 20, maxWidth: 1200, width: '100%', alignSelf: 'center' },
  hero: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: 24,
    paddingVertical: 18,
  },
  heroCopy: { flex: 1, minWidth: 240, gap: 12 },
  eyebrow: { fontSize: 11, letterSpacing: 1.7, fontWeight: '700', color: '#087F70' },
  title: { fontSize: 34, fontWeight: '800', color: '#142D45' },
  description: { fontSize: 16, lineHeight: 25, color: '#61798A', maxWidth: 620 },
  stat: { backgroundColor: '#E4F2EF', padding: 22, borderRadius: 16, minWidth: 190, gap: 4 },
  statNumber: { fontSize: 34, fontWeight: '800', color: '#087F70' },
  statLabel: { fontSize: 13, color: '#346B65' },
  toolbar: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
    gap: 16,
    alignItems: 'center',
    marginBottom: 4,
  },
  tabs: { flexDirection: 'row', padding: 5, borderRadius: 12, backgroundColor: '#E8EDF1', gap: 3 },
  tab: { paddingHorizontal: 18, paddingVertical: 12, borderRadius: 9 },
  activeTab: {
    backgroundColor: '#FFFFFF',
    shadowColor: '#163D53',
    shadowOpacity: 0.08,
    shadowRadius: 5,
    shadowOffset: { width: 0, height: 2 },
  },
  tabText: { fontSize: 14, color: '#61798A', fontWeight: '600' },
  activeTabText: { color: '#173F5F' },
  card: {
    padding: 24,
    borderRadius: 18,
    borderWidth: 1,
    borderColor: '#DFE7ED',
    backgroundColor: '#FFFFFF',
    gap: 20,
  },
  selectedCard: {
    borderColor: '#74B9AE',
    shadowColor: '#173F5F',
    shadowOpacity: 0.07,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 4 },
  },
  cardHeader: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: 16,
  },
  person: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  avatar: {
    width: 50,
    height: 50,
    borderRadius: 16,
    backgroundColor: '#E7EFF4',
    alignItems: 'center',
    justifyContent: 'center',
  },
  initials: { color: '#365A72', fontSize: 18, fontWeight: '700' },
  heading: { fontSize: 18, fontWeight: '700', color: '#142D45' },
  email: { fontSize: 14, color: '#718594' },
  body: { fontSize: 14, color: '#587083', lineHeight: 23 },
  fieldLabel: { fontSize: 10, color: '#7C909E', letterSpacing: 1.2, fontWeight: '700' },
  role: { fontSize: 14, fontWeight: '600', color: '#365A72' },
  badge: {
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 20,
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 0.7,
    color: '#916E2B',
    backgroundColor: '#FFF4D9',
  },
  approved: { color: '#087F70', backgroundColor: '#E3F4EE' },
  rejected: { color: '#A34C4C', backgroundColor: '#FAE9E9' },
  cardFooter: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 14,
  },
  review: { borderTopWidth: 1, borderTopColor: '#DFE7ED', paddingTop: 20, gap: 14 },
  reviewActions: { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  input: {
    minHeight: 110,
    borderWidth: 1,
    borderColor: '#C9D9E2',
    borderRadius: 12,
    padding: 15,
    fontSize: 15,
    color: '#29495E',
    backgroundColor: '#FAFCFD',
    textAlignVertical: 'top',
  },
  empty: {
    padding: 40,
    backgroundColor: '#FFFFFF',
    borderRadius: 18,
    borderWidth: 1,
    borderColor: '#DFE7ED',
    gap: 12,
  },
  pagination: { flexDirection: 'row', justifyContent: 'center', alignItems: 'center', gap: 18 },
  error: {
    padding: 14,
    borderRadius: 10,
    backgroundColor: '#FAE9E9',
    color: '#A34C4C',
    lineHeight: 22,
  },
  success: {
    padding: 14,
    borderRadius: 10,
    backgroundColor: '#E3F4EE',
    color: '#087F70',
    lineHeight: 22,
  },
});
