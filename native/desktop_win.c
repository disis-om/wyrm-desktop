/*
 * wyrmwin.dll: the few Windows calls Wyrm Desktop's shell needs that Java
 * cannot make (OM, 2026-10-12). No SDL, no Vulkan: the installer and the
 * uninstaller load it too, before (or without) the engine.
 *
 *   - rounded window corners (Windows 11 DWM corner preference; Windows 10
 *     has no rounded windows and ignores it),
 *   - the process's AppUserModelID, so the taskbar shows Wyrm (its icon,
 *     its own group) rather than "Java",
 *   - Start menu / desktop shortcuts carrying that same id.
 */

#define WIN32_LEAN_AND_MEAN
#define COBJMACROS
#include <jni.h>
#include <windows.h>
#include <dwmapi.h>
#include <shobjidl.h>
#include <shlguid.h>
#include <propkey.h>
#include <propvarutil.h>
#include <stdlib.h>
#include <string.h>

#define WIN_JNI(name) Java_com_wyrm_desktop_WinApi_##name

#ifndef DWMWA_WINDOW_CORNER_PREFERENCE
#define DWMWA_WINDOW_CORNER_PREFERENCE 33
#endif

/* 0 default, 1 square, 2 round (~8 px), 3 small round (~4 px). */
JNIEXPORT jboolean JNICALL WIN_JNI(nativeCorners)(JNIEnv* env, jclass clazz, jlong hwnd, jint preference) {
  (void)env;
  (void)clazz;
  if (!hwnd) return JNI_FALSE;
  DWORD value = (DWORD)preference;
  HRESULT hr = DwmSetWindowAttribute((HWND)(intptr_t)hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, &value, sizeof(value));
  return SUCCEEDED(hr) ? JNI_TRUE : JNI_FALSE;
}

static wchar_t* wide(JNIEnv* env, jstring text) {
  if (!text) return NULL;
  const jchar* chars = (*env)->GetStringChars(env, text, NULL);
  jsize n = (*env)->GetStringLength(env, text);
  wchar_t* out = (wchar_t*)calloc((size_t)n + 1, sizeof(wchar_t));
  if (out && chars) memcpy(out, chars, (size_t)n * sizeof(wchar_t));
  if (chars) (*env)->ReleaseStringChars(env, text, chars);
  return out;
}

JNIEXPORT jboolean JNICALL WIN_JNI(nativeAppId)(JNIEnv* env, jclass clazz, jstring id) {
  (void)clazz;
  wchar_t* value = wide(env, id);
  if (!value) return JNI_FALSE;
  HRESULT hr = SetCurrentProcessExplicitAppUserModelID(value);
  free(value);
  return SUCCEEDED(hr) ? JNI_TRUE : JNI_FALSE;
}

/* A .lnk at [path] that starts [target] [arguments] in [workDir] with [icon],
   tagged with [appId] so a pinned shortcut and the running window group. */
JNIEXPORT jboolean JNICALL WIN_JNI(nativeShortcut)(JNIEnv* env, jclass clazz, jstring path, jstring target,
                                                     jstring arguments, jstring workDir, jstring icon,
                                                     jstring appId, jstring description) {
  (void)clazz;
  wchar_t* lnk = wide(env, path);
  wchar_t* exe = wide(env, target);
  wchar_t* args = wide(env, arguments);
  wchar_t* dir = wide(env, workDir);
  wchar_t* ico = wide(env, icon);
  wchar_t* aid = wide(env, appId);
  wchar_t* desc = wide(env, description);
  jboolean ok = JNI_FALSE;
  HRESULT init = CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
  IShellLinkW* link = NULL;
  if (lnk && exe &&
      SUCCEEDED(CoCreateInstance(&CLSID_ShellLink, NULL, CLSCTX_INPROC_SERVER, &IID_IShellLinkW, (void**)&link))) {
    IShellLinkW_SetPath(link, exe);
    if (args) IShellLinkW_SetArguments(link, args);
    if (dir) IShellLinkW_SetWorkingDirectory(link, dir);
    if (ico) IShellLinkW_SetIconLocation(link, ico, 0);
    if (desc) IShellLinkW_SetDescription(link, desc);
    if (aid) {
      IPropertyStore* store = NULL;
      if (SUCCEEDED(IShellLinkW_QueryInterface(link, &IID_IPropertyStore, (void**)&store))) {
        /* InitPropVariantFromString is C++-only: the same thing by hand. */
        size_t bytes = (wcslen(aid) + 1) * sizeof(wchar_t);
        PROPVARIANT pv;
        PropVariantInit(&pv);
        pv.vt = VT_LPWSTR;
        pv.pwszVal = (LPWSTR)CoTaskMemAlloc(bytes);
        if (pv.pwszVal) {
          memcpy(pv.pwszVal, aid, bytes);
          IPropertyStore_SetValue(store, &PKEY_AppUserModel_ID, &pv);
          IPropertyStore_Commit(store);
          PropVariantClear(&pv);
        }
        IPropertyStore_Release(store);
      }
    }
    IPersistFile* file = NULL;
    if (SUCCEEDED(IShellLinkW_QueryInterface(link, &IID_IPersistFile, (void**)&file))) {
      if (SUCCEEDED(IPersistFile_Save(file, lnk, TRUE))) ok = JNI_TRUE;
      IPersistFile_Release(file);
    }
    IShellLinkW_Release(link);
  }
  if (SUCCEEDED(init)) CoUninitialize();
  free(lnk); free(exe); free(args); free(dir); free(ico); free(aid); free(desc);
  return ok;
}
