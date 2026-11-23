import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** QA-27 JVM mirror of the Android-free token assertions. No Android imports. */
public final class Qa27Mirror {
    private static final String[][] TOKENS = {
        {"TIMELINE_BACKGROUND", "00000000"},
        {"TIMELINE_BORDER", "FF344963"},
        {"TIMELINE_TEXT", "FFFFFFFF"},
        {"TIMELINE_NOW_MARKER", "FFFF7440"},
        {"TIMELINE_CELL_NORMAL", "FF1C3048"},
        {"TIMELINE_CELL_EMPTY", "FF142030"},
        {"TIMELINE_CELL_FOCUSED", "FF1F67BE"},
    };
    private static final String[] FILLS = {
        "TIMELINE_CELL_NORMAL", "TIMELINE_CELL_EMPTY", "TIMELINE_CELL_FOCUSED"
    };

    private static int ch(String v, int shift) { return Integer.parseInt(v.substring(shift, shift + 2), 16); }
    private static double lin(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
    private static double lum(String v) {
        return 0.2126 * lin(ch(v, 2)) + 0.7152 * lin(ch(v, 4)) + 0.0722 * lin(ch(v, 6));
    }
    private static int contrast(String a, String b) {
        double hi = Math.max(lum(a), lum(b)), lo = Math.min(lum(a), lum(b));
        return (int) Math.floor(1000 * (hi + 0.05) / (lo + 0.05));
    }
    private static int dist(String a, String b) {
        int r = ch(a, 2) - ch(b, 2), g = ch(a, 4) - ch(b, 4), bl = ch(a, 6) - ch(b, 6);
        return (int) Math.round(Math.sqrt(r * r + g * g + bl * bl));
    }

    public static void main(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] t : TOKENS) m.put(t[0], t[1]);
        int pass = 0;
        String text = m.get("TIMELINE_TEXT");
        boolean complete = m.size() == 7 && TOKENS.length == 7;
        boolean alpha = "00000000".equals(m.get("TIMELINE_BACKGROUND"));
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!e.getKey().equals("TIMELINE_BACKGROUND") && !e.getValue().startsWith("FF")) alpha = false;
        }
        boolean contrast = true;
        for (String role : FILLS) if (contrast(text, m.get(role)) < 4500) contrast = false;
        boolean distinct = true;
        for (int i = 0; i < FILLS.length; i++) {
            for (int j = i + 1; j < FILLS.length; j++) {
                if (dist(m.get(FILLS[i]), m.get(FILLS[j])) < 12) distinct = false;
            }
        }
        boolean marker = true;
        for (String role : FILLS) if (dist(m.get("TIMELINE_NOW_MARKER"), m.get(role)) < 24) marker = false;
        if (dist(m.get("TIMELINE_NOW_MARKER"), text) < 24) marker = false;
        boolean border = dist(m.get("TIMELINE_BORDER"), "00000000") >= 16;
        for (String role : FILLS) if (dist(m.get("TIMELINE_BORDER"), m.get(role)) < 16) border = false;
        boolean hex = true;
        for (String v : m.values()) hex = hex && v.matches("[0-9A-F]{8}");

        String[] ids = {"MIRROR_COMPLETE", "MIRROR_ARGB", "MIRROR_ALPHA", "MIRROR_CONTRAST",
            "MIRROR_DISTINCTION", "MIRROR_MARKER", "MIRROR_BORDER"};
        boolean[] results = {complete, hex, alpha, contrast, distinct, marker, border};
        for (int i = 0; i < ids.length; i++) {
            System.out.println("CHECK\t" + ids[i] + "\t" + (results[i] ? "PASS" : "FAIL"));
            if (results[i]) pass++;
        }
        System.out.println("SUMMARY\tPASS=" + pass + "\tFAILED=" + (ids.length - pass)
            + String.format(Locale.ROOT, "\tmin-contrast-milli=%d", contrast(text, m.get("TIMELINE_CELL_FOCUSED"))));
        System.exit(pass == ids.length ? 0 : 1);
    }
}
