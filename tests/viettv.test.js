/* idempotent clock */ /* immutable spread slice no mutation guarantee */
'use strict';

const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const viettv = require('../tools/viettv');
const apkBuilder = require('../scripts/build_apk');

const REPO_ROOT = path.resolve(__dirname, '..');
const IGNORED_DIRS = new Set(['.git', '.toolchain', '.build_cache', '.backups', 'dist', 'node_modules']);

// Split token assembly so tests/viettv.test.js itself contains zero literal forbidden strings
const FORBIDDEN_LITERALS = [
  ['QUl', '6Y'].join(''),
  ['40:E1', ':71'].join(''),
  ['50:E4', ':78'].join(''),
  ['78:53', ':33'].join(''),
  ['192.168', '.68.55'].join(''),
  ['Thu', 'Thuy'].join(''),
  ['Mr', 'Dx'].join(''),
  ['videomanager_', 'Backup'].join(''),
  ['Cook', 'ies'].join('')
];
const SECRET_REGEXES = [
  new RegExp(['AI', 'za'].join('')),
  new RegExp(`${['gs', 'k_'].join('')}[A-Za-z0-9]{20,}`)
];
const LEGACY_PKG_DECL = ['package com.gathin', '.tvhome;'].join('');

function walkFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    if (IGNORED_DIRS.has(entry.name)) return [];
    const full = path.join(dir, entry.name);
    return entry.isDirectory() ? walkFiles(full) : [full];
  });
}

test('1. Zero-PII & Zero-Secret Fortress Scan across entire repository', () => {
  const allFiles = walkFiles(REPO_ROOT);
  assert.ok(allFiles.length >= 20, 'Expected repository files to be scanned');

  const violations = [];
  for (const filePath of allFiles) {
    const rel = path.relative(REPO_ROOT, filePath);
    const content = fs.readFileSync(filePath).toString('latin1');
    for (const token of FORBIDDEN_LITERALS) {
      if (rel.includes(token) || content.includes(token)) {
        violations.push(`${rel}: matched forbidden token (${token.slice(0, 3)}...)`);
      }
    }
    for (const secretRe of SECRET_REGEXES) {
      if (secretRe.test(content)) {
        violations.push(`${rel}: matched forbidden API secret pattern (${secretRe.source})`);
      }
    }
  }
  assert.deepEqual(violations, [], `Zero-PII / Zero-Secret violations detected:\n${violations.join('\n')}`);
});

test('2. Launcher package namespace uses vn.viettv.launcher with zero legacy leaks', () => {
  const mainDir = path.join(REPO_ROOT, 'launcher', 'app', 'src', 'main');
  const mainFiles = walkFiles(mainDir);
  const legacyLeaks = mainFiles.filter((f) => fs.readFileSync(f, 'utf8').includes(LEGACY_PKG_DECL));
  assert.deepEqual(legacyLeaks, [], 'Expected 0 files with legacy package declaration');

  const viettvJavaFiles = mainFiles.filter(
    (f) => f.endsWith('.java') && fs.readFileSync(f, 'utf8').includes('package vn.viettv.launcher;')
  );
  assert.ok(viettvJavaFiles.length >= 25, 'Expected Java sources in vn.viettv.launcher package');
});

