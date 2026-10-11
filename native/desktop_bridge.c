/*
 * Wyrm Desktop's side of the engine (wyrm.dll).
 *
 * The engine is Wyrm Android's, compiled with VLITHER_ANDROID (SDL3 + JNI) and
 * WYRM_DESKTOP. Android's platform files talk to a Java class named
 * com.wyrm.omrajput.WyrmActivity through JNI; on a desktop that class is the
 * Compose app's Kotlin object of the same name, so those files work unchanged.
 * This file supplies what SDL's Android build would have (the JNIEnv), runs
 * the engine on a JVM thread, and connects it to Compose:
 *   - the game area's size (Compose) -> the hidden SDL window,
 *   - every finished frame (tcontext.c readback) -> a double-buffered mailbox
 *     Compose copies from when it draws,
 *   - mouse and keys (Compose) -> SDL events on the engine's window.
 */
#include <jni.h>
#include <SDL3/SDL.h>
#include <direct.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "desktop_compat.h"

/* main.c (TDEF_ENTRY) and its frame policy. */
int main(int argc, char** argv);
void wyrm_set_frame_policy(int vsync, int cap);

static JavaVM* desktop_vm;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  (void)reserved;
  desktop_vm = vm;
  return JNI_VERSION_1_8;
}

void* SDL_GetAndroidJNIEnv(void) {
  if (!desktop_vm) return NULL;
  JNIEnv* env = NULL;
  if ((*desktop_vm)->GetEnv(desktop_vm, (void**)&env, JNI_VERSION_1_8) == JNI_OK)
    return env;
  if ((*desktop_vm)->AttachCurrentThreadAsDaemon(desktop_vm, (void**)&env, NULL) ==
      JNI_OK)
    return env;
  return NULL;
}

void* SDL_GetAndroidActivity(void) { return NULL; }

/* ---- user.dat in %APPDATA%\Wyrm ----
   The engine's working folder is Wyrm Android's tree (it reads app/res from
   there), so its settings file used to land in the source tree. It lives
   with the shell's preferences now. */

static char user_dir[1024];

static void desktop_user_dir_init(void) {
  if (user_dir[0]) return;
  const char* appdata = getenv("APPDATA");
  if (appdata && appdata[0]) {
    SDL_snprintf(user_dir, sizeof(user_dir), "%s\\Wyrm", appdata);
    _mkdir(user_dir);
  } else {
    SDL_strlcpy(user_dir, ".", sizeof(user_dir));
  }
}

/* "" -> user.dat, ".tmp", ".bak": one buffer each, since a save renames one
   onto another; anything else shares a fourth. */
const char* wyrm_desktop_user_file(const char* suffix) {
  static char paths[4][1100];
  desktop_user_dir_init();
  if (!suffix) suffix = "";
  int slot = suffix[0] == 0 ? 0 : strcmp(suffix, ".tmp") == 0 ? 1
           : strcmp(suffix, ".bak") == 0 ? 2 : 3;
  SDL_snprintf(paths[slot], sizeof(paths[slot]), "%s\\user.dat%s", user_dir, suffix);
  return paths[slot];
}

/* Once: an older Wyrm Desktop left user.dat in the working folder; carry it
   over (the old file stays where it was). */
static void desktop_user_migrate(void) {
  const char* target = wyrm_desktop_user_file("");
  FILE* exists = fopen(target, "rb");
  if (exists) {
    fclose(exists);
    return;
  }
  FILE* old = fopen("user.dat", "rb");
  if (!old) return;
  FILE* out = fopen(target, "wb");
  if (out) {
    char buffer[8192];
    size_t n;
    while ((n = fread(buffer, 1, sizeof(buffer), old)) > 0) fwrite(buffer, 1, n, out);
    fclose(out);
    SDL_Log("Wyrm desktop: user.dat carried over to %s", target);
  }
  fclose(old);
}

/* ---- the engine's log on disk (D6, 2026-10-12) ----
   Every SDL_Log line also goes to %LOCALAPPDATA%\Wyrm\logs\engine.log, flushed
   per line, so a crash or drop report can carry the engine's own words. The
   last launch's file is kept as engine.previous.log (a native crash ends the
   process; the next launch reports it with that file). Past 4 MB the file
   starts over. */

static SDL_Mutex* log_lock;
static FILE* log_file;
static long log_bytes;
static char log_path[1100];
static SDL_LogOutputFunction log_default;
static void* log_default_data;

