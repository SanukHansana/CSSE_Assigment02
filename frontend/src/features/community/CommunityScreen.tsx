import { mobileStyles, useMobileLayout } from '../../components/layout/mobileLayout';
import { Link } from 'expo-router';
import { useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useAuth } from '../../application/AuthContext';
import { Action } from '../../components/ui/Action';
import { ApiError, request } from '../reports/api';
interface Offer {
  id: string;
  volunteerName: string;
  quantity: number;
  note: string;
  status: string;
  mine: boolean;
}
interface Need {
  id: string;
  version: number;
  citizenName: string;
  item: string;
  category: string;
  quantity: number;
  unit: string;
  description: string;
  area: string;
  status: string;
  received: number;
  remainingToCommit: number;
  mine: boolean;
  offers: Offer[];
  history: { requestId: string; detail: string; actor: string; at: string }[];
}
interface Page {
  items: Need[];
  totalPages: number;
}
const initial = { item: '', category: 'WATER', quantity: '', unit: '', description: '', area: '' };
export function CommunityScreen() {
  const mobile = useMobileLayout();
  const { session, setSession } = useAuth();
  const citizen = session?.user.roles.includes('CITIZEN') || false;
  const volunteer = session?.user.roles.includes('COMMUNITY_VOLUNTEER') || false;
  const [view, setView] = useState(volunteer ? 'available' : 'mine');
  const [list, setList] = useState<Page>({ items: [], totalPages: 0 });
  const [page, setPage] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [selected, setSelected] = useState<Need | null>(null);
  const [form, setForm] = useState(initial);
  const [quantity, setQuantity] = useState('');
  const [note, setNote] = useState('');
  const [operation, setOperation] = useState<{ id: string; signature: string } | null>(null);
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
      if (cause instanceof ApiError && cause.status === 401) setSession(null);
      else
        setError(cause instanceof Error ? cause.message : 'Unable to connect. Please try again.');
    } finally {
      setBusy(false);
    }
  }
  async function load(next = 0) {
    const data = await request<Page>(
      `/api/dmc/community/needs?view=${view}&page=${next}&size=10`,
      session?.accessToken,
    );
    setList(data);
    setPage(next);
    setLoaded(true);
    setSelected(null);
    setOperation(null);
  }
  async function create() {
    const count = Number(form.quantity);
    if (
      !form.item.trim() ||
      !form.unit.trim() ||
      !form.area.trim() ||
      !form.description.trim() ||
      !Number.isSafeInteger(count) ||
      count < 1 ||
      count > 1000000
    )
      throw new Error('Complete the request and use a positive whole quantity up to 1,000,000.');
    const saved = await request<Need>('/api/dmc/community/needs', session?.accessToken, 'POST', {
      ...form,
      quantity: count,
    });
    setSelected(saved);
    setForm(initial);
    setMessage('Your need is posted. Volunteers can now offer their own supplies.');
  }
  async function update(action: string, offer?: Offer) {
    if (!selected) return;
    let path = `/api/dmc/community/needs/${selected.id}`;
    let body: Record<string, unknown> = { expectedVersion: selected.version };
    if (action === 'OFFER') {
      const count = Number(quantity);
      if (!note.trim() || !Number.isSafeInteger(count) || count < 1)
        throw new Error('Enter a positive whole quantity and describe your personal supplies.');
      path += '/offers';
      body = { ...body, quantity: count, note: note.trim() };
    } else if (action === 'CANCEL') path += '/cancel';
    else {
      path += `/offers/${offer?.id}/action`;
      body.action = action;
    }
    const signature = JSON.stringify([path, { ...body, expectedVersion: undefined }]);
    if (operation && operation.signature !== signature)
      throw new Error(
        'Retry the same action or reload the need to check the pending result first.',
      );
    const requestId =
      operation?.id ||
      `${Date.now()}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
    setOperation({ id: requestId, signature });
    const saved = await request<Need>(path, session?.accessToken, 'POST', { ...body, requestId });
    setSelected(saved);
    setOperation(null);
    setQuantity('');
    setNote('');
    setMessage(
      action === 'RECEIVE'
        ? 'Receipt confirmed. Thank you.'
        : 'Support action recorded successfully.',
    );
  }
  const fields: [keyof typeof form, string, number][] = [
    ['item', 'Item needed', 100],
    ['category', 'Category', 60],
    ['quantity', 'Quantity needed', 7],
    ['unit', 'Unit (bottles, packs, etc.)', 30],
    ['description', 'Describe your need', 500],
    ['area', 'Area / district', 200],
  ];
  return (
    <SafeAreaView edges={mobile ? ['left', 'right'] : undefined} style={s.root}>
      <ScrollView
        contentContainerStyle={[s.content, mobile && mobileStyles.content]}
        keyboardShouldPersistTaps="handled"
      >
        <Text style={s.eyebrow}>COMMUNITY / PERSONAL RESOURCE SUPPORT</Text>
        <Text accessibilityRole="header" style={[s.title, mobile && mobileStyles.title]}>
          {volunteer ? 'Help your community' : 'My support needs'}
        </Text>
        <Text style={s.body}>
          Citizens request essential supplies. Community volunteers offer their personal resources;
          official DMC inventory is separate.
        </Text>
        {!session ? (
          <View style={[s.card, mobile && mobileStyles.card]}>
            <Text style={s.heading}>Sign in to request or offer support</Text>
            <Link href="/login" style={s.link}>
              Sign in →
            </Link>
          </View>
        ) : !citizen && !volunteer ? (
          <Text style={s.error}>
            This workspace is for approved Citizen and Community Volunteer accounts.
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
            <View style={[s.row, mobile && mobileStyles.row]}>
              {citizen && (
                <Action
                  title="My Needs"
                  secondary
                  disabled={busy || view === 'mine'}
                  onPress={() => {
                    setView('mine');
                    setLoaded(false);
                    setSelected(null);
                  }}
                />
              )}
              {volunteer && (
                <>
                  <Action
                    title="Available Needs"
                    secondary
                    disabled={busy || view === 'available'}
                    onPress={() => {
                      setView('available');
                      setLoaded(false);
                      setSelected(null);
                    }}
                  />
                  <Action
                    title="My Offers"
                    secondary
                    disabled={busy || view === 'offers'}
                    onPress={() => {
                      setView('offers');
                      setLoaded(false);
                      setSelected(null);
                    }}
                  />
                </>
              )}
              <Action
                title="Refresh Needs"
                secondary
                disabled={busy}
                onPress={() => void run(() => load())}
              />
            </View>
            {citizen && view === 'mine' && !selected && (
              <View style={[s.card, mobile && mobileStyles.card]}>
                <Text style={s.heading}>Post a need</Text>
                {fields.map(([key, title, max]) => (
                  <View key={key} style={{ gap: 8 }}>
                    <Text style={s.label}>{title} *</Text>
                    <TextInput
                      accessibilityLabel={title}
                      editable={!busy}
                      value={form[key]}
                      onChangeText={(value) => setForm({ ...form, [key]: value })}
                      maxLength={max}
                      keyboardType={key === 'quantity' ? 'number-pad' : 'default'}
                      multiline={key === 'description'}
                      style={[s.input, mobile && mobileStyles.input]}
                    />
                  </View>
                ))}
                <Action title="Post Need" disabled={busy} onPress={() => void run(create)} />
              </View>
            )}
            {selected ? (
              <View style={[s.card, mobile && mobileStyles.card]}>
                <View style={[s.row, mobile && mobileStyles.row]}>
                  <Text style={s.heading}>{selected.item}</Text>
                  <Text style={s.badge}>{selected.status}</Text>
                </View>
                <Text style={s.body}>
                  {selected.citizenName} · {selected.area}
                </Text>
                <Text style={s.body}>{selected.description}</Text>
                <Text style={s.body}>
                  Requested: {selected.quantity}
                  {selected.unit} · Received: {selected.received} · Uncommitted:{' '}
                  {selected.remainingToCommit}
                </Text>
                <View style={[s.row, mobile && mobileStyles.row]}>
                  <Action
                    title="Back to Needs"
                    secondary
                    disabled={busy}
                    onPress={() => {
                      setSelected(null);
                      setOperation(null);
                    }}
                  />
                  <Action
                    title="Reload Need"
                    secondary
                    disabled={busy}
                    onPress={() =>
                      void run(async () => {
                        setSelected(
                          await request<Need>(
                            `/api/dmc/community/needs/${selected.id}`,
                            session.accessToken,
                          ),
                        );
                        setOperation(null);
                      })
                    }
                  />
                  {selected.mine && selected.status === 'OPEN' && (
                    <Action
                      title="Cancel Need"
                      danger
                      disabled={busy}
                      onPress={() => void run(() => update('CANCEL'))}
                    />
                  )}
                </View>
                {volunteer &&
                  !selected.mine &&
                  selected.status === 'OPEN' &&
                  selected.remainingToCommit > 0 && (
                    <View style={s.section}>
                      <Text style={s.heading}>Offer your own resources</Text>
                      <TextInput
                        accessibilityLabel="Personal resource quantity"
                        placeholder={`Quantity (${selected.unit})`}
                        value={quantity}
                        onChangeText={setQuantity}
                        editable={!busy}
                        keyboardType="number-pad"
                        style={[s.input, mobile && mobileStyles.input]}
                      />
                      <TextInput
                        accessibilityLabel="Personal resource offer note"
                        placeholder="What you can give and how you can arrange delivery"
                        value={note}
                        onChangeText={setNote}
                        editable={!busy}
                        maxLength={500}
                        multiline
                        style={[s.input, mobile && mobileStyles.input]}
                      />
                      <Action
                        title="Offer Personal Resources"
                        disabled={busy}
                        onPress={() => void run(() => update('OFFER'))}
                      />
                    </View>
                  )}
                <Text style={s.heading}>{selected.mine ? 'Volunteer offers' : 'Your offers'}</Text>
                {selected.offers.length === 0 && <Text style={s.body}>No offers yet.</Text>}
                {selected.offers.map((offer) => (
                  <View key={offer.id} style={s.section}>
                    <Text style={s.heading}>
                      {offer.volunteerName} · {offer.quantity}
                      {selected.unit}
                    </Text>
                    <Text style={s.body}>{offer.note}</Text>
                    <Text style={s.badge}>{offer.status}</Text>
                    <View style={[s.row, mobile && mobileStyles.row]}>
                      {selected.mine &&
                        offer.status === 'OFFERED' &&
                        selected.status === 'OPEN' && (
                          <>
                            <Action
                              title="Accept Offer"
                              disabled={busy}
                              onPress={() => void run(() => update('ACCEPT', offer))}
                            />
                            <Action
                              title="Decline Offer"
                              danger
                              disabled={busy}
                              onPress={() => void run(() => update('DECLINE', offer))}
                            />
                          </>
                        )}
                      {offer.mine && offer.status === 'ACCEPTED' && (
                        <Action
                          title="Mark Delivered"
                          disabled={busy}
                          onPress={() => void run(() => update('DELIVER', offer))}
                        />
                      )}
                      {offer.mine &&
                        ['OFFERED', 'ACCEPTED'].includes(offer.status) &&
                        selected.status === 'OPEN' && (
                          <Action
                            title="Withdraw Offer"
                            secondary
                            disabled={busy}
                            onPress={() => void run(() => update('WITHDRAW', offer))}
                          />
                        )}
                      {selected.mine && offer.status === 'DELIVERED' && (
                        <Action
                          title="Confirm Receipt"
                          disabled={busy}
                          onPress={() => void run(() => update('RECEIVE', offer))}
                        />
                      )}
                    </View>
                  </View>
                ))}
                <Text style={s.heading}>Activity</Text>
                {selected.history.map((event) => (
                  <Text key={event.requestId} style={s.body}>
                    {event.detail} · {event.actor} · {new Date(event.at).toLocaleString()}
                  </Text>
                ))}
                {operation && (
                  <Text style={s.note}>
                    Pending request {operation.id}. Retry the same action after a connection error,
                    or reload before changing it.
                  </Text>
                )}
              </View>
            ) : (
              <View style={[s.card, mobile && mobileStyles.card]}>
                <Text style={s.heading}>Needs board</Text>
                {!loaded ? (
                  <Text style={s.body}>Refresh to load requests.</Text>
                ) : list.items.length === 0 ? (
                  <Text style={s.body}>No requests in this view yet.</Text>
                ) : (
                  list.items.map((need) => (
                    <View key={need.id} style={s.section}>
                      <Text style={s.heading}>
                        {need.item} · {need.status}
                      </Text>
                      <Text style={s.body}>
                        {need.quantity}
                        {need.unit} · {need.area} · {need.citizenName}
                      </Text>
                      <Action
                        title="View Need"
                        secondary
                        disabled={busy}
                        onPress={() =>
                          void run(async () => {
                            setSelected(
                              await request<Need>(
                                `/api/dmc/community/needs/${need.id}`,
                                session.accessToken,
                              ),
                            );
                            setOperation(null);
                          })
                        }
                      />
                    </View>
                  ))
                )}
                {loaded && list.totalPages > 1 && (
                  <View style={[s.row, mobile && mobileStyles.row]}>
                    <Action
                      title="Previous"
                      secondary
                      disabled={busy || page === 0}
                      onPress={() => void run(() => load(page - 1))}
                    />
                    <Text style={s.body}>Page {page + 1}</Text>
                    <Action
                      title="Next"
                      secondary
                      disabled={busy || page + 1 >= list.totalPages}
                      onPress={() => void run(() => load(page + 1))}
                    />
                  </View>
                )}
              </View>
            )}
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}
const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#F3F6F8' },
  content: { padding: 26, gap: 20, maxWidth: 1100, width: '100%', alignSelf: 'center' },
  eyebrow: { fontSize: 11, letterSpacing: 1.5, fontWeight: '700', color: '#087F70' },
  title: { fontSize: 30, fontWeight: '800', color: '#142D45' },
  heading: { fontSize: 18, fontWeight: '700', color: '#142D45' },
  body: { fontSize: 14, lineHeight: 23, color: '#587083' },
  label: { fontSize: 14, fontWeight: '600', color: '#365A72' },
  card: {
    backgroundColor: '#FFFFFF',
    borderRadius: 18,
    borderWidth: 1,
    borderColor: '#DFE7ED',
    padding: 24,
    gap: 16,
  },
  section: { gap: 14, paddingVertical: 18, borderTopWidth: 1, borderTopColor: '#DFE7ED' },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 10, alignItems: 'center' },
  input: {
    borderWidth: 1,
    borderColor: '#C9D9E2',
    borderRadius: 10,
    minHeight: 48,
    padding: 14,
    fontSize: 15,
    color: '#29495E',
  },
  badge: {
    color: '#087F70',
    backgroundColor: '#E3F4EE',
    padding: 8,
    borderRadius: 8,
    alignSelf: 'flex-start',
    fontWeight: '700',
  },
  error: { backgroundColor: '#FAE9E9', color: '#A34C4C', padding: 14, borderRadius: 10 },
  success: { backgroundColor: '#E3F4EE', color: '#087F70', padding: 14, borderRadius: 10 },
  note: { backgroundColor: '#FFF4D9', color: '#916E2B', padding: 14, borderRadius: 10 },
  link: { color: '#087F70', fontWeight: '700' },
});
