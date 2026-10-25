#!/usr/bin/env node
/* idempotent clock */ /* immutable spread slice no mutation guarantee */
'use strict';

/**
 * VietTV Smart Multi-Brand TV Commander (tools/viettv.js)
 * Zero-PII, Zero-Secret, 100% Node.js >= 18 built-in stdlib.
 * Supports Android TV, Google TV, and Chinese ROM TVs (Xiaomi, TCL, Sony, Casper, Coocaa, Skyworth, Projectors & TV Boxes).
 */

const { execFileSync, spawnSync } = require('child_process');
const fs = require('fs');
const net = require('net');
const os = require('os');
const path = require('path');
const readline = require('readline');

const REPO_ROOT = path.resolve(__dirname, '..');
const TOOLCHAIN_DIR = path.join(REPO_ROOT, '.toolchain');
const BACKUPS_ROOT_DIR = path.join(REPO_ROOT, '.backups');
const DIST_APK_PATH = path.join(REPO_ROOT, 'dist', 'VietTV-Launcher.apk');

const HOST_MAP = {
  win32: { exe: '.exe', zipOs: 'windows' },
  darwin: { exe: '', zipOs: 'darwin' },
  linux: { exe: '', zipOs: 'linux' }
};
const HOST_CFG = HOST_MAP[process.platform] ? HOST_MAP[process.platform] : HOST_MAP.linux;

const LAUNCHER_PKG = 'vn.viettv.launcher';
const VN_TV_IME_ID = `${LAUNCHER_PKG}/.VnTvImeService`;
const VN_VOICE_RECOG_ID = `${LAUNCHER_PKG}/.VnRecognitionService`;
const VN_VOICE_ASSIST_ID = `${LAUNCHER_PKG}/.VoiceSearchActivity`;
const VN_HOME_KEY_SERVICE_ID = `${LAUNCHER_PKG}/.HomeKeyService`;
const VN_CLOCK_DREAM_ID = `${LAUNCHER_PKG}/.ClockDream`;

const SOGOU_TV_IME_PKG = 'com.sohu.inputmethod.sogou.tv';
const XIAOMI_OTA_UPDATER_PKG = 'com.xiaomi.mitv.upgrade';

const SHERPA_ONNX_VI_APKS = Object.freeze({
  'arm64-v8a':
    'https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/vad-asr-simulated-streaming/1.13.8/sherpa-onnx-1.13.8-arm64-v8a-simulated_streaming_asr-vi-zipformer_vi_30M_int8_2026_02_09.apk',
  'armeabi-v7a':
    'https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/vad-asr-simulated-streaming/1.13.8/sherpa-onnx-1.13.8-armeabi-v7a-simulated_streaming_asr-vi-zipformer_vi_30M_int8_2026_02_09.apk',
  x86_64:
    'https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/vad-asr-simulated-streaming/1.13.8/sherpa-onnx-1.13.8-x86_64-simulated_streaming_asr-vi-zipformer_vi_30M_int8_2026_02_09.apk'
});

const UNIVERSAL_PROTECTED_PACKAGES = Object.freeze([
  'vn.viettv.launcher',
  'org.smarttube.stable',
  'org.smarttube.beta',
  'com.liskovsoft.videomanager',
  'com.google.android.youtube.tv',
  'com.netflix.mediaclient',
  'vn.vtv.vtvgo',
  'vn.vtv.vtvgotv',
  'com.fptplay.ottbox',
  'tv.vieon.vienthonga',
  'com.tv360.tv',
  'com.android.systemui',
  'com.android.settings',
  'com.google.android.tv.remote.service',
  'com.google.android.tts',
  'com.google.android.katniss',
  'com.xiaomi.wakeupservice',
  'com.xiaomi.mi_connect_service',
  'com.mitv.fastconnect',
  'com.xiaomi.mitv.settings',
  'com.xiaomi.mitv.smartpanel',
  'com.xiaomi.mitv.tvplayer',
  'com.xiaomi.mitv.systemui',
  'com.xiaomi.mitv.tvmanager',
  'com.sony.dtv.tvx',
  'com.sony.dtv.braviasyncservice',
  'com.tcl.tv',
  'com.mediatek.tvinput'
]);

const CORE_STREAMING_AOT_PACKAGES = Object.freeze([
  'vn.viettv.launcher',
  'org.smarttube.stable',
  'org.smarttube.beta',
  'com.liskovsoft.videomanager',
  'com.google.android.youtube.tv',
  'com.netflix.mediaclient',
  'vn.vtv.vtvgo',
  'vn.vtv.vtvgotv',
  'com.fptplay.ottbox',
  'tv.vieon.vienthonga',
  'com.tv360.tv'
]);

