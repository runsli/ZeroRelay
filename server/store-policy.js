/** @typedef {{ MESSAGE_TTL_SEC?: string, MAX_MESSAGES_PER_ROOM?: string }} StorePolicyEnv */

const DEFAULT_MESSAGE_TTL_SEC = 7_200; // 2 hours
const DEFAULT_MAX_MESSAGES_PER_ROOM = 100;

/**
 * @param {StorePolicyEnv | NodeJS.ProcessEnv | undefined} env
 */
function resolveStorePolicy(env) {
  const ttl = parseInt(env?.MESSAGE_TTL_SEC ?? '', 10);
  const max = parseInt(env?.MAX_MESSAGES_PER_ROOM ?? '', 10);
  return {
    messageTtlSec:
      Number.isFinite(ttl) && ttl >= 60 ? ttl : DEFAULT_MESSAGE_TTL_SEC,
    maxMessagesPerRoom:
      Number.isFinite(max) && max >= 10 ? max : DEFAULT_MAX_MESSAGES_PER_ROOM,
  };
}

module.exports = {
  DEFAULT_MESSAGE_TTL_SEC,
  DEFAULT_MAX_MESSAGES_PER_ROOM,
  resolveStorePolicy,
};
