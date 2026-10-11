/*
 * WyrmSetup.exe (D8, 2026-10-12): the file a player downloads.
 *
 * This small program carries the whole Wyrm app image as a zip appended to
 * itself (then 8 bytes of its length and the tag "WYRMPAY1"). It shows a
 * small rounded splash (only Wyrm's icon), unpacks the zip into %TEMP%\WyrmSetup-<n> with
 * Windows' own tar.exe, and starts `Wyrm\Wyrm.exe --install` from there (or
 * whatever it was given, e.g. `--update` from Wyrm's updater). The rest — the
 * install screens, the progress, shortcuts, the uninstall entry — is drawn by
 * Wyrm itself (shell Installer.kt). WYRM_SETUP_DRYRUN=1 only unpacks and
 * prints the folder (a check without installing anything).
 */

#define WIN32_LEAN_AND_MEAN
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <dwmapi.h>
#include <shellapi.h>
#include <stdint.h>
#include <stdio.h>
#include <wchar.h>

#define PAPER RGB(0xF6, 0xF4, 0xEF)

static HWND splash;
static wchar_t folder[MAX_PATH];
static wchar_t forwarded[2048];
static wchar_t failure[512];
static volatile LONG done;
static HICON icon;

static void fail(const wchar_t* text) {
  lstrcpynW(failure, text, 512);
}

/* The payload: its offset and length in this exe, from the trailer. */
static BOOL find_payload(HANDLE file, uint64_t* offset, uint64_t* length) {
  LARGE_INTEGER size;
  if (!GetFileSizeEx(file, &size) || size.QuadPart < 16) return FALSE;
  LARGE_INTEGER at;
  at.QuadPart = size.QuadPart - 16;
  if (!SetFilePointerEx(file, at, NULL, FILE_BEGIN)) return FALSE;
  unsigned char trailer[16];
  DWORD got = 0;
  if (!ReadFile(file, trailer, 16, &got, NULL) || got != 16) return FALSE;
  if (memcmp(trailer + 8, "WYRMPAY1", 8) != 0) return FALSE;
  uint64_t n = 0;
  for (int i = 7; i >= 0; i--) n = (n << 8) | trailer[i];
  if (n == 0 || n > (uint64_t)size.QuadPart - 16) return FALSE;
  *length = n;
  *offset = (uint64_t)size.QuadPart - 16 - n;
  return TRUE;
}

static BOOL run_and_wait(wchar_t* command, const wchar_t* dir, DWORD* code) {
  STARTUPINFOW si = {sizeof(si)};
  PROCESS_INFORMATION pi;
  if (!CreateProcessW(NULL, command, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, dir, &si, &pi)) return FALSE;
  WaitForSingleObject(pi.hProcess, INFINITE);
  GetExitCodeProcess(pi.hProcess, code);
  CloseHandle(pi.hThread);
  CloseHandle(pi.hProcess);
  return TRUE;
}

