import { Link } from 'expo-router';
import { View } from 'react-native';
import { DmcScreen } from '../features/dmc/screens/DmcScreen';
import { services } from '../application/services';

export default function ConnectionScreen() {
  return (
    <View style={{ flex: 1 }}>
      <Link href="/" style={{ padding: 20, color: '#005BEA' }}>
        ← Back to home
      </Link>
      <DmcScreen service={services.dmc} />
    </View>
  );
}
