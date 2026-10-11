# One-time, idempotent patch of Wyrm Android's engine for Wyrm Desktop.
# Every change sits behind WYRM_DESKTOP, which only Wyrm Desktop's
# native/CMakeLists.txt defines: Android and iOS compile exactly as before.
# Run: python "Wyrm Desktop/native/patch_engine_desktop.py"
import os

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", "Wyrm Android"))
MARK = "WYRM_DESKTOP"


def patch(rel, pairs):
    path = os.path.join(ROOT, rel)
    text = open(path, encoding="utf-8", newline="").read()
    changed = False
    for key, old, new in pairs:
        if key in text.replace("\r\n", "\n"):
            continue  # already applied
        hits = text.count(old)
        if hits != 1:
            o2 = old.replace("\n", "\r\n")
            if text.count(o2) == 1:
                old, new = o2, new.replace("\n", "\r\n")
            else:
                raise SystemExit(f"{rel}: anchor found {hits}x: {old[:70]!r}")
        text = text.replace(old, new, 1)
        changed = True
    if changed:
        open(path, "w", encoding="utf-8", newline="").write(text)
        print("patched", rel)
    else:
        print("already", rel)


# Assets are read from disk on a desktop (the working folder is Wyrm Android's
# root, which holds app/res); the APK extraction path stays Android's.
patch("thermite/src/platform/tassets.c", [
    ("defined(VLITHER_ANDROID) && !defined(WYRM_DESKTOP)",
     "#include \"tassets.h\"\n\n#ifdef VLITHER_ANDROID\n",
     "#include \"tassets.h\"\n\n/* Wyrm Desktop reads app/res straight from disk. */\n#if defined(VLITHER_ANDROID) && !defined(WYRM_DESKTOP)\n"),
])

# The window: hidden, resizable, the size Compose asks for. Compose shows the
# frames (desktop_bridge.c); nothing is ever presented to the screen by SDL.
patch("thermite/src/framework/twindow.c", [
    ("WYRM_DESKTOP: a hidden window",
     """  SDL_SetAppMetadata(env->config.title, APP_VERSION, "com.wyrm.omrajput");
  window->handle = SDL_CreateWindow(
      env->config.title, 1280, 720,
      SDL_WINDOW_VULKAN | SDL_WINDOW_FULLSCREEN | SDL_WINDOW_HIGH_PIXEL_DENSITY);""",
     """  SDL_SetAppMetadata(env->config.title, APP_VERSION, "com.wyrm.omrajput");
#ifdef WYRM_DESKTOP
  /* WYRM_DESKTOP: a hidden window the size of Compose's game area; its frames
     are read back and drawn by Compose (desktop_bridge.c). */
  int desktop_w = 1280, desktop_h = 720;
  wyrm_desktop_initial_size(&desktop_w, &desktop_h);
  window->handle = SDL_CreateWindow(
      env->config.title, desktop_w, desktop_h,
      SDL_WINDOW_VULKAN | SDL_WINDOW_HIDDEN | SDL_WINDOW_RESIZABLE);
#else
  window->handle = SDL_CreateWindow(
      env->config.title, 1280, 720,
      SDL_WINDOW_VULKAN | SDL_WINDOW_FULLSCREEN | SDL_WINDOW_HIGH_PIXEL_DENSITY);
#endif"""),
    ("wyrm_desktop_window_created(window->handle)",
     """  SDL_GetWindowSizeInPixels(window->handle, &window->size[0], &window->size[1]);
  glm_ivec2_copy(window->size, window->lsize);
  glm_ivec2_zero(window->lpos);
  env->config.fullscreen = true;
  return window;""",
     """  SDL_GetWindowSizeInPixels(window->handle, &window->size[0], &window->size[1]);
  glm_ivec2_copy(window->size, window->lsize);
  glm_ivec2_zero(window->lpos);
#ifdef WYRM_DESKTOP
  wyrm_desktop_window_created(window->handle);
  env->config.fullscreen = false;
#else
  env->config.fullscreen = true;
#endif
  return window;"""),
    ("wyrm_desktop_apply_size(window->handle)",
     """void twindow_poll_input(twindow* window) {
#ifdef VLITHER_ANDROID
  SDL_Event event;""",
     """void twindow_poll_input(twindow* window) {
#ifdef WYRM_DESKTOP
  wyrm_desktop_apply_size(window->handle);
#endif
#ifdef VLITHER_ANDROID
  SDL_Event event;"""),
    ("WYRM_DESKTOP: Compose owns full screen",
     """void twindow_toggle_fullscreen(twindow* window) {
#ifdef VLITHER_ANDROID
  SDL_SetWindowFullscreen(window->handle, true);""",
     """void twindow_toggle_fullscreen(twindow* window) {
#if defined(WYRM_DESKTOP)
  /* WYRM_DESKTOP: Compose owns full screen; the hidden window never goes. */
  (void)window;
#elif defined(VLITHER_ANDROID)
  SDL_SetWindowFullscreen(window->handle, true);"""),
])

