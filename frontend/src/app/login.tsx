// Root navigation shares this route’s authenticated session across workspaces.
import { ReporterScreen } from '../features/reports/ReporterScreen';
export default function LoginRoute() {
  return <ReporterScreen authMode="login" />;
}
