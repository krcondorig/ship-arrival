import test from 'node:test';
import assert from 'node:assert/strict';

// The runner's safety-critical defaults must remain manual and bounded.
test('requires API credentials and B/L, with a short positive timeout', async () => {
  const { readConfig } = await import('../src/config.js');
  assert.throws(() => readConfig({}), /HYPERBROWSER_API_KEY/);
  assert.throws(() => readConfig({ HYPERBROWSER_API_KEY: 'secret' }), /HAPAG_BL/);
  assert.throws(() => readConfig({ HYPERBROWSER_API_KEY: 'secret', HAPAG_BL: 'ABC', HAPAG_SPIKE_TIMEOUT_MINUTES: '0' }), /timeout/);
  assert.deepEqual(readConfig({ HYPERBROWSER_API_KEY: 'secret', HAPAG_BL: 'ABC' }), {
    apiKey: 'secret', bl: 'ABC', timeoutMinutes: 10
  });
});

test('tracking URL encodes the supplied B/L and never adds challenge automation options', async () => {
  const { trackingUrl, sessionOptions } = await import('../src/config.js');
  assert.equal(new URL(trackingUrl('AB C/1')).searchParams.get('blno'), 'AB C/1');
  assert.deepEqual(sessionOptions(10), { timeoutMinutes: 10 });
});
