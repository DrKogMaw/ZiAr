const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');

const PROJECTS = path.join(os.homedir(), 'Desktop', 'Projects for Codex');
const APK_DIRECTORY = path.join(PROJECTS, 'APKs');
const EXE_DIRECTORY = path.join(PROJECTS, 'EXEs');
const MAX_APK_BYTES = 200 * 1024 * 1024;

function versionParts(version) {
  if (typeof version !== 'string' || !/^\d+\.\d+\.\d+$/.test(version)) return null;
  return version.split('.').map(Number);
}

function compareVersions(left, right) {
  const a = versionParts(left);
  const b = versionParts(right);
  if (!a || !b) return null;
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] > b[i] ? 1 : -1;
  return 0;
}

function newestFile(directory, pattern) {
  if (!fs.existsSync(directory)) return null;
  return fs.readdirSync(directory, { withFileTypes: true })
    .filter(entry => entry.isFile())
    .map(entry => {
      const match = pattern.exec(entry.name);
      return match ? { version: match[1], file: path.join(directory, entry.name) } : null;
    })
    .filter(Boolean)
    .sort((a, b) => compareVersions(b.version, a.version))[0] || null;
}

function createUpdateSource({ apkDirectory = APK_DIRECTORY, exeDirectory = EXE_DIRECTORY, currentVersion }) {
  function android() {
    const update = newestFile(apkDirectory, /^ZiAr-Android-(\d+\.\d+\.\d+)\.apk$/i);
    if (!update) return null;
    const stat = fs.statSync(update.file);
    if (!stat.isFile() || stat.size < 1024 || stat.size > MAX_APK_BYTES) return null;
    const sha256 = crypto.createHash('sha256').update(fs.readFileSync(update.file)).digest('hex');
    return { ...update, size: stat.size, sha256 };
  }
  function windows() {
    const update = newestFile(exeDirectory, /^ZiAr(?:-Windows-Setup-| Setup )(\d+\.\d+\.\d+)\.exe$/i);
    return update && compareVersions(update.version, currentVersion) > 0 ? update : null;
  }
  return { android, windows, apkDirectory, exeDirectory, currentVersion };
}

module.exports = { createUpdateSource, compareVersions, APK_DIRECTORY, EXE_DIRECTORY };