# Frame readback for Compose: the swapchain image is copied into a host buffer
# at the end of each frame and handed over once the GPU has finished it.
patch("thermite/src/graphics/tcontext.c", [
    ("WYRM_DESKTOP: read back",
     """          .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
          .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,""",
     """#ifdef WYRM_DESKTOP
          /* WYRM_DESKTOP: read back every frame for Compose. */
          .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                        VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
#else
          .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
#endif
          .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,"""),
    ("desktop_capture_record(context",
     """void tcontext_end(tcontext* context) {
  tcontext_frame* fr = context->frames + context->current_frame;

  vkCmdEndRenderPass(fr->cmd);
  vkEndCommandBuffer(fr->cmd);
""",
     """#ifdef WYRM_DESKTOP
/*
 * Wyrm Desktop: the finished swapchain image is copied into one host-visible
 * buffer inside the frame's own command buffer, and published to Compose once
 * that frame's fence says the GPU is done (desktop_bridge.c). The buffer is
 * rebuilt when the size changes; the previous frame has always finished by then
 * because each frame is waited for right after it is submitted.
 */
static VkBuffer desktop_capture_buffer;
static VmaAllocation desktop_capture_memory;
static void* desktop_capture_mapped;
static VkDeviceSize desktop_capture_size;

static void desktop_capture_record(tcontext* context, VkCommandBuffer cmd) {
  VkDeviceSize needed = (VkDeviceSize)context->size[0] * context->size[1] * 4;
  if (needed == 0) return;
  if (needed != desktop_capture_size) {
    if (desktop_capture_buffer)
      vmaDestroyBuffer(context->allocator, desktop_capture_buffer,
                       desktop_capture_memory);
    VmaAllocationInfo info;
    VkResult made = vmaCreateBuffer(
        context->allocator,
        &(VkBufferCreateInfo){.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                              .size = needed,
                              .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                              .sharingMode = VK_SHARING_MODE_EXCLUSIVE},
        &(VmaAllocationCreateInfo){
            .flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT |
                     VMA_ALLOCATION_CREATE_MAPPED_BIT,
            .usage = VMA_MEMORY_USAGE_AUTO},
        &desktop_capture_buffer, &desktop_capture_memory, &info);
    if (made != VK_SUCCESS) {
      desktop_capture_buffer = VK_NULL_HANDLE;
      desktop_capture_size = 0;
      return;
    }
    desktop_capture_mapped = info.pMappedData;
    desktop_capture_size = needed;
  }
  VkImage image = context->swapchain_frames[context->current_image].image;
  VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  vkCmdPipelineBarrier(
      cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1,
      &(VkImageMemoryBarrier){
          .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
          .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
          .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
          .oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
          .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
          .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
          .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
          .image = image,
          .subresourceRange = range});
  vkCmdCopyImageToBuffer(
      cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, desktop_capture_buffer, 1,
      &(VkBufferImageCopy){
          .bufferOffset = 0,
          .bufferRowLength = 0,
          .bufferImageHeight = 0,
          .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
          .imageOffset = {0, 0, 0},
          .imageExtent = {(uint32_t)context->size[0], (uint32_t)context->size[1], 1}});
  vkCmdPipelineBarrier(
      cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
      0, 0, NULL, 1,
      &(VkBufferMemoryBarrier){
          .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
          .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
          .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
          .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
          .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
          .buffer = desktop_capture_buffer,
          .offset = 0,
          .size = VK_WHOLE_SIZE},
      1,
      &(VkImageMemoryBarrier){
          .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
          .srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
          .dstAccessMask = 0,
          .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
          .newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
          .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
          .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
          .image = image,
          .subresourceRange = range});
}

static void desktop_capture_publish(tcontext* context, VkFence fence) {
  if (!desktop_capture_buffer || !desktop_capture_mapped) return;
  vkWaitForFences(context->device, 1, &fence, VK_TRUE, UINT64_MAX);
  vmaInvalidateAllocation(context->allocator, desktop_capture_memory, 0,
                          VK_WHOLE_SIZE);
  VkFormat format = context->surface_format.format;
  int bgra = format == VK_FORMAT_B8G8R8A8_UNORM || format == VK_FORMAT_B8G8R8A8_SRGB;
  wyrm_desktop_publish_frame(desktop_capture_mapped, context->size[0],
                             context->size[1], context->size[0] * 4, bgra);
}
#endif

void tcontext_end(tcontext* context) {
  tcontext_frame* fr = context->frames + context->current_frame;

  vkCmdEndRenderPass(fr->cmd);
#ifdef WYRM_DESKTOP
  desktop_capture_record(context, fr->cmd);
#endif
  vkEndCommandBuffer(fr->cmd);
"""),
    ("desktop_capture_publish(context",
     """  context->last_present_succeeded =
      (r == VK_SUCCESS || r == VK_SUBOPTIMAL_KHR);""",
     """  context->last_present_succeeded =
      (r == VK_SUCCESS || r == VK_SUBOPTIMAL_KHR);
#ifdef WYRM_DESKTOP
  desktop_capture_publish(context, fr->wait_fence);
#endif"""),
])

