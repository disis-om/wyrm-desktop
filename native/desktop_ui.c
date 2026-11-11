/*
 * Wyrm Desktop's game window (OM, 2026-11-12).
 *
 * "lobby ab compose me nahi balki lobby glfw me banegi, and home aane pr
 * compose ka app aayega ... arena me khelte hue koi compose layer nahi
 * aayega ... ui design same rehna chaiye."
 *
 * The lobby (Ready Room) and the match are the engine's own SDL window on
 * screen, as the reference client's GLFW window: its own mouse and keys
 * straight from Windows, presented straight to the screen, no Compose window
 * or layer over it. Compose shows only Home and the pages behind it; going
 * to the lobby swaps the two windows at the same place and size.
 *
 * What the lobby, the arena picker and the chat box *do* stays in Kotlin
 * (NativeGame.kt: the directory, pings, saved arenas, chat sending): Kotlin
 * sends what to show as small text models, this file draws them with ImGui in
 * the app's own design (the Compose pages' sizes, colours, fonts and
 * motion) and sends back what the player did. The strip (Wyrm's window
 * frame: drag, double-click maximise, the three buttons, resize edges) is
 * drawn here too.
 */
#include <jni.h>
#include <SDL3/SDL.h>
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <dwmapi.h>

#include "desktop_compat.h"
#include "user.h"
#include "game/arena_theme.h"
#include "rendering/texture.h"

#ifndef IM_COL32
#define IM_COL32(r, g, b, a) \
  (((ImU32)(a) << 24) | ((ImU32)(b) << 16) | ((ImU32)(g) << 8) | (ImU32)(r))
#endif

void wyrm_desktop_target_request(void* hwnd); /* desktop_bridge.c */
void wyrm_desktop_ui_apply_icon(void);         /* below */

/* ------------------------------------------------------------------ JNI */

#define UI_JNI(name) Java_com_wyrm_omrajput_WyrmActivity_##name

static jclass activity_class;

/* UTF-16 from Java -> real UTF-8 (GetStringUTFChars is modified UTF-8). */
static void jstring_utf8(JNIEnv* env, jstring s, char* out, size_t cap) {
  if (!cap) return;
  out[0] = 0;
  if (!s) return;
  jsize n = (*env)->GetStringLength(env, s);
  const jchar* c = (*env)->GetStringChars(env, s, NULL);
  if (!c) return;
  size_t o = 0;
  for (jsize i = 0; i < n; ++i) {
    uint32_t cp = c[i];
    if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n && c[i + 1] >= 0xDC00 && c[i + 1] <= 0xDFFF) {
      cp = 0x10000 + ((cp - 0xD800) << 10) + (c[i + 1] - 0xDC00);
      ++i;
    }
    char buf[4];
    size_t k;
    if (cp < 0x80) { buf[0] = (char)cp; k = 1; }
    else if (cp < 0x800) { buf[0] = (char)(0xC0 | (cp >> 6)); buf[1] = (char)(0x80 | (cp & 63)); k = 2; }
    else if (cp < 0x10000) { buf[0] = (char)(0xE0 | (cp >> 12)); buf[1] = (char)(0x80 | ((cp >> 6) & 63)); buf[2] = (char)(0x80 | (cp & 63)); k = 3; }
    else { buf[0] = (char)(0xF0 | (cp >> 18)); buf[1] = (char)(0x80 | ((cp >> 12) & 63)); buf[2] = (char)(0x80 | ((cp >> 6) & 63)); buf[3] = (char)(0x80 | (cp & 63)); k = 4; }
    if (o + k >= cap) break;
    memcpy(out + o, buf, k);
    o += k;
  }
  out[o] = 0;
  (*env)->ReleaseStringChars(env, s, c);
}

/* UTF-8 -> a Java string (through UTF-16, so emoji survive). */
static jstring utf8_jstring(JNIEnv* env, const char* s) {
  if (!s) s = "";
  size_t len = strlen(s);
  jchar* w = malloc((len + 1) * sizeof(jchar) * 2);
  if (!w) return (*env)->NewStringUTF(env, "");
  jsize n = 0;
  const unsigned char* p = (const unsigned char*)s;
  while (*p) {
    uint32_t cp;
    int k;
    if (*p < 0x80) { cp = *p; k = 1; }
    else if ((*p & 0xE0) == 0xC0) { cp = *p & 31; k = 2; }
    else if ((*p & 0xF0) == 0xE0) { cp = *p & 15; k = 3; }
    else { cp = *p & 7; k = 4; }
    int ok = 1;
    for (int i = 1; i < k; ++i) {
      if ((p[i] & 0xC0) != 0x80) { ok = 0; break; }
      cp = (cp << 6) | (p[i] & 63);
    }
    if (!ok) { cp = 0xFFFD; k = 1; }
    p += k;
    if (cp >= 0x10000) {
      cp -= 0x10000;
      w[n++] = (jchar)(0xD800 + (cp >> 10));
      w[n++] = (jchar)(0xDC00 + (cp & 1023));
    } else {
      w[n++] = (jchar)cp;
    }
  }
  jstring out = (*env)->NewString(env, w, n);
  free(w);
  return out;
}

enum {
  ACT_PLAY = 1, ACT_PLAY_AI = 2, ACT_HOME = 3, ACT_OPEN_PICKER = 4, ACT_NICKNAME = 5,
  ACT_CLOSE_PICKER = 6, ACT_PICK = 7, ACT_SEARCH = 8, ACT_TOGGLE_ADD = 9, ACT_ADDRESS = 10,
  ACT_SAVE_ADDRESS = 11, ACT_REMOVE_SAVED = 12, ACT_SEE_ALL = 13, ACT_TOGGLE_SAVED = 14,
  ACT_CHAT_OPEN = 20, ACT_CHAT_DRAFT = 21, ACT_CHAT_SEND = 22, ACT_CHAT_CLOSE = 23, ACT_CHAT_TAB = 24,
  ACT_WINDOW_SHOWN = 30, ACT_CLOSE_APP = 31, ACT_FULLSCREEN = 32,
};

/* Engine thread: what the player did, to NativeGame.onAction (Kotlin). */
static void send_action(int action, const char* text) {
  JNIEnv* env = SDL_GetAndroidJNIEnv();
  if (!env) return;
  if (!activity_class) {
    jclass local = (*env)->FindClass(env, "com/wyrm/omrajput/WyrmActivity");
    if (!local) { (*env)->ExceptionClear(env); return; }
    activity_class = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
  }
  jmethodID m = (*env)->GetStaticMethodID(env, activity_class, "desktopUiActionFromNative",
                                          "(ILjava/lang/String;)V");
  if (!m) { (*env)->ExceptionClear(env); return; }
  jstring s = utf8_jstring(env, text);
  (*env)->CallStaticVoidMethod(env, activity_class, m, (jint)action, s);
  if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
  if (s) (*env)->DeleteLocalRef(env, s);
}

/* ------------------------------------------------- shared state (locked) */

static SDL_Mutex* ui_lock;
static void ensure_lock(void) {
  if (!ui_lock) ui_lock = SDL_CreateMutex();
}

/* Show / hide requests from Kotlin. */
static SDL_AtomicInt want_show;
static SDL_AtomicInt want_seq;          /* bumps on every request */
static HWND compose_hwnd;               /* the Compose window: show at its place */
static int req_maximized, req_fullscreen;
static RECT req_restore;
/* The game window's place for Kotlin when it hands back (physical px). */
static SDL_AtomicInt state_shown;
static int st_x, st_y, st_w, st_h, st_max, st_full, st_rx, st_ry, st_rw, st_rh;

/* Models: one text per kind, a version each. */
enum { MODEL_LOBBY, MODEL_PICKER, MODEL_CHAT, MODEL_COUNT };
static char* model_text[MODEL_COUNT];
static int model_version[MODEL_COUNT];

/* Images waiting for the engine thread. */
typedef struct pending_image {
  char name[48];
  int w, h;
  unsigned char* rgba;
  struct pending_image* next;
} pending_image;
static pending_image* pending_images;

/* The pointer: RGBA + hotspot (w = 0: Windows' own). */
static unsigned char* cursor_rgba;
static int cursor_w, cursor_h, cursor_hx, cursor_hy, cursor_seq;

/* Settings › Developer › Swapchain images. */
static SDL_AtomicInt swap_images, swap_images_seen;

/* ----------------------------------------------------- engine thread state */

static SDL_Window* win;
static HWND hwnd;
static int shown;
static int shown_frames;
static int seen_seq = -1;
static int maximized, fullscreen;
static RECT restore_rect;   /* back from maximise */
static RECT windowed_rect;  /* back from full screen */
static int was_playing = -1;
static Uint64 lobby_since;
static Uint64 last_frame_ns;
static float dt = 1.0f / 60.0f;

/* Window drag / resize (the strip and the edges, as WindowChrome.kt). */
static int drag_mode;  /* 0 none, 1 move, 2 resize */
static int drag_dx, drag_dy;
static float drag_start_gx, drag_start_gy;
static RECT drag_from;
static Uint64 last_strip_down;
static int swallow_key_up[SDL_SCANCODE_COUNT];

/* Hit areas from the last frame (pixels). */
static float strip_h, beads_x0;
static float chat_x0, chat_y0, chat_x1, chat_y1;
static int chat_visible;

/* --------------------------------------------------------------- models */

typedef struct {
  char arena[160];
  int code, players;
  char nickname[160];
  int has_run, score, kills;
  double seconds;
  char notice[300];
  int entering, ready, canchat;
  char hint[200];
} lobby_model;

typedef struct {
  char kind;  /* L label, A arena, S see all, V saved header, E empty */
  char title[96];
  char endpoint[96];
  char country[8];
  char latency[32];
  uint32_t latency_argb;
  char tag[32];
  int selected, removable, open, count;
} picker_row;

typedef struct {
  int open;
  char status[120];
  int add, address_error;
  char search[96];
  int nrows;
  picker_row rows[420];
} picker_model;

typedef struct {
  int open, global, busy;
  char error[300];
  char hint[200];
  char draft[1200];
} chat_model;

static lobby_model L = {.code = -1, .players = -1, .ready = 0};
static picker_model P;
static chat_model C;
static int parsed_version[MODEL_COUNT] = {-1, -1, -1};

static char nickname_buf[160];
static char search_buf[96];
static char address_buf[96];
static char draft_buf[1200];
static int nickname_active, search_active, address_active, draft_active;
static int picker_was_open, add_was_open, chat_was_open;
static float picker_anim, chat_anim;

static void copy_field(char* dst, size_t cap, const char* src) {
  size_t n = strlen(src);
  if (n >= cap) n = cap - 1;
  memcpy(dst, src, n);
  dst[n] = 0;
}

/* Splits `line` at tabs into up to `max` fields (in place). */
static int split_tabs(char* line, char** f, int max) {
  int n = 0;
  f[n++] = line;
  for (char* p = line; *p && n < max; ++p) {
    if (*p == '\t') {
      *p = 0;
      f[n++] = p + 1;
    }
  }
  return n;
}

