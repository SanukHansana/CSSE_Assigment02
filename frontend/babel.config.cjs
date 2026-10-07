module.exports = function (api) {
  const testing = api.env('test');
  return { presets: ['babel-preset-expo'], plugins: testing ? ['dynamic-import-node'] : [] };
};