# Controls: on a desktop the snake follows the mouse (from the middle of the
# game area, as slither on the web) and left click / Space / W / Up boosts.
# The touch controls and on-screen keys are not drawn.
patch("app/src/mobile/mobile_controls.c", [
    ("WYRM_DESKTOP: the snake follows the mouse",
     """bool mobile_controls_process_event(tenv* env, const void* raw_event) {
  const SDL_Event* event = raw_event;
  mobile_controls_state* state = &env->usr->mobile_controls;
  mobile_control_settings* cfg = &env->usr->usrs.mobile_controls;
""",
     """bool mobile_controls_process_event(tenv* env, const void* raw_event) {
  const SDL_Event* event = raw_event;
  mobile_controls_state* state = &env->usr->mobile_controls;
  mobile_control_settings* cfg = &env->usr->usrs.mobile_controls;

#ifdef WYRM_DESKTOP
  /* WYRM_DESKTOP: the snake follows the mouse from the middle of the game
     area; left click, Space, W or Up boosts. ImGui still sees every event. */
  if (event->type == SDL_EVENT_MOUSE_MOTION) {
    float dx = event->motion.x - env->wnd->size[0] * 0.5f;
    float dy = event->motion.y - env->wnd->size[1] * 0.5f;
    float length = sqrtf(dx * dx + dy * dy);
    if (length > 1.0f) {
      state->joystick_axis[0] = dx / length;
      state->joystick_axis[1] = dy / length;
      state->aim_valid = true;
    }
    return false;
  }
  if ((event->type == SDL_EVENT_MOUSE_BUTTON_DOWN ||
       event->type == SDL_EVENT_MOUSE_BUTTON_UP) &&
      event->button.button == SDL_BUTTON_LEFT) {
    state->boost_down = event->type == SDL_EVENT_MOUSE_BUTTON_DOWN;
    return false;
  }
  if ((event->type == SDL_EVENT_KEY_DOWN || event->type == SDL_EVENT_KEY_UP) &&
      (event->key.scancode == SDL_SCANCODE_SPACE ||
       event->key.scancode == SDL_SCANCODE_W ||
       event->key.scancode == SDL_SCANCODE_UP)) {
    state->boost_down = event->type == SDL_EVENT_KEY_DOWN;
    return true;
  }
  if (event->type == SDL_EVENT_KEY_DOWN &&
      event->key.scancode == SDL_SCANCODE_AC_BACK) {
    state->back_requested = true; /* Esc in the shell: leave the match */
    return true;
  }
  return false;
#endif
"""),
    ("WYRM_DESKTOP: mouse and keyboard; no touch controls are drawn",
     """void mobile_controls_draw_gameplay(tenv* env) {
#ifdef VLITHER_ANDROID""",
     """void mobile_controls_draw_gameplay(tenv* env) {
#ifdef WYRM_DESKTOP
  /* WYRM_DESKTOP: mouse and keyboard; no touch controls are drawn. */
  (void)env;
  return;
#endif
#ifdef VLITHER_ANDROID"""),
])