test('3. Multi-Brand Debloat Collision & Safety Invariant Gate', () => {
  const check = viettv.runSelfCheck();
  assert.equal(check.status, 'OK');
  assert.equal(check.syntaxValid, true);
  assert.equal(check.launcherPackage, 'vn.viettv.launcher');

  const protectedSet = new Set(viettv.UNIVERSAL_PROTECTED_PACKAGES);
  const expectedBrands = ['xiaomi', 'tcl', 'sony', 'casper_coocaa_skyworth', 'projector_cn', 'generic'];
  for (const brand of expectedBrands) {
    const profile = viettv.BRAND_PROFILES[brand];
    assert.ok(profile, `Missing brand profile: ${brand}`);
    const overlap = profile.bloatware.filter((pkg) => protectedSet.has(pkg));
    assert.deepEqual(overlap, [], `Brand ${brand} overlaps protected packages`);
  }

  assert.equal(viettv.detectBrandProfile({ manufacturer: 'Xiaomi', model: 'MiTV-MOOQ0' }).profileKey, 'xiaomi');
  assert.equal(viettv.detectBrandProfile({ manufacturer: 'Xiaomi', model: 'MiTV-MOOQ0' }).isMiTvModel, true);
  assert.equal(viettv.detectBrandProfile({ manufacturer: 'Xiaomi', model: 'MiBOX4' }).isMiTvModel, false);
  assert.equal(viettv.detectBrandProfile({ manufacturer: 'Sony', model: 'BRAVIA 4K VH2' }).profileKey, 'sony');
  assert.equal(viettv.detectBrandProfile({ manufacturer: 'TCL', model: 'BeyondTV4' }).profileKey, 'tcl');
  assert.equal(viettv.detectBrandProfile({ brand: 'Casper', model: '55UW6000' }).profileKey, 'casper_coocaa_skyworth');
  assert.equal(viettv.detectBrandProfile({ brand: 'Coocaa', model: '50S6G' }).profileKey, 'casper_coocaa_skyworth');
  assert.equal(viettv.detectBrandProfile({ manufacturer: 'XGIMI', model: 'Horizon' }).profileKey, 'projector_cn');
  assert.equal(viettv.detectBrandProfile({ manufacturer: 'Google', model: 'Chromecast' }).profileKey, 'generic');

  assert.equal(viettv.mergeAccessibilityServices('null'), 'vn.viettv.launcher/.HomeKeyService');
  assert.equal(
    viettv.mergeAccessibilityServices('flar2.homebutton/.a11y'),
    'flar2.homebutton/.a11y:vn.viettv.launcher/.HomeKeyService'
  );
  assert.equal(
    viettv.mergeAccessibilityServices('flar2.homebutton/.a11y:vn.viettv.launcher/.HomeKeyService'),
    'flar2.homebutton/.a11y:vn.viettv.launcher/.HomeKeyService'
  );
  assert.equal(viettv.shouldSetVietTvVoiceService('xiaomi', 'com.xiaomi.voicecontrol/.Service'), true);
  assert.equal(viettv.shouldSetVietTvVoiceService('xiaomi', 'com.google.android.katniss/.Service'), false);
  assert.equal(viettv.shouldSetVietTvVoiceService('xiaomi', 'com.k2fsa.sherpa.onnx/.Service'), false);
  assert.equal(viettv.shouldSetVietTvVoiceService('sony', 'null'), false);
});

test('4. Dynamic BLE MAC parser, Snapshot backup/restore & LAN TCP probe', async () => {
  const mockDumpsys = [
    'Bonded devices:',
    '  AA:BB:CC:11:22:33 [Xiaomi RC Voice BLE]',
    '  00:00:00:00:00:00 [Invalid]'
  ].join('\n');
  assert.deepEqual(viettv.extractPairedBleMacsFromDumpsys(mockDumpsys), ['AA:BB:CC:11:22:33']);

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'viettv-snap-'));
  const saved = viettv.savePreOptimizeSnapshot(tmpDir, '192.168.1.88:5555', {
    target: '192.168.1.88:5555',
    props: { model: 'BRAVIA' },
    brandProfile: 'sony',
    enabledPackagesBeforeOptimize: ['vn.viettv.launcher', 'com.sony.dtv.promos'],
    previousSettings: { defaultIme: 'com.google.android.inputmethod.latin' }
  });
  assert.ok(fs.existsSync(saved.snapshotPath));
  const loaded = viettv.loadDeviceSnapshot(tmpDir, '192.168.1.88:5555');
  assert.deepEqual(loaded.snapshot.enabledPackagesBeforeOptimize, ['vn.viettv.launcher', 'com.sony.dtv.promos']);
  fs.rmSync(tmpDir, { recursive: true, force: true });

  const server = net.createServer((sock) => sock.end());
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  const probeHit = await viettv.probeTcpPort('127.0.0.1', port, 300);
  server.close();
  assert.equal(probeHit.open, true);
});

