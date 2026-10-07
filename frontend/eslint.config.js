const { defineConfig } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');
const testGlobals = Object.fromEntries(
  ['jest', 'test', 'expect', 'beforeEach', 'afterEach', 'beforeAll', 'afterAll', 'describe'].map(
    (name) => [name, 'readonly'],
  ),
);
module.exports = defineConfig([
  expoConfig,
  { ignores: ['dist/*', 'coverage/**'] },
  { files: ['__tests__/**/*.js', 'jest.setup.js'], languageOptions: { globals: testGlobals } },
]);
