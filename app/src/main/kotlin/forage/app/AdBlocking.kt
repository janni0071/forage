package forage.app

import forage.core.adblock.AdBlockList
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Ad blocking in the embedded player (the private vault's videos on Android): whether it's on,
 * and which servers the player asked for what, since Forage started, kept in memory only, so a
 * missed ad server can be found and added.
 */
object AdBlocking {
    /** Set from the private vault's own setting as it unlocks; on until then. */
    @Volatile var enabled = true

    /** A server the player asked: how often, and how often it was kept out. */
    data class Server(val host: String, val asked: Int, val blocked: Int)

    private val servers = ConcurrentHashMap<String, Server>()

    /** Whether the player is kept from loading [url]; noted either way. */
    fun check(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        val block = enabled && AdBlockList.blocks(url)
        servers.merge(host, Server(host, 1, if (block) 1 else 0)) { old, _ ->
            old.copy(asked = old.asked + 1, blocked = old.blocked + if (block) 1 else 0)
        }
        return block
    }

    /** The servers asked, those kept out first, then the most asked. */
    fun servers(): List<Server> = servers.values.sortedWith(compareByDescending<Server> { it.blocked > 0 }.thenByDescending { it.asked })

    val blockedCount: Int get() = servers.values.sumOf { it.blocked }

    fun clear() = servers.clear()
}
