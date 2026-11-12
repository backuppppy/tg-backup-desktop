; TG Backup installer (Inno Setup 6). Built by :desktop:packageWebView2, which passes
; /DAppVersion=... /DImageDir=... /DOutDir=... /DIconFile=... to ISCC.
; The installer installs and removes only the program's own files under {app}; the user's
; accounts, settings and backups live elsewhere and are never touched by install, update or uninstall.

#ifndef AppVersion
  #error AppVersion is required (/DAppVersion=...)
#endif
#ifndef ImageDir
  #error ImageDir is required (/DImageDir=...)
#endif
#ifndef OutDir
  #error OutDir is required (/DOutDir=...)
#endif
#ifndef IconFile
  #error IconFile is required (/DIconFile=...)
#endif

[Setup]
AppId={{6F1C2B7E-3D4A-4E59-9B8C-2A1D5E7F9C30}
AppName=TG Backup
AppVersion={#AppVersion}
AppPublisher=TG Backup
DefaultDirName={autopf}\TGBackup
DisableProgramGroupPage=yes
PrivilegesRequired=admin
CloseApplications=force
RestartApplications=no
OutputDir={#OutDir}
OutputBaseFilename=TGBackup-Setup
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\TGBackup.exe

[Languages]
#if FileExists(AddBackslash(CompilerPath) + "Languages\Hebrew.isl")
  #pragma message "INNO_LANGUAGE he"
Name: "he"; MessagesFile: "compiler:Languages\Hebrew.isl"
#else
  #pragma message "INNO_LANGUAGE en (compiler:Languages\Hebrew.isl not found)"
Name: "en"; MessagesFile: "compiler:Default.isl"
#endif

[Tasks]
Name: "desktopicon"; Description: "צור קיצור דרך בשולחן העבודה"

[Files]
Source: "{#ImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\TG Backup"; Filename: "{app}\TGBackup.exe"
Name: "{autodesktop}\TG Backup"; Filename: "{app}\TGBackup.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\TGBackup.exe"; Description: "פתח את TG Backup"; Flags: postinstall nowait skipifsilent

[UninstallRun]
; Gives the computer its sleep setting back if the program still held it; never stops the uninstall.
Filename: "{app}\TGBackup.exe"; Parameters: "--restore-power"; Flags: runhidden waituntilterminated; RunOnceId: "RestorePower"

[Code]
const
  UninstallRoot = 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall';

{ Removes an older MSI install of TGBackup (jpackage/WiX, DisplayName 'TGBackup') found under RootKey. }
procedure RemoveOldMsiUnder(RootKey: Integer);
var
  Keys: TArrayOfString;
  I, ResultCode: Integer;
  DisplayName, Uninst: String;
begin
  if not RegGetSubkeyNames(RootKey, UninstallRoot, Keys) then Exit;
  for I := 0 to GetArrayLength(Keys) - 1 do
  begin
    if RegQueryStringValue(RootKey, UninstallRoot + '\' + Keys[I], 'DisplayName', DisplayName) and (DisplayName = 'TGBackup')
      and RegQueryStringValue(RootKey, UninstallRoot + '\' + Keys[I], 'UninstallString', Uninst)
      and (Pos('MSIEXEC', Uppercase(Trim(Uninst))) = 1) then
    begin
      Log('Removing the older MSI install ' + Keys[I]);
      if Exec(ExpandConstant('{sys}\msiexec.exe'), '/x ' + Keys[I] + ' /qn /norestart', '', SW_HIDE,
        ewWaitUntilTerminated, ResultCode) then
        Log('msiexec /x ' + Keys[I] + ' exited ' + IntToStr(ResultCode))
      else
        Log('msiexec /x ' + Keys[I] + ' could not start: ' + SysErrorMessage(ResultCode));
    end;
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if IsWin64 then RemoveOldMsiUnder(HKLM64);
  RemoveOldMsiUnder(HKLM32);
  RemoveOldMsiUnder(HKCU);
end;
