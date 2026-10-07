jest.mock('expo-router', () => {
  const React = require('react');
  return {
    Link: ({ children, href, ...props }) => React.createElement('a', { ...props, href }, children),
    router: { replace: jest.fn(), push: jest.fn() },
    usePathname: () => '/',
    useRouter: () => ({ push: jest.fn(), replace: jest.fn() }),
    Stack: Object.assign(({ children }) => children, { Screen: () => null }),
  };
});
jest.mock('react-native-safe-area-context', () => {
  const { View } = require('react-native');
  return {
    SafeAreaView: View,
    SafeAreaProvider: View,
    useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
  };
});
