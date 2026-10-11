# wyrm.dll: Wyrm's engine for Windows, compiled from Wyrm Android's LIVE tree
# (app/src + thermite) — never a copy (Wyrm Desktop AGENTS.md rule "One engine").
# The SDL3 build is the same source Android uses (android-sdl).
#
# Platform: VLITHER_ANDROID selects the SDL3 + JNI paths the engine already
# has; WYRM_DESKTOP marks the few places that differ on a desktop (hidden
# window, frame readback for Compose, mouse steering, assets from disk).
cmake_minimum_required(VERSION 3.22)
project(WyrmDesktopNative LANGUAGES C CXX)

get_filename_component(WYRM_ANDROID "${CMAKE_CURRENT_LIST_DIR}/../../Wyrm Android" ABSOLUTE)

set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
set(SDL_EXAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory("${WYRM_ANDROID}/android-sdl" "${CMAKE_BINARY_DIR}/SDL" EXCLUDE_FROM_ALL)

file(GLOB_RECURSE WYRM_C_SOURCES CONFIGURE_DEPENDS
    "${WYRM_ANDROID}/app/src/*.c"
    "${WYRM_ANDROID}/thermite/src/*.c")
file(GLOB_RECURSE WYRM_CXX_SOURCES CONFIGURE_DEPENDS
    "${WYRM_ANDROID}/app/src/*.cpp"
    "${WYRM_ANDROID}/thermite/src/*.cpp")
list(FILTER WYRM_CXX_SOURCES EXCLUDE REGEX "imgui_impl_glfw\\.cpp$")

find_package(JNI REQUIRED)
find_package(Vulkan REQUIRED)

add_library(wyrm SHARED
    ${WYRM_C_SOURCES}
    ${WYRM_CXX_SOURCES}
    "${CMAKE_CURRENT_LIST_DIR}/desktop_bridge.c")

target_compile_features(wyrm PRIVATE c_std_11 cxx_std_17)
target_compile_definitions(wyrm PRIVATE
    SETTINGS_VERSION="2.8"
    CIMGUI_NO_EXPORT
    CGLM_FORCE_DEPTH_ZERO_TO_ONE
    VLITHER_ANDROID=1
    WYRM_DESKTOP=1
    SDL_MAIN_HANDLED=1
    _CRT_SECURE_NO_WARNINGS
    NOMINMAX
    WIN32_LEAN_AND_MEAN
    # The JVM (Android Studio's JBR) loads its own msvcp140.dll 14.29 first, so
    # wyrm.dll binds to it. MSVC 17.10+ headers make std::mutex constexpr, which
    # that older runtime crashes on (VMA's first lock, vmaCreateBuffer, at
    # startup). Microsoft's documented switch keeps the old constructor.
    _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR)
target_include_directories(wyrm BEFORE PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/shim"
    "${CMAKE_CURRENT_LIST_DIR}"
    "${WYRM_ANDROID}/app/src"
    "${WYRM_ANDROID}/android/app/.cxx-version"
    "${WYRM_ANDROID}/thermite/include"
    "${WYRM_ANDROID}/thermite/src"
    "${WYRM_ANDROID}/android-sdl/include"
    ${JNI_INCLUDE_DIRS})
# The engine is written for clang/GCC: GNU C is fine under clang-cl.
target_compile_options(wyrm PRIVATE
    "/FI${CMAKE_CURRENT_LIST_DIR}/desktop_compat.h"
    -Wno-unused-parameter -Wno-unused-function -Wno-unused-variable
    -Wno-missing-braces -Wno-sign-compare -Wno-deprecated-declarations
    -Wno-microsoft-anon-tag -Wno-gnu-empty-initializer -Wno-c23-extensions)

# crypt32: DPAPI (CryptProtectData) for the team vault key, desktop_bridge.c.
target_link_libraries(wyrm PRIVATE SDL3::SDL3 Vulkan::Vulkan ws2_32 iphlpapi crypt32)

# wyrmwin.dll (2026-10-12): the shell's few Windows calls (rounded corners, the
# taskbar's AppUserModelID, shortcuts). No SDL or Vulkan, so the installer can
# load it without the engine.
add_library(wyrmwin SHARED "${CMAKE_CURRENT_LIST_DIR}/desktop_win.c")
target_compile_definitions(wyrmwin PRIVATE _CRT_SECURE_NO_WARNINGS UNICODE _UNICODE)
target_include_directories(wyrmwin PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(wyrmwin PRIVATE dwmapi shell32 ole32 propsys uuid)

# WyrmSetup.exe (2026-10-12, D8): the downloadable setup's stub. Static C
# runtime so it runs on a PC with no Visual C++ runtime. The app image is
# appended to it by tools/release-wyrm-desktop.sh (packaging step).
add_executable(WyrmSetup WIN32 "${CMAKE_CURRENT_LIST_DIR}/setup_stub.c" "${CMAKE_CURRENT_LIST_DIR}/setup_stub.rc")
set_property(TARGET WyrmSetup PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded")
target_compile_definitions(WyrmSetup PRIVATE _CRT_SECURE_NO_WARNINGS UNICODE _UNICODE)
target_link_libraries(WyrmSetup PRIVATE user32 gdi32 dwmapi shell32)
target_link_options(WyrmSetup PRIVATE "/MANIFEST:NO")