static void desktop_log_output(void* userdata, int category, SDL_LogPriority priority,
                               const char* message) {
  (void)userdata;
  if (log_default) log_default(log_default_data, category, priority, message);
  if (!log_lock || !message) return;
  SDL_LockMutex(log_lock);
  if (log_file && log_bytes > 4L * 1024L * 1024L) {
    fclose(log_file);
    log_file = fopen(log_path, "wb");
    log_bytes = 0;
  }
  if (log_file) {
    SDL_Time now = 0;
    SDL_GetCurrentTime(&now);
    SDL_DateTime t;
    int n;
    if (SDL_TimeToDateTime(now, &t, true)) {
      n = fprintf(log_file, "%02d:%02d:%02d.%03d %s\n", t.hour, t.minute, t.second,
                  t.nanosecond / 1000000, message);
    } else {
      n = fprintf(log_file, "%s\n", message);
    }
    if (n > 0) log_bytes += n;
    fflush(log_file);
  }
  SDL_UnlockMutex(log_lock);
}

static void desktop_log_init(void) {
  if (log_lock) return;
  const char* local = getenv("LOCALAPPDATA");
  if (!local || !local[0]) return;
  char dir[1024];
  SDL_snprintf(dir, sizeof(dir), "%s\\Wyrm", local);
  _mkdir(dir);
  SDL_snprintf(dir, sizeof(dir), "%s\\Wyrm\\logs", local);
  _mkdir(dir);
  SDL_snprintf(log_path, sizeof(log_path), "%s\\engine.log", dir);
  char previous[1100];
  SDL_snprintf(previous, sizeof(previous), "%s\\engine.previous.log", dir);
  remove(previous);
  rename(log_path, previous);
  log_file = fopen(log_path, "wb");
  log_lock = SDL_CreateMutex();
  SDL_GetLogOutputFunction(&log_default, &log_default_data);
  SDL_SetLogOutputFunction(desktop_log_output, NULL);
}

/* ---- the hidden window and its size ---- */

static SDL_Window* desktop_window;
static SDL_AtomicInt want_w;
static SDL_AtomicInt want_h;

void wyrm_desktop_window_created(void* sdl_window) { desktop_window = sdl_window; }

void wyrm_desktop_initial_size(int* width, int* height) {
  int w = SDL_GetAtomicInt(&want_w);
  int h = SDL_GetAtomicInt(&want_h);
  if (w > 0 && h > 0) {
    *width = w;
    *height = h;
  }
}

void wyrm_desktop_apply_size(void* sdl_window) {
  int w = SDL_GetAtomicInt(&want_w);
  int h = SDL_GetAtomicInt(&want_h);
  if (w <= 0 || h <= 0 || !sdl_window) return;
  int cw = 0, ch = 0;
  SDL_GetWindowSizeInPixels((SDL_Window*)sdl_window, &cw, &ch);
  if (cw != w || ch != h) SDL_SetWindowSize((SDL_Window*)sdl_window, w, h);
}

/* ---- the frame mailbox ---- */

static SDL_Mutex* frame_lock;
static uint8_t* frame_back;   /* the engine writes here */
static uint8_t* frame_front;  /* Compose copies from here */
static size_t frame_capacity;
static int frame_w, frame_h, frame_bgra;
static uint32_t frame_seq;

void wyrm_desktop_publish_frame(const void* pixels, int width, int height,
                                int stride, int bgra) {
  if (!frame_lock || !pixels || width <= 0 || height <= 0) return;
  size_t bytes = (size_t)width * height * 4;
  /* Copy outside the lock into the back buffer, then swap under it. */
  SDL_LockMutex(frame_lock);
  if (frame_capacity < bytes) {
    free(frame_back);
    free(frame_front);
    frame_back = malloc(bytes);
    frame_front = malloc(bytes);
    frame_capacity = (frame_back && frame_front) ? bytes : 0;
  }
  uint8_t* back = frame_back;
  SDL_UnlockMutex(frame_lock);
  if (!back || frame_capacity < bytes) return;
  if (stride == width * 4) {
    memcpy(back, pixels, bytes);
  } else {
    for (int y = 0; y < height; ++y)
      memcpy(back + (size_t)y * width * 4, (const uint8_t*)pixels + (size_t)y * stride,
             (size_t)width * 4);
  }
  SDL_LockMutex(frame_lock);
  frame_back = frame_front;
  frame_front = back;
  frame_w = width;
  frame_h = height;
  frame_bgra = bgra;
  frame_seq++;
  SDL_UnlockMutex(frame_lock);
}