static DWORD WINAPI unpack(LPVOID unused) {
  (void)unused;
  wchar_t self[MAX_PATH];
  GetModuleFileNameW(NULL, self, MAX_PATH);
  HANDLE file = CreateFileW(self, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
  uint64_t offset = 0, length = 0;
  if (file == INVALID_HANDLE_VALUE || !find_payload(file, &offset, &length)) {
    fail(L"This copy of Wyrm Setup is damaged. Download it again.");
    goto out;
  }
  wchar_t temp[MAX_PATH];
  GetTempPathW(MAX_PATH, temp);
  swprintf(folder, MAX_PATH, L"%sWyrmSetup-%lu", temp, GetTickCount());
  CreateDirectoryW(folder, NULL);
  wchar_t zip[MAX_PATH];
  swprintf(zip, MAX_PATH, L"%s\\payload.zip", folder);
  HANDLE out_file = CreateFileW(zip, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_TEMPORARY, NULL);
  if (out_file == INVALID_HANDLE_VALUE) { fail(L"Wyrm Setup could not write to your temporary folder."); goto out; }
  LARGE_INTEGER at;
  at.QuadPart = (LONGLONG)offset;
  SetFilePointerEx(file, at, NULL, FILE_BEGIN);
  static unsigned char buffer[1 << 20];
  uint64_t left = length;
  while (left > 0) {
    DWORD want = left > sizeof(buffer) ? (DWORD)sizeof(buffer) : (DWORD)left;
    DWORD got = 0, wrote = 0;
    if (!ReadFile(file, buffer, want, &got, NULL) || got == 0 || !WriteFile(out_file, buffer, got, &wrote, NULL) || wrote != got) {
      CloseHandle(out_file);
      fail(L"Wyrm Setup could not unpack itself. Check free disk space and try again.");
      goto out;
    }
    left -= got;
  }
  CloseHandle(out_file);

  /* Windows 10 and 11 have bsdtar, which reads zip files. */
  wchar_t system[MAX_PATH];
  GetSystemDirectoryW(system, MAX_PATH);
  wchar_t command[2048];
  swprintf(command, 2048, L"\"%s\\tar.exe\" -xf \"%s\" -C \"%s\"", system, zip, folder);
  DWORD code = 1;
  if (!run_and_wait(command, folder, &code) || code != 0) {
    fail(L"Wyrm Setup could not unpack Wyrm (Windows' tar.exe is missing or failed).");
    goto out;
  }
  DeleteFileW(zip);

  wchar_t exe[MAX_PATH];
  swprintf(exe, MAX_PATH, L"%s\\Wyrm\\Wyrm.exe", folder);
  if (GetFileAttributesW(exe) == INVALID_FILE_ATTRIBUTES) { fail(L"Wyrm Setup is incomplete. Download it again."); goto out; }

  wchar_t dry[8];
  if (GetEnvironmentVariableW(L"WYRM_SETUP_DRYRUN", dry, 8) > 0) {
    /* A check: say where it unpacked, start nothing. */
    wprintf(L"%s\n", folder);
    goto out;
  }
  wchar_t launch[4096];
  swprintf(launch, 4096, L"\"%s\" %s", exe, forwarded[0] ? forwarded : L"--install");
  STARTUPINFOW si = {sizeof(si)};
  PROCESS_INFORMATION pi;
  wchar_t app_dir[MAX_PATH];
  swprintf(app_dir, MAX_PATH, L"%s\\Wyrm", folder);
  if (!CreateProcessW(NULL, launch, NULL, NULL, FALSE, 0, NULL, app_dir, &si, &pi)) {
    fail(L"Wyrm Setup could not start the installer.");
    goto out;
  }
  /* Keep the splash up a moment while Wyrm's window appears. */
  WaitForInputIdle(pi.hProcess, 6000);
  Sleep(900);
  CloseHandle(pi.hThread);
  CloseHandle(pi.hProcess);
out:
  if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
  InterlockedExchange(&done, 1);
  if (splash) PostMessageW(splash, WM_CLOSE, 0, 0);
  return 0;
}

static LRESULT CALLBACK splash_proc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
  switch (message) {
    case WM_PAINT: {
      PAINTSTRUCT ps;
      HDC dc = BeginPaint(hwnd, &ps);
      RECT r;
      GetClientRect(hwnd, &r);
      HBRUSH paper = CreateSolidBrush(PAPER);
      FillRect(dc, &r, paper);
      DeleteObject(paper);
      /* Only Wyrm's icon, in the middle (OM: no system-font text on the splash). */
      UINT dpi = GetDpiForWindow(hwnd);
      int iconSize = MulDiv(96, dpi, 96);
      if (icon) DrawIconEx(dc, (r.right - iconSize) / 2, (r.bottom - iconSize) / 2, icon, iconSize, iconSize, 0, NULL, DI_NORMAL);
      EndPaint(hwnd, &ps);
      return 0;
    }
    case WM_DESTROY:
      PostQuitMessage(0);
      return 0;
  }
  return DefWindowProcW(hwnd, message, wparam, lparam);
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR args, int show) {
  (void)previous;
  (void)show;
  SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
  if (args) lstrcpynW(forwarded, args, 2048);
  icon = (HICON)LoadImageW(instance, MAKEINTRESOURCEW(1), IMAGE_ICON, 256, 256, LR_DEFAULTCOLOR);

  wchar_t dry[8];
  BOOL quiet = GetEnvironmentVariableW(L"WYRM_SETUP_DRYRUN", dry, 8) > 0;
  if (!quiet) {
    WNDCLASSW wc = {0};
    wc.lpfnWndProc = splash_proc;
    wc.hInstance = instance;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hIcon = icon;
    wc.lpszClassName = L"WyrmSetupSplash";
    RegisterClassW(&wc);
    UINT dpi = GetDpiForSystem();
    int w = MulDiv(200, dpi, 96), h = MulDiv(200, dpi, 96);
    RECT work;
    SystemParametersInfoW(SPI_GETWORKAREA, 0, &work, 0);
    splash = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_TOPMOST, L"WyrmSetupSplash", L"Wyrm Setup", WS_POPUP,
                             work.left + (work.right - work.left - w) / 2, work.top + (work.bottom - work.top - h) / 2,
                             w, h, NULL, NULL, instance, NULL);
    DWORD round = 2; /* Windows 11 rounded corners. */
    DwmSetWindowAttribute(splash, 33, &round, sizeof(round));
    ShowWindow(splash, SW_SHOWNORMAL);
    UpdateWindow(splash);
  }

  HANDLE worker = CreateThread(NULL, 0, unpack, NULL, 0, NULL);
  if (quiet) {
    WaitForSingleObject(worker, INFINITE);
  } else {
    MSG msg;
    while (GetMessageW(&msg, NULL, 0, 0) > 0) {
      TranslateMessage(&msg);
      DispatchMessageW(&msg);
    }
    WaitForSingleObject(worker, INFINITE);
  }
  CloseHandle(worker);
  if (failure[0]) {
    if (quiet) wprintf(L"ERROR %s\n", failure);
    else MessageBoxW(NULL, failure, L"Wyrm Setup", MB_OK | MB_ICONWARNING);
    return 1;
  }
  return 0;
}