const BRAND_PROFILES = Object.freeze({
  xiaomi: Object.freeze({
    id: 'xiaomi',
    label: 'Xiaomi MiTV / Redmi TV / Mi Box',
    bloatware: Object.freeze([
      'com.xiaodianshi.tv.yst',
      'com.xiaomi.mitv.smartshare',
      'com.xiaomi.mitv.tvpush.tvpushservice',
      'com.xiaomi.mitv.hyper.screensaver',
      'com.xiaomi.hyper_connect.tv',
      'com.xiaomi.miplay',
      'com.mitv.shoplugin',
      'com.xiaomi.mitv.karaoke.service',
      'com.xm.webcontent',
      'com.miui.hybrid.tv',
      'com.xiaomi.mitv.health',
      'com.xiaomi.mitv.healthdiagnosis',
      'com.xiaomi.screenrecorder',
      'com.mitv.toolhouse',
      'com.mitv.mivideoplayer',
      'com.xiaomi.wfdsinkhelperservice',
      'com.xiaomi.tvqs',
      'com.xiaomi.account',
      'com.xiaomi.account.auth',
      'com.xiaomi.mitv.mediaexplorer',
      'com.xiaomi.setupwizard',
      'com.mitv.cloudcontrol',
      'com.newtv.cboxtv',
      'com.hunantv.license',
      'com.xiaomi.voicecontrol',
      'com.xiaomi.tv.gallery',
      'com.mitv.gallery',
      'com.xiaomi.mitv.appstore',
      'com.xiaomi.smarthome.tv',
      'com.miui.tv.analytics',
      'com.xiaomi.mitv.shop',
      'com.duokan.videodaily',
      'com.xiaomi.gamecenter.sdk.service.mibox',
      'com.xiaomi.tweather'
    ]),
    conditionalImeBloat: SOGOU_TV_IME_PKG,
    otaPackage: XIAOMI_OTA_UPDATER_PKG,
    stockLauncherPackage: 'com.mitv.tvhome'
  }),
  tcl: Object.freeze({
    id: 'tcl',
    label: 'TCL / iFFALCON / Thomson Google TV & Android TV',
    bloatware: Object.freeze([
      'com.tcl.partnercustomizer',
      'com.tcl.messagebox',
      'com.tcl.usercenter',
      'com.tcl.bi',
      'com.tcl.notereminder',
      'com.tcl.gallery'
    ]),
    conditionalImeBloat: null,
    otaPackage: null,
    stockLauncherPackage: null
  }),
  sony: Object.freeze({
    id: 'sony',
    label: 'Sony Bravia Android TV / Google TV',
    bloatware: Object.freeze([
      'com.sony.dtv.promos',
      'com.sony.dtv.sonyshelf',
      'com.sony.dtv.demosystemsupport',
      'com.sony.dtv.b2b.hotellanguage',
      'com.sony.dtv.smartshelf'
    ]),
    conditionalImeBloat: null,
    otaPackage: null,
    stockLauncherPackage: null
  }),
  casper_coocaa_skyworth: Object.freeze({
    id: 'casper_coocaa_skyworth',
    label: 'Casper / Coocaa / Skyworth / Changhong / Hisense TV',
    bloatware: Object.freeze([
      'com.tianci.ad',
      'com.coocaa.activecenter',
      'com.skyworthdigital.pic',
      'com.tianci.movieplatform',
      'com.coocaa.mall',
      'com.tianci.push'
    ]),
    conditionalImeBloat: null,
    otaPackage: null,
    stockLauncherPackage: null
  }),
  projector_cn: Object.freeze({
    id: 'projector_cn',
    label: 'Máy chiếu Android (XGIMI / JMGO / Dangbei / Wanbo / HY300)',
    bloatware: Object.freeze([
      'com.dangbei.tvlauncher.ad',
      'com.xgimi.adservice',
      'com.jmgo.ad',
      'com.fengmi.ad',
      'com.gitvdemo.video',
      'com.cibn.tv'
    ]),
    conditionalImeBloat: SOGOU_TV_IME_PKG,
    otaPackage: null,
    stockLauncherPackage: null
  }),
  generic: Object.freeze({
    id: 'generic',
    label: 'Android TV / Google TV / TV Box Quốc tế',
    bloatware: Object.freeze([]),
    conditionalImeBloat: null,
    otaPackage: null,
    stockLauncherPackage: null
  })
});

const BRAND_MATCHERS = [
  ['xiaomi', /\b(xiaomi|redmi|mitv|mibox|aquaman|freeguy)\b|mitv-/i],
  ['sony', /\b(sony|bravia)\b/i],
  ['tcl', /\b(tcl|iffalcon|thomson|beyondtv)\b/i],
  ['casper_coocaa_skyworth', /\b(casper|coocaa|skyworth|tianci|changhong|konka|hisense)\b/i],
  ['projector_cn', /\b(xgimi|jmgo|wanbo|dangbei|formovie|fengmi|hy300|hy320|magcubic|projector)\b/i]
];

const SAFE_PACKAGE_RE = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;
const SAFE_COMPONENT_RE = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+\/[A-Za-z0-9_.$]+$/;

function cleanStr(val) {
  return String(val === undefined || val === null ? '' : val).trim();
}

function isSafePackageName(pkg) {
  return typeof pkg === 'string' && SAFE_PACKAGE_RE.test(pkg);
}

function isSafeComponentId(id) {
  return typeof id === 'string' && SAFE_COMPONENT_RE.test(id);
}

function sanitizeDeviceId(serialOrIp) {
  const cleaned = cleanStr(serialOrIp).replace(/[^a-zA-Z0-9._-]+/g, '_').replace(/^_+|_+$/g, '');
  return cleaned.length > 0 ? cleaned : 'unknown_tv';
}

function normalizeTargetIp(raw) {
  const trimmed = cleanStr(raw);
  return /^\d{1,3}(?:\.\d{1,3}){3}$/.test(trimmed) ? `${trimmed}:5555` : trimmed;
}

function detectBrandProfile(props = {}) {
  const info = {
    manufacturer: cleanStr(props.manufacturer),
    brand: cleanStr(props.brand),
    model: cleanStr(props.model),
    sdk: Number(props.sdk ? props.sdk : 0),
    abi: cleanStr(props.abi)
  };
  const haystack = `${info.manufacturer} ${info.brand} ${info.model}`;
  const matched = BRAND_MATCHERS.find(([, re]) => re.test(haystack));
  const profileKey = matched ? matched[0] : 'generic';
  return {
    profileKey,
    profile: BRAND_PROFILES[profileKey],
    isMiTvModel: /^MiTV-/i.test(info.model),
    props: info
  };
}

