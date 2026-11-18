package io.github.kennyfs.mahjong

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records.parse and Records.merge: a records file read, and its hands added to the ones kept. */
class RecordsImportTest {
  private fun hand(id: String, time: String, fav: Boolean = false, kind: RecordKind = RecordKind.practice, group: String? = null) = HandRecord(
    id, time, kind, GameRecord("simple2", Setup()), 0, listOf("A", "B", "C", "D"), listOf(null, "net", "net", "net"),
    listOf(0.0, 0.0, 0.0, 0.0), emptyList(), -1, selfDraw = false, exhausted = true, tai = 0, group = group, fav = fav,
  )

  private fun battle(id: String, time: String) =
    BattleGroup(id, time, RecordKind.table, "server", "simple2", listOf("A", "B"), 0, listOf(0.0, 0.0), 1)

  private fun file(hands: List<HandRecord>, battles: List<BattleGroup> = emptyList()) =
    ExportedRecords(Records.APP, "1.2.0", "2026-11-18T00:00:00Z", hands, battles)

  @Test fun newHandsGoInByTimeAndKnownOnesAreSkipped() {
    val kept = listOf(hand("a", "2026-11-10T10:00:00Z"), hand("c", "2026-11-12T10:00:00Z"))
    // "b" lies between them; "a" is here already (the file's copy differs: it stays as it was); "d" twice in the file.
    val f = file(listOf(hand("b", "2026-11-11T10:00:00Z"), hand("a", "2026-11-10T10:00:00Z", fav = true),
      hand("d", "2026-11-13T10:00:00Z"), hand("d", "2026-11-13T10:00:00Z")))
    val m = Records.merge(kept, emptyList(), f)
    assertEquals(listOf("a", "b", "c", "d"), m.hands.map { it.id })
    assertEquals(false, m.hands.first { it.id == "a" }.fav)
    assertEquals(Imported(added = 2, had = 2, dropped = 0), m.imported)
  }

  @Test fun timesWithAndWithoutFractionsOrderByTheInstant() {
    // As strings "…05Z" comes after "…05.5Z"; as instants it is before.
    val m = Records.merge(listOf(hand("x", "2026-11-10T10:00:05.5Z")), emptyList(), file(listOf(hand("y", "2026-11-10T10:00:05Z"))))
    assertEquals(listOf("y", "x"), m.hands.map { it.id })
  }

  @Test fun nothingNewLeavesTheListsAsTheyWere() {
    val kept = listOf(hand("a", "2026-11-10T10:00:00Z"))
    val battles = listOf(battle("g", "2026-11-10T10:00:00Z"))
    val m = Records.merge(kept, battles, file(kept, battles))
    assertSame(kept, m.hands)
    assertSame(battles, m.battles)
    assertEquals(Imported(0, 1, 0), m.imported)
  }

  @Test fun battlesComeWithTheirHands() {
    val m = Records.merge(emptyList(), listOf(battle("g1", "2026-11-10T10:00:00Z")), file(
      listOf(hand("t1", "2026-11-11T10:00:00Z", kind = RecordKind.table, group = "g2")),
      listOf(battle("g2", "2026-11-11T10:00:00Z"), battle("g1", "2026-11-10T10:00:00Z")),
    ))
    assertEquals(listOf("g1", "g2"), m.battles.map { it.id })
    assertEquals(listOf("t1"), m.hands.map { it.id })
  }

  @Test fun pastTheLimitTheOldestThatAreNotFavoritesGo() {
    val kept = (0 until Records.MAX).map { i -> hand("k$i", "2026-01-01T00:%02d:%02dZ".format(i / 60, i % 60), fav = i == 0) }
    val m = Records.merge(kept, emptyList(), file((0 until 3).map { hand("n$it", "2026-11-18T00:00:0${it}Z") }))
    assertEquals(Records.MAX, m.hands.size)
    assertEquals(Imported(added = 3, had = 0, dropped = 3), m.imported)
    // The favorite stays; the next three oldest went.
    assertEquals(listOf("k0", "k4", "k5"), m.hands.take(3).map { it.id })
    assertEquals(listOf("n0", "n1", "n2"), m.hands.takeLast(3).map { it.id })
  }

  @Test fun anExportedFileReadsBack() {
    val f = file(listOf(hand("a", "2026-11-10T10:00:00Z")), listOf(battle("g", "2026-11-10T10:00:00Z")))
    val back = Records.parse(EngineJson.encodeToString(f))
    assertEquals(f, back)
  }

  @Test fun otherFilesAreRefused() {
    assertThrows(BadRecordsFile::class.java) { Records.parse("not json") }
    assertThrows(BadRecordsFile::class.java) { Records.parse("{\"hands\": []}") }
    assertThrows(BadRecordsFile::class.java) { Records.parse("""{"app": "thirteen", "version": "1", "time": "", "hands": [], "battles": []}""") }
    assertTrue(Records.parse("""{"app": "mahjong-tw", "version": "1", "time": "", "hands": [], "battles": [], "more": 1}""").hands.isEmpty())
  }
}
