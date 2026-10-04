import { Link, router, usePathname } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useMobileLayout } from './mobileLayout';
import { useAuth } from '../../application/AuthContext';
import { Action } from '../ui/Action';
type NavigationLink = {
  href:
    | '/'
    | '/ground-reports'
    | '/relief'
    | '/warnings'
    | '/rescue'
    | '/admin'
    | '/community'
    | '/login'
    | '/register';
  title: string;
};
function navigationLinks(roles: string[]) {
  const links: NavigationLink[] = [{ href: '/', title: 'Home' }];
  if (roles.some((role) => ['CITIZEN', 'COMMUNITY_VOLUNTEER', 'DUTY_OFFICER'].includes(role)))
    links.push({
      href: '/ground-reports',
      title: roles.includes('DUTY_OFFICER') ? 'Review Reports' : 'Ground Reports',
    });
  if (roles.some((role) => ['CITIZEN', 'COMMUNITY_VOLUNTEER'].includes(role)))
    links.push({
      href: '/community',
      title: roles.includes('COMMUNITY_VOLUNTEER') ? 'Community Support' : 'My Needs',
    });
  if (roles.includes('DMC_OFFICER'))
    links.push(
      { href: '/relief', title: 'Relief & Shelters' },
      { href: '/warnings', title: 'Warnings' },
      { href: '/rescue', title: 'Rescue Operations' },
    );
  if (roles.includes('ADMIN')) links.push({ href: '/admin', title: 'Officer Approvals' });

  return links;
}
export function AppNavigation() {
  const { session, setSession } = useAuth();
  const path = usePathname();
  const roles = session?.user.roles || [];
  const links = navigationLinks(roles);
  const phone = useMobileLayout();
  const mobile = phone && !roles.includes('ADMIN') && path !== '/admin';
  const [menuOpen, setMenuOpen] = useState(false);
  const role =
    session?.user.approvalStatus === 'REJECTED'
      ? 'Application Rejected'
      : session?.user.approvalStatus === 'PENDING'
        ? 'Awaiting Approval'
        : roles.includes('ADMIN')
          ? 'Administrator'
          : roles.includes('DMC_OFFICER')
            ? 'DMC Officer'
            : roles.includes('DUTY_OFFICER')
              ? 'Duty Officer'
              : roles.includes('COMMUNITY_VOLUNTEER')
                ? 'Community Volunteer'
                : 'Citizen';
  if (mobile)
    return (
      <SafeAreaView edges={['top']} style={s.mobileHeader}>
        <View style={s.mobileTop}>
          <Link href="/" style={s.mobileBrand}>
            ◈ DMC
          </Link>
          <View style={s.mobileHeading}>
            <Text numberOfLines={1} style={s.mobileSection}>
              {links.find((item) => item.href === path)?.title ||
                (path === '/register'
                  ? 'Create account'
                  : path === '/login'
                    ? 'Sign in'
                    : 'Your workspace')}
            </Text>
            <Text numberOfLines={1} style={s.mobileRole}>
              {session ? role : 'Disaster Reporting Portal'}
            </Text>
          </View>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Account menu"
            accessibilityState={{ expanded: menuOpen }}
            hitSlop={8}
            onPress={() => setMenuOpen(!menuOpen)}
            style={({ pressed }) => [s.menuButton, pressed && s.menuPressed]}
          >
            <Text style={s.menuIcon}>{menuOpen ? '×' : '☰'}</Text>
          </Pressable>
        </View>
        {menuOpen && (
          <View style={s.mobileAccount}>
            {session ? (
              <>
                <Text style={s.name}>{session.user.displayName}</Text>
                <Text style={s.role}>{role}</Text>
                <Action
                  title="Log out"
                  secondary
                  onPress={() => {
                    setSession(null);
                    setMenuOpen(false);
                    router.replace('/login');
                  }}
                />
              </>
            ) : (
              <>
                <Link href="/login" onPress={() => setMenuOpen(false)} style={s.mobileAccountLink}>
                  Sign in →
                </Link>
                <Link
                  href="/register"
                  onPress={() => setMenuOpen(false)}
                  style={s.mobileAccountLink}
                >
                  Create account →
                </Link>
              </>
            )}
            <Link href="/connection" onPress={() => setMenuOpen(false)} style={s.mobileAccountLink}>
              Check service connection →
            </Link>
          </View>
        )}
      </SafeAreaView>
    );
  return (
    <View style={s.bar}>
      <Link href="/" style={s.brand}>
        ◈ DMC<Text style={s.subtitle}>{'\n'}Disaster Management Centre</Text>
      </Link>
      <View style={s.links}>
        {links.map((item) => (
          <Link key={item.href} href={item.href} style={[s.link, path === item.href && s.active]}>
            {item.title}
          </Link>
        ))}
      </View>
      <View style={s.account}>
        {session ? (
          <>
            <View>
              <Text style={s.name}>{session.user.displayName}</Text>
              <Text style={s.role}>{role}</Text>
            </View>
            <Action
              title="Log out"
              secondary
              onPress={() => {
                setSession(null);
                router.replace('/login');
              }}
            />
          </>
        ) : (
          <>
            <Link href="/login" style={s.link}>
              Sign in
            </Link>
            <Link href="/register" style={s.register}>
              Create account
            </Link>
          </>
        )}
      </View>
    </View>
  );
}
/** Secondary phone navigation; every role retains the desktop web workspaces. */
export function MobileTabs() {
  const { session } = useAuth();
  const path = usePathname();
  const phone = useMobileLayout();
  const roles = session?.user.roles || [];
  if (!phone || roles.includes('ADMIN') || path === '/admin') return null;
  const links = session
    ? navigationLinks(roles)
    : ([
        { href: '/', title: 'Home' },
        { href: '/login', title: 'Sign in' },
        { href: '/register', title: 'Register' },
      ] as NavigationLink[]);
  const symbols: Record<string, string> = {
    '/': '⌂',
    '/ground-reports': '≋',
    '/community': '◇',
    '/relief': '▣',
    '/warnings': '!',
    '/rescue': '↗',
    '/login': '→',
    '/register': '+',
  };
  const labels: Record<string, string> = {
    '/ground-reports': roles.includes('DUTY_OFFICER') ? 'Review' : 'Reports',
    '/community': roles.includes('COMMUNITY_VOLUNTEER') ? 'Support' : 'My Needs',
    '/relief': 'Relief',
    '/rescue': 'Rescue',
  };
  return (
    <SafeAreaView edges={['bottom']} style={s.tabSafe}>
      <View accessibilityLabel="Mobile workspaces" style={s.tabs}>
        {links.map((item) => (
          <Link key={item.href} href={item.href} asChild>
            <Pressable
              accessibilityRole="link"
              accessibilityLabel={item.title}
              accessibilityState={{ selected: path === item.href }}
              style={({ pressed }) => [
                s.tab,
                path === item.href && s.tabActive,
                pressed && s.tabPressed,
              ]}
            >
              <Text style={[s.tabSymbol, path === item.href && s.tabSymbolActive]}>
                {symbols[item.href]}
              </Text>
              <Text numberOfLines={1} style={[s.tabLabel, path === item.href && s.tabLabelActive]}>
                {labels[item.href] || item.title}
              </Text>
            </Pressable>
          </Link>
        ))}
      </View>
    </SafeAreaView>
  );
}
const s = StyleSheet.create({
  mobileHeader: { backgroundColor: '#0B293F', borderBottomWidth: 1, borderBottomColor: '#284C60' },
  mobileTop: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  mobileBrand: { color: '#FFFFFF', fontSize: 23, fontWeight: '800' },
  mobileHeading: { flex: 1, gap: 3 },
  mobileSection: { color: '#EAF5F2', fontSize: 14, fontWeight: '700' },
  mobileRole: { color: '#9CC8C5', fontSize: 11 },
  menuButton: {
    width: 44,
    height: 44,
    borderRadius: 14,
    backgroundColor: '#234B61',
    alignItems: 'center',
    justifyContent: 'center',
  },
  menuPressed: { backgroundColor: '#126B63' },
  menuIcon: { fontSize: 23, color: '#FFFFFF' },
  mobileAccount: { padding: 16, gap: 12, borderTopWidth: 1, borderTopColor: '#284C60' },
  mobileAccountLink: { color: '#D4EAE6', paddingVertical: 12, fontSize: 14, fontWeight: '600' },
  tabSafe: { backgroundColor: '#FFFFFF', borderTopWidth: 1, borderTopColor: '#DFE7ED' },
  tabs: { flexDirection: 'row', paddingHorizontal: 8, paddingTop: 7, paddingBottom: 7, gap: 4 },
  tab: {
    flex: 1,
    minHeight: 58,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 3,
    borderRadius: 14,
    paddingHorizontal: 3,
  },
  tabActive: { backgroundColor: '#E3F2EE' },
  tabPressed: { backgroundColor: '#EDF4F6' },
  tabSymbol: { fontSize: 22, color: '#61798A', fontWeight: '700' },
  tabSymbolActive: { color: '#087F70' },
  tabLabel: { fontSize: 11, fontWeight: '600', color: '#61798A' },
  tabLabelActive: { color: '#126B63', fontWeight: '800' },

  bar: {
    width: '100%',
    backgroundColor: '#0B293F',
    paddingHorizontal: 26,
    paddingVertical: 17,
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 18,
    borderBottomWidth: 3,
    borderBottomColor: '#168D80',
  },
  brand: { color: '#FFFFFF', fontSize: 25, fontWeight: '800' },
  subtitle: { fontSize: 10, color: '#B9CFDB', fontWeight: '400' },
  links: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, alignItems: 'center' },
  link: {
    color: '#CDDAE2',
    paddingHorizontal: 12,
    paddingVertical: 11,
    fontSize: 14,
    borderRadius: 8,
  },
  active: { color: '#FFFFFF', backgroundColor: '#234B61', fontWeight: '700' },
  account: { flexDirection: 'row', gap: 16, alignItems: 'center' },
  name: { color: '#FFFFFF', fontSize: 14, fontWeight: '700' },
  role: { color: '#9CC8C5', fontSize: 12, marginTop: 4 },
  register: {
    color: '#FFFFFF',
    backgroundColor: '#087F70',
    borderRadius: 9,
    paddingHorizontal: 16,
    paddingVertical: 12,
    fontWeight: '700',
    fontSize: 14,
  },
});
