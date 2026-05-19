/**
 * 导出二维码 PNG（P3，可选依赖 qrcode）
 */
const fs = require('fs');
const path = require('path');

async function writeQrPng(text, outPath) {
  let QRCode;
  try {
    QRCode = require('qrcode');
  } catch {
    throw new Error('缺少 qrcode 包，请执行: npm install');
  }
  const resolved = path.resolve(outPath);
  await QRCode.toFile(resolved, text, {
    type: 'png',
    width: 480,
    margin: 2,
    errorCorrectionLevel: 'M',
  });
  return resolved;
}

module.exports = { writeQrPng };
