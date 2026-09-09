// Long database operations block the page while the SQLite worker responds, so karma's default
// ping and disconnect timeouts see the browser as dead on slow CI runners.
config.set({
  pingTimeout: 120000,
  browserDisconnectTimeout: 60000,
  browserDisconnectTolerance: 2,
  browserNoActivityTimeout: 300000,
  captureTimeout: 120000,
});
