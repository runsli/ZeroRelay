#!/usr/bin/env node
/**
 * 后台监听守护进程入口（由 /detach 或 watch 启动）
 */
const os = require('os');
const path = require('path');

if (!process.env.ZERO_RELAY_DATA_DIR) {
  process.env.ZERO_RELAY_DATA_DIR = path.join(os.homedir(), '.zero-relay');
}

const { runWatchLoop } = require('./cli');

runWatchLoop().catch((e) => {
  console.error(e);
  process.exit(1);
});
