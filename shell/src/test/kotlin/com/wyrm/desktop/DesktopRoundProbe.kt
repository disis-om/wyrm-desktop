package com.wyrm.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.sun.net.httpserver.HttpServer
import com.wyrm.omrajput.data.*
import com.wyrm.omrajput.ui.TrailsStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import java.awt.event.KeyEvent as AwtKey

/** Local HTTP only, isolated APPDATA. Never posts a message to the live service. */
object DesktopRoundProbe {
    @JvmStatic fun main(args: Array<String>) { runDesktopRoundProbe() }
}

private fun runDesktopRoundProbe(): Unit = runBlocking {
    var checks = 0
    fun verify(value: Boolean, name: String) { check(value) { name }; checks++ }
    val team = TeamService(DesktopContext)
    val presence = TeamPresence(nickname = "Name & plus+", x = 33489, y = 29001, arena = "[2001:db8::1]:444", snakeId = 1234)
    fun query(message: String?) = URI(team.pollRequest(TeamProfile("Fixture", "fake-team-id", "fake-auth-key"), presence, message))
        .rawQuery.split('&').associate { part -> part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), "UTF-8") }
    val silent = query(null)
    verify("msg" in silent && silent["msg"] == "", "silent polls include msg")
    verify(silent["ver"] == "9.76" && "tlm" !in silent, "NTL poll version and backlog contract")
    verify(silent["sid"] == "1234" && silent["valx"] == "33489", "position and snake id")
    verify(query("hello & + नमस्ते")["msg"] == "hello & + नमस्ते", "message URL encoding")
    val desktopTeam = TeamService(DesktopContext, "session1", escapeMessages = true)
    val wire = URI(desktopTeam.pollRequest(TeamProfile("Fixture", "fake-team-id", "fake-auth-key"), presence, "hi <3 & you"))
        .rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    verify(wire["nick"] == "session1Name & plus+" && wire["msg"] == "hi&nbsp;&lt;3&nbsp;&amp;&nbsp;you", "desktop session identity and reference HTML encoding")
    val parse = TeamService::class.java.getDeclaredMethod("parse", String::class.java).apply { isAccessible = true }
    val parsed = parse.invoke(team, """[{"nick":"abcdefghSame name","srv":"ws://EXAMPLE.com:444/slither","sid":1234,"valx":"33489.90657289786","valy":"29001.4"},{"nick":"ijklmnopSame name","srv":"example.com:445","sid":5678,"valx":"123.5","valy":"456.2"}]""") as TeamState
    verify(parsed.connected && parsed.members.size == 2, "roster preserves duplicate names")
    verify(parsed.members[0].snakeId == 1234 && parsed.members[0].x == 33489 && parsed.members[0].y == 29001, "fractional poll positions reach integers")
    val echo = TeamService::class.java.getDeclaredMethod("parse", String::class.java, String::class.java, String::class.java).apply { isAccessible = true }
    val missing = echo.invoke(desktopTeam, """[{"nick":"other123Name","msg":"hello"}]""", "session1Name", "hello") as TeamState
    verify(missing.connected && !missing.messageAcknowledged, "a roster response without our echo cannot clear the message")
    val confirmed = echo.invoke(desktopTeam, """[{"nick":"session1Name","msg":"hi&nbsp;&lt;3&nbsp;&amp;&nbsp;you"}]""", "session1Name", "hi <3 & you") as TeamState
    verify(confirmed.messageAcknowledged && confirmed.messages.single().text == "hi <3 & you", "escaped own echo acknowledges and remains visible")
    val olderClients = TeamService(DesktopContext)
    val repeatedRows = """[{"nick":"WYRMPLYRAlice","msg":"one"},{"nick":"WYRMPLYRBob","msg":"two"}]"""
    parse.invoke(olderClients, repeatedRows)
    verify((parse.invoke(olderClients, repeatedRows) as TeamState).messages.isEmpty(), "shared legacy prefixes do not replay or overwrite each other's history")
    val pack = DesktopTeam::class.java.getDeclaredMethod("pack", TeamState::class.java, String::class.java).apply { isAccessible = true }
    val rows = (pack.invoke(DesktopTeam, parsed, "example.com:444") as String).lines().map { it.split('\t') }
    verify(rows.size == 2 && rows.all { it.size == 11 }, "11-field engine packing")
    verify(rows[0][7] == "1234" && rows[0][10] == "ws://EXAMPLE.com:444/slither", "sid and arena survive packing")
    val outbox = TeamOutbox()
    verify(outbox.queue("first") && !outbox.queue("second"), "one outgoing message at a time")
    outbox.acknowledge("first", false)
    verify(outbox.pending == "first", "failed poll retains message")
    outbox.acknowledge("another", true)
    verify(outbox.pending == "first", "unrelated result cannot clear message")
    outbox.acknowledge("first", true)
    verify(outbox.pending == null, "successful poll acknowledges message")

    val requests = CopyOnWriteArrayList<Triple<String, String, String>>()
    val failPage = AtomicBoolean(true)
    val pageRequests = AtomicInteger()
    val rateLimited = AtomicBoolean(false)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    fun player() = JSONObject().put("id", "fixture").put("displayName", "Fixture").put("username", "fixture").put("bio", "bio")
    server.createContext("/") { exchange ->
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        requests.add(Triple(exchange.requestMethod, exchange.requestURI.toString(), body))
        var status = 200
        val response: JSONObject = when {
            exchange.requestURI.path == "/v1/auth/login" -> JSONObject().put("token", "local-fixture-only").put("player", player())
            exchange.requestURI.path == "/v1/me" -> player()
            exchange.requestURI.path == "/v1/me/avatar" -> player()
            exchange.requestURI.path == "/v1/chat/messages" -> {
                check(exchange.requestHeaders.getFirst("Authorization") == "Bearer local-fixture-only")
                JSONObject().put("messages", JSONArray())
            }
            exchange.requestURI.path == "/v1/leaderboard" -> {
                status = 429; rateLimited.set(true); exchange.responseHeaders.add("Retry-After", "12")
                JSONObject().put("error", "RATE_LIMITED")
            }
            exchange.requestURI.path == "/v1/trails" -> {
                pageRequests.incrementAndGet()
                val more = exchange.requestURI.rawQuery.orEmpty().contains("cursor=")
                if (more && failPage.getAndSet(false)) { status = 503; JSONObject().put("error", "TEMPORARY") }
                else {
                    val trails = JSONArray()
                    for (i in (if (more) 10 else 0) until (if (more) 14 else 10)) trails.put(JSONObject().put("id", "t$i")
                        .put("caption", "Trail $i").put("createdAt", "2026-10-19T00:00:00Z")
                        .put("author", JSONObject().put("playerId", "fixture").put("displayName", "Fixture")))
                    JSONObject().put("trails", trails).put("nextCursor", if (more) JSONObject.NULL else "next+page")
                }
            }
            else -> JSONObject()
        }
        val bytes = response.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
        val repo = WyrmRepository(DesktopContext, "http://127.0.0.1:${server.address.port}", DesktopHttp::open)
        repo.logIn("fixture", "local-only")
        repo.sendGlobal("hello & + नमस्ते")
        val sent = requests.last()
        verify(sent.first == "POST" && sent.second == "/v1/chat/messages" && JSONObject(sent.third).getString("body") == "hello & + नमस्ते", "global POST method, route, auth and JSON body")
        repo.updateProfile(displayName = "Changed", username = "changed", bio = "new bio", avatarKey = "mono-ink")
        val patch = JSONObject(requests.last().third)
        verify(requests.last().first == "PATCH" && !patch.has("ingameName") && patch.getString("bio") == "new bio", "profile PATCH excludes lobby name")
        repo.uploadAvatar(byteArrayOf(1, 2, 3))
        verify(requests.last().first == "PUT" && requests.last().second == "/v1/me/avatar", "avatar upload request")
        repo.removeAvatar()
        verify(requests.last().first == "DELETE", "avatar remove request")
        TrailsStore.reset(); TrailsStore.repository = repo
        val done = AtomicBoolean(false)
        SwingUtilities.invokeAndWait { TrailsStore.refresh { done.set(true) } }
        waitFor { done.get() }
        verify(TrailsStore.trails.size == 10 && !TrailsStore.reachedEnd && pageRequests.get() == 1, "Trails starts with ten and no eager next page")
        SwingUtilities.invokeAndWait { TrailsStore.loadMoreIfNeeded("t6") }
        Thread.sleep(150)
        verify(pageRequests.get() == 1, "prefetch waits until three cards from end")
        SwingUtilities.invokeAndWait { TrailsStore.loadMoreIfNeeded("t7") }
        waitFor { TrailsStore.moreFailed }
        verify(TrailsStore.trails.size == 10 && !TrailsStore.loadingMore, "failed page keeps feed and enables retry")
        SwingUtilities.invokeAndWait { TrailsStore.retryMore() }
        waitFor { TrailsStore.reachedEnd }
        verify(TrailsStore.trails.size == 14 && !TrailsStore.moreFailed, "retry appends page and reaches caught-up state")
        verify(requests.filter { it.second.startsWith("/v1/trails") }.all { "limit=10" in it.second }, "feed page limit stays ten")
        repo.trails(null, "fixture")
        verify("limit=20" in requests.last().second, "profile page limit twenty")
        val limited = runCatching { repo.leaderboard("score") }.exceptionOrNull()!!
        verify(rateLimited.get() && repo.isRateLimited(limited) && repo.retryDelayMs(limited, 10000) == 12000L && repo.shouldStayQuiet, "Retry-After reaches the quiet gate")
    } finally { server.stop(0); TrailsStore.reset() }

    val prefs = DesktopContext.getSharedPreferences("wyrm_desktop_shortcuts", 0)
    prefs.edit().clear().putInt("CHAT", AwtKey.VK_F8).apply()
    val document = DesktopAccountSync.platformDocument()
    prefs.edit().clear().apply()
    DesktopAccountSync.applyPlatform(document)
    verify(DesktopShortcuts.binding(DesktopShortcuts.Action.CHAT) == AwtKey.VK_F8, "shortcut account save and restore")
    val bad = JSONObject().put("prefs", JSONObject().put("wyrm_desktop_shortcuts", JSONObject()
        .put("SEARCH", JSONArray().put("s").put("not-an-int")).put("FUTURE", JSONArray().put("i").put(123))
        .put("REDO", JSONArray().put("i").put(AwtKey.VK_F9))))
    val report = DesktopAccountSync.applyPlatform(bad)
    verify(report.skipped == 2 && DesktopShortcuts.binding(DesktopShortcuts.Action.REDO) == AwtKey.VK_F9, "invalid and future keys skip independently")
    DesktopShortcuts.reset()
    DesktopAccountSync.applyPlatform(JSONObject().put("prefs", JSONObject()))
    verify(DesktopShortcuts.binding(DesktopShortcuts.Action.CHAT) == AwtKey.VK_C, "older account document keeps defaults")
    verify(DesktopShortcuts.binding(DesktopShortcuts.Action.CHAT_PANEL) == AwtKey.VK_C, "older key maps inherit double-C panel toggle")
    verify(DesktopCursors.styles.size == 36 && DesktopCursors.styles.map { it.id }.toSet().size == 36, "36 stable cursor ids")
    DesktopCursors.select("paper")
    DesktopCursors.setNormalLaser(true)
    val cursors = DesktopAccountSync.platformDocument()
    DesktopCursors.select("system")
    DesktopCursors.setNormalLaser(false)
    DesktopAccountSync.applyPlatform(cursors)
    verify(DesktopCursors.current() == "paper" && DesktopCursors.laserInNormal, "cursor and Normal laser restore from Windows account settings")
    val unknownCursor = JSONObject().put("prefs", JSONObject().put("wyrm_desktop_cursor", JSONObject().put("style", JSONArray().put("s").put("future-style"))))
    verify(DesktopAccountSync.applyPlatform(unknownCursor).skipped == 1 && DesktopCursors.current() == "paper", "unknown cursor skips without replacing selected cursor")
    AppGate.state.value = Gate.IN
    ShellNav.tab.value = Tab.Play
    verify(DesktopShortcuts.handle(key(AwtKey.VK_5, AwtKey.CTRL_DOWN_MASK)) && ShellNav.tab.value == Tab.Settings, "Ctrl+5 settings")
    DesktopShortcuts.textSessions = 1
    verify(!DesktopShortcuts.handle(key(AwtKey.VK_1, AwtKey.CTRL_DOWN_MASK)) && ShellNav.tab.value == Tab.Settings, "text field owns shortcuts")
    DesktopShortcuts.textSessions = 0
    val modal = Any(); DesktopShortcuts.modalChange(modal, true)
    verify(!DesktopShortcuts.handle(key(AwtKey.VK_1, AwtKey.CTRL_DOWN_MASK)), "modal owns shortcuts")
    DesktopShortcuts.modalChange(modal, false)
    var undos = 0; DesktopShortcuts.undo = { undos++ }
    verify(DesktopShortcuts.handle(key(AwtKey.VK_LEFT)) && DesktopShortcuts.handle(key(AwtKey.VK_Z, AwtKey.CTRL_DOWN_MASK)) && undos == 2, "both undo shortcuts route centrally")
    DesktopShortcuts.undo = null
    println("Desktop round: $checks checks passed. Local mock HTTP only; no live messages sent.")
    kotlin.system.exitProcess(0)
}

private fun waitFor(test: () -> Boolean) {
    val until = System.currentTimeMillis() + 8000
    while (!test()) { check(System.currentTimeMillis() < until) { "Probe timed out" }; Thread.sleep(25) }
}

@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
private fun key(code: Int, modifiers: Int = 0): KeyEvent = KeyEvent(key = Key(code), type = KeyEventType.KeyDown,
    nativeEvent = AwtKey(java.awt.Canvas(), AwtKey.KEY_PRESSED, System.currentTimeMillis(), modifiers, code, AwtKey.CHAR_UNDEFINED))
