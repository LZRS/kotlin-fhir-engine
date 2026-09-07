// Chrome on CI runners crashes or stalls with the default shared memory location and sandbox.
// These flags are the standard fix for headless Chrome on CI.
config.set({
  customLaunchers: {
    ChromeHeadlessCi: {
      base: 'ChromeHeadless',
      flags: ['--no-sandbox', '--disable-dev-shm-usage'],
    },
  },
  browsers: ['ChromeHeadlessCi'],
});

// Show browser console output in the CI log. Mutated directly because config.set would replace
// the whole client object and drop the Mocha timeout.
config.client = config.client || {};
config.client.captureConsole = true;
config.set({
  browserConsoleLogOptions: {
    level: 'debug',
    terminal: true,
  },
});