static void parse_models(void) {
  ensure_lock();
  for (int kind = 0; kind < MODEL_COUNT; ++kind) {
    SDL_LockMutex(ui_lock);
    int version = model_version[kind];
    char* text = NULL;
    if (version != parsed_version[kind] && model_text[kind]) text = SDL_strdup(model_text[kind]);
    SDL_UnlockMutex(ui_lock);
    if (!text) continue;
    parsed_version[kind] = version;
    if (kind == MODEL_LOBBY) {
      lobby_model m = {.code = -1, .players = -1};
      for (char* line = strtok(text, "\n"); line; line = strtok(NULL, "\n")) {
        char* eq = strchr(line, '=');
        if (!eq) continue;
        *eq = 0;
        const char* k = line;
        char* v = eq + 1;
        if (!strcmp(k, "arena")) copy_field(m.arena, sizeof(m.arena), v);
        else if (!strcmp(k, "code")) m.code = atoi(v);
        else if (!strcmp(k, "players")) m.players = atoi(v);
        else if (!strcmp(k, "nickname")) copy_field(m.nickname, sizeof(m.nickname), v);
        else if (!strcmp(k, "run")) {
          char* f[3];
          if (split_tabs(v, f, 3) == 3) {
            m.has_run = 1;
            m.score = atoi(f[0]);
            m.kills = atoi(f[1]);
            m.seconds = atof(f[2]);
          }
        } else if (!strcmp(k, "notice")) copy_field(m.notice, sizeof(m.notice), v);
        else if (!strcmp(k, "entering")) m.entering = atoi(v);
        else if (!strcmp(k, "ready")) m.ready = atoi(v);
        else if (!strcmp(k, "canchat")) m.canchat = atoi(v);
        else if (!strcmp(k, "hint")) copy_field(m.hint, sizeof(m.hint), v);
      }
      L = m;
      if (!nickname_active) copy_field(nickname_buf, sizeof(nickname_buf), L.nickname);
    } else if (kind == MODEL_PICKER) {
      static picker_model m;
      memset(&m, 0, sizeof(m));
      for (char* line = strtok(text, "\n"); line; line = strtok(NULL, "\n")) {
        char* eq = strchr(line, '=');
        if (!eq) continue;
        *eq = 0;
        const char* k = line;
        char* v = eq + 1;
        if (!strcmp(k, "open")) m.open = atoi(v);
        else if (!strcmp(k, "status")) copy_field(m.status, sizeof(m.status), v);
        else if (!strcmp(k, "add")) m.add = atoi(v);
        else if (!strcmp(k, "addressError")) m.address_error = atoi(v);
        else if (!strcmp(k, "search")) copy_field(m.search, sizeof(m.search), v);
        else if (!strcmp(k, "row") && m.nrows < (int)(sizeof(m.rows) / sizeof(m.rows[0]))) {
          char* f[10];
          int n = split_tabs(v, f, 10);
          picker_row* r = &m.rows[m.nrows];
          memset(r, 0, sizeof(*r));
          r->kind = f[0][0];
          if (r->kind == 'A' && n >= 9) {
            copy_field(r->title, sizeof(r->title), f[1]);
            copy_field(r->endpoint, sizeof(r->endpoint), f[2]);
            copy_field(r->country, sizeof(r->country), f[3]);
            copy_field(r->latency, sizeof(r->latency), f[4]);
            r->latency_argb = (uint32_t)strtoul(f[5], NULL, 16);
            copy_field(r->tag, sizeof(r->tag), f[6]);
            r->selected = atoi(f[7]);
            r->removable = atoi(f[8]);
          } else if (r->kind == 'V' && n >= 3) {
            r->count = atoi(f[1]);
            r->open = atoi(f[2]);
          } else if (n >= 2) {
            copy_field(r->title, sizeof(r->title), f[1]);
          }
          m.nrows++;
        }
      }
      P = m;
      if (!search_active) copy_field(search_buf, sizeof(search_buf), P.search);
      if (!P.add) address_buf[0] = 0;
    } else if (kind == MODEL_CHAT) {
      chat_model m;
      memset(&m, 0, sizeof(m));
      for (char* line = strtok(text, "\n"); line; line = strtok(NULL, "\n")) {
        char* eq = strchr(line, '=');
        if (!eq) continue;
        *eq = 0;
        const char* k = line;
        char* v = eq + 1;
        if (!strcmp(k, "open")) m.open = atoi(v);
        else if (!strcmp(k, "global")) m.global = atoi(v);
        else if (!strcmp(k, "busy")) m.busy = atoi(v);
        else if (!strcmp(k, "error")) copy_field(m.error, sizeof(m.error), v);
        else if (!strcmp(k, "hint")) copy_field(m.hint, sizeof(m.hint), v);
        else if (!strcmp(k, "draft")) copy_field(m.draft, sizeof(m.draft), v);
      }
      int opening = m.open && !C.open;
      C = m;
      if (opening || !draft_active) copy_field(draft_buf, sizeof(draft_buf), C.draft);
    }
    SDL_free(text);
  }
}

/* --------------------------------------------------------------- images */

typedef struct {
  char name[48];
  int w, h;
  texture* tex;
  VkDescriptorSet set;
  texture_upload* up;
  unsigned char* rgba;   /* kept until the upload has finished */
} ui_image;

static ui_image images[160];
static int image_count;

static ui_image* image_find(const char* name) {
  for (int i = 0; i < image_count; ++i)
    if (!strcmp(images[i].name, name)) return &images[i];
  return NULL;
}

