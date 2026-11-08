import com.devtunasoft.tuneros.core.*
import java.io.ByteArrayInputStream

fun main() {
    val m3u = "#EXTM3U\n#EXTINF:-1 tvg-id=\"news.us\" group-title=\"News\",News HD\nhttps://example.com/live.m3u8\n"
    val list = PlaylistParser.parse(m3u.reader())
    check(list.size == 1 && list[0].guideId == "news.us" && list[0].group == "News")
    val xml = "<tv><channel id=\"news.us\"><display-name>News</display-name></channel><programme start=\"20261108100000 -0600\" stop=\"20261108110000 -0600\" channel=\"news.us\"><title>Morning News</title></programme></tv>"
    val guide = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()))
    check(guide.programs.size == 1 && guide.programs[0].title == "Morning News")
    check(MultiviewPolicy.allowedTiles(MultiviewPolicy.Capacity(4, 2)) == 2)
    println("CORE SMOKE PASS: M3U, XMLTV, multiview admission")
}
