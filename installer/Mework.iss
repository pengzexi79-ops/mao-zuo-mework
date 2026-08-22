; Build with Inno Setup 6 after `mvn -f backend/pom.xml package`.
; Portable runtime directories are optional at build time but required for a no-prerequisite release.
#define AppName "Mework"
#include "version.iss"
#define AppPublisher "Mework"
#define AppExeName "start.bat"

[Setup]
AppId={{4F2BA8E5-6552-4A84-8D87-69BDE6B21B79}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
OutputDir=output
OutputBaseFilename=Mework-Setup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64
PrivilegesRequired=lowest
DisableProgramGroupPage=yes

[Files]
Source: "..\start.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\ensure_env.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\start_mysql.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\setup_runtime.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\.env.example"; DestDir: "{app}"; Flags: onlyifdoesntexist
; The installer only accepts the just-built delivery artifact. Historical or fallback JARs are never packaged.
Source: "..\backend\target\mixcut-delivery.jar"; DestDir: "{app}\backend\target"; Flags: ignoreversion
Source: "..\backend\requirements-windows.txt"; DestDir: "{app}\backend"; Flags: ignoreversion
Source: "..\backend\tools\bootstrap_media_runtime.bat"; DestDir: "{app}\backend\tools"; Flags: ignoreversion
Source: "..\backend\tools\media_diagnose.py"; DestDir: "{app}\backend\tools"; Flags: ignoreversion
Source: "..\backend\tools\natural_tts.py"; DestDir: "{app}\backend\tools"; Flags: ignoreversion
Source: "..\backend\src\main\resources\release-notes.json"; DestDir: "{app}\backend\src\main\resources"; Flags: ignoreversion
Source: "output\release-manifest.json"; DestDir: "{app}\installer"; Flags: ignoreversion
; Required runtime folders: a release build must fail rather than silently omit an offline prerequisite.
Source: "..\portable\*"; DestDir: "{app}\portable"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\backend\.venv\*"; DestDir: "{app}\backend\.venv"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\start.bat"; WorkingDir: "{app}"; IconFilename: "{sys}\shell32.dll"; IconIndex: 14
Name: "{group}\{#AppName}"; Filename: "{app}\start.bat"; WorkingDir: "{app}"
Name: "{group}\检查运行环境"; Filename: "{app}\setup_runtime.bat"; WorkingDir: "{app}"

[Run]
Filename: "{app}\setup_runtime.bat"; Description: "检查并准备本机运行环境"; Flags: postinstall nowait skipifsilent
Filename: "{app}\start.bat"; Description: "启动 Mework"; Flags: postinstall nowait skipifsilent
