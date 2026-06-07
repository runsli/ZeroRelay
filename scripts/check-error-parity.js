#!/usr/bin/env node
/**
 * Validate docs/error-manifest.json against cli-errors.js, UserErrorKind, and strings.xml.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const MANIFEST_PATH = path.join(ROOT, 'docs/error-manifest.json');
const STRINGS_EN = path.join(ROOT, 'android/app/src/main/res/values/strings.xml');
const STRINGS_ZH = path.join(ROOT, 'android/app/src/main/res/values-zh-rCN/strings.xml');
const USER_ERROR_KT = path.join(
  ROOT,
  'android/app/src/main/kotlin/app/zerorelay/ui/error/UserError.kt',
);

function fail(messages) {
  console.error('error: error manifest parity check failed:');
  for (const msg of messages) console.error(`  - ${msg}`);
  process.exit(1);
}

function readStringNames(file) {
  const text = fs.readFileSync(file, 'utf8');
  const names = new Set();
  for (const match of text.matchAll(/<string name="([^"]+)"/g)) {
    names.add(match[1]);
  }
  return names;
}

function readUserErrorKinds() {
  const text = fs.readFileSync(USER_ERROR_KT, 'utf8');
  const block = text.match(/enum class UserErrorKind \{([\s\S]*?)\n\}/);
  if (!block) fail(['could not parse UserErrorKind enum in UserError.kt']);
  return new Set(
    block[1]
      .split('\n')
      .map((line) => line.replace(/\/\/.*/, '').trim())
      .filter((line) => line && !line.startsWith('//'))
      .map((line) => line.replace(/,$/, '')),
  );
}

function main() {
  const problems = [];
  const manifest = JSON.parse(fs.readFileSync(MANIFEST_PATH, 'utf8'));
  const ERR = require(path.join(ROOT, 'cli-errors.js'));
  const cliKeys = new Set(Object.keys(ERR));
  const stringsEn = readStringNames(STRINGS_EN);
  const stringsZh = readStringNames(STRINGS_ZH);
  const userErrorKinds = readUserErrorKinds();

  const manifestCliKeys = new Set();
  const mappedKinds = new Set();

  for (const entry of manifest.entries || []) {
    if (!entry.id) problems.push('manifest entry missing id');
    if (entry.user_error_kind) {
      if (!userErrorKinds.has(entry.user_error_kind)) {
        problems.push(`unknown user_error_kind "${entry.user_error_kind}" (${entry.id})`);
      }
      if (mappedKinds.has(entry.user_error_kind)) {
        problems.push(`duplicate user_error_kind "${entry.user_error_kind}"`);
      }
      mappedKinds.add(entry.user_error_kind);
    }
    if (entry.cli_key) {
      if (!cliKeys.has(entry.cli_key)) {
        problems.push(`cli-errors.js missing key "${entry.cli_key}" (${entry.id})`);
      }
      manifestCliKeys.add(entry.cli_key);
    }
    for (const field of ['android_title', 'android_action']) {
      const name = entry[field];
      if (!name) continue;
      if (!stringsEn.has(name)) {
        problems.push(`values/strings.xml missing "${name}" (${entry.id})`);
      }
      if (!stringsZh.has(name)) {
        problems.push(`values-zh-rCN/strings.xml missing "${name}" (${entry.id})`);
      }
    }
    if (!entry.android_title) {
      problems.push(`manifest entry "${entry.id}" missing android_title`);
    }
  }

  for (const entry of manifest.cli_only || []) {
    if (!entry.cli_key) problems.push(`cli_only entry "${entry.id}" missing cli_key`);
    else if (!cliKeys.has(entry.cli_key)) {
      problems.push(`cli-errors.js missing cli_only key "${entry.cli_key}"`);
    } else {
      manifestCliKeys.add(entry.cli_key);
    }
  }

  for (const kind of userErrorKinds) {
    if (kind === 'Generic') continue;
    if (!mappedKinds.has(kind)) {
      problems.push(`UserErrorKind.${kind} not listed in manifest.entries`);
    }
  }

  for (const key of cliKeys) {
    if (!manifestCliKeys.has(key)) {
      problems.push(`cli-errors.js key "${key}" not covered by manifest (entries or cli_only)`);
    }
  }

  if (problems.length) fail(problems);

  const paired = manifest.entries.length;
  const cliOnly = manifest.cli_only?.length || 0;
  console.log(
    `ok: error manifest parity (${paired} paired UserErrorKind entries, ${cliOnly} cli-only keys, ${cliKeys.size} cli-errors keys)`,
  );
}

main();
