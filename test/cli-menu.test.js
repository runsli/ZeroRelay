const assert = require('node:assert/strict');
const { describe, it } = require('node:test');

const {
  runInteractiveMainMenu,
  promptStartChat,
  handleConfigureServer,
  handleAddContact,
} = require('../cli-menu');

function baseCtx(overrides = {}) {
  const calls = [];
  return {
    calls,
    identity: { publicKey: 'dGVzdA==' },
    DATA_DIR: '/tmp/zerorelay-test',
    getServerUrl: () => 'http://127.0.0.1:8787',
    fingerprint: () => 'SAFE123',
    watchSessionStore: {
      isWatchRunning: () => false,
      getWatchPid: () => null,
      loadWatchSession: () => null,
      startWatchDaemon: () => 42,
      watchLogPath: () => '/tmp/watch.log',
    },
    cliConfig: {
      loadConfig: () => ({ recentContactIds: [] }),
      setServerUrl: () => calls.push(['setServerUrl']),
      normalizeServerUrl: (url) => url.trim(),
    },
    loadContacts: () => [],
    loadGroups: () => [],
    printGroupSummary: () => {},
    printHelp: () => calls.push(['printHelp']),
    encodeContactPayload: () => 'invite-payload',
    qrcodeTerminal: { generate: (_payload, _opts, cb) => cb('QR') },
    writeQrPng: async () => '/tmp/qr.png',
    addContact: async (raw) => calls.push(['addContact', raw]),
    chatWithContact: async (contact) => calls.push(['chatWithContact', contact.id]),
    chatWithGroup: async (group) => calls.push(['chatWithGroup', group.id]),
    promptLine: async () => '',
    ...overrides,
  };
}

describe('cli-menu', () => {
  it('option 4 starts chat with selected contact', async () => {
    const bob = { id: 'bob', name: 'Bob', verified: true };
    const prompts = ['4', '1', '0'];
    let step = 0;
    const ctx = baseCtx({
      loadContacts: () => [bob],
      promptLine: async () => prompts[step++],
    });

    await runInteractiveMainMenu(ctx);

    assert.deepEqual(ctx.calls, [['chatWithContact', 'bob']]);
  });

  it('option 4 starts group chat when g-prefix is used', async () => {
    const group = { id: 'grp-1', displayName: 'Team' };
    const prompts = ['4', 'g1', '0'];
    let step = 0;
    const ctx = baseCtx({
      loadGroups: () => [group],
      promptLine: async () => prompts[step++],
    });

    await runInteractiveMainMenu(ctx);

    assert.deepEqual(ctx.calls, [['chatWithGroup', 'grp-1']]);
  });

  it('promptStartChat invokes chatWithContact for numeric pick', async () => {
    const alice = { id: 'alice', name: 'Alice', verified: false };
    const ctx = baseCtx({
      loadContacts: () => [alice],
      promptLine: async () => '1',
    });

    await promptStartChat(ctx);

    assert.deepEqual(ctx.calls, [['chatWithContact', 'alice']]);
  });

  it('promptStartChat does not chat when no contacts or groups', async () => {
    const ctx = baseCtx({
      promptLine: async () => {
        throw new Error('promptLine should not be called');
      },
    });

    await promptStartChat(ctx);
    assert.equal(ctx.calls.length, 0);
  });

  it('handleConfigureServer saves new URL', async () => {
    const saved = [];
    const ctx = baseCtx({
      promptLine: async () => 'https://relay.example.com',
      cliConfig: {
        loadConfig: () => ({ recentContactIds: [] }),
        setServerUrl: (_dir, url) => saved.push(url),
        normalizeServerUrl: (url) => url.trim(),
      },
    });

    await handleConfigureServer(ctx);
    assert.deepEqual(saved, ['https://relay.example.com']);
  });

  it('handleAddContact forwards pasted invite', async () => {
    const ctx = baseCtx({
      promptLine: async () => 'zerorelay://v1?d=abc',
    });

    await handleAddContact(ctx);
    assert.deepEqual(ctx.calls, [['addContact', 'zerorelay://v1?d=abc']]);
  });

  it('option 1 routes to configure server', async () => {
    const prompts = ['1', 'https://new.example.com', '0'];
    let step = 0;
    const saved = [];
    const ctx = baseCtx({
      promptLine: async () => prompts[step++],
      cliConfig: {
        loadConfig: () => ({ recentContactIds: [] }),
        setServerUrl: (_dir, url) => saved.push(url),
        normalizeServerUrl: (url) => url.trim(),
      },
    });

    await runInteractiveMainMenu(ctx);

    assert.deepEqual(saved, ['https://new.example.com']);
  });
});
