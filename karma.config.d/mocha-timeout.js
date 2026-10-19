// Mocha's default 2 s per test fails heavy wasm tests on a busy runner with a bare "Error"
// (PlayerInspectorTest 6000-event case, LanguageDisplayNameTest). Merge, so Kotlin's own client settings stay.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 30000 });
