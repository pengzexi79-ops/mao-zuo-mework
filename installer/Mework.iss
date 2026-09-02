; Build with Inno Setup 6 after `mvn -f backend/pom.xml package`.
; Portable runtime directories are optional at build time but required for a no-prerequisite release.
#define AppName "猫作·Mework"
#include "version.iss"
#define AppPublisher "猫作·Mework"
#define AppExeName "Mework.exe"

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
SetupIconFile=Mework.ico
UninstallDisplayIcon={app}\Mework.ico

[Files]
Source: "..\start.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\ensure_env.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\ensure_env.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\start_mysql.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\start_mysql.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\setup_runtime.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: ".\launcher\output\Mework.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: ".\launcher\output\Microsoft.Web.WebView2.Core.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: ".\launcher\output\Microsoft.Web.WebView2.WinForms.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: ".\launcher\output\WebView2Loader.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: ".\launcher\output\MicrosoftEdgeWebView2RuntimeInstallerX64.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Check: WebView2RuntimeNeeded
Source: "..\.env.example"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\INSTALLATION_GUIDE.md"; DestDir: "{app}\docs"; Flags: ignoreversion
Source: "..\AI_INSTALLATION_GUIDE.md"; DestDir: "{app}\docs"; Flags: ignoreversion
Source: "..\PRIVACY_RELEASE.md"; DestDir: "{app}\docs"; Flags: ignoreversion
Source: ".\Mework.ico"; DestDir: "{app}"; Flags: ignoreversion
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
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\Mework.ico"
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\Mework.ico"
Name: "{group}\检查运行环境"; Filename: "{app}\setup_runtime.bat"; WorkingDir: "{app}"

[Run]
; Only one post-install launcher is allowed to avoid concurrent .env/MySQL/venv setup.
Filename: "{tmp}\MicrosoftEdgeWebView2RuntimeInstallerX64.exe"; Parameters: "/silent /install"; StatusMsg: "正在安装桌面显示组件..."; Flags: waituntilterminated; Check: WebView2RuntimeNeeded
Filename: "{app}\{#AppExeName}"; Description: "启动猫作·Mework（首次启动会创建空数据库和本机密钥）"; Flags: postinstall nowait skipifsilent

[Code]
function WebView2RuntimeNeeded: Boolean;
var
  Version: String;
begin
  Result := not (
    RegQueryStringValue(HKLM32, 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', Version) or
    RegQueryStringValue(HKCU, 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', Version)
  );
end;

function GetDefaultInstallDir(Param: String): String;
begin
  if DirExists('D:\') then
    Result := 'D:\Mework'
  else
    Result := ExpandConstant('{localappdata}\Programs\Mework');
end;
