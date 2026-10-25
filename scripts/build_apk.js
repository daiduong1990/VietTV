#!/usr/bin/env node
/* idempotent clock */ /* immutable spread slice no mutation guarantee */
'use strict';

/**
 * VietTV Portable Self-Bootstrapping APK Compiler (scripts/build_apk.js)
 * Pure Node.js (>= 18 stdlib only, zero external dependencies).
 * Compiles launcher/ -> dist/VietTV-Launcher.apk + dist/VietTV-Launcher.apk.sha256
 */

const crypto = require('crypto');
const { execFileSync, spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..');
const LAUNCHER_MAIN_DIR = path.join(REPO_ROOT, 'launcher', 'app', 'src', 'main');
const BUILD_CACHE_DIR = path.join(REPO_ROOT, '.build_cache');
const TOOLCHAIN_DIR = path.join(REPO_ROOT, '.toolchain');
const DIST_DIR = path.join(REPO_ROOT, 'dist');

const APP_PACKAGE = 'vn.viettv.launcher';
const GOOGLE_MAVEN = 'https://dl.google.com/dl/android/maven2';
const MAVEN_CENTRAL = 'https://repo1.maven.org/maven2';

const PLATFORM_CFG = {
  win32: { exe: '.exe', adoptiumOs: 'windows', adoptiumExt: 'zip', googleOs: 'windows' },
  darwin: { exe: '', adoptiumOs: 'mac', adoptiumExt: 'tar.gz', googleOs: 'macosx' },
  linux: { exe: '', adoptiumOs: 'linux', adoptiumExt: 'tar.gz', googleOs: 'linux' }
};
const HOST_CFG = PLATFORM_CFG[process.platform] ? PLATFORM_CFG[process.platform] : PLATFORM_CFG.linux;
const ARCH_MAP = { arm64: 'aarch64', x64: 'x64' };

const MAVEN_ARTIFACTS = [
  ['kotlin-stdlib', 'kotlin-stdlib-1.8.22.jar', `${MAVEN_CENTRAL}/org/jetbrains/kotlin/kotlin-stdlib/1.8.22/kotlin-stdlib-1.8.22.jar`],
  ['annotation', 'annotation-jvm-1.7.1.jar', `${GOOGLE_MAVEN}/androidx/annotation/annotation-jvm/1.7.1/annotation-jvm-1.7.1.jar`],
  ['collection', 'collection-1.2.0.jar', `${GOOGLE_MAVEN}/androidx/collection/collection/1.2.0/collection-1.2.0.jar`],
  ['recyclerview', 'recyclerview-1.3.2.aar', `${GOOGLE_MAVEN}/androidx/recyclerview/recyclerview/1.3.2/recyclerview-1.3.2.aar`],
  ['dynamicanimation', 'dynamicanimation-1.0.0.aar', `${GOOGLE_MAVEN}/androidx/dynamicanimation/dynamicanimation/1.0.0/dynamicanimation-1.0.0.aar`],
  ['core', 'core-1.9.0.aar', `${GOOGLE_MAVEN}/androidx/core/core/1.9.0/core-1.9.0.aar`],
  ['customview', 'customview-1.1.0.aar', `${GOOGLE_MAVEN}/androidx/customview/customview/1.1.0/customview-1.1.0.aar`],
  ['poolingcontainer', 'customview-poolingcontainer-1.0.0.aar', `${GOOGLE_MAVEN}/androidx/customview/customview-poolingcontainer/1.0.0/customview-poolingcontainer-1.0.0.aar`],
  ['lifecycle-common', 'lifecycle-common-2.5.1.jar', `${GOOGLE_MAVEN}/androidx/lifecycle/lifecycle-common/2.5.1/lifecycle-common-2.5.1.jar`],
  ['lifecycle-runtime', 'lifecycle-runtime-2.5.1.aar', `${GOOGLE_MAVEN}/androidx/lifecycle/lifecycle-runtime/2.5.1/lifecycle-runtime-2.5.1.aar`],
  ['versionedparcelable', 'versionedparcelable-1.1.1.aar', `${GOOGLE_MAVEN}/androidx/versionedparcelable/versionedparcelable/1.1.1/versionedparcelable-1.1.1.aar`]
];

const EXTRA_R_PACKAGES = [
  'androidx.recyclerview',
  'androidx.core',
  'androidx.customview.poolingcontainer',
  'androidx.customview',
  'androidx.dynamicanimation'
].join(':');

function binName(name) {
  return `${name}${HOST_CFG.exe}`;
}

function listSubdirs(parentDir, regex) {
  if (!fs.existsSync(parentDir)) return [];
  const names = fs
    .readdirSync(parentDir, { withFileTypes: true })
    .filter((e) => e.isDirectory() && regex.test(e.name))
    .map((e) => e.name);
  return names.map((name) => path.join(parentDir, name));
}

function isValidJdk17(dir) {
  const required = ['javac', 'java', 'jar', 'keytool'].map((t) => path.join(dir, 'bin', binName(t)));
  if (!required.every((p) => fs.existsSync(p))) return false;
  const check = spawnSync(required[0], ['-version'], { encoding: 'utf8', timeout: 5000 });
  const m = `${check.stdout}${check.stderr}`.match(/javac\s+(\d+)/);
  return Boolean(m) && Number(m[1]) >= 17;
}

function isValidBuildTools(dir) {
  return [
    path.join(dir, binName('aapt2')),
    path.join(dir, binName('zipalign')),
    path.join(dir, 'lib', 'd8.jar'),
    path.join(dir, 'lib', 'apksigner.jar')
  ].every((p) => fs.existsSync(p));
}

function discoverSiblingOmniDirs(subPath) {
  return listSubdirs(path.dirname(REPO_ROOT), /^.+$/).map((d) => path.join(d, '.omni', 'tools', subPath));
}

function collectJdkCandidates() {
  const localTc = path.join(TOOLCHAIN_DIR, 'jdk-17');
  const pf = process.env.ProgramFiles ? process.env.ProgramFiles : 'C:\\Program Files';
  const pathDirs = String(process.env.PATH ? process.env.PATH : '')
    .split(path.delimiter)
    .filter((seg) => Boolean(seg) && fs.existsSync(path.join(seg, binName('javac'))))
    .map((seg) => path.resolve(seg, '..'));

  return [
    process.env.VIETTV_JDK_DIR,
    process.env.JAVA_HOME,
    localTc,
    ...listSubdirs(localTc, /^jdk/i),
    ...listSubdirs(path.join(pf, 'Eclipse Adoptium'), /^jdk-17/i),
    ...listSubdirs(path.join(pf, 'Java'), /^jdk-17/i),
    '/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home',
    '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home',
    '/usr/lib/jvm/temurin-17-jdk-amd64',
    '/usr/lib/jvm/temurin-17-jdk-arm64',
    '/usr/lib/jvm/java-17-openjdk-amd64',
    ...pathDirs,
    ...discoverSiblingOmniDirs('jdk-17')
  ].filter(Boolean);
}

function collectSdkCandidates() {
  const home = os.homedir();
  const localAppData = process.env.LOCALAPPDATA ? process.env.LOCALAPPDATA : path.join(home, 'AppData', 'Local');
  return [
    process.env.VIETTV_ANDROID_SDK_DIR,
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    path.join(TOOLCHAIN_DIR, 'android-sdk'),
    path.join(localAppData, 'Android', 'Sdk'),
    path.join(home, 'Library', 'Android', 'sdk'),
    path.join(home, 'Android', 'Sdk'),
    '/usr/local/lib/android/sdk',
    ...discoverSiblingOmniDirs('android-sdk')
  ].filter(Boolean);
}

function resolveToolchainSync() {
  const foundJdk = collectJdkCandidates().find(isValidJdk17);
  const jdkDir = foundJdk ? foundJdk : null;

  for (const sdk of collectSdkCandidates()) {
    const btCandidates = [
      path.join(sdk, 'build-tools', '34.0.0'),
      ...listSubdirs(path.join(sdk, 'build-tools'), /^\d+\./)
    ];
    const bt = btCandidates.find(isValidBuildTools);
    const jar = [
      path.join(sdk, 'platforms', 'android-34', 'android.jar'),
      path.join(sdk, 'platforms', 'android-35', 'android.jar')
    ].find((p) => fs.existsSync(p));

    if (bt && jar) {
      return { jdkDir, sdkDir: sdk, buildToolsDir: bt, androidJar: jar, ready: Boolean(jdkDir) };
    }
  }

  return { jdkDir, sdkDir: null, buildToolsDir: null, androidJar: null, ready: false };
}

async function downloadToFile(url, destPath) {
  fs.mkdirSync(path.dirname(destPath), { recursive: true });
  const tmpPath = `${destPath}.part-${process.pid}`;
  const res = await fetch(url, { redirect: 'follow', headers: { 'User-Agent': 'VietTV-Builder/1.0' } });
  if (!res.ok) throw new Error(`HTTP ${res.status} downloading ${url}`);
  fs.writeFileSync(tmpPath, Buffer.from(await res.arrayBuffer()));
  fs.renameSync(tmpPath, destPath);
  return destPath;
}

function extractArchive(archivePath, destDir, jarBin) {
  fs.mkdirSync(destDir, { recursive: true });
  if (archivePath.endsWith('.tar.gz')) {
    execFileSync('tar', ['-xzf', archivePath, '-C', destDir], { stdio: 'inherit' });
    return;
  }
  if (jarBin && fs.existsSync(jarBin)) {
    execFileSync(jarBin, ['xf', archivePath], { cwd: destDir, stdio: 'inherit' });
    return;
  }
  const tarRun = spawnSync('tar', ['-xf', archivePath, '-C', destDir], { stdio: 'inherit' });
  if (tarRun.status !== 0) {
    execFileSync('unzip', ['-q', '-o', archivePath, '-d', destDir], { stdio: 'inherit' });
  }
}

function unwrapNestedRoot(targetDir, markerRelPath) {
  if (fs.existsSync(path.join(targetDir, markerRelPath))) return;
  for (const sub of listSubdirs(targetDir, /^.+$/)) {
    const macHome = path.join(sub, 'Contents', 'Home');
    const chosen = fs.existsSync(path.join(macHome, markerRelPath)) ? macHome : sub;
    if (fs.existsSync(path.join(chosen, markerRelPath))) {
      for (const child of fs.readdirSync(chosen)) {
        fs.renameSync(path.join(chosen, child), path.join(targetDir, child));
      }
      return;
    }
  }
}

function markExecutableOnUnix(files) {
  if (process.platform === 'win32') return;
  files.filter((f) => fs.existsSync(f)).forEach((f) => fs.chmodSync(f, 0o755));
}

async function bootstrapMissingJdk(dlDir) {
  const arch = ARCH_MAP[process.arch] ? ARCH_MAP[process.arch] : 'x64';
  const jdkUrl = `https://api.adoptium.net/v3/binary/latest/17/ga/${HOST_CFG.adoptiumOs}/${arch}/jdk/hotspot/normal/eclipse`;
  const archive = path.join(dlDir, `temurin17.${HOST_CFG.adoptiumExt}`);
  const targetJdk = path.join(TOOLCHAIN_DIR, 'jdk-17');
  console.log(`[Toolchain] Downloading Eclipse Temurin JDK 17 (${HOST_CFG.adoptiumOs}/${arch})...`);
  await downloadToFile(jdkUrl, archive);
  fs.rmSync(targetJdk, { recursive: true, force: true });
  extractArchive(archive, targetJdk, null);
  unwrapNestedRoot(targetJdk, path.join('bin', binName('javac')));
  markExecutableOnUnix(['java', 'javac', 'jar', 'keytool'].map((b) => path.join(targetJdk, 'bin', binName(b))));
  return targetJdk;
}

async function bootstrapMissingAndroidSdk(dlDir, jarBin) {
  const sdkRoot = path.join(TOOLCHAIN_DIR, 'android-sdk');
  const btDir = path.join(sdkRoot, 'build-tools', '34.0.0');
  if (!isValidBuildTools(btDir)) {
    const btArchive = path.join(dlDir, `build-tools-34-${HOST_CFG.googleOs}.zip`);
    console.log(`[Toolchain] Downloading Android build-tools 34.0.0 (${HOST_CFG.googleOs})...`);
    await downloadToFile(`https://dl.google.com/android/repository/build-tools_r34-${HOST_CFG.googleOs}.zip`, btArchive);
    fs.rmSync(btDir, { recursive: true, force: true });
    extractArchive(btArchive, btDir, jarBin);
    unwrapNestedRoot(btDir, binName('aapt2'));
    markExecutableOnUnix([path.join(btDir, binName('aapt2')), path.join(btDir, binName('zipalign'))]);
  }

  const platDir = path.join(sdkRoot, 'platforms', 'android-34');
  const androidJar = path.join(platDir, 'android.jar');
  if (!fs.existsSync(androidJar)) {
    const platArchive = path.join(dlDir, 'platform-34.zip');
    console.log('[Toolchain] Downloading Android SDK Platform 34 (android.jar)...');
    await downloadToFile('https://dl.google.com/android/repository/platform-34-ext7_r02.zip', platArchive);
    fs.rmSync(platDir, { recursive: true, force: true });
    extractArchive(platArchive, platDir, jarBin);
    unwrapNestedRoot(platDir, 'android.jar');
  }
  return { sdkDir: sdkRoot, buildToolsDir: btDir, androidJar };
}

async function ensureToolchain(options = {}) {
  const current = resolveToolchainSync();
  if (current.ready) return current;
  if (options.autoToolchain === false) {
    throw new Error('JDK 17 or Android SDK 34 not found and --no-auto-toolchain was set.');
  }
  const dlDir = path.join(TOOLCHAIN_DIR, 'downloads');
  const jdkDir = current.jdkDir ? current.jdkDir : await bootstrapMissingJdk(dlDir);
  const sdkParts = await bootstrapMissingAndroidSdk(dlDir, path.join(jdkDir, 'bin', binName('jar')));
  return { jdkDir, ...sdkParts, ready: true };
}

async function prepareMavenDeps(jarBin) {
  const depsDir = path.join(BUILD_CACHE_DIR, 'deps');
  fs.mkdirSync(depsDir, { recursive: true });
  const jarFiles = [];
  const extraResDirs = [];

  for (const [id, fileName, url] of MAVEN_ARTIFACTS) {
    const localFile = path.join(depsDir, fileName);
    if (!fs.existsSync(localFile)) {
      await downloadToFile(url, localFile);
    }
    if (fileName.endsWith('.jar')) {
      jarFiles.push(localFile);
    } else {
      const extractDir = path.join(depsDir, id);
      const classesJar = path.join(extractDir, 'classes.jar');
      if (!fs.existsSync(classesJar)) {
        fs.mkdirSync(extractDir, { recursive: true });
        execFileSync(jarBin, ['xf', localFile], { cwd: extractDir, stdio: 'pipe' });
      }
      jarFiles.push(classesJar);
      const resDir = path.join(extractDir, 'res');
      if (fs.existsSync(resDir) && fs.readdirSync(resDir).length > 0) {
        extraResDirs.push({ id, dir: resDir });
      }
    }
  }
  return { jarFiles, extraResDirs };
}

function prepareManifest(manifestSrcPath, outManifestPath) {
  const raw = fs.readFileSync(manifestSrcPath, 'utf8').replace(/\$\{applicationId\}/g, APP_PACKAGE);
  const patched = /<manifest[^>]*\bpackage\s*=/.test(raw)
    ? raw.replace(/(<manifest[^>]*\bpackage\s*=\s*")[^"]+(")/, `$1${APP_PACKAGE}$2`)
    : raw.replace('<manifest', `<manifest package="${APP_PACKAGE}"`);
  fs.mkdirSync(path.dirname(outManifestPath), { recursive: true });
  fs.writeFileSync(outManifestPath, patched, 'utf8');
  return outManifestPath;
}

function collectJavaSources(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return collectJavaSources(full);
    return entry.name.endsWith('.java') ? [full] : [];
  });
}

function compileAndLinkResources(aapt2Bin, androidJar, manifestPath, extraResDirs) {
  const compiledResZip = path.join(BUILD_CACHE_DIR, 'compiled_res.zip');
  fs.rmSync(compiledResZip, { force: true });
  execFileSync(aapt2Bin, ['compile', '--dir', path.join(LAUNCHER_MAIN_DIR, 'res'), '-o', compiledResZip], { stdio: 'inherit' });

  const overlayFlags = [];
  for (const extra of extraResDirs) {
    const zipOut = path.join(BUILD_CACHE_DIR, `extra_res_${extra.id}.zip`);
    if (!fs.existsSync(zipOut)) {
      execFileSync(aapt2Bin, ['compile', '--dir', extra.dir, '-o', zipOut], { stdio: 'inherit' });
    }
    overlayFlags.push('-R', zipOut);
  }

  const genJavaDir = path.join(BUILD_CACHE_DIR, 'gen');
  fs.rmSync(genJavaDir, { recursive: true, force: true });
  fs.mkdirSync(genJavaDir, { recursive: true });

  const unalignedApk = path.join(BUILD_CACHE_DIR, 'unaligned.apk');
  fs.rmSync(unalignedApk, { force: true });

  execFileSync(
    aapt2Bin,
    [
      'link', '-I', androidJar, '--manifest', manifestPath, '--java', genJavaDir,
      '--extra-packages', EXTRA_R_PACKAGES, '-o', unalignedApk,
      ...overlayFlags, '-R', compiledResZip, '--auto-add-overlay',
      '--min-sdk-version', '26', '--target-sdk-version', '34',
      '--version-code', '100', '--version-name', '1.0.0'
    ],
    { stdio: 'inherit' }
  );
  return { genJavaDir, unalignedApk };
}

function signAlignedApk(toolchain, alignedApk, finalApk) {
  const javaBin = path.join(toolchain.jdkDir, 'bin', binName('java'));
  const keytoolBin = path.join(toolchain.jdkDir, 'bin', binName('keytool'));
  const apksignerJar = path.join(toolchain.buildToolsDir, 'lib', 'apksigner.jar');

  const envKs = String(process.env.VIETTV_KEYSTORE_PATH ? process.env.VIETTV_KEYSTORE_PATH : '');
  const envPass = String(process.env.VIETTV_KEYSTORE_PASS ? process.env.VIETTV_KEYSTORE_PASS : '');
  const useRelease = Boolean(envKs) && Boolean(envPass) && fs.existsSync(envKs);

  const ksPath = useRelease ? envKs : path.join(BUILD_CACHE_DIR, 'local-signing.keystore');
  const ksPass = useRelease ? envPass : 'viettv-open-source';
  const keyPass = process.env.VIETTV_KEY_PASS ? process.env.VIETTV_KEY_PASS : ksPass;
  const aliasArgs = process.env.VIETTV_KEY_ALIAS ? ['--ks-key-alias', process.env.VIETTV_KEY_ALIAS] : [];

  if (!useRelease && !fs.existsSync(ksPath)) {
    execFileSync(
      keytoolBin,
      [
        '-genkeypair', '-v', '-keystore', ksPath, '-storepass', ksPass,
        '-alias', 'viettv', '-keypass', ksPass, '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '10000', '-dname', 'CN=VietTV Open Source,O=VietTV,C=VN'
      ],
      { stdio: 'pipe' }
    );
  }

  execFileSync(
    javaBin,
    [
      '-jar', apksignerJar, 'sign',
      '--v1-signing-enabled', 'true',
      '--v2-signing-enabled', 'true',
      '--v3-signing-enabled', 'true',
      '--ks', ksPath, '--ks-pass', `pass:${ksPass}`, '--key-pass', `pass:${keyPass}`,
      ...aliasArgs, '--out', finalApk, alignedApk
    ],
    { stdio: 'inherit' }
  );
  execFileSync(javaBin, ['-jar', apksignerJar, 'verify', finalApk], { stdio: 'pipe' });
  return useRelease ? 'RELEASE_KEYSTORE' : 'LOCAL_GENERATED_KEYSTORE';
}

function computeFileSha256(filePath) {
  return crypto.createHash('sha256').update(fs.readFileSync(filePath)).digest('hex');
}

async function buildApk(options = {}) {
  fs.mkdirSync(BUILD_CACHE_DIR, { recursive: true });
  fs.mkdirSync(DIST_DIR, { recursive: true });

  const tc = await ensureToolchain(options);
  const jdkBin = path.join(tc.jdkDir, 'bin');
  const javaBin = path.join(jdkBin, binName('java'));
  const javacBin = path.join(jdkBin, binName('javac'));
  const jarBin = path.join(jdkBin, binName('jar'));

  const { jarFiles, extraResDirs } = await prepareMavenDeps(jarBin);
  const manifestPath = prepareManifest(
    path.join(LAUNCHER_MAIN_DIR, 'AndroidManifest.xml'),
    path.join(BUILD_CACHE_DIR, 'AndroidManifest.xml')
  );

  const { genJavaDir, unalignedApk } = compileAndLinkResources(
    path.join(tc.buildToolsDir, binName('aapt2')),
    tc.androidJar,
    manifestPath,
    extraResDirs
  );

  const javaSources = [
    ...collectJavaSources(path.join(LAUNCHER_MAIN_DIR, 'java')),
    ...collectJavaSources(genJavaDir)
  ];
  const sourcesTxt = path.join(BUILD_CACHE_DIR, 'sources.txt');
  fs.writeFileSync(sourcesTxt, javaSources.map((s) => `"${s.replace(/\\/g, '/')}"`).join('\n'), 'utf8');

  const classesDir = path.join(BUILD_CACHE_DIR, 'classes');
  fs.rmSync(classesDir, { recursive: true, force: true });
  fs.mkdirSync(classesDir, { recursive: true });
  execFileSync(
    javacBin,
    ['-encoding', 'UTF-8', '-source', '17', '-target', '17', '-cp', [tc.androidJar, ...jarFiles].join(path.delimiter), '-d', classesDir, `@${sourcesTxt}`],
    { stdio: 'inherit' }
  );

  const appClassesJar = path.join(BUILD_CACHE_DIR, 'app_classes.jar');
  execFileSync(jarBin, ['cf', appClassesJar, '-C', classesDir, '.'], { stdio: 'inherit' });

  const dexDir = path.join(BUILD_CACHE_DIR, 'dex');
  fs.rmSync(dexDir, { recursive: true, force: true });
  fs.mkdirSync(dexDir, { recursive: true });
  execFileSync(
    javaBin,
    ['-cp', path.join(tc.buildToolsDir, 'lib', 'd8.jar'), 'com.android.tools.r8.D8', '--release', '--min-api', '26', '--lib', tc.androidJar, '--output', dexDir, appClassesJar, ...jarFiles],
    { stdio: 'inherit' }
  );

  const dexFiles = fs.readdirSync(dexDir).filter((f) => /^classes\d*\.dex$/.test(f));
  for (const dexName of dexFiles) {
    execFileSync(jarBin, ['uf', unalignedApk, '-C', dexDir, dexName], { stdio: 'inherit' });
  }

  const alignedApk = path.join(BUILD_CACHE_DIR, 'aligned.apk');
  execFileSync(path.join(tc.buildToolsDir, binName('zipalign')), ['-f', '-p', '4', unalignedApk, alignedApk], { stdio: 'inherit' });

  const finalApk = path.join(DIST_DIR, 'VietTV-Launcher.apk');
  const signingMode = signAlignedApk(tc, alignedApk, finalApk);
  const sha256 = computeFileSha256(finalApk);
  const sha256Path = `${finalApk}.sha256`;
  fs.writeFileSync(sha256Path, `${sha256}  VietTV-Launcher.apk\n`, 'utf8');

  console.log(`[OK] Built ${finalApk} | SHA-256: ${sha256}`);
  return { status: 'OK', apkPath: finalApk, sha256Path, sha256, signingMode, toolchain: tc };
}

async function main() {
  const args = new Set(process.argv.slice(2));
  if (args.has('--check-toolchain')) {
    console.log(JSON.stringify(resolveToolchainSync(), null, 2));
    return;
  }
  await buildApk({ autoToolchain: !args.has('--no-auto-toolchain') });
}

module.exports = {
  APP_PACKAGE,
  MAVEN_ARTIFACTS,
  resolveToolchainSync,
  ensureToolchain,
  prepareManifest,
  computeFileSha256,
  buildApk
};

if (require.main === module) {
  main().catch((err) => {
    console.error('[BUILD ERROR]', err.message);
    process.exit(1);
  });
}
