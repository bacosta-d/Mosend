; Mosend Windows 安装包（NSIS）
; 用法: makensis -DARCH=amd64 -DAPPVERSION=1.2.0 installer.nsi

!ifndef ARCH
  !define ARCH "amd64"
!endif
!ifndef APPVERSION
  !define APPVERSION "1.2.0"
!endif

Unicode true
Name "Mosend"
OutFile "dist\Mosend-Setup-${APPVERSION}-${ARCH}.exe"
InstallDir "$LOCALAPPDATA\Mosend"
InstallDirRegKey HKCU "Software\Mosend" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
Icon "app.ico"
UninstallIcon "app.ico"
ShowInstDetails show
ShowUninstDetails show

!include "MUI2.nsh"

!define MUI_ABORTWARNING
!define MUI_ICON "app.ico"
!define MUI_UNICON "app.ico"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES

!define MUI_FINISHPAGE_RUN "$INSTDIR\Mosend.exe"
!define MUI_FINISHPAGE_RUN_TEXT "立即启动 Mosend"
!define MUI_FINISHPAGE_SHOWREADME "$INSTDIR\README.md"
!define MUI_FINISHPAGE_SHOWREADME_TEXT "查看使用说明（含防火墙设置）"
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"

LangString DESC_SecMain ${LANG_SIMPCHINESE} "安装 Mosend 主程序（局域网文件互传）。"
LangString DESC_SecMain ${LANG_ENGLISH} "Install Mosend main program."
LangString DESC_SecDesktop ${LANG_SIMPCHINESE} "在桌面创建快捷方式。"
LangString DESC_SecDesktop ${LANG_ENGLISH} "Create a desktop shortcut."

Section "Mosend 主程序" SecMain
  SectionIn RO
  SetOutPath "$INSTDIR"
  File /oname=Mosend.exe "dist\Mosend-windows-${ARCH}.exe"
  File "README.md"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  CreateDirectory "$SMPROGRAMS\Mosend"
  CreateShortcut "$SMPROGRAMS\Mosend\Mosend.lnk" "$INSTDIR\Mosend.exe" "" "$INSTDIR\Mosend.exe" 0
  CreateShortcut "$SMPROGRAMS\Mosend\卸载 Mosend.lnk" "$INSTDIR\Uninstall.exe"

  WriteRegStr HKCU "Software\Mosend" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mosend" "DisplayName" "Mosend"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mosend" "DisplayVersion" "${APPVERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mosend" "Publisher" "Mosend"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mosend" "DisplayIcon" "$INSTDIR\Mosend.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mosend" "UninstallString" "$\"$INSTDIR\Uninstall.exe$\""
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mosend" "InstallLocation" "$INSTDIR"
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mosend" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mosend" "NoRepair" 1
SectionEnd

Section "创建桌面快捷方式" SecDesktop
  CreateShortcut "$DESKTOP\Mosend.lnk" "$INSTDIR\Mosend.exe" "" "$INSTDIR\Mosend.exe" 0
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecMain} $(DESC_SecMain)
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDesktop} $(DESC_SecDesktop)
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
  Delete "$SMPROGRAMS\Mosend\Mosend.lnk"
  Delete "$SMPROGRAMS\Mosend\卸载 Mosend.lnk"
  RMDir "$SMPROGRAMS\Mosend"
  Delete "$DESKTOP\Mosend.lnk"

  Delete "$INSTDIR\Mosend.exe"
  Delete "$INSTDIR\README.md"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"

  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mosend"
  DeleteRegKey HKCU "Software\Mosend"
SectionEnd
