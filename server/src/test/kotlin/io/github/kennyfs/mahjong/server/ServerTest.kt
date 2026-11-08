package io.github.kennyfs.mahjong.server

import io.github.kennyfs.mahjong.protocol.Act
import io.github.kennyfs.mahjong.protocol.Action
import io.github.kennyfs.mahjong.protocol.BattleJson
import io.github.kennyfs.mahjong.protocol.ClientMsg
import io.github.kennyfs.mahjong.protocol.Create
import io.github.kennyfs.mahjong.protocol.EngineClient
import io.github.kennyfs.mahjong.protocol.HandResult
import io.github.kennyfs.mahjong.protocol.Hello
import io.github.kennyfs.mahjong.protocol.Hub
import io.github.kennyfs.mahjong.protocol.HubLimits
import io.github.kennyfs.mahjong.protocol.Length
import io.github.kennyfs.mahjong.protocol.Mode
import io.github.kennyfs.mahjong.protocol.PROTOCOL_VERSION
import io.github.kennyfs.mahjong.protocol.Pace
import io.github.kennyfs.mahjong.protocol.RoomSettings
import io.github.kennyfs.mahjong.protocol.RuleChoice
import io.github.kennyfs.mahjong.protocol.ServerMsg
import io.github.kennyfs.mahjong.protocol.Start
import io.github.kennyfs.mahjong.protocol.State
import io.github.kennyfs.mahjong.protocol.TableView
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The server with the real engine: a table hand played to its end by a WebSocket client and three bots. */
class ServerTest {
  private val binary = System.getenv("MAHJONG_BINARY") ?: ""

  private suspend fun DefaultClientWebSocketSession.send(msg: ClientMsg) =
    outgoing.send(Frame.Text(BattleJson.encodeToString(ClientMsg.serializer(), msg)))

  private suspend fun DefaultClientWebSocketSession.next(): ServerMsg = withTimeout(30_000) {
    val f = incoming.receive() as Frame.Text
    BattleJson.decodeFromString(ServerMsg.serializer(), f.readText())
  }

  @Test
  fun aTableHandOverWebSockets() {
    assumeTrue(File(binary).canExecute())
    val pool = EnginePool(binary, 2, 64, 10_000)
    val hub = Hub(EngineClient(pool::call), CoroutineScope(SupervisorJob() + Dispatchers.Default), HubLimits(pace = Pace.NONE, messagesPerSecond = 1e6, messageBurst = 1_000_000))
    testApplication {
      application { battle(hub, Config(proxies = 0)) }
      val client = createClient { install(WebSockets) }
      client.webSocket("/ws") {
        send(Hello(PROTOCOL_VERSION, "Host"))
        send(Create(RoomSettings(Mode.TABLE, Length.HAND, rules = RuleChoice("standard", "see"), timeLimit = 10)))
        while (next() !is State) Unit
        send(Start)
        var result: HandResult? = null
        while (result == null) {
          when (val m = next()) {
            is TableView -> if (m.view["my_turn"]?.jsonPrimitive?.boolean == true) {
              val legal = m.view["legal"]!!.jsonArray.map { it.jsonObject }
              val a = legal.firstOrNull { it["type"]!!.jsonPrimitive.content in setOf("discard", "pass") } ?: legal.first()
              send(Act(m.hand, m.step, BattleJson.decodeFromJsonElement(Action.serializer(), a)))
            }
            is HandResult -> result = m
            else -> {}
          }
        }
        assertEquals(0.0, result.outcome.tables[0].points.sum(), 1e-9)
      }
    }
    pool.close()
  }

  @Test
  fun addressesForLimits() {
    assertEquals("1.2.3.4", clientAddress("10.0.0.2", "9.9.9.9, 1.2.3.4"))
    assertEquals("9.9.9.9", clientAddress("10.0.0.2", "8.8.8.8, 9.9.9.9, 1.2.3.4", proxies = 2))
    assertEquals("10.0.0.2", clientAddress("10.0.0.2", "1.2.3.4", proxies = 0))
    assertEquals("20:01:0d:b8:00:00:00:01::/64", clientAddress("2001:db8:0:1:2:3:4:5", null))
  }
}
