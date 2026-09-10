// See the copy at the repository root for why: Chrome will not start as root
// without --no-sandbox, and the self-hosted Linux runner executes as root.
// Karma reads karma.config.d per module, so a module with its own browser
// tests needs its own copy — :testing:wasmJsBrowserTest is the task that
// actually failed.
config.set({
    customLaunchers: {
        ChromeHeadlessNoSandbox: {
            base: 'ChromeHeadless',
            flags: ['--no-sandbox', '--disable-dev-shm-usage'],
        },
    },
    browsers: ['ChromeHeadlessNoSandbox'],
});
