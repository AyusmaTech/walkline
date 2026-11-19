import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { auditRepository, isPrivatePath } from './check-public-repo.mjs';

function repository(t) {
  const root = mkdtempSync(join(tmpdir(), 'walkline-audit-test-'));
  execFileSync('git', ['init', '--quiet'], { cwd: root });
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const write = (path, value) => { const target = join(root, path); mkdirSync(dirname(target), { recursive: true }); writeFileSync(target, value); };
  return { root, write, git: (...args) => execFileSync('git', args, { cwd: root }) };
}

test('ignored private configuration stays outside public candidates', t => {
  const { root, write } = repository(t);
  write('.gitignore', '.env\n.local/\nartifacts/\n');
  write('.env', 'private configuration'); write('.local/notes.txt', 'internal notes'); write('artifacts/app.apk', 'private build');
  write('.env.example', 'NAV_API_TOKEN=\n'); write('README.md', 'Public documentation');
  const result = auditRepository(root);
  assert.deepEqual(result.findings, []);
  assert.deepEqual(result.paths.sort(), ['.env.example', '.gitignore', 'README.md']);
});

test('a force-added ignored file is rejected even with innocent content', t => {
  const { root, write, git } = repository(t);
  write('.gitignore', '.env\n'); write('.env', 'harmless'); git('add', '-f', '.env');
  assert.ok(auditRepository(root).findings.some(f => f.path === '.env' && f.rule === 'private-or-generated-path'));
});

test('staged secrets remain detectable after the working copy is cleaned', t => {
  const { root, write, git } = repository(t);
  write('config.txt', 'AI' + 'za' + 'A'.repeat(35)); git('add', 'config.txt'); write('config.txt', 'clean');
  const result = auditRepository(root);
  assert.ok(result.findings.some(f => f.rule === 'google-api-key' && f.origin === 'index'));
  assert.ok(result.snapshots.some(s => s.origin === 'worktree' && s.data.toString() === 'clean'));
});

test('actual local token copies are rejected without returning the value', t => {
  const { root, write } = repository(t);
  const value = 'local-' + 'sensitive'.repeat(5);
  write('.gitignore', '.env\n'); write('backend/.env', 'NAV_API_TOKEN=' + value); write('notes.txt', 'prefix ' + value);
  const result = auditRepository(root);
  assert.ok(result.findings.some(f => f.path === 'notes.txt' && f.rule === 'local-secret-or-setup-identifier'));
  assert.ok(!JSON.stringify(result.findings).includes(value));
});

test('local cloud identifiers and personal machine paths are rejected', t => {
  const { root, write } = repository(t);
  write('.gitignore', '.local/\n'); write('.local/google-cloud-setup.json', JSON.stringify({ project: 'private-' + 'project-id' }));
  write('notes.txt', ['private-' + 'project-id', '/' + 'Users/' + 'someone/project'].join('\n'));
  const rules = auditRepository(root).findings.map(f => f.rule);
  assert.ok(rules.includes('local-secret-or-setup-identifier')); assert.ok(rules.includes('personal-machine-path'));
});

test('only configuration examples and the required wrapper JAR are allowed', () => {
  for (const path of ['backend/.env', 'android/local.properties', '.local/notes.md', '.local/.env.example', 'artifacts/local.properties.example', 'app.apk', 'debug.keystore', 'key.pem', 'other.jar', 'private-walk.gpx']) assert.equal(isPrivatePath(path), true, path);
  for (const path of ['backend/.env.example', 'android/local.properties.example', 'android/gradle/wrapper/gradle-wrapper.jar', 'README.md']) assert.equal(isPrivatePath(path), false, path);
});

test('the allowed wrapper JAR must still match its official checksum', t => {
  const { root, write } = repository(t);
  write('android/gradle/wrapper/gradle-wrapper.jar', Buffer.from('unverified binary'));
  assert.ok(auditRepository(root).findings.some(f => f.rule === 'gradle-wrapper-checksum'));
});
