// Time limit per test in milliseconds. The default 2000 is too short for the SQLite worker
// startup, so give each test up to 30 seconds.
config.set({
  client: {
    mocha: {
      timeout: 30000,
    },
  },
});
