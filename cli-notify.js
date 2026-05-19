/**
 * 终端通知（P3）：响铃 + OSC，无法等同系统推送，但可在后台 watch 时提醒。
 */
function terminalNotify(title, body) {
  const safeTitle = String(title || 'ZeroRelay').replace(/[\x00-\x1f\x7f]/g, '');
  const safeBody = String(body || '').replace(/[\x00-\x1f\x7f]/g, '').slice(0, 200);

  try {
    process.stdout.write('\x07');
  } catch {
    /* ignore */
  }

  if (!process.stdout.isTTY) return;

  try {
    process.stdout.write(`\x1b]9;${safeTitle}\x07`);
  } catch {
    /* ignore */
  }
  try {
    process.stdout.write(`\x1b]777;notify;${safeTitle};${safeBody}\x07`);
  } catch {
    /* ignore */
  }
}

module.exports = { terminalNotify };
