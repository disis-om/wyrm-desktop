package com.wyrm.desktop

import com.wyrm.omrajput.data.TeamPresence
import com.wyrm.omrajput.data.TeamService
import kotlinx.coroutines.runBlocking
import java.io.File

/** Reads the existing account and one normal empty-message roster poll. No chat post. */
object DesktopLiveReadProbe {
    @JvmStatic fun main(args: Array<String>) { runDesktopLiveReadProbe() }
}

private fun runDesktopLiveReadProbe(): Unit = runBlocking {
    val repo = Account.repository
    println("Live read: saved account session=${repo.hasSession}")
    val player = if (repo.hasSession) runCatching { repo.me() }.getOrNull() else null
    println("Live read: authenticated profile=${player != null}")
    if (player != null) {
        println("Live read: global GET=${runCatching { repo.globalMessages().size }.getOrNull() ?: "failed"}")
        println("Live read: online GET=${runCatching { repo.onlinePlayers().size }.getOrNull() ?: "failed"}")
    }
    val bin = File(System.getProperty("wyrm.native.bin"))
    System.load(File(bin, "SDL3.dll").absolutePath)
    System.load(File(bin, "wyrm.dll").absolutePath)
    val service = TeamService(DesktopContext)
    println("Live read: saved team configured=${service.configured}")
    if (service.configured) {
        val roster = service.poll(TeamPresence(nickname = player?.ingameName.orEmpty()), null)
        println("Live read: NTL poll accepted=${roster.connected}, members=${roster.members.size}")
        if (roster.connected) {
            val sample = File(System.getProperty("wyrm.probe.out"), "team-check/live-roster.tsv")
            sample.parentFile.mkdirs()
            sample.writeText(roster.members.mapIndexed { index, member -> listOf("sample$index", member.x, member.y,
                member.score, member.rank, if (member.bot) 1 else 0, 0, member.snakeId, -1, "", member.arena.replace('\t', ' ').replace('\n', ' ')).joinToString("\t") }.joinToString("\n"))
        }
    }
    println("Live read complete. No team or global test message sent.")
    kotlin.system.exitProcess(0)
}
