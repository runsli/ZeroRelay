/** 中继运行策略（安全默认值） */

export function legacyHttpEnabled(env?: { ENABLE_LEGACY_HTTP?: string }): boolean {
  return env?.ENABLE_LEGACY_HTTP === '1';
}

export const LEGACY_HTTP_DISABLED_ERROR =
  'legacy HTTP API disabled; use POST /t (tunnel) or set ENABLE_LEGACY_HTTP=1';

/** Legacy JSON API 计划移除日期（HTTP Sunset） */
export const LEGACY_HTTP_SUNSET = '2026-09-01';

export const LEGACY_HTTP_HEADERS: Record<string, string> = {
  Deprecation: 'true',
  Sunset: 'Sat, 01 Sep 2026 00:00:00 GMT',
  Link: '</t>; rel="successor-version"',
};