test('5. JDK 17 JVM Verification Hook for Launcher Core Engines', (t) => {
  const tc = apkBuilder.resolveToolchainSync();
  if (!tc.ready) {
    t.skip('JDK 17 + android.jar not locally pre-installed; CI runs full JVM verification');
    return;
  }

  const javaDir = path.join(REPO_ROOT, 'launcher', 'app', 'src', 'main', 'java', 'vn', 'viettv', 'launcher');
  const profilerPath = path.join(javaDir, 'TvHardwareProfiler.java');
  const routerReady =
    fs.existsSync(profilerPath) &&
    fs.readFileSync(profilerPath, 'utf8').includes('resolveTvInputSwitchIntent');

  const coreNames = ['TelexEngine.java', 'TvHardwareProfiler.java', 'VoiceSttClient.java', 'LivePrices.java'];
  const targetNames = routerReady ? [...coreNames, 'TvVoiceCommandRouter.java'] : coreNames;
  const existingSources = targetNames.map((n) => path.join(javaDir, n)).filter((p) => fs.existsSync(p));

  const tmpJvm = fs.mkdtempSync(path.join(os.tmpdir(), 'viettv-jvm-'));
  const stubsPath = path.join(tmpJvm, 'JvmPeerStubs.java');
  fs.writeFileSync(
    stubsPath,
    [
      'package vn.viettv.launcher;',
      'final class Config {',
      '  static final String SETTINGS_NAME = "launcher_settings";',
      '  static final String FUEL_REGION_KEY = "fuel_region";',
      '}',
      'class MainActivity extends android.app.Activity {',
      '  static final String ACTION_HOME_KEY = "vn.viettv.launcher.HOME";',
      '}',
      'class LauncherSettingsActivity extends android.app.Activity {}'
    ].join('\n'),
    'utf8'
  );

  const harnessPath = path.join(tmpJvm, 'VietTvJvmSuite.java');
  fs.writeFileSync(
    harnessPath,
    [
      'package vn.viettv.launcher;',
      'import java.lang.reflect.*;',
      'public class VietTvJvmSuite {',
      '  public static void main(String[] args) throws Exception {',
      '    String w = TelexEngine.applyKey("viet", \'e\', true);',
      '    w = TelexEngine.applyKey(w, \'j\', true);',
      '    if (!"vi\\u1EC7t".equals(w)) throw new AssertionError("Telex failed: " + w);',
      '    if (!"SONY_BRAVIA".equals(TvHardwareProfiler.detectBrand("Sony", "Sony", "BRAVIA 4K").name())) {',
      '      throw new AssertionError("TvHardwareProfiler Sony detection failed");',
      '    }',
      '    byte[] wav = VoiceSttClient.wrapPcm16kMonoAsWav(new byte[]{1, 2, 3, 4});',
      '    if (wav.length != 48 || wav[0] != \'R\' || wav[8] != \'W\') throw new AssertionError("Bad WAV header");',
      '    String sampleHtml = "<tr><td>X\\u0103ng RON 95-III</td><td>21.500</td><td>21.930</td></tr>" +',
      '      "<tr><td>X\\u0103ng E5 RON 92-II</td><td>20.400</td><td>20.800</td></tr>" +',
      '      "<tr><td>D\\u1EA7u DO 0,05S-II</td><td>19.100</td><td>19.480</td></tr>";',
      '    int[] z1 = LivePrices.parseWebgiaPetrolimexHtml(sampleHtml, 1);',
      '    int[] z2 = LivePrices.parseWebgiaPetrolimexHtml(sampleHtml, 2);',
      '    if (z1[0] != 21500 || z2[0] != 21930 || z1[1] != 20400 || z2[1] != 20800) {',
      '      throw new AssertionError("Fuel Zone 1 vs Zone 2 mismatch");',
      '    }',
      '    System.out.println("JVM_SUITE_OK");',
      '  }',
      '}'
    ].join('\n'),
    'utf8'
  );

  const exe = process.platform === 'win32' ? '.exe' : '';
  const javac = path.join(tc.jdkDir, 'bin', `javac${exe}`);
  const java = path.join(tc.jdkDir, 'bin', `java${exe}`);

  const compileRes = spawnSync(
    javac,
    ['-encoding', 'UTF-8', '-source', '17', '-target', '17', '-cp', tc.androidJar, '-d', tmpJvm, stubsPath, ...existingSources, harnessPath],
    { encoding: 'utf8' }
  );
  assert.equal(compileRes.status, 0, `javac failed: ${compileRes.stderr}`);

  const runRes = spawnSync(
    java,
    ['-ea', '-cp', [tmpJvm, tc.androidJar].join(path.delimiter), 'vn.viettv.launcher.VietTvJvmSuite'],
    { encoding: 'utf8' }
  );
  fs.rmSync(tmpJvm, { recursive: true, force: true });
  assert.equal(runRes.status, 0, `JVM suite failed: ${runRes.stderr}`);
  assert.ok(runRes.stdout.includes('JVM_SUITE_OK'));
});
