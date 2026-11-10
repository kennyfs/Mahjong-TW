package io.github.kennyfs.mahjong.server

import io.github.kennyfs.mahjong.protocol.BattleJson
import io.github.kennyfs.mahjong.protocol.EngineClient
import io.github.kennyfs.mahjong.protocol.Hub
import io.github.kennyfs.mahjong.protocol.HubLimits
import io.github.kennyfs.mahjong.protocol.Limits
import io.github.kennyfs.mahjong.protocol.Pace
import io.github.kennyfs.mahjong.protocol.ServerMsg
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("mahjong")

/** The server's settings, from the environment (see server/compose.yml). */
data class Config(
  val host: String = env("HOST", "0.0.0.0"),
  val port: Int = env("PORT", "8080").toInt(),
  val binary: String = env("MAHJONG_BINARY", "/opt/mahjong/mahjong"),
  val workers: Int = env("ENGINE_WORKERS", "2").toInt(),
  val queue: Int = env("ENGINE_QUEUE", "256").toInt(),
  val timeoutMs: Long = env("ENGINE_TIMEOUT_MS", "10000").toLong(),
  val maxRooms: Int = env("MAX_ROOMS", "200").toInt(),
  val maxConnections: Int = env("MAX_CONNECTIONS", "2000").toInt(),
  val connectionsPerAddress: Int = env("CONNECTIONS_PER_ADDRESS", "16").toInt(),
  /**
   * How many reverse proxies in front append to X-Forwarded-For: 1 behind Caddy (compose.yml), 2 behind Cloudflare
   * and then Traefik; 0 when clients reach the server directly.
   */
  val proxies: Int = env("TRUST_PROXY", "0").toInt(),
  /**
   * How long every discard stays alone on the table (whoever could claim it), and a bot's own turn: the protocol's
   * defaults (Limits); shorter ones only to test.
   */
  val claimWindowMs: Long = env("CLAIM_WINDOW_MS", Limits.CLAIM_WINDOW_MS.toString()).toLong(),
  val botTurnMs: Long = env("BOT_TURN_MS", Limits.BOT_TURN_MS.toString()).toLong(),
)

private fun env(name: String, default: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

/** Largest message a client may send: an Act is about 100 bytes, settings a few hundred. */
const val MAX_FRAME = 4096L
/** Messages waiting for a slow client before it is dropped (a table view goes out after each decision point). */
const val OUT_QUEUE = 512

fun main() {
  val config = Config()
  val pool = EnginePool(config.binary, config.workers, config.queue, config.timeoutMs)
  // Asks the engine once, so a missing or broken binary stops the server here rather than in a room.
  runBlocking { pool.call("{\"cmd\":\"rules\",\"rules\":\"simple1,none\"}") }.let { require("\"spec\"" in it) { "The engine does not answer: $it" } }
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val hub = Hub(EngineClient(pool::call), scope, HubLimits(maxRooms = config.maxRooms, pace = Pace(config.claimWindowMs, config.botTurnMs)))
  log.info("Mahjong battle server on ${config.host}:${config.port}, ${config.workers} engine workers, claim window ${config.claimWindowMs} ms")
  embeddedServer(CIO, host = config.host, port = config.port) { battle(hub, config) }.start(wait = true)
}

fun Application.battle(hub: Hub, config: Config) {
  install(WebSockets) {
    pingPeriod = 15.seconds
    timeout = 30.seconds
    maxFrameSize = MAX_FRAME
    masking = false
  }
  val total = AtomicInteger(0)
  val perAddress = ConcurrentHashMap<String, AtomicInteger>()
  routing {
    get("/health") { call.respondText("ok") }
    webSocket("/ws") {
      val address = clientAddress(call.request.origin.remoteAddress, call.request.header("X-Forwarded-For"), config.proxies)
      val mine = perAddress.computeIfAbsent(address) { AtomicInteger(0) }
      try {
        if (total.incrementAndGet() > config.maxConnections || mine.incrementAndGet() > config.connectionsPerAddress) {
          close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too many connections"))
          return@webSocket
        }
        val out = Channel<ServerMsg>(OUT_QUEUE)
        val conn = hub.open(address, { msg -> if (out.trySend(msg).isFailure) out.close() }, { out.close() })
        val sender = launch {
          for (msg in out) outgoing.send(Frame.Text(BattleJson.encodeToString(ServerMsg.serializer(), msg)))
          close(CloseReason(CloseReason.Codes.NORMAL, ""))
        }
        try {
          for (frame in incoming) {
            if (frame !is Frame.Text) break
            hub.receive(conn, frame.readText())
            if (!conn.open) break
          }
        } finally {
          hub.closed(conn)
          out.close()
          sender.join()
        }
      } finally {
        total.decrementAndGet()
        if (mine.decrementAndGet() <= 0) perAddress.remove(address, mine)
      }
    }
  }
}

/**
 * What limits apply to: the client's address, an IPv6 address by its /64, since one client usually has a whole /64.
 * Behind [proxies] trusted proxies, it is that many X-Forwarded-For entries from the end, which those proxies wrote;
 * with fewer entries (a proxy that drops the header from sources it does not trust), the first.
 */
fun clientAddress(remote: String, forwarded: String?, proxies: Int = 1): String {
  val entries = if (proxies > 0) forwarded?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty() else emptyList()
  val raw = entries.getOrNull(entries.size - proxies) ?: entries.firstOrNull() ?: remote
  // Only an address literal is parsed: a name would be looked up.
  if (!raw.matches(Regex("[0-9a-fA-F:.\\[\\]]+"))) return raw
  val ip = runCatching { InetAddress.getByName(raw.removePrefix("[").substringBefore("]")) }.getOrNull() ?: return raw
  val b = ip.address
  return if (b.size == 16) b.take(8).joinToString(":") { "%02x".format(it) } + "::/64" else ip.hostAddress
}
