export interface RelayLogEnv {
  RELAY_LOG?: string;
}

/** Production Workers: off unless RELAY_LOG=1 */
export function relayLogEnabled(env?: RelayLogEnv): boolean {
  return env?.RELAY_LOG === '1';
}

export function logRelayError(
  env: RelayLogEnv | undefined,
  tag: string,
  error: unknown,
): void {
  if (!relayLogEnabled(env)) return;
  console.error(tag, error);
}