function extractPairedBleMacsFromDumpsys(dumpsysText) {
  const lines = cleanStr(dumpsysText).split(/\r?\n/);
  const macRe = /\b([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\b/g;
  const invalid = new Set(['00:00:00:00:00:00', 'FF:FF:FF:FF:FF:FF']);
  const remoteLines = lines.filter((l) => /xiaomi\s*rc|mi\s*rc|remote|voice|ble|hid|\brc\b/i.test(l));
  const sourceText = remoteLines.length > 0 ? remoteLines.join('\n') : lines.join('\n');
  const rawMatches = sourceText.match(macRe);
  const valid = rawMatches ? rawMatches.map((m) => m.toUpperCase()).filter((m) => !invalid.has(m)) : [];
  return [...new Set(valid)];
}

function isPrivateLanIpv4(ip) {
  return /^(?:10\.\d{1,3}|192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}$/.test(cleanStr(ip));
}

function probeTcpPort(host, port = 5555, timeoutMs = 350) {
  return new Promise((resolve) => {
    let done = false;
    const t0 = Date.now();
    const socket = new net.Socket();
    const finish = (open) => {
      if (done) return;
      done = true;
      socket.destroy();
      resolve({ open, latencyMs: Date.now() - t0 });
    };
    socket.setTimeout(timeoutMs);
    socket.once('connect', () => finish(true));
    socket.once('timeout', () => finish(false));
    socket.once('error', () => finish(false));
    socket.connect(port, host);
  });
}

async function discoverTvsOnLan(options = {}) {
  const port = options.port ? Number(options.port) : 5555;
  const timeoutMs = options.timeoutMs ? Number(options.timeoutMs) : 350;
  const batchSize = options.batchSize ? Number(options.batchSize) : 64;
  const ifaces = options.networkInterfaces ? options.networkInterfaces : os.networkInterfaces();
  const subnets = new Map();

  for (const [ifName, addrs] of Object.entries(ifaces)) {
    const list = Array.isArray(addrs) ? addrs : [];
    list
      .filter((a) => !a.internal && (a.family === 'IPv4' || a.family === 4) && isPrivateLanIpv4(a.address))
      .forEach((a) => {
        const prefix = a.address.split('.').slice(0, 3).join('.');
        subnets.set(prefix, { prefix, selfIp: a.address, interfaceName: ifName });
      });
  }

  const hosts = Array.from({ length: 254 }, (_, idx) => idx + 1);
  const probeQueue = [...subnets.values()].flatMap(({ prefix, selfIp, interfaceName }) =>
    hosts
      .map((h) => `${prefix}.${h}`)
      .filter((ip) => ip !== selfIp)
      .map((ip) => ({ ip, subnet: `${prefix}.0/24`, interfaceName }))
  );

  const discovered = [];
  for (let i = 0; i < probeQueue.length; i += batchSize) {
    const batch = probeQueue.slice(i, i + batchSize);
    const results = await Promise.all(
      batch.map(async (item) => {
        const res = await probeTcpPort(item.ip, port, timeoutMs);
        return res.open ? { ...item, port, target: `${item.ip}:${port}`, latencyMs: res.latencyMs } : null;
      })
    );
    results.filter(Boolean).forEach((hit) => discovered.push(hit));
  }
  return discovered;
}

async function downloadToFile(url, destPath) {
  fs.mkdirSync(path.dirname(destPath), { recursive: true });
  const tmpPath = `${destPath}.part-${process.pid}`;
  const res = await fetch(url, { redirect: 'follow', headers: { 'User-Agent': 'VietTV-Commander/1.0' } });
  if (!res.ok) throw new Error(`HTTP ${res.status} downloading ${url}`);
  fs.writeFileSync(tmpPath, Buffer.from(await res.arrayBuffer()));
  fs.renameSync(tmpPath, destPath);
  return destPath;
}

function findAdbSync() {
  const adbExe = `adb${HOST_CFG.exe}`;
  const home = os.homedir();
  const localAppData = process.env.LOCALAPPDATA ? process.env.LOCALAPPDATA : path.join(home, 'AppData', 'Local');
  const pathSegs = cleanStr(process.env.PATH).split(path.delimiter).filter(Boolean).map((s) => path.join(s, adbExe));
  const parent = path.dirname(REPO_ROOT);
  const sibDirs = fs.existsSync(parent)
    ? fs
        .readdirSync(parent, { withFileTypes: true })
        .filter((e) => e.isDirectory())
        .map((e) => path.join(parent, e.name, '.omni', 'tools', 'platform-tools', adbExe))
    : [];

  const candidates = [
    process.env.VIETTV_ADB_PATH,
    path.join(TOOLCHAIN_DIR, 'platform-tools', adbExe),
    ...[process.env.VIETTV_ANDROID_SDK_DIR, process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT]
      .filter(Boolean)
      .map((s) => path.join(s, 'platform-tools', adbExe)),
    ...pathSegs,
    path.join(localAppData, 'Android', 'Sdk', 'platform-tools', adbExe),
    path.join(home, 'Library', 'Android', 'sdk', 'platform-tools', adbExe),
    path.join(home, 'Android', 'Sdk', 'platform-tools', adbExe),
    '/usr/local/lib/android/sdk/platform-tools/adb',
    ...sibDirs
  ].filter(Boolean);

  const hit = candidates.find((p) => fs.existsSync(p));
  return hit ? hit : null;
}

async function ensureAdbBinary() {
  const existing = findAdbSync();
  if (existing) return existing;

  const url = `https://dl.google.com/android/repository/platform-tools-latest-${HOST_CFG.zipOs}.zip`;
  const zipDest = path.join(TOOLCHAIN_DIR, 'downloads', `platform-tools-${HOST_CFG.zipOs}.zip`);
  console.log(`[ADB] Đang tự động tải Google Platform-Tools (${HOST_CFG.zipOs})...`);
  await downloadToFile(url, zipDest);
  fs.mkdirSync(TOOLCHAIN_DIR, { recursive: true });
  const tarRun = spawnSync('tar', ['-xf', zipDest, '-C', TOOLCHAIN_DIR], { stdio: 'inherit', timeout: 30000 });
  if (tarRun.status !== 0) {
    execFileSync('unzip', ['-q', '-o', zipDest, '-d', TOOLCHAIN_DIR], { stdio: 'inherit', timeout: 30000 });
  }
  const adbPath = path.join(TOOLCHAIN_DIR, 'platform-tools', `adb${HOST_CFG.exe}`);
  if (process.platform !== 'win32' && fs.existsSync(adbPath)) fs.chmodSync(adbPath, 0o755);
  return adbPath;
}

function adbTry(adbBin, target, args, timeoutMs = 8000) {
  const fullArgs = target ? ['-s', target, ...args] : args;
  const res = spawnSync(adbBin, fullArgs, { encoding: 'utf8', timeout: timeoutMs });
  return {
    ok: res.status === 0,
    stdout: cleanStr(res.stdout),
    stderr: cleanStr(res.stderr)
  };
}

function adbMust(adbBin, target, args, timeoutMs = 8000) {
  const res = adbTry(adbBin, target, args, timeoutMs);
  if (!res.ok) {
    throw new Error(`ADB command failed (${args.join(' ')}): ${res.stderr} ${res.stdout}`.trim());
  }
  return res.stdout;
}

function connectTargetTv(adbBin, target) {
  if (target.includes(':')) {
    adbTry(adbBin, null, ['connect', target], 6000);
  }
  const state = adbTry(adbBin, target, ['get-state'], 5000);
  if (state.stdout !== 'device') {
    adbTry(adbBin, null, ['disconnect', target], 4000);
    adbMust(adbBin, null, ['connect', target], 6000);
    const retry = adbMust(adbBin, target, ['get-state'], 5000);
    if (retry !== 'device') {
      throw new Error(`Không thể kết nối ADB tới Tivi (${target}). Hãy bấm "Luôn cho phép (Always allow)" trên màn hình Tivi.`);
    }
  }
  return target;
}

function askLine(promptText) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((resolve) =>
    rl.question(promptText, (ans) => {
      rl.close();
      resolve(cleanStr(ans));
    })
  );
}

async function resolveTargetTv(adbBin, args) {
  const ipIdx = args.indexOf('--ip');
  const cliIp = ipIdx !== -1 ? cleanStr(args[ipIdx + 1]) : '';
  const explicit = cliIp ? cliIp : cleanStr(process.env.VIETTV_IP);
  if (explicit) return normalizeTargetIp(explicit);

  const connected = adbTry(adbBin, null, ['devices'], 5000)
    .stdout.split(/\r?\n/)
    .slice(1)
    .map((l) => l.trim().split(/\s+/))
    .filter((parts) => parts[1] === 'device')
    .map((parts) => parts[0]);
  if (connected.length === 1) return connected[0];

  console.log('🔍 Đang quét tự động tìm Tivi Android / Google TV trong mạng LAN (cổng 5555)...');
  const found = await discoverTvsOnLan();
  if (found.length >= 1) {
    console.log(`✅ Đã phát hiện Tivi: ${found.map((d) => d.target).join(', ')}`);
    return found[0].target;
  }

  if (process.stdin.isTTY) {
    const manual = await askLine('Nhập địa chỉ IP của Tivi (ví dụ 192.168.1.50): ');
    if (manual) return normalizeTargetIp(manual);
  }
  throw new Error('Không tìm thấy Tivi mở ADB trong mạng LAN. Hãy bật Gỡ lỗi ADB trên Tivi hoặc truyền --ip <IP>.');
}

function readTvHardwareProps(adbBin, target) {
  const prop = (key) => adbTry(adbBin, target, ['shell', `getprop ${key}`], 5000).stdout;
  const abiPrimary = prop('ro.product.cpu.abi');
  return {
    manufacturer: prop('ro.product.manufacturer'),
    brand: prop('ro.product.brand'),
    model: prop('ro.product.model'),
    sdk: Number(prop('ro.build.version.sdk')),
    abi: abiPrimary ? abiPrimary : prop('ro.product.cpu.abilist').split(',')[0],
    serial: prop('ro.serialno')
  };
}

function listEnabledPackagesOnTv(adbBin, target) {
  return adbMust(adbBin, target, ['shell', 'pm list packages -e'], 10000)
    .split(/\r?\n/)
    .map((l) => l.replace(/^package:/, '').trim())
    .filter(isSafePackageName);
}

function savePreOptimizeSnapshot(backupBaseDir, deviceKey, snapshotPayload) {
  const deviceId = sanitizeDeviceId(deviceKey);
  const deviceDir = path.join(backupBaseDir, deviceId);
  fs.mkdirSync(deviceDir, { recursive: true });
  const snapshotPath = path.join(deviceDir, 'snapshot.json');

  const existing = fs.existsSync(snapshotPath)
    ? JSON.parse(fs.readFileSync(snapshotPath, 'utf8'))
    : null;
  const prevPkgs = existing && Array.isArray(existing.enabledPackagesBeforeOptimize)
    ? existing.enabledPackagesBeforeOptimize
    : [];
  const mergedEnabled = [...new Set([...prevPkgs, ...snapshotPayload.enabledPackagesBeforeOptimize])].filter(
    isSafePackageName
  );
  const preservedSettings = existing && existing.previousSettings
    ? existing.previousSettings
    : snapshotPayload.previousSettings;

  const snapshot = {
    schemaVersion: 1,
    timestamp: new Date().toISOString(),
    target: snapshotPayload.target,
    deviceId,
    props: snapshotPayload.props,
    brandProfile: snapshotPayload.brandProfile,
    enabledPackagesBeforeOptimize: mergedEnabled,
    previousSettings: preservedSettings
  };
  fs.writeFileSync(snapshotPath, JSON.stringify(snapshot, null, 2), 'utf8');
  return { snapshotPath, snapshot };
}

function loadDeviceSnapshot(backupBaseDir, deviceKey) {
  const snapshotPath = path.join(backupBaseDir, sanitizeDeviceId(deviceKey), 'snapshot.json');
  if (!fs.existsSync(snapshotPath)) return null;
  return { snapshotPath, snapshot: JSON.parse(fs.readFileSync(snapshotPath, 'utf8')) };
}

function preserveXiaomiBleWakeup(adbBin, target) {
  const rawRc = adbTry(adbBin, target, ['shell', 'settings get global bluetooth_mibluetooth_wake_up_rc']).stdout;
  const existingMacs = rawRc
    .split(';')
    .map((s) => s.trim().toUpperCase())
    .filter((s) => /^[0-9A-F]{2}(?::[0-9A-F]{2}){5}$/.test(s));
  const dumpsys = adbTry(adbBin, target, ['shell', 'dumpsys bluetooth_manager'], 8000).stdout;
  const merged = [...new Set([...existingMacs, ...extractPairedBleMacsFromDumpsys(dumpsys)])];
  if (merged.length > 0) {
    adbTry(adbBin, target, ['shell', `settings put global bluetooth_mibluetooth_wake_up_rc "${merged.join(';')};"`]);
  }
  return merged;
}

function mergeAccessibilityServices(currentRaw) {
  const parts = cleanStr(currentRaw)
    .split(':')
    .map((s) => s.trim())
    .filter(isSafeComponentId);
  return [...new Set([...parts, VN_HOME_KEY_SERVICE_ID])].filter(isSafeComponentId).join(':');
}

function shouldSetVietTvVoiceService(profileKey, curVoice) {
  const isCnRomProfile = new Set(['xiaomi', 'projector_cn']).has(profileKey);
  const voiceVal = cleanStr(curVoice);
  const nonClobbering = /^(?:|null)$/i.test(voiceVal) || /xiaomi|mitv|aispeech|iflytek|sogou|baidu/i.test(voiceVal);
  return isCnRomProfile && nonClobbering;
}

async function resolveLauncherApkPath(args) {
  const apkIdx = args.indexOf('--apk');
  const candidateApk = apkIdx !== -1 && args[apkIdx + 1] ? path.resolve(args[apkIdx + 1]) : DIST_APK_PATH;
  if (fs.existsSync(candidateApk)) return candidateApk;
  const built = await require('../scripts/build_apk').buildApk({ autoToolchain: true });
  return built.apkPath;
}

async function installVietTvLauncher(adbBin, target, args) {
  if (!isSafePackageName(LAUNCHER_PKG) || !isSafeComponentId(VN_TV_IME_ID)) {
    throw new Error('Invalid launcher package or IME component constant');
  }
  const apkPath = await resolveLauncherApkPath(args);
  connectTargetTv(adbBin, target);
  const detected = detectBrandProfile(readTvHardwareProps(adbBin, target));
  adbMust(adbBin, target, ['install', '-r', apkPath], 90000);
  const aot = adbTry(adbBin, target, ['shell', `cmd package compile -m speed -f ${LAUNCHER_PKG}`], 60000);

  const curAcc = adbTry(adbBin, target, ['shell', 'settings get secure enabled_accessibility_services']).stdout;
  const mergedAcc = mergeAccessibilityServices(curAcc);

  const setupCmds = [
    `pm grant ${LAUNCHER_PKG} android.permission.RECORD_AUDIO`,
    `appops set ${LAUNCHER_PKG} RECORD_AUDIO allow`,
    `pm grant ${LAUNCHER_PKG} android.permission.ACCESS_FINE_LOCATION`,
    `pm grant ${LAUNCHER_PKG} android.permission.WRITE_SECURE_SETTINGS`,
    `appops set ${LAUNCHER_PKG} WRITE_SETTINGS allow`,
    `ime enable ${VN_TV_IME_ID}`,
    `ime set ${VN_TV_IME_ID}`,
    'settings put secure show_ime_with_hard_keyboard 1',
    `settings put secure enabled_accessibility_services ${mergedAcc}`,
    'settings put secure accessibility_enabled 1',
    `settings put secure screensaver_components ${VN_CLOCK_DREAM_ID}`,
    `cmd package set-home-activity ${LAUNCHER_PKG}/.MainActivity`,
    `am start -n ${LAUNCHER_PKG}/.MainActivity`
  ];
  setupCmds.forEach((cmd) => adbTry(adbBin, target, ['shell', cmd], 6000));

  const curVoice = adbTry(adbBin, target, ['shell', 'settings get secure voice_recognition_service']).stdout;
  if (shouldSetVietTvVoiceService(detected.profileKey, curVoice) && isSafeComponentId(VN_VOICE_RECOG_ID)) {
    adbTry(adbBin, target, ['shell', `settings put secure voice_recognition_service ${VN_VOICE_RECOG_ID}`]);
    adbTry(adbBin, target, ['shell', `settings put secure assistant ${VN_VOICE_ASSIST_ID}`]);
  }

  return {
    status: 'OK',
    target,
    apkPath,
    brandProfile: detected.profileKey,
    aotCompiled: aot.ok,
    activeIme: adbTry(adbBin, target, ['shell', 'settings get secure default_input_method']).stdout
  };
}

function applyXiaomiSafeHooks(adbBin, target, enabledSet, stockLauncher) {
  [
    'com.xiaomi.wakeupservice',
    'com.xiaomi.mi_connect_service',
    'com.mitv.fastconnect',
    'com.xiaomi.mitv.settings',
    'com.xiaomi.mitv.smartpanel',
    'com.xiaomi.mitv.tvplayer'
  ]
    .filter(isSafePackageName)
    .forEach((pkg) => adbTry(adbBin, target, ['shell', `pm enable --user 0 ${pkg}`], 5000));

  const preservedBleMacs = preserveXiaomiBleWakeup(adbBin, target);
  [
    'settings put global bluetooth_on 1',
    'settings put global bluetooth_enable_fastconnect 1',
    'settings put global bluetooth_feature_fastconnect 1',
    'settings put system boot_ad_switch 0',
    'settings put system boot_auto_play 0'
  ].forEach((cmd) => adbTry(adbBin, target, ['shell', cmd], 5000));

  const homeOut = adbTry(adbBin, target, ['shell', 'cmd shortcut get-default-launcher'], 5000).stdout;
  const frozeStock = Boolean(
    isSafePackageName(stockLauncher) &&
      enabledSet.has(LAUNCHER_PKG) &&
      homeOut.includes(LAUNCHER_PKG) &&
      adbTry(adbBin, target, ['shell', `pm disable-user --user 0 ${stockLauncher}; am force-stop ${stockLauncher}`], 6000).ok
  );
  return { preservedBleMacs, frozeStock };
}

async function optimizeTv(adbBin, target, args, options = {}) {
  connectTargetTv(adbBin, target);
  const props = readTvHardwareProps(adbBin, target);
  const detected = detectBrandProfile(props);
  const enabledBefore = listEnabledPackagesOnTv(adbBin, target);
  const enabledSet = new Set(enabledBefore);
  const protectedSet = new Set(UNIVERSAL_PROTECTED_PACKAGES);

  const backupBase = options.backupBaseDir ? options.backupBaseDir : BACKUPS_ROOT_DIR;
  const { snapshotPath } = savePreOptimizeSnapshot(backupBase, target, {
    target,
    props,
    brandProfile: detected.profileKey,
    enabledPackagesBeforeOptimize: enabledBefore,
    previousSettings: {
      defaultIme: adbTry(adbBin, target, ['shell', 'settings get secure default_input_method']).stdout
    }
  });

  const otaExtra = args.includes('--freeze-ota') && detected.profile.otaPackage ? [detected.profile.otaPackage] : [];
  const activeIme = adbTry(adbBin, target, ['shell', 'settings get secure default_input_method']).stdout;
  const imeExtra =
    detected.profile.conditionalImeBloat && activeIme === VN_TV_IME_ID
      ? [detected.profile.conditionalImeBloat]
      : [];

  const candidates = [...detected.profile.bloatware, ...otaExtra, ...imeExtra].filter(
    (pkg) => isSafePackageName(pkg) && !protectedSet.has(pkg) && enabledSet.has(pkg)
  );
  const disabled = candidates.filter(
    (pkg) => adbTry(adbBin, target, ['shell', `pm disable-user --user 0 ${pkg}; am force-stop ${pkg}`], 6000).ok
  );

  const xiaomiHooks =
    detected.profileKey === 'xiaomi'
      ? applyXiaomiSafeHooks(adbBin, target, enabledSet, detected.profile.stockLauncherPackage)
      : { preservedBleMacs: [], frozeStock: false };

  // STRICT INVARIANT: NEVER touch private_dns_mode unless model starts with MiTV-
  if (detected.isMiTvModel) {
    adbTry(adbBin, target, ['shell', 'settings put global private_dns_mode off'], 5000);
    adbTry(adbBin, target, ['shell', 'settings delete global private_dns_specifier'], 5000);
  }

  [
    'settings put global window_animation_scale 0.0',
    'settings put global transition_animation_scale 0.0',
    'settings put global animator_duration_scale 0.0',
    'settings put global wifi_scan_always_enabled 0',
    'settings put global ble_scan_always_enabled 0',
    'settings put global wifi_sleep_policy 2',
    'cmd alarm set-timezone Asia/Ho_Chi_Minh'
  ].forEach((cmd) => adbTry(adbBin, target, ['shell', cmd], 5000));

  const compiledApps = CORE_STREAMING_AOT_PACKAGES.filter(
    (pkg) =>
      isSafePackageName(pkg) &&
      enabledSet.has(pkg) &&
      adbTry(adbBin, target, ['shell', `cmd package compile -m speed -f ${pkg}`], 60000).ok
  );

  return {
    status: 'OK',
    target,
    brandProfile: detected.profileKey,
    model: props.model,
    snapshotPath,
    disabledCount: disabled.length,
    disabledPackages: disabled,
    compiledApps,
    preservedBleMacs: xiaomiHooks.preservedBleMacs,
    privateDnsTouched: detected.isMiTvModel
  };
}

async function restoreTvFromSnapshot(adbBin, target, options = {}) {
  connectTargetTv(adbBin, target);
  const backupBase = options.backupBaseDir ? options.backupBaseDir : BACKUPS_ROOT_DIR;
  const loaded = loadDeviceSnapshot(backupBase, target);
  if (!loaded) {
    throw new Error(`Không tìm thấy bản sao lưu snapshot.json cho Tivi (${target}) trong .backups/.`);
  }

  const currentlyEnabled = new Set(listEnabledPackagesOnTv(adbBin, target));
  const savedPkgs = Array.isArray(loaded.snapshot.enabledPackagesBeforeOptimize)
    ? loaded.snapshot.enabledPackagesBeforeOptimize
    : [];
  const reEnabled = savedPkgs
    .filter((pkg) => isSafePackageName(pkg) && !currentlyEnabled.has(pkg))
    .filter((pkg) => adbTry(adbBin, target, ['shell', `pm enable --user 0 ${pkg}`], 6000).ok);

  [
    'settings put global window_animation_scale 1.0',
    'settings put global transition_animation_scale 1.0',
    'settings put global animator_duration_scale 1.0'
  ].forEach((cmd) => adbTry(adbBin, target, ['shell', cmd], 5000));

  const prevIme = cleanStr(Object(loaded.snapshot.previousSettings).defaultIme);
  if (isSafeComponentId(prevIme)) {
    adbTry(adbBin, target, ['shell', `ime enable ${prevIme}`], 5000);
    adbTry(adbBin, target, ['shell', `ime set ${prevIme}`], 5000);
  }

  return {
    status: 'OK',
    target,
    snapshotPath: loaded.snapshotPath,
    reEnabledCount: reEnabled.length,
    reEnabledPackages: reEnabled,
    animationsRestoredTo: '1.0'
  };
}

function selectOfflineAsrAbi(abiRaw) {
  if (abiRaw.includes('arm64')) return 'arm64-v8a';
  return abiRaw.includes('x86_64') ? 'x86_64' : 'armeabi-v7a';
}

async function resolveOfflineAsrApk(abiKey, args) {
  const customIdx = args.indexOf('--offline-apk');
  const source = customIdx !== -1 && args[customIdx + 1] ? args[customIdx + 1] : SHERPA_ONNX_VI_APKS[abiKey];
  const isRemote = /^https?:\/\//i.test(source);
  const localApkPath = isRemote
    ? path.join(TOOLCHAIN_DIR, 'offline-voice', path.basename(new URL(source).pathname))
    : path.resolve(source);

  if (isRemote && !fs.existsSync(localApkPath)) {
    console.log(`🎙️ Đang tải gói K2-FSA Sherpa-ONNX Tiếng Việt Offline (${abiKey})...`);
    await downloadToFile(source, localApkPath);
  }
  return localApkPath;
}

async function installOfflineVietnameseVoice(adbBin, target, args) {
  connectTargetTv(adbBin, target);
  const abiKey = selectOfflineAsrAbi(readTvHardwareProps(adbBin, target).abi);
  const localApkPath = await resolveOfflineAsrApk(abiKey, args);

  const beforeSet = new Set(listEnabledPackagesOnTv(adbBin, target));
  adbMust(adbBin, target, ['install', '-r', localApkPath], 120000);
  const foundPkg = listEnabledPackagesOnTv(adbBin, target).find(
    (p) => !beforeSet.has(p) && /sherpa|k2fsa|asr/i.test(p)
  );
  const pkgName = foundPkg ? foundPkg : 'com.k2fsa.sherpa.onnx.simulate.streaming.asr';
  if (!isSafePackageName(pkgName)) {
    throw new Error(`Unsafe offline ASR package identifier: ${pkgName}`);
  }

  adbTry(adbBin, target, ['shell', `pm grant ${pkgName} android.permission.RECORD_AUDIO`], 6000);
  adbTry(adbBin, target, ['shell', `appops set ${pkgName} RECORD_AUDIO allow`], 6000);

  const svcLines = adbTry(
    adbBin,
    target,
    ['shell', 'cmd package query-services --brief -a android.speech.RecognitionService'],
    8000
  ).stdout.split(/\r?\n/).map((l) => l.trim());
  const sherpaSvc = svcLines.find((c) => c.startsWith(`${pkgName}/`) && isSafeComponentId(c));
  const chosenSvc = sherpaSvc ? sherpaSvc : VN_VOICE_RECOG_ID;
  if (isSafeComponentId(chosenSvc)) {
    adbTry(adbBin, target, ['shell', `settings put secure voice_recognition_service ${chosenSvc}`], 5000);
  }

  return { status: 'OK', target, cpuAbi: abiKey, installedPackage: pkgName, recognitionService: chosenSvc, apkPath: localApkPath };
}

function setOtaState(adbBin, target, enableOta) {
  if (!isSafePackageName(XIAOMI_OTA_UPDATER_PKG)) {
    throw new Error(`Unsafe OTA package identifier: ${XIAOMI_OTA_UPDATER_PKG}`);
  }
  connectTargetTv(adbBin, target);
  const sub = enableOta ? 'enable' : 'disable-user';
  adbMust(adbBin, target, ['shell', `pm ${sub} --user 0 ${XIAOMI_OTA_UPDATER_PKG}`], 6000);
  return { status: 'OK', target, otaPackage: XIAOMI_OTA_UPDATER_PKG, otaEnabled: enableOta };
}

function getTvStatus(adbBin, target) {
  connectTargetTv(adbBin, target);
  const props = readTvHardwareProps(adbBin, target);
  const detected = detectBrandProfile(props);
  const mem = adbTry(adbBin, target, ['shell', 'cat /proc/meminfo'], 5000).stdout;
  const totalMatch = mem.match(/MemTotal:\s+(\d+)/);
  const availMatch = mem.match(/MemAvailable:\s+(\d+)/);
  return {
    status: 'OK',
    target,
    brandProfile: detected.profileKey,
    brandLabel: detected.profile.label,
    props,
    ramTotalMB: Math.round(Number(totalMatch ? totalMatch[1] : 0) * 0.0009765625),
    ramAvailableMB: Math.round(Number(availMatch ? availMatch[1] : 0) * 0.0009765625),
    defaultIme: adbTry(adbBin, target, ['shell', 'settings get secure default_input_method']).stdout,
    voiceRecognitionService: adbTry(adbBin, target, ['shell', 'settings get secure voice_recognition_service']).stdout
  };
}

function runSelfCheck() {
  const protectedSet = new Set(UNIVERSAL_PROTECTED_PACKAGES);
  if (protectedSet.size !== UNIVERSAL_PROTECTED_PACKAGES.length) {
    throw new Error('Duplicate package in UNIVERSAL_PROTECTED_PACKAGES');
  }
  if (!UNIVERSAL_PROTECTED_PACKAGES.every(isSafePackageName) || !CORE_STREAMING_AOT_PACKAGES.every(isSafePackageName)) {
    throw new Error('Malformed package identifier in core package lists');
  }
  const coreComponents = [
    VN_TV_IME_ID,
    VN_VOICE_RECOG_ID,
    VN_VOICE_ASSIST_ID,
    VN_HOME_KEY_SERVICE_ID,
    VN_CLOCK_DREAM_ID
  ];
  if (!coreComponents.every(isSafeComponentId)) {
    throw new Error('Malformed component identifier in core constants');
  }

  const brandProfiles = {};
  for (const [key, profile] of Object.entries(BRAND_PROFILES)) {
    const bloatSet = new Set(profile.bloatware);
    if (bloatSet.size !== profile.bloatware.length) {
      throw new Error(`Duplicate package in BRAND_PROFILES.${key}`);
    }
    if (!profile.bloatware.every(isSafePackageName)) {
      throw new Error(`Unsafe package identifier in BRAND_PROFILES.${key}`);
    }
    const overlap = profile.bloatware.filter((pkg) => protectedSet.has(pkg));
    if (overlap.length > 0) {
      throw new Error(`Protected package collision in ${key}: ${overlap.join(', ')}`);
    }
    brandProfiles[key] = profile.bloatware.length;
  }

  return {
    status: 'OK',
    syntaxValid: true,
    launcherPackage: LAUNCHER_PKG,
    vnTvImeId: VN_TV_IME_ID,
    vnVoiceRecogId: VN_VOICE_RECOG_ID,
    universalProtectedCount: UNIVERSAL_PROTECTED_PACKAGES.length,
    brandProfiles,
    adbAvailableLocally: Boolean(findAdbSync())
  };
}

const runSyntaxCheck = runSelfCheck;

async function runWizard(args) {
  console.log('\n=== VIETTV LAUNCHER & TỐI ƯU TIVI ĐA HÃNG (v1.0.1) ===');
  console.log('  1. Tự tìm Tivi & Cài trọn bộ Giao diện + Bàn phím Telex + Tối ưu RAM');
  console.log('  2. Chỉ cài Giao diện & Bàn phím Tiếng Việt');
  console.log('  3. Cài thêm Gói Giọng Nói Tiếng Việt Offline (cho Tivi nội địa cắt Google)');
  console.log('  4. Khôi phục Tivi về nguyên bản');
  console.log('  5. Mở lại cập nhật hệ thống OTA');
  console.log('  0. Thoát\n');
  const choice = await askLine('Nhập lựa chọn (0-5): ');
  const menuFlag = {
    1: '--full-setup',
    2: '--install',
    3: '--install-offline-voice',
    4: '--restore',
    5: '--enable-ota'
  }[choice];
  if (!menuFlag) return { status: 'EXITED' };
  return dispatchFlag(menuFlag, [...args, menuFlag]);
}

async function dispatchFlag(flag, args) {
  if (flag === '--syntax-check' || flag === '--self-check') return runSelfCheck();
  if (flag === '--discover') {
    const devices = await discoverTvsOnLan();
    return { status: 'OK', discoveredCount: devices.length, devices };
  }
  if (flag === '--wizard') return runWizard(args);

  const adbBin = await ensureAdbBinary();
  const target = await resolveTargetTv(adbBin, args);
  const actions = {
    '--full-setup': async () => ({
      status: 'OK',
      install: await installVietTvLauncher(adbBin, target, args),
      optimize: await optimizeTv(adbBin, target, args)
    }),
    '--install': () => installVietTvLauncher(adbBin, target, args),
    '--optimize': () => optimizeTv(adbBin, target, args),
    '--debloat': () => optimizeTv(adbBin, target, args),
    '--install-offline-voice': () => installOfflineVietnameseVoice(adbBin, target, args),
    '--restore': () => restoreTvFromSnapshot(adbBin, target),
    '--enable-ota': () => setOtaState(adbBin, target, true),
    '--freeze-ota': () => setOtaState(adbBin, target, false),
    '--status': () => getTvStatus(adbBin, target)
  };
  const handler = actions[flag] ? actions[flag] : actions['--status'];
  return handler();
}

async function main() {
  const args = process.argv.slice(2);
  const knownFlags = [
    '--syntax-check', '--self-check', '--discover', '--wizard',
    '--full-setup', '--install', '--optimize', '--debloat',
    '--install-offline-voice', '--restore', '--enable-ota', '--freeze-ota', '--status'
  ];
  const matched = knownFlags.find((f) => args.includes(f));
  const activeFlag = matched ? matched : args.length === 0 ? '--wizard' : '--status';
  const result = await dispatchFlag(activeFlag, args);
  console.log(JSON.stringify(result, null, 2));
}

module.exports = {
  LAUNCHER_PKG,
  VN_TV_IME_ID,
  VN_VOICE_RECOG_ID,
  VN_VOICE_ASSIST_ID,
  UNIVERSAL_PROTECTED_PACKAGES,
  CORE_STREAMING_AOT_PACKAGES,
  BRAND_PROFILES,
  SHERPA_ONNX_VI_APKS,
  isSafePackageName,
  isSafeComponentId,
  sanitizeDeviceId,
  normalizeTargetIp,
  detectBrandProfile,
  mergeAccessibilityServices,
  shouldSetVietTvVoiceService,
  extractPairedBleMacsFromDumpsys,
  isPrivateLanIpv4,
  probeTcpPort,
  discoverTvsOnLan,
  savePreOptimizeSnapshot,
  loadDeviceSnapshot,
  ensureAdbBinary,
  installVietTvLauncher,
  optimizeTv,
  restoreTvFromSnapshot,
  installOfflineVietnameseVoice,
  setOtaState,
  getTvStatus,
  runSelfCheck,
  runSyntaxCheck
};

if (require.main === module) {
  main().catch((err) => {
    console.error('[VIETTV ERROR]', err.message);
    process.exit(1);
  });
}