static void images_pump(tenv* env) {
  ensure_lock();
  SDL_LockMutex(ui_lock);
  pending_image* list = pending_images;
  pending_images = NULL;
  SDL_UnlockMutex(ui_lock);
  /* Oldest first. */
  pending_image* rev = NULL;
  while (list) {
    pending_image* next = list->next;
    list->next = rev;
    rev = list;
    list = next;
  }
  for (pending_image* p = rev; p;) {
    pending_image* next = p->next;
    ui_image* img = image_find(p->name);
    if (!img && image_count < (int)(sizeof(images) / sizeof(images[0]))) {
      img = &images[image_count++];
      memset(img, 0, sizeof(*img));
      SDL_strlcpy(img->name, p->name, sizeof(img->name));
    }
    if (img) {
      if (img->up) {
        texture_upload_cancel(env->ctx, img->up);
        img->up = NULL;
      }
      free(img->rgba);
      img->rgba = p->rgba;
      img->w = p->w;
      img->h = p->h;
      img->up = texture_upload_begin(env->ctx, img->rgba, p->w, p->h, false);
    } else {
      free(p->rgba);
    }
    free(p);
    p = next;
  }
  for (int i = 0; i < image_count; ++i) {
    ui_image* img = &images[i];
    if (!img->up) continue;
    texture* done = NULL;
    if (!texture_upload_poll(env->ctx, img->up, &done)) continue;
    img->up = NULL;
    free(img->rgba);
    img->rgba = NULL;
    if (!done) continue;
    if (img->set || img->tex) vkDeviceWaitIdle(env->ctx->device);
    if (img->set) igImplVulkan_RemoveTexture(img->set);
    if (img->tex) destroy_texture(env->ctx, img->tex);
    img->tex = done;
    img->set = igImplVulkan_AddTexture(env->usr->r->linear_sampler, done->view,
                                       VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  }
}

static ImTextureRef image_ref(const ui_image* img) {
  return (ImTextureRef){NULL, (ImTextureID)(uintptr_t)img->set};
}

/* --------------------------------------------------------------- drawing */

static float S = 1.0f;       /* dp -> px */
static float fade = 1.0f;    /* the page's appear alpha */
static tuser_data* usr;

#define DP(v) ((v) * S)
/* Compose sizes are em sizes; ImGui sizes are ascent-to-descent. */
#define K_BODY 1.366f
#define K_DISPLAY 1.525f

static ImU32 col(arena_theme_role role, float alpha) {
  return arena_theme_colour(role, alpha * fade);
}
static ImU32 argb_col(uint32_t argb, float alpha) {
  float a = (float)((argb >> 24) & 255) * alpha * fade;
  if (a > 255) a = 255;
  return ((ImU32)(a + 0.5f) << 24) | ((argb & 255) << 16) | (argb & 0xFF00) | ((argb >> 16) & 255);
}
static ImU32 mix(ImU32 a, ImU32 b, float t) {
  if (t <= 0) return a;
  if (t >= 1) return b;
  ImU32 out = 0;
  for (int s = 0; s < 32; s += 8) {
    float x = (float)((a >> s) & 255), y = (float)((b >> s) & 255);
    out |= (ImU32)(x + (y - x) * t + 0.5f) << s;
  }
  return out;
}

enum { F_MEDIUM, F_SEMIBOLD, F_BOLD, F_EXTRABOLD, F_DISPLAY };
static ImFont* font(int which) {
  switch (which) {
    case F_SEMIBOLD: return usr->imgui_data.regular_font[0];
    case F_BOLD: return usr->imgui_data.mono_font_bold[0];
    case F_EXTRABOLD: return usr->imgui_data.regular_font_bold[0];
    case F_DISPLAY: return usr->imgui_data.display_font[0];
    default: return usr->imgui_data.body_font[0];
  }
}
static float fsize(int which, float sp) { return DP(sp) * (which == F_DISPLAY ? K_DISPLAY : K_BODY); }

static int utf8_len(unsigned char c) {
  return c < 0x80 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
}

/* Width of `text` in `which` at `sp`, with Compose's letter spacing. */
static float text_w(int which, float sp, float spacing_sp, const char* text) {
  ImVec2 size;
  ImFont_CalcTextSizeA(&size, font(which), fsize(which, sp), FLT_MAX, 0, text, NULL, NULL);
  if (spacing_sp != 0) {
    int n = 0;
    for (const unsigned char* p = (const unsigned char*)text; *p; p += utf8_len(*p)) n++;
    size.x += DP(spacing_sp) * n;
  }
  return size.x;
}
static float text_h(int which, float sp) { return fsize(which, sp); }

static void text_at(ImDrawList* dl, int which, float sp, float spacing_sp, float x, float y,
                    ImU32 colour, const char* text) {
  if (!text || !*text) return;
  float size = fsize(which, sp);
  if (spacing_sp == 0) {
    ImDrawList_AddText_FontPtr(dl, font(which), size, (ImVec2){roundf(x), roundf(y)}, colour, text, NULL, 0, NULL);
    return;
  }
  for (const char* p = text; *p;) {
    int k = utf8_len((unsigned char)*p);
    ImDrawList_AddText_FontPtr(dl, font(which), size, (ImVec2){roundf(x), roundf(y)}, colour, p, p + k, 0, NULL);
    ImVec2 w;
    ImFont_CalcTextSizeA(&w, font(which), size, FLT_MAX, 0, p, p + k, NULL);
    x += w.x + DP(spacing_sp);
    p += k;
  }
}

/* One line, cut with an ellipsis to `max_w`. */
static void text_fit(ImDrawList* dl, int which, float sp, float x, float y, float max_w,
                     ImU32 colour, const char* text) {
  if (text_w(which, sp, 0, text) <= max_w) {
    text_at(dl, which, sp, 0, x, y, colour, text);
    return;
  }
  char buf[400];
  SDL_strlcpy(buf, text, sizeof(buf));
  size_t n = strlen(buf);
  while (n > 0) {
    do { n--; } while (n > 0 && ((unsigned char)buf[n] & 0xC0) == 0x80);
    buf[n] = 0;
    char probe[410];
    SDL_snprintf(probe, sizeof(probe), "%s\xE2\x80\xA6", buf);
    if (text_w(which, sp, 0, probe) <= max_w) {
      text_at(dl, which, sp, 0, x, y, colour, probe);
      return;
    }
  }
}

/* Simple per-id animations (hover wells, press scale). */
typedef struct { ImGuiID id; float v; int alive; } anim_slot;
static anim_slot anims[256];
static float anim(ImGuiID id, float target, float speed) {
  anim_slot* free_slot = NULL;
  for (int i = 0; i < 256; ++i) {
    if (anims[i].alive && anims[i].id == id) {
      float k = 1.0f - expf(-speed * dt);
      anims[i].v += (target - anims[i].v) * k;
      if (fabsf(anims[i].v - target) < 0.001f) anims[i].v = target;
      return anims[i].v;
    }
    if (!anims[i].alive && !free_slot) free_slot = &anims[i];
  }
  if (!free_slot) free_slot = &anims[id % 256];
  free_slot->alive = 1;
  free_slot->id = id;
  free_slot->v = target;
  return target;
}

/* Material's FastOutSlowIn, cubic-bezier(0.4, 0, 0.2, 1). */
static float fast_out_slow_in(float t) {
  if (t <= 0) return 0;
  if (t >= 1) return 1;
  float lo = 0, hi = 1, u = t;
  for (int i = 0; i < 24; ++i) {
    u = (lo + hi) * 0.5f;
    float x = 3 * (1 - u) * (1 - u) * u * 0.4f + 3 * (1 - u) * u * u * 0.2f + u * u * u;
    if (x < t) lo = u; else hi = u;
  }
  return 3 * (1 - u) * u * u * 1.0f + u * u * u;
}

/* An invisible button over a rect. Returns clicked; hovered/pressed out. */
static bool hit(const char* id, float x, float y, float w, float h, bool* hovered, bool* pressed) {
  igSetCursorScreenPos((ImVec2){x, y});
  bool clicked = igInvisibleButton(id, (ImVec2){w > 1 ? w : 1, h > 1 ? h : 1}, ImGuiButtonFlags_MouseButtonLeft);
  if (hovered) *hovered = igIsItemHovered(0);
  if (pressed) *pressed = igIsItemActive();
  return clicked;
}

static void scaled_rect(float* x, float* y, float* w, float* h, float s) {
  float nw = *w * s, nh = *h * s;
  *x += (*w - nw) * 0.5f;
  *y += (*h - nh) * 0.5f;
  *w = nw;
  *h = nh;
}

static void image_tinted(ImDrawList* dl, const char* name, float x, float y, float w, float h, ImU32 tint) {
  ui_image* img = image_find(name);
  if (!img || !img->set) return;
  ImDrawList_AddImage(dl, image_ref(img), (ImVec2){x, y}, (ImVec2){x + w, y + h}, (ImVec2){0, 0},
                      (ImVec2){1, 1}, tint);
}

/* The app's InputText look: no frame, the given font and colours. */
static int limit_codepoints(ImGuiInputTextCallbackData* data) {
  int limit = (int)(intptr_t)data->UserData;
  int count = 0;
  for (int i = 0; i < data->BufTextLen;) {
    int k = utf8_len((unsigned char)data->Buf[i]);
    if (count == limit) {
      ImGuiInputTextCallbackData_DeleteChars(data, i, data->BufTextLen - i);
      break;
    }
    count++;
    i += k;
  }
  /* No line breaks or control characters. */
  for (int i = 0; i < data->BufTextLen; ++i) {
    if ((unsigned char)data->Buf[i] < 0x20) {
      ImGuiInputTextCallbackData_DeleteChars(data, i, 1);
      --i;
    }
  }
  return 0;
}

static bool field(const char* id, const char* hint, char* buf, size_t cap, int limit, int which,
                  float sp, float x, float y, float w, ImU32 ink, ImU32 hint_ink, ImU32 caret,
                  int* active, bool focus_now, ImGuiInputTextFlags extra) {
  float size = fsize(which, sp);
  igSetCursorScreenPos((ImVec2){x, y});
  igPushFont(font(which), size);
  igPushStyleColor_U32(ImGuiCol_FrameBg, 0);
  igPushStyleColor_U32(ImGuiCol_FrameBgHovered, 0);
  igPushStyleColor_U32(ImGuiCol_FrameBgActive, 0);
  igPushStyleColor_U32(ImGuiCol_Text, ink);
  igPushStyleColor_U32(ImGuiCol_TextDisabled, hint_ink);
  igPushStyleColor_U32(ImGuiCol_InputTextCursor, caret);
  igPushStyleColor_U32(ImGuiCol_TextSelectedBg, (caret & 0x00FFFFFFu) | 0x40000000u);
  igPushStyleVar_Vec2(ImGuiStyleVar_FramePadding, (ImVec2){0, 0});
  igPushStyleVar_Float(ImGuiStyleVar_FrameBorderSize, 0);
  igSetNextItemWidth(w);
  if (focus_now) igSetKeyboardFocusHere(0);
  bool changed = igInputTextWithHint(id, hint, buf, cap,
                                     ImGuiInputTextFlags_CallbackEdit | extra,
                                     limit_codepoints, (void*)(intptr_t)limit);
  *active = igIsItemActive();
  igPopStyleVar(2);
  igPopStyleColor(7);
  igPopFont();
  return changed;
}

/* ------------------------------------------------------- window helpers */

static RECT work_area(void) {
  MONITORINFO mi = {.cbSize = sizeof(mi)};
  HMONITOR m = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
  RECT r = {0, 0, 1280, 800};
  if (GetMonitorInfoW(m, &mi)) r = mi.rcWork;
  return r;
}
static RECT monitor_area(void) {
  MONITORINFO mi = {.cbSize = sizeof(mi)};
  HMONITOR m = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
  RECT r = {0, 0, 1280, 800};
  if (GetMonitorInfoW(m, &mi)) r = mi.rcMonitor;
  return r;
}
static RECT window_rect(void) {
  RECT r = {0, 0, 0, 0};
  GetWindowRect(hwnd, &r);
  return r;
}
static void place(RECT r) {
  SetWindowPos(hwnd, NULL, r.left, r.top, r.right - r.left, r.bottom - r.top,
               SWP_NOZORDER | SWP_NOACTIVATE);
}

static void toggle_maximize(void) {
  if (fullscreen) return;
  if (maximized) {
    place(restore_rect);
    maximized = 0;
  } else {
    restore_rect = window_rect();
    place(work_area());
    maximized = 1;
  }
}

static void set_fullscreen(int on) {
  if (on == fullscreen) return;
  if (on) {
    windowed_rect = window_rect();
    RECT m = monitor_area();
    SetWindowPos(hwnd, HWND_TOP, m.left, m.top, m.right - m.left, m.bottom - m.top,
                 SWP_NOACTIVATE | SWP_FRAMECHANGED);
    fullscreen = 1;
  } else {
    fullscreen = 0;
    place(windowed_rect);
  }
  send_action(ACT_FULLSCREEN, on ? "1" : "0");
}

void wyrm_desktop_toggle_fullscreen(void) {
  if (shown) set_fullscreen(!fullscreen);
}

static void publish_state(void) {
  RECT r = window_rect();
  ensure_lock();
  SDL_LockMutex(ui_lock);
  st_x = r.left;
  st_y = r.top;
  st_w = r.right - r.left;
  st_h = r.bottom - r.top;
  st_max = maximized;
  st_full = fullscreen;
  RECT back = fullscreen ? windowed_rect : r;
  if (maximized) back = restore_rect;
  st_rx = back.left;
  st_ry = back.top;
  st_rw = back.right - back.left;
  st_rh = back.bottom - back.top;
  SDL_UnlockMutex(ui_lock);
}

/* --------------------------------------------------- show / hide (engine) */

void wyrm_desktop_native_window_poll(void* envp, void* sdl_window) {
  tenv* env = envp;
  win = sdl_window;
  if (!win) return;
  if (!hwnd)
    hwnd = (HWND)SDL_GetPointerProperty(SDL_GetWindowProperties(win),
                                        SDL_PROP_WINDOW_WIN32_HWND_POINTER, NULL);
  wyrm_desktop_ui_apply_icon();
  int seq = SDL_GetAtomicInt(&want_seq);
  if (seq == seen_seq) {
    if (shown) publish_state();
    return;
  }
  seen_seq = seq;
  int want = SDL_GetAtomicInt(&want_show);
  if (want && !shown && hwnd) {
    ensure_lock();
    SDL_LockMutex(ui_lock);
    HWND from = compose_hwnd;
    int max = req_maximized, full = req_fullscreen;
    RECT back = req_restore;
    SDL_UnlockMutex(ui_lock);
    RECT r = {100, 100, 1380, 900};
    if (from && IsWindow(from)) GetWindowRect(from, &r);
    maximized = max;
    restore_rect = back;
    fullscreen = 0;
    /* Windows 11's rounded corners, as the Compose window. */
    DWORD corner = 2; /* DWMWCP_ROUND */
    DwmSetWindowAttribute(hwnd, 33 /* DWMWA_WINDOW_CORNER_PREFERENCE */, &corner, sizeof(corner));
    SetWindowPos(hwnd, HWND_TOP, r.left, r.top, r.right - r.left, r.bottom - r.top, 0);
    SDL_ShowWindow(win);
    SDL_RaiseWindow(win);
    SetForegroundWindow(hwnd);
    if (full) {
      set_fullscreen(1);
      /* Out of full screen: the app window's size, else a centred 80 %. */
      if (back.right - back.left > 200 && back.bottom - back.top > 200) {
        windowed_rect = back;
      } else {
        RECT wa = work_area();
        int ww = (wa.right - wa.left) * 8 / 10, wh = (wa.bottom - wa.top) * 8 / 10;
        windowed_rect = (RECT){wa.left + (wa.right - wa.left - ww) / 2, wa.top + (wa.bottom - wa.top - wh) / 2, 0, 0};
        windowed_rect.right = windowed_rect.left + ww;
        windowed_rect.bottom = windowed_rect.top + wh;
      }
    }
    wyrm_desktop_target_request(hwnd);
    shown = 1;
    shown_frames = 0;
    lobby_since = SDL_GetTicksNS();
    was_playing = env->usr->gdata.curr_screen == PLAYING;
    SDL_SetAtomicInt(&state_shown, 1);
    env->wnd->_refresh = true;
    SDL_StartTextInput(win);
    publish_state();
    SDL_Log("Wyrm game window: on screen at %ld,%ld %ldx%ld", r.left, r.top,
            r.right - r.left, r.bottom - r.top);
  } else if (!want && shown) {
    publish_state();
    SDL_StopTextInput(win);
    drag_mode = 0;
    SDL_HideWindow(win);
    wyrm_desktop_target_request(NULL);
    shown = 0;
    SDL_SetAtomicInt(&state_shown, 0);
    SDL_Log("Wyrm game window: back to Home");
  }
}

int wyrm_desktop_native_shown(void) { return SDL_GetAtomicInt(&state_shown) != 0; }

int wyrm_desktop_native_hover(void) {
  return shown && SDL_GetMouseFocus() == win && SDL_GetKeyboardFocus() == win;
}

int wyrm_desktop_swap_images(void) { return SDL_GetAtomicInt(&swap_images); }
int wyrm_desktop_swap_images_changed(void) {
  int now = SDL_GetAtomicInt(&swap_images);
  if (now == SDL_GetAtomicInt(&swap_images_seen)) return 0;
  SDL_SetAtomicInt(&swap_images_seen, now);
  return wyrm_desktop_direct();
}

/* ------------------------------------------------------------- events */

static int owned_now;
int wyrm_desktop_ui_event_owned(void) { return owned_now; }

static int playing_now(tenv* env) { return env->usr->gdata.curr_screen == PLAYING; }

static int edge_at(float x, float y) {
  /* Bit 1 left, 2 right, 4 top, 8 bottom. */
  if (maximized || fullscreen) return 0;
  int w = 0, h = 0;
  SDL_GetWindowSizeInPixels(win, &w, &h);
  float e = DP(5), c = DP(12);
  int m = 0;
  if (x < e) m |= 1;
  if (x >= w - e) m |= 2;
  if (y < e) m |= 4;
  if (y >= h - e) m |= 8;
  /* Corners take a larger square, as ResizeEdges. */
  if (!m) return 0;
  if ((m & 3) && (y < c)) m |= 4;
  if ((m & 3) && (y >= h - c)) m |= 8;
  if ((m & 12) && (x < c)) m |= 1;
  if ((m & 12) && (x >= w - c)) m |= 2;
  return m;
}

static void push_back_key(void) {
  for (int down = 1; down >= 0; --down) {
    SDL_Event e;
    SDL_zero(e);
    e.type = down ? SDL_EVENT_KEY_DOWN : SDL_EVENT_KEY_UP;
    e.key.timestamp = SDL_GetTicksNS();
    e.key.windowID = SDL_GetWindowID(win);
    e.key.scancode = SDL_SCANCODE_AC_BACK;
    e.key.down = down;
    SDL_PushEvent(&e);
  }
}

static void chat_close_local(void) {
  if (!C.open) return;
  C.open = 0;
  send_action(ACT_CHAT_CLOSE, "");
}

int wyrm_desktop_ui_event(void* envp, const void* ev) {
  tenv* env = envp;
  const SDL_Event* e = ev;
  owned_now = 0;
  if (!shown) return 0;
  int playing = playing_now(env);
  int lobby = !playing;
  switch (e->type) {
    case SDL_EVENT_WINDOW_CLOSE_REQUESTED:
      send_action(ACT_CLOSE_APP, "");
      return 2;
    case SDL_EVENT_QUIT:
      return 0;
    case SDL_EVENT_KEY_DOWN:
    case SDL_EVENT_KEY_UP: {
      SDL_Scancode sc = e->key.scancode;
      bool down = e->type == SDL_EVENT_KEY_DOWN;
      if (!down && sc < SDL_SCANCODE_COUNT && swallow_key_up[sc]) {
        swallow_key_up[sc] = 0;
        return 2;
      }
      if (!down) {
        if (lobby || C.open) { owned_now = 1; return 1; }
        return 0;
      }
      if (sc == SDL_SCANCODE_AC_BACK) return 0; /* ours, pushed below */
      bool alt = (e->key.mod & SDL_KMOD_ALT) != 0;
      if (sc == SDL_SCANCODE_F11 || (alt && (sc == SDL_SCANCODE_RETURN || sc == SDL_SCANCODE_KP_ENTER))) {
        if (!e->key.repeat) set_fullscreen(!fullscreen);
        swallow_key_up[sc] = 1;
        return 2;
      }
      if (lobby) {
        if (sc == SDL_SCANCODE_ESCAPE) {
          if (!e->key.repeat) send_action(P.open ? ACT_CLOSE_PICKER : ACT_HOME, "");
          swallow_key_up[sc] = 1;
          return 2;
        }
        if ((sc == SDL_SCANCODE_RETURN || sc == SDL_SCANCODE_KP_ENTER) && !P.open) {
          if (!e->key.repeat && L.ready && !L.entering) {
            if (nickname_active) send_action(ACT_NICKNAME, nickname_buf);
            send_action(ACT_PLAY, "");
          }
          swallow_key_up[sc] = 1;
          return 2;
        }
        owned_now = 1;
        return 1;
      }
      /* In a match. */
      if (C.open) {
        if (sc == SDL_SCANCODE_ESCAPE) {
          chat_close_local();
          swallow_key_up[sc] = 1;
          return 2;
        }
        if (sc == SDL_SCANCODE_RETURN || sc == SDL_SCANCODE_KP_ENTER) {
          if (!e->key.repeat && draft_buf[0] && !C.busy) {
            send_action(ACT_CHAT_DRAFT, draft_buf);
            send_action(ACT_CHAT_SEND, "");
          }
          swallow_key_up[sc] = 1;
          return 2;
        }
        if (sc == SDL_SCANCODE_TAB) {
          if (!e->key.repeat) send_action(ACT_CHAT_TAB, "");
          swallow_key_up[sc] = 1;
          return 2;
        }
        owned_now = 1;
        return 1;
      }
      if (sc == SDL_SCANCODE_ESCAPE) {
        if (!e->key.repeat) push_back_key();
        swallow_key_up[sc] = 1;
        return 2;
      }
      if ((sc == SDL_SCANCODE_RETURN || sc == SDL_SCANCODE_KP_ENTER) && L.canchat) {
        if (!e->key.repeat) send_action(ACT_CHAT_OPEN, "");
        swallow_key_up[sc] = 1;
        return 2;
      }
      return 0;
    }
    case SDL_EVENT_TEXT_INPUT:
    case SDL_EVENT_TEXT_EDITING:
      if (lobby || C.open) { owned_now = 1; return 1; }
      return 2; /* typing nothing: the game reads keys, not text */
    case SDL_EVENT_MOUSE_BUTTON_DOWN: {
      float x = e->button.x, y = e->button.y;
      if (e->button.button == SDL_BUTTON_LEFT) {
        int edge = edge_at(x, y);
        if (edge) {
          drag_mode = 2;
          drag_dx = edge;
          SDL_GetGlobalMouseState(&drag_start_gx, &drag_start_gy);
          drag_from = window_rect();
          return 2;
        }
        if (lobby && !fullscreen && y < strip_h && x < beads_x0 && !P.open) {
          Uint64 now = SDL_GetTicks();
          if (now - last_strip_down < 350) {
            last_strip_down = 0;
            toggle_maximize();
            return 2;
          }
          last_strip_down = now;
          drag_mode = 1;
          drag_dx = 0;
          SDL_GetGlobalMouseState(&drag_start_gx, &drag_start_gy);
          drag_from = window_rect();
          return 2;
        }
      }
      if (lobby) { owned_now = 1; return 1; }
      if (chat_visible && x >= chat_x0 && x < chat_x1 && y >= chat_y0 && y < chat_y1) {
        owned_now = 1;
        return 1;
      }
      return 0;
    }
    case SDL_EVENT_MOUSE_MOTION: {
      if (drag_mode) {
        float gx, gy;
        SDL_GetGlobalMouseState(&gx, &gy);
        int ddx = (int)lroundf(gx - drag_start_gx), ddy = (int)lroundf(gy - drag_start_gy);
        if (drag_mode == 1) {
          if (!drag_dx && (abs(ddx) > 3 || abs(ddy) > 3)) {
            drag_dx = 1; /* moving */
            if (maximized) {
              /* As WindowChrome.unmaximizeForDrag: back to its size, under the pointer. */
              RECT old = restore_rect;
              int ow = old.right - old.left, oh = old.bottom - old.top;
              float fraction = (gx - drag_from.left) / (float)(drag_from.right - drag_from.left > 0 ? drag_from.right - drag_from.left : 1);
              maximized = 0;
              RECT r = {(LONG)(gx - ow * fraction), (LONG)(gy - DP(18)), 0, 0};
              r.right = r.left + ow;
              r.bottom = r.top + oh;
              place(r);
              drag_from = r;
              drag_start_gx = gx;
              drag_start_gy = gy;
              ddx = ddy = 0;
            }
          }
          if (drag_dx)
            SetWindowPos(hwnd, NULL, drag_from.left + ddx, drag_from.top + ddy, 0, 0,
                         SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
        } else {
          RECT r = drag_from;
          int min_w = (int)DP(1000), min_h = (int)DP(640);
          if (drag_dx & 2) { r.right = drag_from.right + ddx; if (r.right - r.left < min_w) r.right = r.left + min_w; }
          if (drag_dx & 1) { r.left = drag_from.left + ddx; if (r.right - r.left < min_w) r.left = r.right - min_w; }
          if (drag_dx & 8) { r.bottom = drag_from.bottom + ddy; if (r.bottom - r.top < min_h) r.bottom = r.top + min_h; }
          if (drag_dx & 4) { r.top = drag_from.top + ddy; if (r.bottom - r.top < min_h) r.top = r.bottom - min_h; }
          place(r);
        }
        return 2;
      }
      if (lobby) { owned_now = 1; return 1; }
      return 0;
    }
    case SDL_EVENT_MOUSE_BUTTON_UP:
      if (drag_mode && e->button.button == SDL_BUTTON_LEFT) {
        drag_mode = 0;
        return 2;
      }
      if (lobby) { owned_now = 1; return 1; }
      return 0;
    case SDL_EVENT_MOUSE_WHEEL:
      if (lobby) { owned_now = 1; return 1; }
      if (chat_visible && e->wheel.mouse_x >= chat_x0 && e->wheel.mouse_x < chat_x1 &&
          e->wheel.mouse_y >= chat_y0 && e->wheel.mouse_y < chat_y1) {
        owned_now = 1;
        return 1;
      }
      return 0;
    case SDL_EVENT_WINDOW_FOCUS_LOST:
      drag_mode = 0;
      return 0;
    default:
      return 0;
  }
}

/* ------------------------------------------------------------- the strip */

static void window_bead(ImDrawList* dl, const char* id, int kind, float x, float y) {
  /* WindowChrome.kt Bead: 36 x 26, rounded 9, a 10 dp glyph, 1.3 dp line. */
  float w = DP(36), h = DP(26);
  bool hov = false, pr = false;
  bool clicked = hit(id, x, y, w, h, &hov, &pr);
  ImGuiID gid = igGetID_Str(id);
  float t = anim(gid, hov || pr ? 1.0f : 0.0f, 1000.0f / 140.0f * 3.0f);
  int close = kind == 3;
  ImU32 well;
  if (close) well = col(ARENA_THEME_INK, (pr ? 0.82f : 1.0f) * t);
  else well = pr ? col(ARENA_THEME_RULE, t) : col(ARENA_THEME_WELL, t);
  if (t > 0.01f) ImDrawList_AddRectFilled(dl, (ImVec2){x, y}, (ImVec2){x + w, y + h}, well, DP(9), 0);
  ImU32 ink = close ? mix(col(ARENA_THEME_MUTE, 1), col(ARENA_THEME_ON_INK, 1), t)
                    : mix(col(ARENA_THEME_MUTE, 1), col(ARENA_THEME_INK, 1), t);
  float s = DP(10), line = DP(1.3f);
  float gx = x + (w - s) * 0.5f, gy = y + (h - s) * 0.5f;
  if (kind == 3) {
    ImDrawList_AddLine(dl, (ImVec2){gx, gy}, (ImVec2){gx + s, gy + s}, ink, line);
    ImDrawList_AddLine(dl, (ImVec2){gx, gy + s}, (ImVec2){gx + s, gy}, ink, line);
  } else if (kind == 0) {
    ImDrawList_AddLine(dl, (ImVec2){gx, gy + s / 2}, (ImVec2){gx + s, gy + s / 2}, ink, line);
  } else if (kind == 1) {
    ImDrawList_AddRect(dl, (ImVec2){gx, gy}, (ImVec2){gx + s, gy + s}, ink, DP(2.5f), 0, line);
  } else {
    float d = s * 0.78f, o = s - d;
    ImDrawList_AddRect(dl, (ImVec2){gx, gy + o}, (ImVec2){gx + d, gy + o + d}, ink, DP(2), 0, line);
    ImDrawList_AddLine(dl, (ImVec2){gx + o + line, gy}, (ImVec2){gx + s, gy}, ink, line);
    ImDrawList_AddLine(dl, (ImVec2){gx + s, gy}, (ImVec2){gx + s, gy + d - line}, ink, line);
  }
  if (clicked) {
    if (kind == 0) ShowWindow(hwnd, SW_MINIMIZE);
    else if (kind == 3) send_action(ACT_CLOSE_APP, "");
    else toggle_maximize();
  }
}

static void draw_strip(ImDrawList* dl, float vw) {
  strip_h = DP(38);
  /* Icon 18 dp rounded 5, "Wyrm" 12.5 sp bold, 0.2 sp, mute. */
  float x = DP(14), cy = strip_h * 0.5f;
  ui_image* icon = image_find("app");
  if (icon && icon->set)
    ImDrawList_AddImageRounded(dl, image_ref(icon), (ImVec2){x, cy - DP(9)}, (ImVec2){x + DP(18), cy + DP(9)},
                               (ImVec2){0, 0}, (ImVec2){1, 1}, IM_COL32(255, 255, 255, (int)(255 * fade)), DP(5), 0);
  x += DP(18) + DP(8);
  text_at(dl, F_BOLD, 12.5f, 0.2f, x, cy - text_h(F_BOLD, 12.5f) * 0.5f, col(ARENA_THEME_MUTE, 1), "Wyrm");
  float bw = DP(36), gap = DP(2);
  float right = vw - DP(12);
  float bx = right - bw * 3 - gap * 2;
  beads_x0 = bx;
  float by = cy - DP(13);
  window_bead(dl, "##wyrm_min", 0, bx, by);
  window_bead(dl, "##wyrm_max", maximized ? 2 : 1, bx + bw + gap, by);
  window_bead(dl, "##wyrm_close", 3, bx + (bw + gap) * 2, by);
}

/* -------------------------------------------------------------- the lobby */

static void card(ImDrawList* dl, float x, float y, float w, float h, float r, ImU32 fill, ImU32 border) {
  ImDrawList_AddRectFilled(dl, (ImVec2){x, y}, (ImVec2){x + w, y + h}, fill, r, 0);
  if (border) ImDrawList_AddRect(dl, (ImVec2){x + 0.5f, y + 0.5f}, (ImVec2){x + w - 0.5f, y + h - 0.5f}, border, r, 0, DP(1));
}

static void chip(ImDrawList* dl, float x, float y, const char* label, const char* value, float* out_w) {
  /* Capsule, well, 12 x 6 padding: LABEL 10 sp bold 1 sp quiet, 6 dp, value 13 sp bold. */
  char upper[64];
  SDL_strlcpy(upper, label, sizeof(upper));
  for (char* p = upper; *p; ++p) if (*p >= 'a' && *p <= 'z') *p -= 32;
  float lw = text_w(F_BOLD, 10, 1, upper), vw = text_w(F_BOLD, 13, 0, value);
  float h = DP(6) * 2 + text_h(F_BOLD, 13);
  float w = DP(12) * 2 + lw + DP(6) + vw;
  ImDrawList_AddRectFilled(dl, (ImVec2){x, y}, (ImVec2){x + w, y + h}, col(ARENA_THEME_WELL, 1), h * 0.5f, 0);
  float cy = y + h * 0.5f;
  text_at(dl, F_BOLD, 10, 1, x + DP(12), cy - text_h(F_BOLD, 10) * 0.5f, col(ARENA_THEME_QUIET, 1), upper);
  text_at(dl, F_BOLD, 13, 0, x + DP(12) + lw + DP(6), cy - text_h(F_BOLD, 13) * 0.5f, col(ARENA_THEME_INK, 1), value);
  *out_w = w;
}

static void group_digits(long long v, char* out, size_t cap) {
  char raw[32];
  SDL_snprintf(raw, sizeof(raw), "%lld", v < 0 ? -v : v);
  size_t n = strlen(raw), o = 0;
  if (v < 0 && o + 1 < cap) out[o++] = '-';
  for (size_t i = 0; i < n && o + 2 < cap; ++i) {
    out[o++] = raw[i];
    size_t left = n - 1 - i;
    if (left && left % 3 == 0) out[o++] = ',';
  }
  out[o] = 0;
}

static void lobby_button(ImDrawList* dl, const char* id, const char* label, const char* icon,
                         float x, float y, float w, bool enabled, int action) {
  /* LobbyButton: h 54, capsule, card (hover well), rule border, icon 17 dp + 8 + 14.5 sp bold. */
  float h = DP(54);
  bool hov = false, pr = false;
  bool clicked = hit(id, x, y, w, h, &hov, &pr);
  float s = anim(igGetID_Str(id), pr && enabled ? 0.972f : 1.0f, 30.0f);
  float bx = x, by = y, bw = w, bh = h;
  scaled_rect(&bx, &by, &bw, &bh, s);
  card(dl, bx, by, bw, bh, bh * 0.5f, hov && enabled ? col(ARENA_THEME_WELL, 1) : col(ARENA_THEME_CARD, 1),
       col(ARENA_THEME_RULE, 1));
  ImU32 ink = enabled ? col(ARENA_THEME_INK, 1) : col(ARENA_THEME_QUIET, 1);
  float lw = text_w(F_BOLD, 14.5f, 0, label);
  float total = DP(17) + DP(8) + lw;
  float cx = bx + (bw - total) * 0.5f, cy = by + bh * 0.5f;
  image_tinted(dl, icon, cx, cy - DP(8.5f), DP(17), DP(17), ink);
  text_at(dl, F_BOLD, 14.5f, 0, cx + DP(25), cy - text_h(F_BOLD, 14.5f) * 0.5f, ink, label);
  if (clicked && enabled) send_action(action, "");
}

static void play_button(ImDrawList* dl, float x, float y, bool enabled, bool entering) {
  float w = DP(220), h = DP(58);
  bool hov = false, pr = false;
  bool clicked = hit("##wyrm_play", x, y, w, h, &hov, &pr);
  float s = anim(igGetID_Str("##wyrm_play"), pr && enabled ? 0.972f : 1.0f, 30.0f);
  float bx = x, by = y, bw = w, bh = h;
  scaled_rect(&bx, &by, &bw, &bh, s);
  float a = (!enabled && !entering) ? 0.35f : (hov ? 0.88f : 1.0f);
  ImDrawList_AddRectFilled(dl, (ImVec2){bx, by}, (ImVec2){bx + bw, by + bh}, col(ARENA_THEME_INK, a), bh * 0.5f, 0);
  float t = anim(igGetID_Str("##wyrm_play_busy"), entering ? 1.0f : 0.0f, 12.0f);
  float cy = by + bh * 0.5f;
  if (t < 0.999f) {
    float lw = text_w(F_BOLD, 16, 2.4f, "PLAY") - DP(2.4f);
    text_at(dl, F_BOLD, 16, 2.4f, bx + (bw - lw) * 0.5f, cy - text_h(F_BOLD, 16) * 0.5f, col(ARENA_THEME_ON_INK, 1 - t), "PLAY");
  }
  if (t > 0.001f) {
    const char* busy = "Entering\xE2\x80\xA6";
    float lw = text_w(F_BOLD, 15, 0.3f, busy) - DP(0.3f);
    text_at(dl, F_BOLD, 15, 0.3f, bx + (bw - lw) * 0.5f, cy - text_h(F_BOLD, 15) * 0.5f, col(ARENA_THEME_ON_INK, t), busy);
  }
  if (clicked && enabled) {
    if (nickname_active) send_action(ACT_NICKNAME, nickname_buf);
    send_action(ACT_PLAY, "");
  }
}

static void draw_picker(float vw, float vh, float top);

static void draw_lobby(tenv* env, float vw, float vh) {
  igSetNextWindowPos((ImVec2){0, 0}, ImGuiCond_Always, (ImVec2){0, 0});
  igSetNextWindowSize((ImVec2){vw, vh}, ImGuiCond_Always);
  igPushStyleVar_Vec2(ImGuiStyleVar_WindowPadding, (ImVec2){0, 0});
  igPushStyleVar_Float(ImGuiStyleVar_WindowBorderSize, 0);
  igBegin("##wyrm_native_lobby", NULL,
          ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoSavedSettings |
              ImGuiWindowFlags_NoScrollWithMouse | ImGuiWindowFlags_NoBackground | ImGuiWindowFlags_NoDocking |
              ImGuiWindowFlags_NoFocusOnAppearing);
  igPopStyleVar(2);
  igBringWindowToDisplayFront(igGetCurrentWindow());
  ImDrawList* dl = igGetWindowDrawList();

  /* Paper everywhere (the strip sits on the same paper). */
  float saved_fade = fade;
  fade = 1.0f;
  ImDrawList_AddRectFilled(dl, (ImVec2){0, 0}, (ImVec2){vw, vh}, col(ARENA_THEME_PAPER, 1), 0, 0);
  float top = 0;
  if (!fullscreen) {
    draw_strip(dl, vw);
    top = strip_h;
  } else {
    strip_h = 0;
  }

  /* Appear: out of a blur in Compose; here a fade and a 24 dp rise, 620 ms. */
  float since = (float)((SDL_GetTicksNS() - lobby_since) / 1e9);
  float p = fast_out_slow_in(since / 0.62f);
  fade = saved_fade * p;
  float rise = (1.0f - p) * DP(24);

  float area_h = vh - top;
  float cx = vw * 0.5f, cy = top + area_h * 0.5f;
  /* The curving W, large and faint behind (520 dp, ink at 4.5 %). */
  {
    float s = DP(520);
    ui_image* mark = image_find("mark");
    if (mark && mark->set) {
      float mh = s * (float)mark->h / (float)(mark->w ? mark->w : 1);
      image_tinted(dl, "mark", cx - s * 0.5f, cy - mh * 0.5f, s, mh, col(ARENA_THEME_INK, 0.045f));
    }
  }

  /* Heights first: the column is centred. */
  float colw = DP(600);
  float h_kicker = text_h(F_BOLD, 11.5f);
  float h_title = text_h(F_BOLD, 36);
  bool chips = L.code >= 0 || L.players >= 0;
  float h_arena = DP(20) * 2 + text_h(F_BOLD, 11) + DP(8) + text_h(F_DISPLAY, 36) +
                  (chips ? DP(12) + DP(12) + text_h(F_BOLD, 13) : 0);
  float h_name = DP(18) * 2 + text_h(F_BOLD, 11) + DP(6) + text_h(F_DISPLAY, 32);
  float h_run = L.has_run ? DP(14) + DP(12) * 2 + text_h(F_BOLD, 15) : 0;
  float h_notice = L.notice[0] ? DP(14) + text_h(F_MEDIUM, 13.5f) : 0;
  float total = h_kicker + DP(4) + h_title + DP(28) + h_arena + DP(14) + h_name + h_run + h_notice + DP(30) + DP(58);
  float x0 = cx - colw * 0.5f;
  float y = cy - total * 0.5f + rise;

  /* READY ROOM / Enter the arena. */
  {
    float w = text_w(F_BOLD, 11.5f, 1.6f, "READY ROOM") - DP(1.6f);
    text_at(dl, F_BOLD, 11.5f, 1.6f, cx - w * 0.5f, y, col(ARENA_THEME_QUIET, 1), "READY ROOM");
    y += h_kicker + DP(4);
    w = text_w(F_BOLD, 36, -0.8f, "Enter the arena") + DP(0.8f);
    text_at(dl, F_BOLD, 36, -0.8f, cx - w * 0.5f, y, col(ARENA_THEME_INK, 1), "Enter the arena");
    y += h_title + DP(28);
  }

  /* The arena card (click: the picker). */
  {
    bool hov = false, pr = false;
    bool clicked = hit("##wyrm_arena_card", x0, y, colw, h_arena, &hov, &pr);
    float s = anim(igGetID_Str("##wyrm_arena_card"), pr ? 0.972f : 1.0f, 30.0f);
    float bx = x0, by = y, bw = colw, bh = h_arena;
    scaled_rect(&bx, &by, &bw, &bh, s);
    float hv = anim(igGetID_Str("##wyrm_arena_card_h"), hov ? 1.0f : 0.0f, 20.0f);
    card(dl, bx, by, bw, bh, DP(20), col(ARENA_THEME_CARD, 1),
         mix(col(ARENA_THEME_RULE, 1), col(ARENA_THEME_INK, 0.25f), hv));
    float ix = bx + DP(26), iy = by + DP(20), iw = bw - DP(52);
    text_at(dl, F_BOLD, 11, 1.3f, ix, iy, col(ARENA_THEME_QUIET, 1), "SELECTED ARENA");
    float chw = text_w(F_SEMIBOLD, 13, 0, "Change");
    text_at(dl, F_SEMIBOLD, 13, 0, ix + iw - chw, iy + (text_h(F_BOLD, 11) - text_h(F_SEMIBOLD, 13)) * 0.5f,
            col(ARENA_THEME_LINK, 1), "Change");
    iy += text_h(F_BOLD, 11) + DP(8);
    bool blank = !L.arena[0];
    text_fit(dl, F_DISPLAY, 36, ix, iy, iw, blank ? col(ARENA_THEME_QUIET, 1) : col(ARENA_THEME_INK, 1),
             blank ? "Pick an arena" : L.arena);
    iy += text_h(F_DISPLAY, 36);
    if (chips) {
      iy += DP(12);
      float cxp = ix, w = 0;
      char v[32];
      if (L.code >= 0) {
        SDL_snprintf(v, sizeof(v), "%d", L.code);
        chip(dl, cxp, iy, "Server code", v, &w);
        cxp += w + DP(8);
      }
      if (L.players >= 0) {
        SDL_snprintf(v, sizeof(v), "%d", L.players);
        chip(dl, cxp, iy, "Playing", v, &w);
      }
    }
    if (clicked) send_action(ACT_OPEN_PICKER, "");
    y += h_arena + DP(14);
  }

  /* Playing as (the nickname, Display 32 sp). */
  {
    card(dl, x0, y, colw, h_name, DP(20), col(ARENA_THEME_CARD, 1), col(ARENA_THEME_RULE, 1));
    float ix = x0 + DP(26), iy = y + DP(18);
    text_at(dl, F_BOLD, 11, 1.3f, ix, iy, col(ARENA_THEME_QUIET, 1), "PLAYING AS");
    iy += text_h(F_BOLD, 11) + DP(6);
    int was = nickname_active;
    if (field("##wyrm_nickname", "Wyrm Player", nickname_buf, sizeof(nickname_buf), 24, F_DISPLAY, 32, ix, iy,
              colw - DP(52), col(ARENA_THEME_INK, 1), col(ARENA_THEME_QUIET, 1), col(ARENA_THEME_LINK, 1),
              &nickname_active, false, 0))
      send_action(ACT_NICKNAME, nickname_buf);
    if (was && !nickname_active) send_action(ACT_NICKNAME, nickname_buf);
    y += h_name;
  }

  /* The last run. */
  if (L.has_run) {
    y += DP(14);
    float h = DP(12) * 2 + text_h(F_BOLD, 15);
    ImDrawList_AddRectFilled(dl, (ImVec2){x0, y}, (ImVec2){x0 + colw, y + h}, col(ARENA_THEME_WELL, 1), DP(16), 0);
    float my = y + h * 0.5f;
    text_at(dl, F_BOLD, 10.5f, 1.2f, x0 + DP(22), my - text_h(F_BOLD, 10.5f) * 0.5f, col(ARENA_THEME_QUIET, 1), "LAST RUN");
    char score[32], kills[16], time[16];
    group_digits(L.score, score, sizeof(score));
    SDL_snprintf(kills, sizeof(kills), "%d", L.kills);
    long total_s = (long)L.seconds;
    SDL_snprintf(time, sizeof(time), "%ld:%02ld", total_s / 60, total_s % 60);
    const char* labels[3] = {"Score", "Kills", "Time"};
    const char* values[3] = {score, kills, time};
    float xr = x0 + colw - DP(22);
    for (int i = 2; i >= 0; --i) {
      float vw2 = text_w(F_BOLD, 15, 0, values[i]);
      float lw = text_w(F_MEDIUM, 12.5f, 0, labels[i]);
      text_at(dl, F_BOLD, 15, 0, xr - vw2, my - text_h(F_BOLD, 15) * 0.5f, col(ARENA_THEME_INK, 1), values[i]);
      text_at(dl, F_MEDIUM, 12.5f, 0, xr - vw2 - DP(6) - lw, my - text_h(F_MEDIUM, 12.5f) * 0.5f,
              col(ARENA_THEME_MUTE, 1), labels[i]);
      xr -= vw2 + DP(6) + lw + DP(22);
    }
    y += h;
  }

  if (L.notice[0]) {
    y += DP(14);
    float w = text_w(F_MEDIUM, 13.5f, 0, L.notice);
    text_fit(dl, F_MEDIUM, 13.5f, cx - fminf(w, colw) * 0.5f, y, colw, col(ARENA_THEME_BADGE, 1), L.notice);
    y += text_h(F_MEDIUM, 13.5f);
  }

  /* Home · Play with AI · PLAY. */
  y += DP(30);
  {
    float total_w = DP(128) + DP(10) + DP(170) + DP(10) + DP(220);
    float bx = cx - total_w * 0.5f;
    bool enabled = !L.entering;
    lobby_button(dl, "##wyrm_home", "Home", "icon.house", bx, y + DP(2), DP(128), enabled, ACT_HOME);
    lobby_button(dl, "##wyrm_ai", "Play with AI", "icon.bot", bx + DP(138), y + DP(2), DP(170), enabled, ACT_PLAY_AI);
    play_button(dl, bx + DP(318), y, L.ready && !L.entering, L.entering != 0);
  }
  fade = saved_fade;
  igEnd();

  draw_picker(vw, vh, top);
}

/* ------------------------------------------------------- the arena picker */

static void picker_field(ImDrawList* dl, const char* id, const char* hint, char* buf, size_t cap, float x, float y,
                         float w, int* active, bool focus, int action, int enter_action) {
  int was = *active;
  ImU32 hint_ink = col(ARENA_THEME_QUIET, 0.7f);
  if (field(id, hint, buf, cap, 64, F_MEDIUM, 13, x, y, w, col(ARENA_THEME_INK, 1), hint_ink,
            col(ARENA_THEME_LINK, 1), active, focus, ImGuiInputTextFlags_EnterReturnsTrue)) {
    send_action(action, buf);
    if (enter_action) send_action(enter_action, "");
  }
  if (*active && enter_action == 0) {
    static char last[96];
    if (strcmp(last, buf) != 0) {
      SDL_strlcpy(last, buf, sizeof(last));
      send_action(action, buf);
    }
  }
  (void)was;
  (void)dl;
}

static void plus_glyph(ImDrawList* dl, float cx, float cy, float s, ImU32 ink) {
  ui_image* img = image_find("icon.plus");
  if (img && img->set) {
    image_tinted(dl, "icon.plus", cx - s * 0.5f, cy - s * 0.5f, s, s, ink);
    return;
  }
  float l = DP(1.8f);
  ImDrawList_AddLine(dl, (ImVec2){cx - s * 0.35f, cy}, (ImVec2){cx + s * 0.35f, cy}, ink, l);
  ImDrawList_AddLine(dl, (ImVec2){cx, cy - s * 0.35f}, (ImVec2){cx, cy + s * 0.35f}, ink, l);
}

static void draw_picker(float vw, float vh, float top) {
  bool open = P.open != 0;
  if (open && !picker_was_open) picker_anim = 0;
  picker_was_open = open;
  if (!open) return;
  picker_anim += dt / 0.28f;
  if (picker_anim > 1) picker_anim = 1;
  float p = 1 - (1 - picker_anim) * (1 - picker_anim) * (1 - picker_anim);

  /* Scrim: black at 22 %, click closes. */
  igSetNextWindowPos((ImVec2){0, 0}, ImGuiCond_Always, (ImVec2){0, 0});
  igSetNextWindowSize((ImVec2){vw, vh}, ImGuiCond_Always);
  igPushStyleVar_Vec2(ImGuiStyleVar_WindowPadding, (ImVec2){0, 0});
  igPushStyleVar_Float(ImGuiStyleVar_WindowBorderSize, 0);
  igBegin("##wyrm_picker", NULL,
          ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoSavedSettings |
              ImGuiWindowFlags_NoScrollWithMouse | ImGuiWindowFlags_NoBackground | ImGuiWindowFlags_NoDocking);
  igPopStyleVar(2);
  igBringWindowToDisplayFront(igGetCurrentWindow());
  ImDrawList* dl = igGetWindowDrawList();
  float saved_fade = fade;
  fade = 1.0f;
  ImDrawList_AddRectFilled(dl, (ImVec2){0, 0}, (ImVec2){vw, vh}, IM_COL32(0, 0, 0, (int)(255 * 0.22f * p)), 0, 0);

  float sw = DP(680), sh = (vh - top) * 0.84f;
  float sx = (vw - sw) * 0.5f, sy = top + ((vh - top) - sh) * 0.5f;
  /* Scale in from 0.94. */
  float sc = 0.94f + 0.06f * p;
  float bx = sx, by = sy, bw = sw, bh = sh;
  scaled_rect(&bx, &by, &bw, &bh, sc);
  /* The scrim's click (outside the sheet) closes; the sheet's own items
     sit over it. */
  igSetCursorScreenPos((ImVec2){0, 0});
  igSetNextItemAllowOverlap();
  if (igInvisibleButton("##wyrm_scrim", (ImVec2){vw, vh}, ImGuiButtonFlags_MouseButtonLeft)) {
    ImVec2 m = igGetIO_Nil()->MousePos;
    if (m.x < bx || m.x > bx + bw || m.y < by || m.y > by + bh) send_action(ACT_CLOSE_PICKER, "");
  }
  fade = p;
  /* paperPanel: a soft shadow, paper at 97 %, hairline. */
  for (int i = 6; i >= 1; --i) {
    float g = DP(4) * i;
    ImDrawList_AddRectFilled(dl, (ImVec2){bx - g * 0.5f, by - g * 0.2f}, (ImVec2){bx + bw + g * 0.5f, by + bh + g},
                             IM_COL32(0, 0, 0, (int)(255 * 0.035f * p)), DP(26) + g, 0);
  }
  card(dl, bx, by, bw, bh, DP(26), col(ARENA_THEME_PAPER, 0.97f), col(ARENA_THEME_RULE, 1));

  /* Header: LIVE DIRECTORY / Pick a server · status · + · Close. */
  float hx = bx + DP(22), hy = by + DP(20);
  text_at(dl, F_BOLD, 10, 1, hx, hy, col(ARENA_THEME_LIVE, 1), "LIVE DIRECTORY");
  float title_y = hy + text_h(F_BOLD, 10) + DP(2);
  text_at(dl, F_BOLD, 27, 0, hx, title_y, col(ARENA_THEME_INK, 1), "Pick a server");
  float header_bottom = title_y + text_h(F_BOLD, 27);
  float right = bx + bw - DP(18);
  /* Close. */
  {
    float w = text_w(F_SEMIBOLD, 13, 0, "Close");
    float x = right - w - DP(4);
    float y = header_bottom - DP(8) - text_h(F_SEMIBOLD, 13) - DP(8);
    bool hov = false;
    if (hit("##wyrm_picker_close", x - DP(4), y, w + DP(8), text_h(F_SEMIBOLD, 13) + DP(16), &hov, NULL))
      send_action(ACT_CLOSE_PICKER, "");
    if (hov) igSetMouseCursor(ImGuiMouseCursor_Hand);
    text_at(dl, F_SEMIBOLD, 13, 0, x, y + DP(8), col(ARENA_THEME_LINK, 1), "Close");
    right = x - DP(4) - DP(10);
  }
  /* +. */
  {
    float s = DP(36);
    float x = right - s, y = header_bottom - s;
    bool hov = false;
    if (hit("##wyrm_picker_add", x, y, s, s, &hov, NULL)) send_action(ACT_TOGGLE_ADD, "");
    if (hov) igSetMouseCursor(ImGuiMouseCursor_Hand);
    ImDrawList_AddCircleFilled(dl, (ImVec2){x + s / 2, y + s / 2}, s / 2, P.add ? col(ARENA_THEME_INK, 1) : col(ARENA_THEME_CARD, 1), 0);
    ImDrawList_AddCircle(dl, (ImVec2){x + s / 2, y + s / 2}, s / 2 - 0.5f, col(ARENA_THEME_RULE, 1), 0, DP(1));
    plus_glyph(dl, x + s / 2, y + s / 2, DP(19), P.add ? col(ARENA_THEME_ON_INK, 1) : col(ARENA_THEME_INK, 1));
    right = x - DP(12);
  }
  {
    float w = text_w(F_MEDIUM, 12, 0, P.status);
    text_at(dl, F_MEDIUM, 12, 0, right - w, header_bottom - DP(8) - text_h(F_MEDIUM, 12), col(ARENA_THEME_QUIET, 1), P.status);
  }

  /* Search. */
  float sy2 = header_bottom + DP(14);
  float fx = bx + DP(16), fw = bw - DP(32), fh = DP(44);
  card(dl, fx, sy2, fw, fh, DP(13), col(ARENA_THEME_CARD, 0.82f), col(ARENA_THEME_RULE, 1));
  image_tinted(dl, "icon.search", fx + DP(14), sy2 + (fh - DP(16)) * 0.5f, DP(16), DP(16), col(ARENA_THEME_INK, 1));
  bool first_frame = picker_anim < 0.2f && !search_active;
  picker_field(dl, "##wyrm_picker_search", "Arena code or IP", search_buf, sizeof(search_buf), fx + DP(38),
               sy2 + (fh - text_h(F_MEDIUM, 13)) * 0.5f, fw - DP(52), &search_active, first_frame && !P.add,
               ACT_SEARCH, 0);

  /* The list (a child that scrolls). */
  float ly = sy2 + fh;
  float lh = by + bh - ly;
  igSetCursorScreenPos((ImVec2){bx, ly});
  igPushStyleVar_Vec2(ImGuiStyleVar_WindowPadding, (ImVec2){0, 0});
  igPushStyleColor_U32(ImGuiCol_ScrollbarBg, 0);
  igPushStyleColor_U32(ImGuiCol_ScrollbarGrab, col(ARENA_THEME_INK, 0.18f));
  igPushStyleColor_U32(ImGuiCol_ScrollbarGrabHovered, col(ARENA_THEME_INK, 0.28f));
  igPushStyleColor_U32(ImGuiCol_ScrollbarGrabActive, col(ARENA_THEME_INK, 0.36f));
  igPushStyleVar_Float(ImGuiStyleVar_ScrollbarSize, DP(6));
  igBeginChild_Str("##wyrm_picker_list", (ImVec2){bw, lh}, 0, ImGuiWindowFlags_NoBackground);
  ImDrawList* ldl = igGetWindowDrawList();
  ImVec2 origin;
  igGetCursorScreenPos(&origin);
  float rx = origin.x + DP(16), rw = bw - DP(32);
  float cy = origin.y + DP(14);
  float gap = DP(9);

  if (P.add) {
    float h = DP(14) * 2 + text_h(F_MEDIUM, 13);
    card(ldl, rx, cy, rw, h, DP(14), col(ARENA_THEME_CARD, 1), col(ARENA_THEME_RULE, 1));
    float save_w = text_w(F_BOLD, 13, 0, "Save");
    if (!add_was_open) address_buf[0] = 0;
    picker_field(ldl, "##wyrm_picker_address", "IP address:port", address_buf, sizeof(address_buf), rx + DP(14),
                 cy + DP(14), rw - DP(28) - save_w - DP(8), &address_active, !add_was_open, ACT_ADDRESS, ACT_SAVE_ADDRESS);
    bool hov = false;
    if (hit("##wyrm_picker_save", rx + rw - DP(14) - save_w, cy, save_w, h, &hov, NULL)) {
      send_action(ACT_ADDRESS, address_buf);
      send_action(ACT_SAVE_ADDRESS, "");
    }
    if (hov) igSetMouseCursor(ImGuiMouseCursor_Hand);
    text_at(ldl, F_BOLD, 13, 0, rx + rw - DP(14) - save_w, cy + DP(14), col(ARENA_THEME_LINK, 1), "Save");
    cy += h + gap;
    if (P.address_error) {
      const char* msg = "Enter an IPv4 address or [IPv6] address and a port (1\xE2\x80\x93" "65535).";
      text_at(ldl, F_MEDIUM, 11, 0, rx, cy, IM_COL32(0xFF, 0x3B, 0x30, (int)(255 * fade)), msg);
      cy += text_h(F_MEDIUM, 11) + gap;
    }
  }
  add_was_open = P.add;

  /* The saved fold is a card around its header and rows. */
  int saved_card_start = -1;
  float saved_card_y = 0;
  for (int i = 0; i < P.nrows; ++i) {
    picker_row* r = &P.rows[i];
    char id[160];
    SDL_snprintf(id, sizeof(id), "##wyrm_row_%c_%s_%d", r->kind, r->endpoint, i);
    if (r->kind == 'L') {
      float top_pad = DP(12);
      text_at(ldl, F_BOLD, 10, 0.8f, rx, cy + top_pad, col(ARENA_THEME_QUIET, 1), r->title);
      cy += top_pad + text_h(F_BOLD, 10) + DP(3) + gap;
    } else if (r->kind == 'S') {
      float h = DP(14) * 2 + text_h(F_BOLD, 13);
      bool hov = false;
      if (hit(id, rx, cy, rw, h, &hov, NULL)) send_action(ACT_SEE_ALL, "");
      if (hov) igSetMouseCursor(ImGuiMouseCursor_Hand);
      float w = text_w(F_BOLD, 13, 0, r->title);
      text_at(ldl, F_BOLD, 13, 0, rx + (rw - w) * 0.5f, cy + DP(14), col(ARENA_THEME_INK, 1), r->title);
      cy += h + gap;
    } else if (r->kind == 'E') {
      text_at(ldl, F_MEDIUM, 12, 0, rx + DP(20), cy + DP(20), col(ARENA_THEME_QUIET, 1), r->title);
      cy += DP(40) + text_h(F_MEDIUM, 12) + gap;
    } else if (r->kind == 'V') {
      saved_card_start = i;
      saved_card_y = cy;
      /* Header row inside the card (14 dp padding). */
      float h = text_h(F_BOLD, 10) + DP(15);
      /* The card's height: header + open rows. */
      float rows_h = 0;
      if (r->open) {
        for (int j = i + 1; j < P.nrows && P.rows[j].kind == 'A' && P.rows[j].removable; ++j)
          rows_h += DP(9) + DP(14) * 2 + text_h(F_BOLD, 15) + DP(3) + text_h(F_MEDIUM, 10.5f);
      }
      float card_h = DP(14) * 2 + h + rows_h - DP(15) + DP(3);
      card(ldl, rx, cy, rw, card_h, DP(15), col(ARENA_THEME_CARD, 0.9f), col(ARENA_THEME_RULE, 1));
      char label[64];
      SDL_snprintf(label, sizeof(label), "SAVED ARENAS \xC2\xB7 %d", r->count);
      bool hov = false;
      if (hit(id, rx, cy, rw, DP(14) + h, &hov, NULL)) send_action(ACT_TOGGLE_SAVED, "");
      if (hov) igSetMouseCursor(ImGuiMouseCursor_Hand);
      text_at(ldl, F_BOLD, 10, 0.8f, rx + DP(14), cy + DP(14), col(ARENA_THEME_QUIET, 1), label);
      /* Chevron (turns 90 when open). */
      float ang = anim(igGetID_Str("##wyrm_saved_chev"), r->open ? 1.5707963f : 0.0f, 14.0f);
      float ccx = rx + rw - DP(14) - DP(7), ccy = cy + DP(14) + text_h(F_BOLD, 10) * 0.5f;
      ImVec2 pts[3];
      float d = DP(3.5f);
      ImVec2 base[3] = {{-d * 0.5f, -d}, {d * 0.5f, 0}, {-d * 0.5f, d}};
      for (int k = 0; k < 3; ++k) {
        float ca = cosf(ang), sa = sinf(ang);
        pts[k] = (ImVec2){ccx + base[k].x * ca - base[k].y * sa, ccy + base[k].x * sa + base[k].y * ca};
      }
      ImDrawList_AddPolyline(ldl, pts, 3, col(ARENA_THEME_INK, 1), 0, DP(1.6f));
      cy += DP(14) + h - DP(15) + DP(3);
      if (!r->open) cy += DP(14) + gap;
    } else if (r->kind == 'A') {
      int inside_saved = r->removable && saved_card_start >= 0;
      float inset = inside_saved ? DP(14) : 0;
      if (inside_saved) cy += DP(9) - (cy == saved_card_y ? 0 : 0);
      float x = rx + inset, w = rw - inset * 2;
      float h = DP(14) * 2 + text_h(F_BOLD, 15) + DP(3) + text_h(F_MEDIUM, 10.5f);
      bool hov = false, pr = false;
      /* Remove sits on the row: its own hit first. */
      float remove_w = r->removable ? text_w(F_SEMIBOLD, 12, 0, "Remove") + DP(8) : 0;
      bool removed = false;
      if (r->removable) {
        char rid[180];
        SDL_snprintf(rid, sizeof(rid), "%s_remove", id);
        bool rh = false;
        if (hit(rid, x + w - DP(14) - remove_w, cy, remove_w, h, &rh, NULL)) {
          send_action(ACT_REMOVE_SAVED, r->endpoint);
          removed = true;
        }
        if (rh) igSetMouseCursor(ImGuiMouseCursor_Hand);
      }
      bool clicked = hit(id, x, cy, w - (r->removable ? remove_w + DP(14) : 0), h, &hov, &pr);
      if (hov) igSetMouseCursor(ImGuiMouseCursor_Hand);
      float bg = r->selected ? 0.9f : (hov ? 1.0f : 0.9f);
      ImDrawList_AddRectFilled(ldl, (ImVec2){x, cy}, (ImVec2){x + w, cy + h}, col(ARENA_THEME_CARD, bg), DP(15), 0);
      float bt = r->selected ? DP(2) : DP(1);
      ImDrawList_AddRect(ldl, (ImVec2){x + bt * 0.5f, cy + bt * 0.5f}, (ImVec2){x + w - bt * 0.5f, cy + h - bt * 0.5f},
                         r->selected ? col(ARENA_THEME_INK, 1) : col(ARENA_THEME_RULE, 1), DP(15), 0, bt);
      float tx = x + DP(14), ty = cy + DP(14);
      text_at(ldl, F_BOLD, 15, 0, tx, ty, col(ARENA_THEME_INK, 1), r->title);
      float after = tx + text_w(F_BOLD, 15, 0, r->title);
      float mid = ty + text_h(F_BOLD, 15) * 0.5f;
      if (r->country[0]) {
        /* CountryBadge: 20 x 14 flag rounded 3 with a hairline, 5 dp, code 10.5 sp bold 0.6 sp. */
        float fx2 = after + DP(8);
        ImDrawList_AddRectFilled(ldl, (ImVec2){fx2, mid - DP(7)}, (ImVec2){fx2 + DP(20), mid + DP(7)},
                                 col(ARENA_THEME_TRACK, 1), DP(3), 0);
        char flag[16];
        SDL_snprintf(flag, sizeof(flag), "flag.%s", r->country);
        for (char* q = flag; *q; ++q) if (*q >= 'A' && *q <= 'Z') *q += 32;
        ui_image* fi = image_find(flag);
        if (fi && fi->set) {
          /* Crop to fill 20 x 14. */
          float fa = (float)fi->w / (float)(fi->h ? fi->h : 1), ta = 20.0f / 14.0f;
          ImVec2 uv0 = {0, 0}, uv1 = {1, 1};
          if (fa > ta) { float k = ta / fa; uv0.x = (1 - k) * 0.5f; uv1.x = 1 - uv0.x; }
          else { float k = fa / ta; uv0.y = (1 - k) * 0.5f; uv1.y = 1 - uv0.y; }
          ImDrawList_AddImageRounded(ldl, image_ref(fi), (ImVec2){fx2, mid - DP(7)}, (ImVec2){fx2 + DP(20), mid + DP(7)},
                                     uv0, uv1, IM_COL32(255, 255, 255, (int)(255 * fade)), DP(3), 0);
        }
        ImDrawList_AddRect(ldl, (ImVec2){fx2, mid - DP(7)}, (ImVec2){fx2 + DP(20), mid + DP(7)},
                           col(ARENA_THEME_INK, 0.14f), DP(3), 0, DP(0.5f));
        char cc[8];
        SDL_strlcpy(cc, r->country, sizeof(cc));
        text_at(ldl, F_BOLD, 10.5f, 0.6f, fx2 + DP(25), mid - text_h(F_BOLD, 10.5f) * 0.5f, col(ARENA_THEME_QUIET, 1), cc);
        after = fx2 + DP(25) + text_w(F_BOLD, 10.5f, 0.6f, cc);
      }
      if (r->tag[0]) {
        float tw = text_w(F_BOLD, 10, 0, r->tag);
        float px = after + DP(8), ph = text_h(F_BOLD, 10) + DP(4);
        ImDrawList_AddRectFilled(ldl, (ImVec2){px, mid - ph * 0.5f}, (ImVec2){px + tw + DP(14), mid + ph * 0.5f},
                                 col(ARENA_THEME_LIVE, 0.12f), ph * 0.5f, 0);
        text_at(ldl, F_BOLD, 10, 0, px + DP(7), mid - text_h(F_BOLD, 10) * 0.5f, col(ARENA_THEME_LIVE, 1), r->tag);
      }
      text_at(ldl, F_MEDIUM, 10.5f, 0, tx, ty + text_h(F_BOLD, 15) + DP(3), col(ARENA_THEME_QUIET, 1), r->endpoint);
      float lat_w = text_w(F_BOLD, 12, 0, r->latency);
      float lat_x = x + w - DP(14) - (r->removable ? remove_w + DP(12) - DP(8) : 0) - lat_w;
      text_at(ldl, F_BOLD, 12, 0, lat_x, cy + (h - text_h(F_BOLD, 12)) * 0.5f, argb_col(r->latency_argb, 1), r->latency);
      if (r->removable)
        text_at(ldl, F_SEMIBOLD, 12, 0, x + w - DP(14) - remove_w + DP(4), cy + (h - text_h(F_SEMIBOLD, 12)) * 0.5f,
                col(ARENA_THEME_BADGE, 1), "Remove");
      if (clicked && !removed) send_action(ACT_PICK, r->endpoint);
      cy += h;
      /* The last saved row closes the card. */
      int next_saved = i + 1 < P.nrows && P.rows[i + 1].kind == 'A' && P.rows[i + 1].removable;
      if (inside_saved && !next_saved) {
        cy += DP(14) + gap;
        saved_card_start = -1;
      } else if (!inside_saved) {
        cy += gap;
      }
    }
  }
  cy += DP(18) - gap;
  igSetCursorScreenPos((ImVec2){origin.x, origin.y});
  igDummy((ImVec2){bw, cy - origin.y});
  igEndChild();
  igPopStyleVar(2);
  igPopStyleColor(4);
  fade = saved_fade;
  igEnd();
}

/* -------------------------------------------------------- the chat box */

static void draw_chat(float vw, float vh) {
  chat_visible = 0;
  bool open = C.open != 0;
  if (open && !chat_was_open) chat_anim = 0;
  int opening = open && !chat_was_open;
  chat_was_open = open;
  if (!open) {
    draft_active = 0;
    return;
  }
  chat_anim += dt / 0.18f;
  if (chat_anim > 1) chat_anim = 1;
  /* TeamPage ArenaComposer: max 620 dp wide, bottom centre, 40 dp up; a
     paperPanel (rounded 22, paper at 95 %) with TEAM / GLOBAL, the field and Send. */
  float w = fminf(DP(620), vw - DP(48));
  float h = DP(8) * 2 + DP(38);
  float x = (vw - w) * 0.5f, y = vh - DP(40) - h;
  float err_h = C.error[0] ? text_h(F_MEDIUM, 14) + DP(24) : 0;
  chat_x0 = x;
  chat_y0 = y - err_h;
  chat_x1 = x + w;
  chat_y1 = y + h;
  chat_visible = 1;
  igSetNextWindowPos((ImVec2){x, y - err_h}, ImGuiCond_Always, (ImVec2){0, 0});
  igSetNextWindowSize((ImVec2){w, h + err_h}, ImGuiCond_Always);
  igPushStyleVar_Vec2(ImGuiStyleVar_WindowPadding, (ImVec2){0, 0});
  igPushStyleVar_Float(ImGuiStyleVar_WindowBorderSize, 0);
  igBegin("##wyrm_chat_box", NULL,
          ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoSavedSettings |
              ImGuiWindowFlags_NoScrollWithMouse | ImGuiWindowFlags_NoBackground | ImGuiWindowFlags_NoDocking);
  igPopStyleVar(2);
  igBringWindowToDisplayFront(igGetCurrentWindow());
  ImDrawList* dl = igGetWindowDrawList();
  float saved_fade = fade;
  fade = chat_anim;
  if (C.error[0]) text_fit(dl, F_MEDIUM, 14, x + DP(12), y - err_h + DP(12), w - DP(24), col(ARENA_THEME_BADGE, 1), C.error);
  for (int i = 5; i >= 1; --i) {
    float g = DP(3) * i;
    ImDrawList_AddRectFilled(dl, (ImVec2){x - g * 0.5f, y - g * 0.2f}, (ImVec2){x + w + g * 0.5f, y + h + g},
                             IM_COL32(0, 0, 0, (int)(255 * 0.03f * fade)), DP(22) + g, 0);
  }
  card(dl, x, y, w, h, DP(22), col(ARENA_THEME_PAPER, 0.95f), col(ARENA_THEME_RULE, 1));
  const char* tab = C.global ? "GLOBAL" : "TEAM";
  float cy = y + h * 0.5f;
  text_at(dl, F_BOLD, 10.5f, 0, x + DP(18), cy - text_h(F_BOLD, 10.5f) * 0.5f, col(ARENA_THEME_LIVE, 1), tab);
  float fx = x + DP(18) + text_w(F_BOLD, 10.5f, 0, tab) + DP(12);
  /* Send: a filled pill, h 38, 18 dp sides, 13.5 sp bold on ink. */
  const char* send = C.busy ? "Sending\xE2\x80\xA6" : "Send";
  float sw = text_w(F_BOLD, 13.5f, 0, send) + DP(36);
  float sx = x + w - DP(8) - sw, sy = cy - DP(19);
  bool can = draft_buf[0] && !C.busy;
  bool hov = false;
  if (hit("##wyrm_chat_send", sx, sy, sw, DP(38), &hov, NULL) && can) {
    send_action(ACT_CHAT_DRAFT, draft_buf);
    send_action(ACT_CHAT_SEND, "");
  }
  if (hov && can) igSetMouseCursor(ImGuiMouseCursor_Hand);
  ImDrawList_AddRectFilled(dl, (ImVec2){sx, sy}, (ImVec2){sx + sw, sy + DP(38)}, col(ARENA_THEME_INK, can ? 1.0f : 0.35f), DP(19), 0);
  text_at(dl, F_BOLD, 13.5f, 0, sx + DP(18), cy - text_h(F_BOLD, 13.5f) * 0.5f, col(ARENA_THEME_ON_INK, 1), send);
  float field_w = sx - DP(8) - fx;
  static char last_sent[1200];
  if (field("##wyrm_chat_field", C.hint, draft_buf, sizeof(draft_buf), 280, F_MEDIUM, 14.5f, fx,
            cy - text_h(F_MEDIUM, 14.5f) * 0.5f, field_w, col(ARENA_THEME_INK, 1), col(ARENA_THEME_QUIET, 1),
            col(ARENA_THEME_INK, 1), &draft_active, opening || (!draft_active && chat_anim < 1), 0)) {
    if (strcmp(last_sent, draft_buf) != 0) {
      SDL_strlcpy(last_sent, draft_buf, sizeof(last_sent));
      send_action(ACT_CHAT_DRAFT, draft_buf);
    }
  }
  fade = saved_fade;
  igEnd();
}

/* --------------------------------------------------------- the pointer */

static SDL_Cursor* custom_cursor;
static int custom_cursor_seq = -1;
static SDL_Cursor* system_cursors[SDL_SYSTEM_CURSOR_COUNT];
static SDL_Cursor* current_cursor;
static int cursor_hidden;

static SDL_Cursor* system_cursor(SDL_SystemCursor id) {
  if (!system_cursors[id]) system_cursors[id] = SDL_CreateSystemCursor(id);
  return system_cursors[id];
}

static void apply_cursor(ImGuiMouseCursor want, int edge) {
  ensure_lock();
  SDL_LockMutex(ui_lock);
  if (custom_cursor_seq != cursor_seq) {
    custom_cursor_seq = cursor_seq;
    if (custom_cursor) {
      if (current_cursor == custom_cursor) current_cursor = NULL;
      SDL_DestroyCursor(custom_cursor);
      custom_cursor = NULL;
    }
    if (cursor_rgba && cursor_w > 0) {
      SDL_Surface* s = SDL_CreateSurfaceFrom(cursor_w, cursor_h, SDL_PIXELFORMAT_RGBA32, cursor_rgba, cursor_w * 4);
      if (s) {
        custom_cursor = SDL_CreateColorCursor(s, cursor_hx, cursor_hy);
        SDL_DestroySurface(s);
      }
    }
  }
  SDL_UnlockMutex(ui_lock);
  SDL_Cursor* c;
  if (edge) {
    int lr = edge & 3, tb = edge & 12;
    if (lr && tb) c = system_cursor(((edge & 1) && (edge & 4)) || ((edge & 2) && (edge & 8))
                                       ? SDL_SYSTEM_CURSOR_NWSE_RESIZE : SDL_SYSTEM_CURSOR_NESW_RESIZE);
    else if (lr) c = system_cursor(SDL_SYSTEM_CURSOR_EW_RESIZE);
    else c = system_cursor(SDL_SYSTEM_CURSOR_NS_RESIZE);
  } else {
    switch (want) {
      case ImGuiMouseCursor_TextInput: c = system_cursor(SDL_SYSTEM_CURSOR_TEXT); break;
      case ImGuiMouseCursor_Hand: c = system_cursor(SDL_SYSTEM_CURSOR_POINTER); break;
      case ImGuiMouseCursor_ResizeEW: c = system_cursor(SDL_SYSTEM_CURSOR_EW_RESIZE); break;
      case ImGuiMouseCursor_ResizeNS: c = system_cursor(SDL_SYSTEM_CURSOR_NS_RESIZE); break;
      default: c = custom_cursor ? custom_cursor : system_cursor(SDL_SYSTEM_CURSOR_DEFAULT); break;
    }
  }
  if (cursor_hidden) {
    SDL_ShowCursor();
    cursor_hidden = 0;
  }
  if (c && c != current_cursor) {
    SDL_SetCursor(c);
    current_cursor = c;
  }
}

/* ---------------------------------------------------------------- frame */

int wyrm_desktop_ui_draw(void* envp) {
  tenv* env = envp;
  usr = env->usr;
  images_pump(env);
  parse_models();
  if (!shown) return 0;
  Uint64 now = SDL_GetTicksNS();
  dt = last_frame_ns ? (float)((now - last_frame_ns) / 1e9) : 1.0f / 60.0f;
  if (dt > 0.1f) dt = 0.1f;
  last_frame_ns = now;
  float scale = SDL_GetWindowDisplayScale(win);
  S = scale > 0.25f ? scale : 1.0f;
  ImGuiIO* io = igGetIO_Nil();
  io->ConfigFlags |= ImGuiConfigFlags_NoMouseCursorChange;
  ImGuiViewport* vp = igGetMainViewport();
  float vw = vp->Size.x, vh = vp->Size.y;
  int playing = playing_now(env);
  if (playing != was_playing) {
    if (!playing) lobby_since = now;
    if (playing) {
      nickname_active = search_active = address_active = 0;
    } else {
      chat_was_open = 0;
      draft_active = 0;
    }
    was_playing = playing;
  }
  if (++shown_frames == 3) send_action(ACT_WINDOW_SHOWN, "");
  if (!playing) {
    draw_lobby(env, vw, vh);
  } else {
    strip_h = 0;
    draw_chat(vw, vh);
  }
  /* The pointer: resize edges, ImGui's own (text, hand), else Wyrm's. */
  float mx = io->MousePos.x, my = io->MousePos.y;
  int edge = drag_mode == 2 ? drag_dx : (mx > -1000 ? edge_at(mx, my) : 0);
  ImGuiMouseCursor want = igGetMouseCursor();
  if (playing && !(chat_visible && mx >= chat_x0 && mx < chat_x1 && my >= chat_y0 && my < chat_y1))
    want = ImGuiMouseCursor_Arrow;
  apply_cursor(want, edge);
  return !playing;
}

/* ------------------------------------------------------- JNI: Kotlin -> */

/* Show the game window where the Compose window is (`compose` = its HWND),
   or back to Home (show false). `maximized`/`restore*` carry Wyrm's own
   maximise (WindowChrome) across, `fullscreen` the F11 state. */
JNIEXPORT void JNICALL UI_JNI(nativeDesktopGameWindow)(JNIEnv* env, jclass clazz, jboolean show,
                                                        jlong compose, jboolean max, jint rx, jint ry,
                                                        jint rw, jint rh, jboolean full) {
  (void)env;
  (void)clazz;
  ensure_lock();
  SDL_LockMutex(ui_lock);
  compose_hwnd = (HWND)(intptr_t)compose;
  req_maximized = max ? 1 : 0;
  req_fullscreen = full ? 1 : 0;
  req_restore = (RECT){rx, ry, rx + rw, ry + rh};
  SDL_UnlockMutex(ui_lock);
  SDL_SetAtomicInt(&want_show, show ? 1 : 0);
  SDL_AddAtomicInt(&want_seq, 1);
}

/* {x, y, w, h, maximized, restore x, y, w, h, fullscreen, shown} in physical px. */
JNIEXPORT jintArray JNICALL UI_JNI(nativeDesktopGameWindowState)(JNIEnv* env, jclass clazz) {
  (void)clazz;
  ensure_lock();
  jint v[11];
  SDL_LockMutex(ui_lock);
  v[0] = st_x; v[1] = st_y; v[2] = st_w; v[3] = st_h; v[4] = st_max;
  v[5] = st_rx; v[6] = st_ry; v[7] = st_rw; v[8] = st_rh; v[9] = st_full;
  SDL_UnlockMutex(ui_lock);
  v[10] = SDL_GetAtomicInt(&state_shown);
  jintArray out = (*env)->NewIntArray(env, 11);
  if (out) (*env)->SetIntArrayRegion(env, out, 0, 11, v);
  return out;
}

/* kind 0 lobby, 1 picker, 2 chat box: "key=value" lines (NativeGame.kt). */
JNIEXPORT void JNICALL UI_JNI(nativeDesktopUiModel)(JNIEnv* env, jclass clazz, jint kind, jstring text) {
  (void)clazz;
  if (kind < 0 || kind >= MODEL_COUNT) return;
  jsize n = text ? (*env)->GetStringLength(env, text) : 0;
  size_t cap = (size_t)n * 4 + 4;
  char* buf = malloc(cap);
  if (!buf) return;
  jstring_utf8(env, text, buf, cap);
  ensure_lock();
  SDL_LockMutex(ui_lock);
  free(model_text[kind]);
  model_text[kind] = buf;
  model_version[kind]++;
  SDL_UnlockMutex(ui_lock);
}

/* An image for the native pages (RGBA, straight alpha): the W, icons, flags. */
JNIEXPORT void JNICALL UI_JNI(nativeDesktopUiImage)(JNIEnv* env, jclass clazz, jstring name, jint w, jint h,
                                                     jbyteArray rgba) {
  (void)clazz;
  if (!name || !rgba || w <= 0 || h <= 0) return;
  jsize n = (*env)->GetArrayLength(env, rgba);
  if ((size_t)n < (size_t)w * h * 4) return;
  pending_image* p = calloc(1, sizeof(*p));
  if (!p) return;
  p->rgba = malloc((size_t)w * h * 4);
  if (!p->rgba) { free(p); return; }
  (*env)->GetByteArrayRegion(env, rgba, 0, w * h * 4, (jbyte*)p->rgba);
  jstring_utf8(env, name, p->name, sizeof(p->name));
  p->w = w;
  p->h = h;
  ensure_lock();
  SDL_LockMutex(ui_lock);
  p->next = pending_images;
  pending_images = p;
  SDL_UnlockMutex(ui_lock);
}

/* Wyrm's pointer (Settings › Mouse pointer): RGBA + hotspot; empty = Windows'. */
JNIEXPORT void JNICALL UI_JNI(nativeDesktopUiCursor)(JNIEnv* env, jclass clazz, jbyteArray rgba, jint w, jint h,
                                                      jint hx, jint hy) {
  (void)clazz;
  ensure_lock();
  unsigned char* pixels = NULL;
  if (rgba && w > 0 && h > 0 && (*env)->GetArrayLength(env, rgba) >= w * h * 4) {
    pixels = malloc((size_t)w * h * 4);
    if (pixels) (*env)->GetByteArrayRegion(env, rgba, 0, w * h * 4, (jbyte*)pixels);
  }
  SDL_LockMutex(ui_lock);
  free(cursor_rgba);
  cursor_rgba = pixels;
  cursor_w = pixels ? w : 0;
  cursor_h = pixels ? h : 0;
  cursor_hx = hx;
  cursor_hy = hy;
  cursor_seq++;
  SDL_UnlockMutex(ui_lock);
}

/* The window's icon in the taskbar and Alt+Tab (RGBA). */
static unsigned char* icon_rgba;
static int icon_w, icon_h, icon_seq, icon_seen;
JNIEXPORT void JNICALL UI_JNI(nativeDesktopUiIcon)(JNIEnv* env, jclass clazz, jbyteArray rgba, jint w, jint h) {
  (void)clazz;
  if (!rgba || w <= 0 || h <= 0 || (*env)->GetArrayLength(env, rgba) < w * h * 4) return;
  unsigned char* pixels = malloc((size_t)w * h * 4);
  if (!pixels) return;
  (*env)->GetByteArrayRegion(env, rgba, 0, w * h * 4, (jbyte*)pixels);
  ensure_lock();
  SDL_LockMutex(ui_lock);
  free(icon_rgba);
  icon_rgba = pixels;
  icon_w = w;
  icon_h = h;
  icon_seq++;
  SDL_UnlockMutex(ui_lock);
}

/* Engine thread, from the poll: a new icon goes on the window. */
void wyrm_desktop_ui_apply_icon(void) {
  if (!win) return;
  ensure_lock();
  SDL_LockMutex(ui_lock);
  if (icon_seq != icon_seen && icon_rgba) {
    icon_seen = icon_seq;
    SDL_Surface* s = SDL_CreateSurfaceFrom(icon_w, icon_h, SDL_PIXELFORMAT_RGBA32, icon_rgba, icon_w * 4);
    if (s) {
      SDL_SetWindowIcon(win, s);
      SDL_DestroySurface(s);
    }
  }
  SDL_UnlockMutex(ui_lock);
}

/* Settings › Developer › Swapchain images: 0 = the reference client's
   (minImageCount + 1), else 2, 3 or 4. */
JNIEXPORT void JNICALL UI_JNI(nativeDesktopSetSwapImages)(JNIEnv* env, jclass clazz, jint images) {
  (void)env;
  (void)clazz;
  if (images != 0 && (images < 2 || images > 4)) images = 0;
  SDL_SetAtomicInt(&swap_images, images);
}
