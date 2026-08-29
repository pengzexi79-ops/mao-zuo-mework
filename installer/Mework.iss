; Build with Inno Setup 6 after `mvn -f backend/pom.xml package`.
; Portable runtime directories are optional at build time but required for a no-prerequisite release.
#define AppName "猫作·Mework"
#include "version.iss"
#define AppPublisher "猫作·Mework"
#define AppExeName "start.bat"

[Setup]
AppId={{4F2BA8E5-6552-4A84-8D87-69BDE6B21B79}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={code:GetDefaultInstallDir}
DefaultGroupName={#AppName}
OutputDir=output
OutputBaseFilename=Mework-Setup-{#AppVersion}
; The bundled runtime is multi-gigabyte. No compression keeps peak memory bounded on constrained build PCs.
Compression=none
SolidCompression=no
; The complete portable runtime is multi-gigabyte; span data into companion .bin slices.
DiskSpanning=yes
DiskSliceSize=1500000000
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
WizardStyle=modern

[Files]
Source: "..\start.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\ensure_env.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\ensure_env.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\start_mysql.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\start_mysql.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\setup_runtime.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\.env.example"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\INSTALLATION_GUIDE.md"; DestDir: "{app}\docs"; Flags: ignoreversion
Source: "..\AI_INSTALLATION_GUIDE.md"; DestDir: "{app}\docs"; Flags: ignoreversion
Source: "..\PRIVACY_RELEASE.md"; DestDir: "{app}\docs"; Flags: ignoreversion
; The installer only accepts the just-built delivery artifact. Historical or fallback JARs are never packaged.
Source: "..\backend\target\mixcut-delivery.jar"; DestDir: "{app}\backend\target"; Flags: ignoreversion
Source: "..\backend\requirements-windows.txt"; DestDir: "{app}\backend"; Flags: ignoreversion
Source: "..\backend\tools\bootstrap_media_runtime.bat"; DestDir: "{app}\backend\tools"; Flags: ignoreversion
Source: "..\backend\tools\media_diagnose.py"; DestDir: "{app}\backend\tools"; Flags: ignoreversion
Source: "..\backend\tools\natural_tts.py"; DestDir: "{app}\backend\tools"; Flags: ignoreversion
Source: "..\backend\src\main\resources\release-notes.json"; DestDir: "{app}\backend\src\main\resources"; Flags: ignoreversion
Source: "..\backend\src\main\resources\db\schema.sql"; DestDir: "{app}\backend\src\main\resources\db"; Flags: ignoreversion
Source: ".\output\release-manifest.json"; DestDir: "{app}\installer"; Flags: ignoreversion
Source: ".\ai-setup-manifest.json"; DestDir: "{app}\installer"; Flags: ignoreversion
; Release runtime allowlist. Never replace these entries with portable\*.
Source: "..\portable\jdk-17\*"; DestDir: "{app}\portable\jdk-17"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\portable\mysql\*"; DestDir: "{app}\portable\mysql"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "**\data\**;**\*.log"
Source: "..\portable\ffmpeg\*"; DestDir: "{app}\portable\ffmpeg"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "**\*.log"
Source: "..\portable\python\*"; DestDir: "{app}\portable\python"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "**\__pycache__\**;**\*.pyc;**\*.pyo;**\*.log"
Source: "..\portable\whisper\*"; DestDir: "{app}\portable\whisper"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "**\*.log"
Source: "..\portable\whisper-models\*"; DestDir: "{app}\portable\whisper-models"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "**\*.log"
Source: "..\portable\imagemagick\*"; DestDir: "{app}\portable\imagemagick"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "**\*.log"
Source: "..\backend\.venv\*"; DestDir: "{app}\backend\.venv"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "**\__pycache__\**;**\*.pyc;**\*.pyo;**\*.log"

[Icons]
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\start.bat"; WorkingDir: "{app}"; IconFilename: "{sys}\shell32.dll"; IconIndex: 14
Name: "{group}\{#AppName}"; Filename: "{app}\start.bat"; WorkingDir: "{app}"
Name: "{group}\检查运行环境"; Filename: "{app}\setup_runtime.bat"; WorkingDir: "{app}"

[Run]
; Only one post-install launcher is allowed to avoid concurrent .env/MySQL/venv setup.
Filename: "{app}\start.bat"; Description: "启动猫作·Mework（首次启动会创建空数据库和本机密钥）"; Flags: postinstall nowait skipifsilent

[Code]
function GetDefaultInstallDir(Param: String): String;
begin
  if DirExists('D:\') then
    Result := 'D:\Mework'
  else
    Result := ExpandConstant('{localappdata}\Programs\Mework');
end;
