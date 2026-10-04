import { useState } from 'react';
import { mobileStyles, useMobileLayout } from '../../../components/layout/mobileLayout';
import { Link } from 'expo-router';
import { Pressable, ScrollView, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

const planned = [
  {
    number: '02',
    title: 'Relief resources & shelters',
    description: 'Manage relief supplies, allocations, and shelter availability.',
  },
  {
    number: '03',
    title: 'Disaster warnings',
    description: 'Prepare official warnings and track their delivery.',
  },
  {
    number: '04',
    title: 'Rescue operations',
    description: 'Coordinate requests, rescue teams, and operation progress.',
  },
];

export function HomeScreen() {
  const mobile = useMobileLayout();
  const [showFlow, setShowFlow] = useState(false);
  const wide = useWindowDimensions().width >= 850;
  return (
    <SafeAreaView edges={mobile ? ['left', 'right'] : undefined} style={styles.root}>
      <ScrollView contentContainerStyle={styles.scroll}>
        <View style={[styles.content, mobile && mobileStyles.content]}>
          <View style={[styles.hero, mobile && mobileStyles.card, wide && styles.heroWide]}>
            <View style={[styles.heroCopy, mobile && { flex: 0 }]}>
              <Text style={styles.eyebrow}>DISASTER MANAGEMENT CENTRE</Text>
              <Text
                accessibilityRole="header"
                style={[
                  styles.heroTitle,
                  mobile && { fontSize: 29, lineHeight: 37 },
                  wide && styles.heroTitleWide,
                ]}
              >
                Better information.{'\n'}Faster coordination.
              </Text>
              <Text style={styles.heroBody}>
                Report what you observe and help officers build a clearer picture of conditions on
                the ground.
              </Text>
              <Link href="/ground-reports" style={[styles.primary, styles.primaryText]}>
                Open ground reports →
              </Link>
            </View>
            {mobile && (
              <Pressable
                accessibilityRole="button"
                accessibilityState={{ expanded: showFlow }}
                onPress={() => setShowFlow(!showFlow)}
                style={({ pressed }) => [styles.mobileDisclosure, pressed && { opacity: 0.75 }]}
              >
                <Text style={styles.mobileDisclosureText}>
                  {showFlow ? 'Hide reporting flow −' : 'How reporting works +'}
                </Text>
              </Pressable>
            )}
            {(!mobile || showFlow) && (
              <View style={[styles.heroPanel, mobile && mobileStyles.card]}>
                <Text style={styles.panelLabel}>GROUND REPORTING</Text>
                <Text style={styles.panelTitle}>From observation{'\n'}to verified evidence</Text>
                {[
                  'Describe the hazard',
                  'Attach a photo and location',
                  'Duty Officer reviews the evidence',
                ].map((step, index) => (
                  <View key={step} style={styles.flowRow}>
                    <Text style={styles.stepNumber}>{index + 1}</Text>
                    <Text style={styles.flowText}>{step}</Text>
                  </View>
                ))}
                <Text style={styles.panelNote}>
                  Reports support official assessment after verification.
                </Text>
              </View>
            )}
          </View>
          <View style={styles.sectionHeading}>
            <Text
              accessibilityRole="header"
              style={[styles.sectionTitle, mobile && { fontSize: 23 }]}
            >
              Your workspace
            </Text>
            <Text style={styles.sectionSubtitle}>One place to report and coordinate.</Text>
          </View>
          <View style={styles.grid}>
            <View
              style={[
                styles.feature,
                mobile && mobileStyles.card,
                styles.activeFeature,
                wide && styles.half,
              ]}
            >
              <View style={styles.cardTop}>
                <Text style={styles.cardNumber}>01</Text>
                <Text style={styles.badge}>AVAILABLE</Text>
              </View>
              <Text style={styles.cardTitle}>Submit & verify ground hazard reports</Text>
              <Text style={styles.cardBody}>
                Record a hazard, provide evidence, and review its credibility before it supports an
                official assessment.
              </Text>
              <Link href="/ground-reports" style={styles.cardLink}>
                Open reporting workspace →
              </Link>
            </View>
            {planned.map((feature) => (
              <View
                key={feature.number}
                style={[styles.feature, mobile && mobileStyles.card, wide && styles.half]}
              >
                <View style={styles.cardTop}>
                  <Text style={styles.cardNumber}>{feature.number}</Text>
                  <Text style={styles.plannedBadge}>AVAILABLE</Text>
                </View>
                <Text style={styles.cardTitle}>{feature.title}</Text>
                <Text style={styles.cardBody}>{feature.description}</Text>
                {feature.number === '03' ? (
                  <Link href="/warnings" style={styles.cardLink}>
                    Open warning workspace →
                  </Link>
                ) : feature.number === '02' ? (
                  <Link href="/relief" style={styles.cardLink}>
                    Open relief workspace →
                  </Link>
                ) : (
                  <Link href="/rescue" style={styles.cardLink}>
                    Open rescue workspace →
                  </Link>
                )}
              </View>
            ))}
          </View>
          <View style={[styles.feature, mobile && mobileStyles.card, styles.activeFeature]}>
            <Text style={styles.cardTitle}>Community support</Text>
            <Text style={styles.cardBody}>
              Citizens can request essential supplies, and community volunteers can offer their
              personal resources.
            </Text>
            <Link href="/community" style={styles.cardLink}>
              Open needs board →
            </Link>
          </View>
          <View style={styles.footer}>
            <Link href="/admin" style={styles.connection}>
              Admin verification →
            </Link>
            <Text style={styles.footerText}>DMC · Disaster Reporting Portal</Text>
            <Link href="/connection" style={styles.connection}>
              Check service connection →
            </Link>
          </View>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}
const styles = StyleSheet.create({
  mobileDisclosure: { backgroundColor: '#DDECEB', borderRadius: 12, padding: 15, minHeight: 48 },
  mobileDisclosureText: { color: '#126B63', fontWeight: '700', fontSize: 14 },
  root: { flex: 1, backgroundColor: '#F3F6F8' },
  scroll: { flexGrow: 1 },
  header: {
    backgroundColor: '#06234D',
    paddingHorizontal: 24,
    paddingVertical: 20,
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 24,
  },
  brand: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  logo: {
    width: 43,
    height: 47,
    borderWidth: 2,
    borderColor: '#60B9FF',
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoText: { fontSize: 27, fontWeight: '800', color: '#FFFFFF' },
  brandName: { color: '#FFFFFF', fontSize: 24, fontWeight: '700' },
  brandSub: { color: '#CCDCEF', fontSize: 12 },
  nav: { flexDirection: 'row', gap: 24 },
  currentNav: { color: '#FFFFFF', fontSize: 15, fontWeight: '700' },
  navLink: { color: '#CCDCEF', fontSize: 15 },
  content: { width: '100%', maxWidth: 1200, alignSelf: 'center', padding: 24, gap: 28 },
  hero: { backgroundColor: '#EAF1FB', borderRadius: 24, padding: 24, gap: 28 },
  heroWide: { flexDirection: 'row', padding: 40 },
  heroCopy: { flex: 1, gap: 20 },
  eyebrow: { color: '#087F70', fontSize: 12, letterSpacing: 1.5, fontWeight: '700' },
  heroTitle: { fontSize: 32, lineHeight: 40, fontWeight: '800', color: '#09274F' },
  heroTitleWide: { fontSize: 44, lineHeight: 53 },
  heroBody: { color: '#52647B', fontSize: 17, lineHeight: 27, maxWidth: 480 },
  primary: {
    backgroundColor: '#087F70',
    borderRadius: 10,
    paddingHorizontal: 22,
    paddingVertical: 17,
    alignSelf: 'flex-start',
    minHeight: 48,
  },
  primaryText: { color: '#FFFFFF', fontSize: 16, fontWeight: '700' },
  pressed: { opacity: 0.8 },
  heroPanel: { backgroundColor: '#06234D', borderRadius: 18, padding: 25, gap: 19, flex: 1 },
  panelLabel: { color: '#8DCBFF', fontSize: 12, letterSpacing: 1.3, fontWeight: '700' },
  panelTitle: { color: '#FFFFFF', fontSize: 25, lineHeight: 32, fontWeight: '700' },
  flowRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  stepNumber: {
    color: '#8DCBFF',
    borderWidth: 1,
    borderColor: '#456382',
    borderRadius: 16,
    width: 30,
    height: 30,
    textAlign: 'center',
    lineHeight: 28,
  },
  flowText: { color: '#FFFFFF', fontSize: 15, flex: 1 },
  panelNote: { color: '#B8CCE4', fontSize: 13, lineHeight: 21 },
  sectionHeading: { gap: 7 },
  sectionTitle: { fontSize: 26, fontWeight: '700', color: '#09274F' },
  sectionSubtitle: { fontSize: 15, color: '#52647B' },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: 16 },
  feature: {
    width: '100%',
    backgroundColor: '#FFFFFF',
    padding: 24,
    gap: 16,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#DFE7ED',
  },
  half: { width: '48%' },
  activeFeature: { borderColor: '#9CBFFA' },
  cardTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 10 },
  cardNumber: { color: '#52647B', fontSize: 18, fontWeight: '700' },
  badge: {
    color: '#087F70',
    backgroundColor: '#EDF4FF',
    padding: 7,
    fontSize: 10,
    fontWeight: '700',
    borderRadius: 6,
  },
  plannedBadge: {
    color: '#64748B',
    backgroundColor: '#F1F4F8',
    padding: 7,
    fontSize: 10,
    fontWeight: '700',
    borderRadius: 6,
  },
  cardTitle: { color: '#09274F', fontSize: 21, lineHeight: 29, fontWeight: '700' },
  cardBody: { color: '#52647B', fontSize: 15, lineHeight: 24 },
  cardLink: { color: '#087F70', fontSize: 15, fontWeight: '700', paddingVertical: 8 },
  comingSoon: { color: '#64748B', fontSize: 14, paddingVertical: 8 },
  footer: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
    gap: 16,
    paddingVertical: 14,
  },
  footerText: { color: '#64748B', fontSize: 13 },
  connection: { color: '#087F70', fontSize: 13, paddingVertical: 4 },
});
