/**
 * 传输层日志（仅元数据，不记录明文或完整密文）
 * 设置 TRANSPORT_LOG=1 开启（默认关闭）
 */

const ENABLED = process.env.TRANSPORT_LOG === '1';
const LOG_POLL_EMPTY = process.env.LOG_POLL_EMPTY === '1';

const C = {
  reset: '\x1b[0m',
  dim: '\x1b[2m',
  bold: '\x1b[1m',
  green: '\x1b[32m',
  cyan: '\x1b[36m',
  yellow: '\x1b[33m',
  magenta: '\x1b[35m',
  red: '\x1b[31m',
  blue: '\x1b[34m',
};

function timestamp() {
  return new Date().toISOString().replace('T', ' ').slice(0, 19);
}

function shortRoom(roomId) {
  if (!roomId) return '?';
  const s = String(roomId);
  return s.length > 16 ? `${s.slice(0, 16)}…` : s;
}

function shortId(id) {
  if (!id) return '?';
  const s = String(id);
  return s.length > 14 ? `${s.slice(0, 14)}…` : s;
}

function cipherSize(message) {
  const n = (message.ciphertext || '').length;
  if (n < 1024) return `${n}B`;
  return `${(n / 1024).toFixed(1)}KB`;
}

function line(color, tag, parts) {
  if (!ENABLED) return;
  const body = parts.filter(Boolean).join('  ');
  console.log(`${C.dim}[${timestamp()}]${C.reset} ${color}${tag}${C.reset}  ${body}`);
}

const transportLog = {
  enabled: ENABLED,

  banner(port) {
    if (!ENABLED) return;
    console.log('');
    console.log(`${C.cyan}${C.bold}── ZeroRelay 传输日志（TRANSPORT_LOG=1）──${C.reset}`);
    console.log(`${C.dim}   仅记录房间/发送者/消息 ID/密文大小，不记录明文（服务端无密钥，无法解密）${C.reset}`);
    console.log(`${C.dim}   客户端加解密日志: Android Logcat 标签 ZeroRelay.Crypto（Debug 包）${C.reset}`);
    console.log(`${C.dim}   CLI: CRYPTO_LOG=1 node cli.js chat <id>  明文预览: CRYPTO_LOG_PLAINTEXT=1${C.reset}`);
    console.log(`${C.dim}   开启传输日志: TRANSPORT_LOG=1 npm start${C.reset}`);
    console.log(`${C.dim}   监听: http://localhost:${port}${C.reset}`);
    console.log('');
  },

  wsConnect({ roomId, senderId, online }) {
    line(C.green, '🔌 WS连接', [
      `${C.dim}room=${C.reset}${shortRoom(roomId)}`,
      `${C.dim}from=${C.reset}${senderId || 'anonymous'}`,
      `${C.dim}在线=${C.reset}${online}`,
    ]);
  },

  wsDisconnect({ roomId, senderId, online }) {
    line(C.yellow, '🔌 WS断开', [
      `${C.dim}room=${C.reset}${shortRoom(roomId)}`,
      `${C.dim}from=${C.reset}${senderId || '?'}`,
      `${C.dim}剩余=${C.reset}${online}`,
    ]);
  },

  messageStored({ channel, roomId, message, totalInRoom }) {
    line(C.blue, '📤 入站', [
      `${C.dim}via=${C.reset}${channel}`,
      `${C.dim}room=${C.reset}${shortRoom(roomId)}`,
      `${C.dim}from=${C.reset}${message.senderId || 'anonymous'}`,
      `${C.dim}id=${C.reset}${shortId(message.id)}`,
      `${C.dim}cipher=${C.reset}${cipherSize(message)}`,
      `${C.dim}房内=${C.reset}${totalInRoom}条`,
    ]);
  },

  broadcast({ roomId, type, recipients }) {
    line(C.magenta, '📡 广播', [
      `${C.dim}room=${C.reset}${shortRoom(roomId)}`,
      `${C.dim}type=${C.reset}${type}`,
      `${C.dim}→${C.reset}${recipients} 订阅`,
    ]);
  },

  httpPoll({ roomId, since, count, waitedMs }) {
    if (count === 0 && !LOG_POLL_EMPTY) return;
    line(C.cyan, '📥 轮询', [
      `${C.dim}room=${C.reset}${shortRoom(roomId)}`,
      `${C.dim}since=${C.reset}${since}`,
      `${C.dim}返回=${C.reset}${count}条`,
      waitedMs != null ? `${C.dim}等待=${C.reset}${waitedMs}ms` : null,
    ]);
  },

  roomCleared({ roomId, channel }) {
    line(C.red, '🗑️  清空', [
      `${C.dim}via=${C.reset}${channel}`,
      `${C.dim}room=${C.reset}${shortRoom(roomId)}`,
    ]);
  },

  httpRequest({ method, path, status }) {
    if (!ENABLED) return;
    if (path === '/' && method === 'GET') return;
    if (path.startsWith('/messages') && method === 'GET') return; // 由 httpPoll 单独记
    if (path.match(/\/info$/) && method === 'GET') return;
    line(C.dim, '🌐 HTTP', [
      `${method} ${path}`,
      status ? `→ ${status}` : null,
    ]);
  },
};

module.exports = transportLog;
