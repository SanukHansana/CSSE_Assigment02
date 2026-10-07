import { createContext, useContext, useState } from 'react';
import type { Dispatch, PropsWithChildren, SetStateAction } from 'react';
import type { Session } from '../features/reports/api';
const AuthContext = createContext<{
  session: Session | null;
  setSession: Dispatch<SetStateAction<Session | null>>;
} | null>(null);
export function AuthProvider({ children }: PropsWithChildren) {
  const [session, setSession] = useState<Session | null>(null);
  return <AuthContext.Provider value={{ session, setSession }}>{children}</AuthContext.Provider>;
}
export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error('AuthProvider is required.');
  return value;
}
