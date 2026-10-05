/** Shared design tokens keep presentation consistent across features. */
export const theme = {
  colors: {
    background: '#F4F7FB',
    surface: '#FFFFFF',
    text: '#172338',
    muted: '#56657A',
    primary: '#2457D6',
    border: '#D9E1ED',
    error: '#B42318',
  },
  spacing: { sm: 8, md: 16, lg: 24, xl: 32 },
  radius: { md: 12, lg: 20 },
} as const;
