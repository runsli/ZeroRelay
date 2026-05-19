/**
 * 后台监听会话持久化与守护进程管理
 */
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const WATCH_SESSION = 'watch-session.json';
const WATCH_PID = 'watch.pid';
const WATCH_LOG = 'watch.log';

function watchSessionPath(dataDir) {
  return path.join(dataDir, WATCH_SESSION);
}

function watchPidPath(dataDir) {
  return path.join(dataDir, WATCH_PID);
}

function watchLogPath(dataDir) {
  return path.join(dataDir, WATCH_LOG);
}

function saveWatchSession(dataDir, session) {
  fs.mkdirSync(dataDir, { recursive: true, mode: 0o700 });
  fs.writeFileSync(watchSessionPath(dataDir), JSON.stringify(session, null, 2), {
    mode: 0o600,
  });
  try {
    fs.chmodSync(watchSessionPath(dataDir), 0o600);
  } catch {
    /* ignore */
  }
}

function loadWatchSession(dataDir) {
  const file = watchSessionPath(dataDir);
  if (!fs.existsSync(file)) return null;
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

function clearWatchSession(dataDir) {
  try {
    fs.unlinkSync(watchSessionPath(dataDir));
  } catch {
    /* ignore */
  }
}

function isWatchRunning(dataDir) {
  const pidFile = watchPidPath(dataDir);
  if (!fs.existsSync(pidFile)) return false;
  const pid = parseInt(fs.readFileSync(pidFile, 'utf8'), 10);
  if (!pid) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    try {
      fs.unlinkSync(pidFile);
    } catch {
      /* ignore */
    }
    return false;
  }
}

function getWatchPid(dataDir) {
  if (!fs.existsSync(watchPidPath(dataDir))) return null;
  const pid = parseInt(fs.readFileSync(watchPidPath(dataDir), 'utf8'), 10);
  return Number.isFinite(pid) ? pid : null;
}

function startWatchDaemon(dataDir) {
  if (isWatchRunning(dataDir)) return getWatchPid(dataDir);
  const script = path.join(__dirname, 'cli-watch.js');
  const logFd = fs.openSync(watchLogPath(dataDir), 'a');
  const child = spawn(process.execPath, [script], {
    detached: true,
    stdio: ['ignore', logFd, logFd],
    env: { ...process.env, ZERO_RELAY_DATA_DIR: dataDir },
  });
  child.unref();
  fs.writeFileSync(watchPidPath(dataDir), String(child.pid), { mode: 0o600 });
  return child.pid;
}

function stopWatchDaemon(dataDir) {
  const pid = getWatchPid(dataDir);
  if (!pid) return false;
  try {
    process.kill(pid, 'SIGTERM');
  } catch {
    /* already dead */
  }
  try {
    fs.unlinkSync(watchPidPath(dataDir));
  } catch {
    /* ignore */
  }
  return true;
}

module.exports = {
  saveWatchSession,
  loadWatchSession,
  clearWatchSession,
  isWatchRunning,
  getWatchPid,
  startWatchDaemon,
  stopWatchDaemon,
  watchLogPath,
};