/* ---- JNI: Compose -> engine ---- */

#define DESKTOP_JNI(name) Java_com_wyrm_omrajput_WyrmActivity_##name

JNIEXPORT jint JNICALL DESKTOP_JNI(nativeDesktopRun)(JNIEnv* env, jclass clazz,
                                                     jstring work_dir, jint width,
                                                     jint height) {
  (void)clazz;
  desktop_log_init();
  if (!frame_lock) frame_lock = SDL_CreateMutex();
  if (width > 0 && height > 0) {
    SDL_SetAtomicInt(&want_w, width);
    SDL_SetAtomicInt(&want_h, height);
  }
  const char* dir = work_dir ? (*env)->GetStringUTFChars(env, work_dir, NULL) : NULL;
  if (dir) {
    _chdir(dir);
    (*env)->ReleaseStringUTFChars(env, work_dir, dir);
  }
  desktop_user_migrate();
  /* The hidden window does not hold presents to the display: cap at 120 Hz. */
  wyrm_set_frame_policy(1, 120);
  char arg0[] = "wyrm";
  char* argv[] = {arg0, NULL};
  return main(1, argv);
}

JNIEXPORT void JNICALL DESKTOP_JNI(nativeDesktopResize)(JNIEnv* env, jclass clazz,
                                                        jint width, jint height) {
  (void)env;
  (void)clazz;
  if (width < 64) width = 64;
  if (height < 64) height = 64;
  SDL_SetAtomicInt(&want_w, width);
  SDL_SetAtomicInt(&want_h, height);
}

/* Copies the newest frame into `dst` when it is newer than `last_seq` and fits.
   Returns (width << 48) | (height << 32) | seq, with bit 31 of the low word set
   when the frame is BGRA; 0 when there is no frame yet. */
JNIEXPORT jlong JNICALL DESKTOP_JNI(nativeDesktopCopyFrame)(JNIEnv* env, jclass clazz,
                                                            jbyteArray dst,
                                                            jlong last_seq) {
  (void)clazz;
  if (!frame_lock) return 0;
  SDL_LockMutex(frame_lock);
  uint32_t seq = frame_seq;
  int w = frame_w, h = frame_h, bgra = frame_bgra;
  if (seq == 0 || w <= 0 || h <= 0) {
    SDL_UnlockMutex(frame_lock);
    return 0;
  }
  if ((jlong)(seq & 0x7fffffffu) != last_seq && dst) {
    jsize length = (*env)->GetArrayLength(env, dst);
    size_t bytes = (size_t)w * h * 4;
    if ((size_t)length >= bytes) {
      void* target = (*env)->GetPrimitiveArrayCritical(env, dst, NULL);
      if (target) {
        memcpy(target, frame_front, bytes);
        (*env)->ReleasePrimitiveArrayCritical(env, dst, target, 0);
      }
    }
  }
  SDL_UnlockMutex(frame_lock);
  return ((jlong)w << 48) | ((jlong)h << 32) | (jlong)(bgra ? 0x80000000u : 0) |
         (jlong)(seq & 0x7fffffffu);
}

static SDL_WindowID desktop_window_id(void) {
  return desktop_window ? SDL_GetWindowID(desktop_window) : 0;
}

