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
- **A Hebrew temp dir breaks TDLib (proven).** The library extracts its DLLs into a new dir under `java.io.tmpdir` on every start and loads them from there. With a Hebrew `TEMP` it fails: `UnsatisfiedLinkError: ...\tmp\windows-x64...\libcrypto-3-x64.dll: Can't find dependent libraries` (probe step in runs `41725414413` and `41726724213`). The TDLib database dir itself works under a Hebrew path.
- Setting `java.io.tmpdir` from `main` does not help, because Java 17 fixes it when the JVM starts (run `41725414413`: property changed, extraction still went to the Hebrew dir).
- **Fix: `NativeTmp.ensureAsciiTmpdir`**, the first call in `main`. When the temp dir is not ASCII, the program restarts itself once with `TEMP`/`TMP` set to `<data dir>\tmp` (if ASCII) or `%ProgramData%\TGBackup-<user hash>\tmp`, and cleans leftover DLL dirs older than an hour. Verified in run `41726724213`: `NATIVE_TMP_RELAUNCH C:\ProgramData\TGBackup-c368a648\tmp` then `TDLIB_OK 1.8.67`. The installed `TGBackup.exe` with a Hebrew `TEMP` also restarts this way and loads the page.

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
- **Window placement (fixed).** The first WebView2 run placed the 1100×750 window off-centre and clipped on the runner's 1024×768 screen. Now `WindowFit` centres the window in the work area of its monitor and shrinks a side that does not fit. Run `41726724213`: `WINDOW_RECT (0,0,1024,720) WORK_AREA (0,0,1024,720)`, `WINDOW_INSIDE yes`, and CI fails if the window is not fully inside the screen.
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

1. Hebrew/space user name: data dirs, TDLib db and view profile all work under a Hebrew path. A Hebrew temp dir breaks TDLib, and `NativeTmp` handles it with a restart (see §1).
2. Offline first run: both candidates load the local page with an unreachable proxy.
3. Port in use: `LocalPageServer` binds `127.0.0.1:0` and reports the chosen port (tested, two servers get different ports).
4. Second launch: owned by the next plan (single instance).
5. Missing WebView2 runtime: fallback above.

## 5. Next plan

Delete `org.tgbackup.desktop.spike` except what is reused (`LocalPageServer` pattern, WebView2 window; `WindowFit` and `NativeTmp` already live outside the spike package), drop the JCEF package task, and write plan 2: engine port (`engine`, `data`, `tg`, `export` from the Android app).
