/** 中继运行策略（与 server/src/relay-policy.ts 对齐） */

function legacyHttpEnabled(env) {
  return env?.ENABLE_LEGACY_HTTP === '1';
}

const LEGACY_HTTP_DISABLED_ERROR =
  'legacy HTTP API disabled; use POST /t (tunnel) or set ENABLE_LEGACY_HTTP=1';

const LEGACY_HTTP_SUNSET = '2026-09-01';

const LEGACY_HTTP_HEADERS = {
  Deprecation: 'true',
  Sunset: 'Sat, 01 Sep 2026 00:00:00 GMT',
  Link: '</t>; rel="successor-version"',
};

module.exports = {
  legacyHttpEnabled,
  LEGACY_HTTP_DISABLED_ERROR,
  LEGACY_HTTP_SUNSET,
  LEGACY_HTTP_HEADERS,
};