# Frame pacing: the hidden window does not hold presents to the display, so the
# desktop paces itself with Android's cap (the bridge sets 120 Hz by default).
patch("app/src/main.c", [
    ("defined(__ANDROID__) || defined(WYRM_DESKTOP)",
     """#ifdef __ANDROID__
#include <stdatomic.h>
#include <SDL3/SDL.h>
""",
     """#if defined(__ANDROID__) || defined(WYRM_DESKTOP)
#include <stdatomic.h>
#include <SDL3/SDL.h>
"""),
    ("#if defined(__ANDROID__) || defined(WYRM_DESKTOP)\n  wyrm_pace(draw);",
     """#ifdef __ANDROID__
  wyrm_pace(draw);
#endif""",
     """#if defined(__ANDROID__) || defined(WYRM_DESKTOP)
  wyrm_pace(draw);
#endif"""),
])
# Team chat has no button on the desktop yet (OM, 2026-10-03: team and chat come
# later). The button is not drawn and has no hit area.
patch("app/src/platform/android_team.c", [
    ("WYRM_DESKTOP: no team chat button",
     """void android_team_draw_chat_button(tenv* env) {
  bool anchored = chat_anchored;""",
     """void android_team_draw_chat_button(tenv* env) {
#ifdef WYRM_DESKTOP
  /* WYRM_DESKTOP: no team chat button (team and chat come later). */
  (void)env;
  chat_anchored = false;
  chat_button[2] = 0.0f;
  chat_button[3] = 0.0f;
  return;
#endif
  bool anchored = chat_anchored;"""),
])
# Settings › Performance › Vsync: the present mode follows the switch on the
# desktop too (2026-10-07).
patch("app/src/main.c", [
    ("|| defined(WYRM_DESKTOP)\n  wyrm_apply_present_mode(env);",
     """#ifdef __ANDROID__
  wyrm_apply_present_mode(env);
#endif""",
     """#if defined(__ANDROID__) || defined(WYRM_DESKTOP)
  wyrm_apply_present_mode(env);
#endif"""),
    ("|| defined(WYRM_DESKTOP)\n  /* This rebuild used the current present mode. */",
     """#ifdef __ANDROID__
  /* This rebuild used the current present mode. */""",
     """#if defined(__ANDROID__) || defined(WYRM_DESKTOP)
  /* This rebuild used the current present mode. */"""),
])
# user.dat in %APPDATA%\Wyrm (desktop_bridge.c wyrm_desktop_user_file), not in
# the working folder, which is Wyrm Android's tree (2026-10-07).
patch("app/src/constants.h", [
    ("WYRM_DESKTOP: user.dat lives in %APPDATA%",
     """#define USER_SETTINGS_FILE "user.dat"
""",
     """#ifdef WYRM_DESKTOP
/* WYRM_DESKTOP: user.dat lives in %APPDATA%\\Wyrm (desktop_bridge.c), not in
   the engine's working folder (Wyrm Android's tree, which holds app/res). */
const char* wyrm_desktop_user_file(const char* suffix);
#define USER_SETTINGS_FILE wyrm_desktop_user_file("")
#else
#define USER_SETTINGS_FILE "user.dat"
#endif
"""),
])
patch("app/src/game/user_settings.c", [
    ("wyrm_desktop_user_file(\".tmp\")",
     """#define USER_SETTINGS_TEMP_FILE USER_SETTINGS_FILE ".tmp"
#define USER_SETTINGS_BACKUP_FILE USER_SETTINGS_FILE ".bak\"""",
     """#ifdef WYRM_DESKTOP
#define USER_SETTINGS_TEMP_FILE wyrm_desktop_user_file(".tmp")
#define USER_SETTINGS_BACKUP_FILE wyrm_desktop_user_file(".bak")
#else
#define USER_SETTINGS_TEMP_FILE USER_SETTINGS_FILE ".tmp"
#define USER_SETTINGS_BACKUP_FILE USER_SETTINGS_FILE ".bak"
#endif"""),
])
# Eyes Back on the keyboard: E (2026-10-07).
patch("app/src/game/input.c", [
    ("WYRM_DESKTOP: E is Eyes Back's key",
     """  if (mobile_hotkeys_pressed(env, MOBILE_HOTKEY_EYES_BACK))
    eyes_back_toggle(gdata);
""",
     """  if (mobile_hotkeys_pressed(env, MOBILE_HOTKEY_EYES_BACK))
    eyes_back_toggle(gdata);
#ifdef WYRM_DESKTOP
  /* WYRM_DESKTOP: E is Eyes Back's key (no on-screen keys on a desktop). */
  if (tkeyboard_key_pressed(env->kb, GLFW_KEY_E)) eyes_back_toggle(gdata);
#endif
"""),
])
print("done")
