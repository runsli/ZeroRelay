const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { afterEach, beforeEach, describe, it } = require('node:test');

const cliConfig = require('../cli-config');

describe('cli-config', () => {
  /** @type {string} */
  let dataDir;

  beforeEach(() => {
    dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'zr-cli-config-'));
  });

  afterEach(() => {
    fs.rmSync(dataDir, { recursive: true, force: true });
  });

  it('loadConfig returns defaults when file is missing', () => {
    const cfg = cliConfig.loadConfig(dataDir);
    assert.equal(cfg.serverUrl, null);
    assert.deepEqual(cfg.recentContactIds, []);
    assert.deepEqual(cfg.recentRoomIds, []);
  });

  it('setServerUrl persists URL as given', () => {
    cliConfig.setServerUrl(dataDir, 'https://relay.example.com');
    const cfg = cliConfig.loadConfig(dataDir);
    assert.equal(cfg.serverUrl, 'https://relay.example.com');
  });

  it('rememberServerUrl normalizes bare hostnames', () => {
    cliConfig.rememberServerUrl(dataDir, 'relay.example.com');
    const cfg = cliConfig.loadConfig(dataDir);
    assert.equal(cfg.serverUrl, 'https://relay.example.com');
  });

  it('addRecentContact dedupes and caps list', () => {
    for (let i = 0; i < cliConfig.MAX_RECENT + 3; i += 1) {
      cliConfig.addRecentContact(dataDir, `id-${i}`);
    }
    const cfg = cliConfig.loadConfig(dataDir);
    assert.equal(cfg.recentContactIds.length, cliConfig.MAX_RECENT);
    assert.equal(cfg.recentContactIds[0], `id-${cliConfig.MAX_RECENT + 2}`);
    assert.ok(!cfg.recentContactIds.includes('id-0'));
  });

  it('addRecentContact moves existing id to front', () => {
    cliConfig.addRecentContact(dataDir, 'a');
    cliConfig.addRecentContact(dataDir, 'b');
    cliConfig.addRecentContact(dataDir, 'a');
    const cfg = cliConfig.loadConfig(dataDir);
    assert.deepEqual(cfg.recentContactIds, ['a', 'b']);
  });

  it('addRecentRoom persists room ids', () => {
    cliConfig.addRecentRoom(dataDir, 'room-1');
    cliConfig.addRecentRoom(dataDir, 'room-2');
    const cfg = cliConfig.loadConfig(dataDir);
    assert.deepEqual(cfg.recentRoomIds, ['room-2', 'room-1']);
  });

  it('loadConfig tolerates corrupt JSON', () => {
    fs.writeFileSync(path.join(dataDir, 'config.json'), '{not json');
    const cfg = cliConfig.loadConfig(dataDir);
    assert.equal(cfg.serverUrl, null);
    assert.deepEqual(cfg.recentContactIds, []);
  });

  it('normalizeServerUrl uses http for local hosts', () => {
    assert.equal(cliConfig.normalizeServerUrl('127.0.0.1:8787'), 'http://127.0.0.1:8787');
    assert.equal(cliConfig.normalizeServerUrl('https://relay.example.com/'), 'https://relay.example.com');
  });
});
