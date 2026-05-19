import QRCode from 'qrcode';
import { DirectChatSession } from '../chat/direct-chat';
import { encodeContactPayload, parseContactPayload } from '../crypto/contact-payload';
import { contactIdFromPublicKey, fingerprint } from '../crypto/identity';
import { senderIdFromPublicKey } from '../crypto/relay-crypto';
import {
  buildDefaultRelayUrl,
  defaultRelayUrlAtRuntime,
  effectiveServerUrl,
  loadConfig,
  loadContacts,
  loadOrCreateIdentity,
  saveContacts,
  saveServerUrl,
  type Contact,
} from '../store/app-store';

type View = 'setup' | 'home' | 'chat';

export class App {
  private readonly root: HTMLElement;
  private view: View = 'setup';
  private contacts: Contact[] = [];
  private serverUrl = '';
  private chatSession: DirectChatSession | null = null;
  private mySenderId = '';
  private chatListEl: HTMLElement | null = null;

  constructor(root: HTMLElement) {
    this.root = root;
    void this.boot();
  }

  private async boot(): Promise<void> {
    const id = await loadOrCreateIdentity();
    this.mySenderId = await senderIdFromPublicKey(id.publicKey);
    const cfg = await loadConfig();
    this.contacts = await loadContacts();
    if (cfg.serverUrl) {
      this.serverUrl = cfg.serverUrl;
      this.view = 'home';
    } else {
      const preset = defaultRelayUrlAtRuntime();
      if (preset) {
        this.serverUrl = await saveServerUrl(preset);
        this.view = 'home';
      }
    }
    this.render();
  }

  private render(): void {
    this.root.innerHTML = '';
    if (this.view === 'setup') this.renderSetup();
    else if (this.view === 'home') void this.renderHome();
    else this.renderChat();
  }

  private header(title: string): HTMLElement {
    const h = document.createElement('header');
    h.className = 'app-header';
    h.innerHTML = `<h1>${title}</h1><p class="muted">ZeroRelay Web · 与 CLI / Android 协议对齐</p>`;
    return h;
  }

