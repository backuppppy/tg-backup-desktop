# TG Backup Desktop — Spike Results

Plan: `docs/superpowers/plans/2026-11-03-foundation-and-spike.md`
Evidence run: GitHub Actions run `41669134311` on commit `a807487` (all four jobs green: `linux`, `windows`, `windows-spike (webview2)`, `windows-spike (jcef)`).

## 1. TDLib on the JVM

| Host | Result | Evidence |
|---|---|---|
| Linux/arm64 (dev host) | PASS | `TdlibSmokeTest` green locally and in job `linux` (`BUILD SUCCESSFUL in 1m 30s`) |
| windows-latest | PASS | `TDLIB_OK 1.8.67` |
| Hebrew + space data dir | PASS | `TDLIB_LOG D:\a\_temp\תיקייה עם רווח\TGBackup\tdlib-smoke\tdlib.log` |

Findings:
- `tdl-coroutines-jvm:15.0.0` loads `tdjsonjava.dll` from its JAR on Windows with no extra setup. TDLib version 1.8.67.
- The green run uses an ASCII `java.io.tmpdir` (`TGB_NATIVE_TMP`, commit `51054a0`) for the native DLL extraction; the run before it, with a Hebrew `TEMP`, failed (run `41653524803`, log no longer available, so the cause is not proven). **The real program must not depend on this:** set `java.io.tmpdir` (or the library's extract dir) to an ASCII path, e.g. `C:\ProgramData\TGBackup\natives` or a dir next to `TGBackup.exe`, before TDLib loads, because a Hebrew Windows user name puts `%TEMP%` under a Hebrew path. The TDLib database dir itself works under a Hebrew path.

## 2. Embedded view — two candidates

Both installers were built with jpackage `--type exe` (WiX 3.14 is on the runner, `INSTALLER_KIND wix`), installed silently, and run from the installed `TGBackup.exe --smoke` with `LOCALAPPDATA` under `תיקייה עם רווח` and an unreachable proxy (`127.0.0.1:9`).

| | WebView2 (`webview_java`, JNA) | JCEF (`jcefmaven`, bundled natives) |
|---|---|---|
| `TGBackup-Setup.exe` | **149.1 MB** | 317.7 MB |
| Installed size (app-image) | **198.9 MB** | 575.6 MB |
| Offline smoke | `PAGE_LOADED` | `PAGE_LOADED` |
| Profile under Hebrew path | `...\תיקייה עם רווח\TGBackup\webview2` created (`EBWebView`) | `...\TGBackup\cef` created (35 entries) |
| Hebrew RTL rendering | correct | correct |
| Runtime dependency | WebView2 runtime from Windows | none |
| Screenshot artifact | `screenshot-webview2` | `screenshot-jcef` |

WebView2 runtime on the runner: `preinstalled=yes version=156.0.4314.50`.

Notes:
- In the WebView2 screenshot the 1100×750 window is placed off-centre and clipped on the runner's 1024×768 screen. Not a rendering problem: the real window must be centred and clamped to the screen work area (next plan).
- The jitpack `webview` DLLs are pinned by SHA-256 in the build (commit `a807487`).

## 3. Decision

**Recommended: WebView2.** The installer is half the size (149 vs 318 MB) and the installed program a third (199 vs 576 MB), with the same offline and Hebrew-path results.

To be clear for the user: WebView2 is the rendering engine built into Windows (the one Edge uses), but **no Edge or browser window opens**. The screens show inside TGBackup's own window with its own title and icon.

**Fallback for a missing WebView2 runtime** (old Windows 10 without updates). Windows 11 and updated Windows 10 already include it.
1. At startup, check the registry key `HKLM\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}` (and the per-user `HKCU` key).
2. If it is missing, show a Hebrew message with one button that runs Microsoft's Evergreen bootstrapper (~2 MB, bundled in the installer), then reopen the window.
3. While it is missing, the engine and jobs still run: the tray menu works, and the screens can be reached from the phone over Tailscale.

JCEF is kept as the documented alternative if WebView2 turns out unstable on the user's real Windows test.

## 4. Review-focus answers

1. Hebrew/space user name: data dirs, TDLib db and view profile all work under a Hebrew path. Native extraction temp dir must be ASCII (see §1).
2. Offline first run: both candidates load the local page with an unreachable proxy.
3. Port in use: `LocalPageServer` binds `127.0.0.1:0` and reports the chosen port (tested, two servers get different ports).
4. Second launch: owned by the next plan (single instance).
5. Missing WebView2 runtime: fallback above.

## 5. Next plan

Delete `org.tgbackup.desktop.spike` except what is reused (`LocalPageServer` pattern, WebView2 window, ASCII native tmp dir), drop the JCEF package task, and write plan 2: engine port (`engine`, `data`, `tg`, `export` from the Android app).
