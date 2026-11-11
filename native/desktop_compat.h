/*
 * Force-included into every engine file when Wyrm Desktop builds wyrm.dll
 * (native/CMakeLists.txt, /FI). The engine's Android paths ask SDL for the
 * JVM's JNIEnv; on a desktop the JVM is Compose's, and desktop_bridge.c
 * answers instead. The rest is the small set of hooks the WYRM_DESKTOP
 * blocks in thermite call (hidden window, size requests, frame readback).
 */
#ifndef WYRM_DESKTOP_COMPAT_H
#define WYRM_DESKTOP_COMPAT_H

#ifdef __cplusplus
extern "C" {
#endif

/* SDL's Android-only JNI helpers, answered by desktop_bridge.c. */
void* SDL_GetAndroidJNIEnv(void);
void* SDL_GetAndroidActivity(void);

/* The hidden SDL window the engine renders into. */
void wyrm_desktop_window_created(void* sdl_window);
void wyrm_desktop_initial_size(int* width, int* height);
/* Engine thread, once per input poll: applies a size Compose asked for. */
void wyrm_desktop_apply_size(void* sdl_window);
/* Engine thread, after a frame finished on the GPU: hands its pixels to the
   Compose side. `bgra` is 1 for B8G8R8A8 swapchains, 0 for R8G8B8A8. */
void wyrm_desktop_publish_frame(const void* pixels, int width, int height,
                                int stride, int bgra);

/* Direct presentation (2026-11-05, desktop_bridge.c): the HWND the shell asks
   the engine to present to (NULL = hidden window + readback), the one in use,
   and the Vulkan surface for it (`instance` is a VkInstance, `surface` a
   VkSurfaceKHR*; void here so this header needs no Vulkan). */
void* wyrm_desktop_target_wanted(void);
void* wyrm_desktop_target_hwnd(void);
void wyrm_desktop_target_set_active(void* hwnd);
int wyrm_desktop_direct(void);
int wyrm_desktop_create_surface(void* sdl_window, void* instance, void* surface);
/* The laser's shell-side conditions, and the hint line (engine draws both in
   a direct match). */
int wyrm_desktop_laser_wanted(int assist);
void wyrm_desktop_draw_hint(void);

/* The game window (OM, 2026-11-12, desktop_ui.c): the lobby and the match
   are the SDL window itself on screen; Compose shows only Home. `env` is a
   tenv*, `window` an SDL_Window*, `event` an SDL_Event*. */
int wyrm_desktop_native_shown(void);
void wyrm_desktop_native_window_poll(void* env, void* window);
/* 0 = the game gets it too, 1 = ImGui only, 2 = nobody else. */
int wyrm_desktop_ui_event(void* env, const void* event);
int wyrm_desktop_ui_event_owned(void);
void wyrm_desktop_toggle_fullscreen(void);
/* Main.c, inside the frame's ImGui window: the native lobby, picker, chat
   box and window strip. True when the lobby drew (nothing else needed). */
int wyrm_desktop_ui_draw(void* env);
/* Settings › Developer › Swapchain images (0 = the reference client's). */
int wyrm_desktop_swap_images(void);
int wyrm_desktop_swap_images_changed(void);

#ifdef __cplusplus
}
#endif

#endif
