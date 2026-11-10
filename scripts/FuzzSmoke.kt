import com.devtunasoft.tuneros.core.*
import java.io.ByteArrayInputStream
import kotlin.random.Random

fun main() {
    val rnd = Random(20261108)
    repeat(1000) {
        val text = buildString {
            append("#EXTM3U\n")
            repeat(rnd.nextInt(0, 12)) { n ->
                append("#EXTINF:-1 tvg-id=\"id$n\" group-title=\"News\",Channel$n\n")
                append(if (rnd.nextBoolean()) "https://example.com/$n.m3u8\n" else "file:///etc/passwd\n")
            }
        }
        val result = PlaylistParser.parse(text.reader())
        check(result.all { it.streamUrl.startsWith("https://") })
        check(result.map { it.id }.distinct().size == result.size)
    }
    repeat(250) {
        val malformed = "<!DOCTYPE tv [<!ENTITY leak SYSTEM \"file:///etc/passwd\">]><tv><channel id=\"$it\"><display-name>&leak;</display-name></channel></tv>"
        check(runCatching { GuideParser.parse(ByteArrayInputStream(malformed.toByteArray())) }.isFailure)
    }
    for (decoders in -1..7) for (connections in -1..7) {
        val cap = MultiviewPolicy.allowedTiles(MultiviewPolicy.Capacity(decoders, connections))
        check(cap in 0..4 && cap <= decoders.coerceAtLeast(0) && cap <= connections.coerceAtLeast(0))
    }
    println("SEEDED RANDOMIZED SMOKE PASS: 1000 playlists, 250 XXE attempts, 81 capacity combinations")
}