/* action: 0 move, 1 down, 2 up, 3 wheel. x, y in the game area's pixels. */
JNIEXPORT void JNICALL DESKTOP_JNI(nativeDesktopPointer)(JNIEnv* env, jclass clazz,
                                                         jint action, jfloat x,
                                                         jfloat y, jint button,
                                                         jfloat wheel) {
  (void)env;
  (void)clazz;
  SDL_Event event;
  SDL_zero(event);
  Uint64 now = SDL_GetTicksNS();
  if (action == 0) {
    event.type = SDL_EVENT_MOUSE_MOTION;
    event.motion.timestamp = now;
    event.motion.windowID = desktop_window_id();
    event.motion.which = 1;
    event.motion.x = x;
    event.motion.y = y;
  } else if (action == 1 || action == 2) {
    event.type = action == 1 ? SDL_EVENT_MOUSE_BUTTON_DOWN : SDL_EVENT_MOUSE_BUTTON_UP;
    event.button.timestamp = now;
    event.button.windowID = desktop_window_id();
    event.button.which = 1;
    /* 1 left, 2 right, 3 middle, 4 back (X1), 5 forward (X2). */
    event.button.button = (Uint8)(button == 2   ? SDL_BUTTON_RIGHT
                                  : button == 3 ? SDL_BUTTON_MIDDLE
                                  : button == 4 ? SDL_BUTTON_X1
                                  : button == 5 ? SDL_BUTTON_X2
                                                : SDL_BUTTON_LEFT);
    event.button.down = action == 1;
    event.button.clicks = 1;
    event.button.x = x;
    event.button.y = y;
  } else if (action == 3) {
    event.type = SDL_EVENT_MOUSE_WHEEL;
    event.wheel.timestamp = now;
    event.wheel.windowID = desktop_window_id();
    event.wheel.which = 1;
    event.wheel.y = wheel;
    event.wheel.mouse_x = x;
    event.wheel.mouse_y = y;
  } else {
    return;
  }
  SDL_PushEvent(&event);
}

/* An SDL scancode, pressed or released. */
JNIEXPORT void JNICALL DESKTOP_JNI(nativeDesktopKey)(JNIEnv* env, jclass clazz,
                                                     jint scancode, jboolean down) {
  (void)env;
  (void)clazz;
  if (scancode <= 0 || scancode >= SDL_SCANCODE_COUNT) return;
  SDL_Event event;
  SDL_zero(event);
  event.type = down ? SDL_EVENT_KEY_DOWN : SDL_EVENT_KEY_UP;
  event.key.timestamp = SDL_GetTicksNS();
  event.key.windowID = desktop_window_id();
  event.key.scancode = (SDL_Scancode)scancode;
  event.key.key = SDL_GetKeyFromScancode((SDL_Scancode)scancode, SDL_KMOD_NONE, false);
  event.key.down = down;
  SDL_PushEvent(&event);
}

JNIEXPORT void JNICALL DESKTOP_JNI(nativeDesktopQuit)(JNIEnv* env, jclass clazz) {
  (void)env;
  (void)clazz;
  SDL_Event event;
  SDL_zero(event);
  event.type = SDL_EVENT_QUIT;
  SDL_PushEvent(&event);
}

/* ---- Secrets: Windows DPAPI ----
   The Windows twin of Android's Keystore (OM, 2026-10-11, team keys): a blob
   protected here can be opened again only by the same Windows user on this
   computer. Used for the team vault's AES key (TeamVaultKey.kt). */
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <wincrypt.h>

static jbyteArray dpapi(JNIEnv* env, jbyteArray input, bool protect) {
  if (!input) return NULL;
  jsize length = (*env)->GetArrayLength(env, input);
  jbyte* bytes = (*env)->GetByteArrayElements(env, input, NULL);
  if (!bytes) return NULL;
  DATA_BLOB in = {(DWORD)length, (BYTE*)bytes};
  DATA_BLOB out = {0, NULL};
  BOOL ok = protect
                ? CryptProtectData(&in, L"Wyrm", NULL, NULL, NULL,
                                   CRYPTPROTECT_UI_FORBIDDEN, &out)
                : CryptUnprotectData(&in, NULL, NULL, NULL, NULL,
                                     CRYPTPROTECT_UI_FORBIDDEN, &out);
  (*env)->ReleaseByteArrayElements(env, input, bytes, JNI_ABORT);
  if (!ok) return NULL;
  jbyteArray result = (*env)->NewByteArray(env, (jsize)out.cbData);
  if (result)
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)out.cbData,
                               (const jbyte*)out.pbData);
  SecureZeroMemory(out.pbData, out.cbData);
  LocalFree(out.pbData);
  return result;
}

JNIEXPORT jbyteArray JNICALL DESKTOP_JNI(nativeDesktopProtect)(JNIEnv* env, jclass clazz,
                                                               jbyteArray input) {
  (void)clazz;
  return dpapi(env, input, true);
}

JNIEXPORT jbyteArray JNICALL DESKTOP_JNI(nativeDesktopUnprotect)(JNIEnv* env, jclass clazz,
                                                                 jbyteArray input) {
  (void)clazz;
  return dpapi(env, input, false);
}
