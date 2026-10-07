import { Link, router, usePathname } from 'expo-router';
import { StyleSheet, Text, View } from 'react-native';
import { useAuth } from '../../application/AuthContext';
import { Action } from '../ui/Action';
export function AppNavigation() {
  const { session, setSession } = useAuth();
  const path = usePathname();
  const roles = session?.user.roles || [];
  const links: {
    href: '/' | '/ground-reports' | '/relief' | '/warnings' | '/rescue' | '/admin' | '/community';
    title: string;
  }[] = [{ href: '/', title: 'Home' }];
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
const s = StyleSheet.create({
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
