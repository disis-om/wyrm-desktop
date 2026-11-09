"""Exercise the production C endpoint, roster parser and teammate predicates."""
from pathlib import Path
import os
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'Wyrm Desktop/build/team-check'
OUT.mkdir(parents=True, exist_ok=True)
NDK = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin'
VC = Path('C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC')
VC = sorted(VC.iterdir())[-1]
SDK = Path('C:/Program Files (x86)/Windows Kits/10')
VER = sorted((SDK/'Include').iterdir())[-1].name

def function(text, name):
    match = re.search(r'^(?:static )?(?:bool|size_t|void) ' + name + r'\([^\n]*', text, re.M)
    assert match, name
    start = match.start()
    brace = text.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

for platform, folder in [('android', 'Wyrm Android'), ('ios', 'Wyrm iOS/build-original-source')]:
    source = (ROOT/folder/'app/src/platform/android_team.c').read_text(encoding='utf-8')
    struct = source[source.index('typedef struct team_member {'):source.index('} team_member;') + len('} team_member;')]
    prelude = '''#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
typedef struct { int id, ntl_id; } snake;
typedef struct { char arena[192]; } settings;
typedef struct { struct { snake* snakes; int snake_id; } data; } game_data;
typedef struct { settings usrs; game_data gdata; } user;
typedef struct { user* usr; } tenv;
static int snake_count;
#define tdarray_length(x) snake_count
static const char* user_settings_arena(const settings* s) { return s->arena; }
'''
    c = prelude + struct + '''
static team_member frame_members[16];
static int frame_member_count;
'''
    for name in ['tab_field', 'arena_key', 'team_member_here', 'team_member_is_me', 'android_team_is_teammate']:
        c += function(source, name) + '\n'
    # Reuse the complete actual parsing loop, not a second parser in the test.
    start = source.index('  team_member parsed[TEAM_MAX_MEMBERS];')
    end = source.index('  SDL_LockMutex(team_mutex);', start)
    parsing = source[start:end].replace('TEAM_MAX_MEMBERS', '16')
    c += 'static void parse(const char* text) {\n' + parsing + '''
frame_member_count = count; memcpy(frame_members, parsed, sizeof(team_member) * count); }
static int checked;
#define CHECK(x) do { ++checked; if (!(x)) { printf("FAILED %d: %s\\n", __LINE__, #x); return 1; } } while (0)
static bool same(const char* a, const char* b) { char x[192], y[192]; return arena_key(a,x,sizeof(x)) && arena_key(b,y,sizeof(y)) && !strcmp(x,y); }
int main(void) {
  CHECK(same("ws://EXAMPLE.com:444/slither", " example.COM "));
  CHECK(same("wss://example.com:0444/slither?x=1", "example.com:444"));
  CHECK(!same("example.com:445", "example.com:444"));
  CHECK(!same("_GAME_MENU_", "_GAME_MENU_"));
  CHECK(same("[2001:db8::1]:444", "ws://[2001:0DB8:0:0:0:0:0:1]:444/slither"));
  CHECK(same("2001:db8::1", "[2001:db8::1]:444"));
  CHECK(!same("[2001:db8::2]:444", "[2001:db8::1]:444"));
  CHECK(!same("[2001:db8::1]:445", "[2001:db8::1]:444"));
  CHECK(!same("bad:99999999999999999", "bad:444"));
  CHECK(!same("ws://", "example.com:444"));
  user usr = {0}; tenv env = { &usr };
  strcpy(usr.usrs.arena, "example.com:444");
  /* Poll coordinates use world units, including fractional strings on the app side.
     These are anonymous regression fixtures, not a claim of a live team capture. */
  parse("Same name\\t33489\\t29001\\t5000\\t9\\t0\\t0\\t1234\\t-1\\tkey owner\\tws://EXAMPLE.com:444/slither\\n"
        "Elsewhere\\t30000\\t30000\\t300\\t3\\t0\\t1\\t5678\\t-1\\towner\\texample.com:445");
  CHECK(frame_member_count == 2);
  CHECK(frame_members[0].x == 33489 && frame_members[0].y == 29001);
  CHECK(frame_members[0].sid == 1234);
  CHECK(team_member_here(&env, frame_members));
  CHECK(!team_member_here(&env, frame_members + 1));
  CHECK(android_team_is_teammate(&env, 1234, "different display name"));
  CHECK(!android_team_is_teammate(&env, 5678, "Elsewhere"));
  CHECK(!android_team_is_teammate(&env, 9999, "Same name"));
  CHECK(android_team_is_teammate(&env, 0, "Same name"));
  snake me = {99, 9000}; snake_count = 1; usr.gdata.data.snakes = &me; usr.gdata.data.snake_id = 99;
  CHECK(!team_member_is_me(&env, frame_members));
  me.ntl_id = 1234; CHECK(team_member_is_me(&env, frame_members));
  strcpy(usr.usrs.arena, "example.com:445");
  CHECK(!team_member_here(&env, frame_members));
  CHECK(team_member_here(&env, frame_members + 1));
  parse("Same name\\t33489\\t29001\\t0\\t0\\t0\\t0\\t0\\t-1\\t\\t[2001:db8::1]:444");
  strcpy(usr.usrs.arena, "ws://[2001:0db8:0:0:0:0:0:1]:444/slither");
  CHECK(team_member_here(&env, frame_members));
  CHECK(android_team_is_teammate(&env, 1234, "Same name"));
  parse("Same name\\t33489\\t29001\\t0\\t0\\t0\\t0\\t0\\t-1\\t\\t2001:db8::1:444");
  CHECK(team_member_here(&env, frame_members));
  printf("%d production C checks passed\\n", checked); return 0;
}
'''
    if len(sys.argv) > 1:
        fixture = Path(sys.argv[1]).resolve()
        assert fixture.is_file()
        extra = '''
  FILE* samples = fopen("FIXTURE", "r"); CHECK(samples != NULL);
  char row[512]; int live = 0;
  while (fgets(row, sizeof(row), samples)) {
    parse(row); CHECK(frame_member_count == 1);
    tab_field(row, 10, usr.usrs.arena, sizeof(usr.usrs.arena));
    char key[192];
    if (arena_key(usr.usrs.arena, key, sizeof(key))) { CHECK(team_member_here(&env, frame_members)); ++live; }
  }
  fclose(samples); printf("%d live roster rows checked\\n", live);
'''.replace('FIXTURE', fixture.as_posix())
        c = c.replace('  printf("%d production C checks passed', extra + '  printf("%d production C checks passed')
    path = OUT/f'{platform}.c'
    path.write_text(c, encoding='utf-8')
    args = [str(NDK/'clang.exe'), '--target=x86_64-pc-windows-msvc', '-c',
            '-Werror', '-D_CRT_SECURE_NO_WARNINGS', '-Wno-unused-function', '-I'+str(VC/'include')]
    for part in ['ucrt', 'shared', 'um']: args.append('-I'+str(SDK/'Include'/VER/part))
    args += [str(path), '-o', str(OUT/f'{platform}.obj')]
    subprocess.run(args, check=True)
    link = [str(ROOT/'Wyrm Desktop/build/toolchain/lld-link.exe'), str(OUT/f'{platform}.obj'),
            '/out:'+str(OUT/f'{platform}.exe'), '/libpath:'+str(VC/'lib/x64')]
    for part in ['ucrt', 'um']: link.append('/libpath:'+str(SDK/'Lib'/VER/part/'x64'))
    link += ['libcmt.lib', 'libvcruntime.lib', 'libucrt.lib', 'kernel32.lib', 'oldnames.lib']
    subprocess.run(link, check=True)
    print(platform, flush=True)
    subprocess.run([str(OUT/f'{platform}.exe')], check=True)