  private renderSetup(): void {
    const wrap = document.createElement('div');
    wrap.appendChild(this.header('连接中继'));
    const form = document.createElement('form');
    form.className = 'card stack';
    const label = document.createElement('label');
    label.textContent = '中继地址';
    const input = document.createElement('input');
    input.name = 'server';
    input.type = 'url';
    input.placeholder = 'https://relay.example.com 或 http://127.0.0.1:8787';
    input.required = true;
    input.value = this.serverUrl || defaultRelayUrlAtRuntime() || 'http://127.0.0.1:8787';
    label.appendChild(input);
    const hint = document.createElement('p');
    hint.className = 'hint';
    hint.innerHTML =
      '本地开发：先 <code>cd server && npm start</code>，填 <code>http://127.0.0.1:8787</code>（Vite 会通过 /relay 代理）';
    const btn = document.createElement('button');
    btn.type = 'submit';
    btn.className = 'btn primary';
    btn.textContent = '保存并继续';
    form.append(label, hint, btn);
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      try {
        this.serverUrl = await saveServerUrl(input.value);
        this.view = 'home';
        this.render();
      } catch (err) {
        alert(String(err));
      }
    });
    wrap.appendChild(form);
    this.root.appendChild(wrap);
  }

  private async renderHome(): Promise<void> {
    const wrap = document.createElement('div');
    wrap.appendChild(this.header('ZeroRelay'));

    const serverCard = document.createElement('section');
    serverCard.className = 'card stack';
    const row = document.createElement('div');
    row.className = 'row';
    row.innerHTML = `<span class="muted">中继</span><code>${effectiveServerUrl(this.serverUrl)}</code>`;
    const changeBtn = document.createElement('button');
    changeBtn.type = 'button';
    changeBtn.className = 'btn ghost';
    changeBtn.textContent = '更改';
    changeBtn.addEventListener('click', () => {
      this.view = 'setup';
      this.render();
    });
    row.appendChild(changeBtn);
    serverCard.appendChild(row);

    const idCard = document.createElement('section');
    idCard.className = 'card stack';
    idCard.innerHTML = `<h2>我的身份</h2><p class="muted">ID: <code>${this.mySenderId}</code></p>`;
    const qrCanvas = document.createElement('canvas');
    qrCanvas.className = 'qr';
    const payloadPre = document.createElement('textarea');
    payloadPre.className = 'payload';
    payloadPre.readOnly = true;
    payloadPre.rows = 3;
    idCard.append(qrCanvas, payloadPre);

    const id = await loadOrCreateIdentity();
    payloadPre.value = encodeContactPayload(id.publicKey, 'Web');
    await QRCode.toCanvas(qrCanvas, payloadPre.value, { width: 200, margin: 1 });

    const addCard = document.createElement('section');
    addCard.className = 'card stack';
    addCard.innerHTML = `<h2>添加联系人</h2>`;
    const addInput = document.createElement('textarea');
    addInput.placeholder = '粘贴 zerorelay:// 链接';
    addInput.rows = 3;
    const addBtn = document.createElement('button');
    addBtn.className = 'btn primary';
    addBtn.textContent = '添加';
    addBtn.addEventListener('click', () => void this.addContact(addInput.value));
    addCard.append(addInput, addBtn);

    const listCard = document.createElement('section');
    listCard.className = 'card stack';
    listCard.innerHTML = `<h2>联系人</h2>`;
    const list = document.createElement('ul');
    list.className = 'contact-list';
    if (this.contacts.length === 0) {
      const li = document.createElement('li');
      li.className = 'muted';
      li.textContent = '暂无联系人';
      list.appendChild(li);
    } else {
      for (const c of this.contacts) {
        const li = document.createElement('li');
        li.className = 'contact-item';
        const verified = c.verified ? '已验证' : '未验证';
        li.innerHTML = `
          <div class="contact-top">
            <strong>${escapeHtml(c.name)}</strong>
            <span class="badge ${c.verified ? 'ok' : 'warn'}">${verified}</span>
          </div>
          <div class="row actions">
            <button type="button" class="btn" data-action="chat" data-id="${c.id}">聊天</button>
            <button type="button" class="btn ghost" data-action="verify" data-id="${c.id}">标记已验证</button>
          </div>`;
        list.appendChild(li);
      }
    }
    list.addEventListener('click', (e) => {
      const t = (e.target as HTMLElement).closest('button');
      if (!t) return;
      const contact = this.contacts.find((c) => c.id === t.dataset.id);
      if (!contact) return;
      if (t.dataset.action === 'verify') void this.verifyContact(contact);
      if (t.dataset.action === 'chat') void this.openChat(contact);
    });
    listCard.appendChild(list);

    wrap.append(serverCard, idCard, addCard, listCard);
    this.root.appendChild(wrap);
  }

  private async addContact(raw: string): Promise<void> {
    try {
      const id = await loadOrCreateIdentity();
      const { publicKey, name } = await parseContactPayload(raw);
      if (publicKey === id.publicKey) throw new Error('不能添加自己');
      const cid = await contactIdFromPublicKey(publicKey);
      const displayName = name || (await fingerprint(publicKey));
      this.contacts = [
        {
          id: cid,
          name: displayName,
          publicKey,
          addedAt: Date.now(),
          verified: false,
          verifiedAt: null,
        },
        ...this.contacts.filter((c) => c.id !== cid),
      ];
      await saveContacts(this.contacts);
      alert(`已添加 ${displayName}\n安全码: ${await fingerprint(publicKey)}`);
      this.render();
    } catch (e) {
      alert(String(e));
    }
  }

  private async verifyContact(contact: Contact): Promise<void> {
    const fp = await fingerprint(contact.publicKey);
    if (!confirm(`确认已与对方核对安全码？\n\n${fp}`)) return;
    this.contacts = this.contacts.map((c) =>
      c.id === contact.id ? { ...c, verified: true, verifiedAt: Date.now() } : c,
    );
    await saveContacts(this.contacts);
    this.render();
  }

  private async openChat(contact: Contact): Promise<void> {
    const id = await loadOrCreateIdentity();
    this.chatSession?.stop();
    const session = await DirectChatSession.open(id, contact, this.serverUrl);
    this.chatSession = session;
    this.view = 'chat';
    await session.start();
    session.setOnUpdate(() => this.renderChatMessages());
    this.render();
  }

  private renderChat(): void {
    const session = this.chatSession;
    if (!session) {
      this.view = 'home';
      this.render();
      return;
    }
    const el = document.createElement('div');
    el.className = 'chat-page';

    const head = document.createElement('header');
    head.className = 'chat-header';
    const back = document.createElement('button');
    back.type = 'button';
    back.className = 'btn ghost';
    back.textContent = '← 返回';
    back.addEventListener('click', () => {
      session.stop();
      this.chatSession = null;
      this.view = 'home';
      this.render();
    });
    const title = document.createElement('h2');
    title.textContent = session.contact.name;
    const status = document.createElement('span');
    status.className = 'muted';
    status.textContent = session.contact.verified ? '已验证' : '发送前需验证';
    head.append(back, title, status);

    this.chatListEl = document.createElement('div');
    this.chatListEl.className = 'chat-messages';

    const form = document.createElement('form');
    form.className = 'chat-compose';
    const input = document.createElement('input');
    input.name = 'text';
    input.type = 'text';
    input.placeholder = '输入消息…';
    input.autocomplete = 'off';
    input.required = true;
    const send = document.createElement('button');
    send.type = 'submit';
    send.className = 'btn primary';
    send.textContent = '发送';
    form.append(input, send);
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const text = input.value.trim();
      if (!text) return;
      try {
        await session.sendText(text);
        input.value = '';
        this.renderChatMessages();
      } catch (err) {
        alert(String(err));
      }
    });

    el.append(head, this.chatListEl, form);
    this.root.appendChild(el);
    this.renderChatMessages();
  }

  private renderChatMessages(): void {
    if (!this.chatListEl || !this.chatSession) return;
    this.chatListEl.innerHTML = '';
    for (const m of this.chatSession.getMessages()) {
      const bubble = document.createElement('div');
      bubble.className = `bubble ${m.outgoing ? 'out' : 'in'}`;
      const time = new Date(m.timestamp).toLocaleTimeString();
      bubble.innerHTML = `<p>${escapeHtml(m.text)}</p><time>${time}</time>`;
      this.chatListEl.appendChild(bubble);
    }
    this.chatListEl.scrollTop = this.chatListEl.scrollHeight;
  }
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
