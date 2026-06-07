/**
 * Interactive numbered menu for `zerorelay` with no subcommand args.
 * Thin wrapper over flows implemented in cli.js — no crypto duplication.
 */

function printMenuHeader(ctx) {
  const { identity, getServerUrl, fingerprint, watchSessionStore, DATA_DIR, cliConfig, loadContacts } = ctx;
  const contacts = loadContacts();
  const cfg = cliConfig.loadConfig(DATA_DIR);
  console.log('\n── ZeroRelay ──');
  console.log('Server:', getServerUrl());
  console.log('Safety number:', fingerprint(identity.publicKey));
  if (watchSessionStore.isWatchRunning(DATA_DIR)) {
    const pid = watchSessionStore.getWatchPid(DATA_DIR);
    console.log(`[*] Background watch running (pid ${pid}) — option 6 or watch stop`);
  }
  if (cfg.recentContactIds?.length) {
    const recent = cfg.recentContactIds
      .map((id) => contacts.find((c) => c.id === id))
      .filter(Boolean)
      .slice(0, 3);
    if (recent.length) {
      console.log('Recent:', recent.map((c) => c.name).join(', '));
    }
  }
}

function printNumberedMenu() {
  console.log(`
  1) Configure server
  2) Show my QR / public key
  3) Add contact
  4) Start chat
  5) Help
  6) Background watch
  0) Exit`);
}

async function promptStartChat(ctx) {
  const { loadContacts, loadGroups, printGroupSummary, promptLine, chatWithContact, chatWithGroup } = ctx;
  const contacts = loadContacts();
  const groups = loadGroups();
  if (contacts.length === 0 && groups.length === 0) {
    console.log('[*] No contacts or groups yet. Use option 3 to add a contact first.');
    return;
  }
  console.log('\n── Start chat ──');
  if (contacts.length > 0) {
    console.log('Contacts:');
    contacts.forEach((c, i) => {
      const flag = c.verified ? '' : ' [unverified]';
      console.log(`  ${i + 1}) ${c.name}${flag}`);
    });
  }
  if (groups.length > 0) {
    console.log('Groups:');
    groups.forEach((g, i) => {
      printGroupSummary(g, contacts);
      console.log(`       → g${i + 1}`);
    });
  }
  const pick = await promptLine('\nEnter number or g+number (empty to cancel): ');
  const trimmed = pick.trim().toLowerCase();
  if (!trimmed) return;
  if (trimmed.startsWith('g')) {
    const idx = parseInt(trimmed.slice(1), 10) - 1;
    if (idx < 0 || idx >= groups.length) {
      console.log('[-] Invalid group number');
      return;
    }
    await chatWithGroup(groups[idx]);
    return;
  }
  const idx = parseInt(trimmed, 10) - 1;
  if (idx < 0 || idx >= contacts.length) {
    console.log('[-] Invalid contact number');
    return;
  }
  await chatWithContact(contacts[idx]);
}

async function handleConfigureServer(ctx) {
  const { getServerUrl, promptLine, cliConfig, DATA_DIR } = ctx;
  console.log('\n── Configure server ──');
  console.log('Current:', getServerUrl());
  const url = await promptLine('New server URL (Enter to keep current): ');
  if (!url.trim()) return;
  cliConfig.setServerUrl(DATA_DIR, cliConfig.normalizeServerUrl(url));
  console.log('[+] Saved');
}

async function handleShowQr(ctx) {
  const { identity, encodeContactPayload, fingerprint, qrcodeTerminal, promptLine, writeQrPng } = ctx;
  const payload = encodeContactPayload(identity.publicKey, null);
  console.log('\n── My QR / public key ──');
  console.log('Safety number:', fingerprint(identity.publicKey));
  qrcodeTerminal.generate(payload, { small: true }, (code) => {
    console.log(code);
    console.log('\n', payload, '\n');
  });
  const pngAns = await promptLine('Save PNG path (Enter to skip): ');
  if (!pngAns.trim()) return;
  try {
    const out = await writeQrPng(payload, pngAns.trim());
    console.log(`[+] Saved ${out}`);
  } catch (e) {
    console.log('[-]', e.message);
  }
}

async function handleAddContact(ctx) {
  const { promptLine, addContact } = ctx;
  console.log('\n── Add contact ──');
  const raw = await promptLine('Paste invite (contact or group): ');
  if (!raw.trim()) return;
  await addContact(raw);
}

async function handleBackgroundWatch(ctx) {
  const { watchSessionStore, DATA_DIR } = ctx;
  const sess = watchSessionStore.loadWatchSession(DATA_DIR);
  if (!sess) {
    console.log('[-] No detached session. Use /detach in a chat first, or start a chat once.');
    return;
  }
  if (watchSessionStore.isWatchRunning(DATA_DIR)) {
    console.log('[*] Already running. Log:', watchSessionStore.watchLogPath(DATA_DIR));
    return;
  }
  const pid = watchSessionStore.startWatchDaemon(DATA_DIR);
  console.log(`[+] Started background watch pid ${pid}`);
}

/**
 * @param {object} ctx Dependencies from cli.js (identity, loaders, chat flows, etc.)
 */
async function runInteractiveMainMenu(ctx) {
  while (true) {
    printMenuHeader(ctx);
    printNumberedMenu();
    const pick = await ctx.promptLine('\nChoice: ');
    const trimmed = pick.trim().toLowerCase();
    if (!trimmed || trimmed === '0') {
      console.log('Bye.');
      return;
    }
    switch (trimmed) {
      case '1':
      case 'c':
        await handleConfigureServer(ctx);
        break;
      case '2':
      case 'q':
        await handleShowQr(ctx);
        break;
      case '3':
      case 'a':
        await handleAddContact(ctx);
        break;
      case '4':
        await promptStartChat(ctx);
        break;
      case '5':
      case 'h':
        ctx.printHelp();
        break;
      case '6':
      case 'w':
        await handleBackgroundWatch(ctx);
        break;
      default: {
        // Legacy: bare number or gN jumps straight into chat
        const contacts = ctx.loadContacts();
        const groups = ctx.loadGroups();
        if (trimmed.startsWith('g')) {
          const idx = parseInt(trimmed.slice(1), 10) - 1;
          if (idx >= 0 && idx < groups.length) {
            await ctx.chatWithGroup(groups[idx]);
            break;
          }
        }
        const idx = parseInt(trimmed, 10) - 1;
        if (idx >= 0 && idx < contacts.length) {
          await ctx.chatWithContact(contacts[idx]);
          break;
        }
        console.log('[-] Invalid choice. Enter 1–6, 0 to exit, or 5 for help.');
      }
    }
  }
}

module.exports = {
  runInteractiveMainMenu,
  promptStartChat,
  handleConfigureServer,
  handleShowQr,
  handleAddContact,
  handleBackgroundWatch,
};
