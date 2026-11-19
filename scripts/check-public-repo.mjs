import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const git = (root, args) => execFileSync('git', args, { cwd: root, maxBuffer: 32 * 1024 * 1024 });
// Official Gradle 8.14.3 wrapper checksum; update from Gradle when upgrading it.
const wrapperSha256 = '7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172';
const patterns = [
  ['private-key', /-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----/g],
  ['google-api-key', /AIza[0-9A-Za-z_-]{35}/g],
  ['github-token', /(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,})/g],
  ['aws-access-key', /(?:AKIA|ASIA)[0-9A-Z]{16}/g],
  ['slack-token', /xox[baprs]-[A-Za-z0-9-]{15,}/g],
  ['openai-token', /\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{32,}/g],
  ['personal-machine-path', /\/Users\/[^/\s`'"<>]+|\/home\/(?!runner(?:\/|\b))[^/\s`'"<>]+|[A-Z]:\\Users\\[^\\\s]+/g],
];

export function isPrivatePath(path) {
  const name = path.split('/').at(-1);
  if (/(?:^|\/)(?:\.local|\.secrets|\.aws|\.codex|\.agents|\.idea|\.vscode|\.gradle|\.kotlin|\.cache|node_modules|build|artifacts|coverage|recordings|screenshots|exports)(?:\/|$)/.test(path)) return true;
  if (name === '.env.example' || name === 'local.properties.example') return false;
  if (name === '.env' || name.startsWith('.env.') || name === 'local.properties') return true;
  if (/^(?:credentials\.json|service[-_]account.*\.json|google-services\.json|GoogleService-Info\.plist)$/i.test(name)) return true;
  if (/\.(?:apk|aab|apks|ipa|jks|keystore|pem|key|p12|pfx|mobileprovision|log|hprof|zip|tar|tgz|mp4|mov|webm|gpx|kml|kmz|db|sqlite|sqlite3)$/i.test(name) || /\.tar\.gz$/i.test(name)) return true;
  return /\.jar$/i.test(name) && path !== 'android/gradle/wrapper/gradle-wrapper.jar';
}

function localSensitiveValues(root) {
  const values = new Set();
  for (const name of ['backend/.env', 'android/local.properties']) {
    const path = join(root, name);
    if (!existsSync(path)) continue;
    for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
      const match = line.match(/^\s*([A-Za-z_][\w.]*)\s*=\s*(.*?)\s*$/);
      if (!match || !/key|token|secret|password/i.test(match[1])) continue;
      const value = match[2].replace(/^(['"])(.*)\1$/, '$2');
      if (value.length >= 12) values.add(value);
    }
  }
  const metadata = join(root, '.local/google-cloud-setup.json');
  if (existsSync(metadata)) {
    let data;
    try { data = JSON.parse(readFileSync(metadata, 'utf8')); }
    catch { throw new Error('Private setup metadata is invalid JSON; fix it locally before checking.'); }
    for (const key of ['project', 'account', 'serverIps', 'debugSha1', 'androidKey', 'serverKey']) {
      for (const value of [data[key]].flat()) if (typeof value === 'string' && value.length >= 8) values.add(value);
    }
  }
  return [...values];
}

export function auditRepository(root) {
  const paths = [...new Set(git(root, ['ls-files', '--cached', '--others', '--exclude-standard', '-z']).toString().split('\0').filter(Boolean))];
  const index = new Map();
  for (const entry of git(root, ['ls-files', '--stage', '-z']).toString().split('\0').filter(Boolean)) {
    const [header, path] = entry.split('\t');
    const [mode, object, stage] = header.split(' ');
    if (stage !== '0') throw new Error('Resolve merge conflicts before checking public content.');
    index.set(path, { mode, object });
  }
  const localValues = localSensitiveValues(root), findings = [], snapshots = [], seen = new Set();
  function add(path, origin, rule, line) {
    const key = JSON.stringify([path, origin, rule, line]);
    if (!seen.has(key)) { seen.add(key); findings.push({ path, origin, rule, line }); }
  }
  function inspect(path, origin, data) {
    snapshots.push({ path, origin, data });
    if (path === 'android/gradle/wrapper/gradle-wrapper.jar' && createHash('sha256').update(data).digest('hex') !== wrapperSha256)
      add(path, origin, 'gradle-wrapper-checksum');
    if (data.length > 2 * 1024 * 1024) add(path, origin, 'large-file-review');
    for (const value of localValues) if (data.includes(Buffer.from(value)) || path.includes(value)) add(path, origin, 'local-secret-or-setup-identifier');
    if (data.includes(0)) return;
    const text = data.toString('utf8');
    for (const [rule, pattern] of patterns) {
      for (const match of text.matchAll(pattern)) add(path, origin, rule, text.slice(0, match.index).split('\n').length);
      pattern.lastIndex = 0;
    }
  }
  for (const path of paths) {
    if (isPrivatePath(path)) add(path, 'repository', 'private-or-generated-path');
    const entry = index.get(path);
    let indexed;
    if (entry) {
      if (entry.mode !== '100644' && entry.mode !== '100755') add(path, 'index', 'unsupported-file-type');
      else { indexed = git(root, ['cat-file', 'blob', entry.object]); inspect(path, 'index', indexed); }
    }
    const absolute = join(root, path);
    let stat;
    try { stat = lstatSync(absolute); } catch (error) { if (error.code === 'ENOENT') continue; throw error; }
    if (!stat.isFile()) { add(path, 'worktree', 'unsupported-file-type'); continue; }
    const working = readFileSync(absolute);
    if (!indexed || !working.equals(indexed)) inspect(path, 'worktree', working);
  }
  return { paths, findings, snapshots, localValues };
}

function safePath(path, values) {
  let result = path;
  for (const value of values) result = result.split(value).join('[redacted]');
  for (const [rule, pattern] of patterns) if (rule !== 'personal-machine-path') result = result.replace(pattern, '[redacted]');
  return JSON.stringify(result);
}

export function runGitleaks(root, executable, snapshots) {
  const scanRoot = mkdtempSync(join(tmpdir(), 'walkline-public-scan-'));
  try {
    for (const file of snapshots) {
      const target = join(scanRoot, file.origin, file.path);
      mkdirSync(dirname(target), { recursive: true }); writeFileSync(target, file.data);
    }
    for (const args of [['dir', '--redact', '--no-banner', scanRoot],
      ...(spawnSync('git', ['rev-parse', '--verify', 'HEAD'], { cwd: root, stdio: 'ignore' }).status === 0
        ? [['git', '--redact', '--no-banner', '--log-opts=--all', root]] : [])]) {
      const result = spawnSync(executable, args, { cwd: root, stdio: 'inherit' });
      if (result.error) throw new Error('Gitleaks could not start. Install it or pass its executable path.');
      if (result.status !== 0) throw new Error('Gitleaks did not pass; review its redacted findings.');
    }
  } finally { rmSync(scanRoot, { recursive: true, force: true }); }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    if (args.length && (args.length !== 2 || args[0] !== '--gitleaks')) throw new Error('Usage: node scripts/check-public-repo.mjs [--gitleaks EXECUTABLE]');
    const root = git(process.cwd(), ['rev-parse', '--show-toplevel']).toString().trim();
    const result = auditRepository(root);
    if (result.findings.length) {
      for (const finding of result.findings) console.error(`${finding.rule}: ${safePath(finding.path, result.localValues)}${finding.line ? ':' + finding.line : ''} [${finding.origin}]`);
      throw new Error('Public repository check failed. Secret values are omitted.');
    }
    console.log(`PASS: ${result.paths.length} public candidate files; private paths and configured local values excluded.`);
    if (args.length) runGitleaks(root, resolveExecutable(args[1]), result.snapshots);
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}

function resolveExecutable(name) { return name.includes('/') || name.includes('\\') ? resolve(name) : name; }
