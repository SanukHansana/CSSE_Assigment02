module.exports = {
  preset: 'jest-expo/web',
  testEnvironment: 'jsdom',
  testMatch: ['**/__tests__/**/*.test.js'],
  setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
  collectCoverageFrom: ['src/**/*.{ts,tsx}'],
  coverageReporters: ['text', 'json', 'lcov'],
  coverageThreshold: { global: { lines: 81, branches: 81, statements: 81, functions: 81 } },
};
