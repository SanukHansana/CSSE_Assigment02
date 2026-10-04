jest.mock('react-native-web/dist/exports/useWindowDimensions', () => ({
  __esModule: true,
  default: jest.fn(() => ({ width: 1280, height: 900, scale: 1, fontScale: 1 })),
}));
jest.mock('expo-router', () => {
  const React = require('react');
  return {
    Link: ({ children, href, asChild, onPress, ...props }) =>
      asChild
        ? React.cloneElement(children, { href })
        : React.createElement('a', { ...props, href, onClick: onPress }, children),
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
