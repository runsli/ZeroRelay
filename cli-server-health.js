/**
 * 服务器连通性与 TLS pin 检查（与 Android ServerHealth 对齐）
 */
const https = require('https');
const http = require('http');
const { URL } = require('url');
const identityStore = require('./cli-identity-store');
const relayCrypto = require('./cli-relay-crypto');
const { normalizeServerUrl } = require('./cli-config');

class CertificatePinMismatchError extends Error {
  constructor(newPin) {
    super('服务器证书已变更，需确认后信任');
    this.name = 'CertificatePinMismatchError';
    this.newPin = newPin;
  }
}

function evaluatePin(dataDir, hostname, peerCert) {
  const pin = relayCrypto.certPinFromPeerCert(peerCert);
  const pins = identityStore.getTlsPinsForHost(dataDir, hostname);
  if (pins.length === 0) {
    return { trusted: true, newPin: pin, tofu: true };
  }
  if (pin && pins.includes(pin)) {
    return { trusted: true, newPin: null, tofu: false };
  }
  return { trusted: false, newPin: pin, tofu: false };
}

function checkServer(dataDir, baseUrl) {
  const normalizedUrl = normalizeServerUrl(baseUrl);
  if (!normalizedUrl) {
    return Promise.reject(new Error('服务器地址为空'));
  }
  const reqUrl = new URL(`${normalizedUrl}/`);
  const lib = reqUrl.protocol === 'https:' ? https : http;

  return new Promise((resolve, reject) => {
    const options = {
      hostname: reqUrl.hostname,
      port: reqUrl.port || (reqUrl.protocol === 'https:' ? 443 : 80),
      path: reqUrl.pathname,
      method: 'GET',
      headers: { Accept: 'application/json' },
    };

    if (reqUrl.protocol === 'https:') {
      options.checkServerIdentity = (host, cert) => {
        const evalResult = evaluatePin(dataDir, host, cert);
        if (!evalResult.trusted && evalResult.newPin) {
          reject(new CertificatePinMismatchError(evalResult.newPin));
          return undefined;
        }
        if (evalResult.tofu && evalResult.newPin) {
          identityStore.rememberTlsPin(dataDir, host, evalResult.newPin);
        }
        return undefined;
      };
    }

    const req = lib.request(options, (res) => {
      let body = '';
      res.on('data', (c) => (body += c));
      res.on('end', () => {
        if (res.statusCode < 200 || res.statusCode >= 300) {
          reject(new Error(`HTTP ${res.statusCode}`));
          return;
        }
        let status = '';
        try {
          status = JSON.parse(body).status;
        } catch {
          reject(new Error('服务器响应异常'));
          return;
        }
        if (status !== 'ok') {
          reject(new Error('服务器响应异常'));
          return;
        }
        const pinned = reqUrl.protocol === 'https:' && identityStore.getTlsPinsForHost(dataDir, reqUrl.hostname).length > 0;
        resolve({ normalizedUrl, pinned });
      });
    });
    req.on('error', reject);
    req.setTimeout(10000, () => {
      req.destroy();
      reject(new Error('连接超时'));
    });
    req.end();
  });
}

module.exports = { checkServer, CertificatePinMismatchError, evaluatePin, normalizeServerUrl };
