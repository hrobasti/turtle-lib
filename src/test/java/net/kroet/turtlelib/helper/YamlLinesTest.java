package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import net.kroet.turtlelib.helper.YamlLines.Kind;
import net.kroet.turtlelib.helper.YamlLines.Line;
import org.junit.jupiter.api.Test;

class YamlLinesTest {

    private static List<Line> lines(String text) {
        return YamlLines.parse(text).lines();
    }

    private static List<String> paths(List<Line> lines) {
        return lines.stream().map(line -> line.path() == null ? "-" : line.path()).toList();
    }

    private static List<Kind> kinds(List<Line> lines) {
        return lines.stream().map(Line::kind).toList();
    }

    @Test
    void keysGetDottedPathsAndTheIndentStepIsDetected() {
        var parsed = YamlLines.parse(String.join("\n",
                "# Header",
                "first: 1",
                "",
                "section:",
                "    one: a",
                "    two:",
                "        deep: b",
                "last: end",
                ""));

        assertEquals(List.of(Kind.COMMENT, Kind.KEY, Kind.BLANK, Kind.KEY, Kind.KEY, Kind.KEY, Kind.KEY, Kind.KEY),
                kinds(parsed.lines()));
        assertEquals(List.of("-", "first", "-", "section", "section.one", "section.two", "section.two.deep", "last"),
                paths(parsed.lines()));
        assertEquals(4, parsed.indentStep());
    }

    @Test
    void blockScalarIsOneValueThatEndsAtTheNextKey() {
        List<Line> lines = lines(String.join("\n",
                "motd: |",
                "  Welcome",
                "  to the server",
                "",
                "folded: >-",
                "  One long",
                "  line",
                "other: 1",
                ""));

        assertEquals(List.of("motd", "-", "-", "-", "folded", "-", "-", "other"), paths(lines));
        assertEquals(Kind.VALUE, lines.get(1).kind());
        assertEquals(2, YamlLines.blockEnd(lines, 0));
        assertEquals(6, YamlLines.blockEnd(lines, 4));
    }

    @Test
    void blockScalarLinesThatLookLikeKeysAreText() {
        List<Line> lines = lines("motd: |\n  Rules: be nice\nother: 1\n");

        assertEquals(Kind.VALUE, lines.get(1).kind());
        assertNull(lines.get(1).path());
    }

    @Test
    void blockScalarsInListsAndWithIndicatorsAreTextToo() {
        List<Line> lines = lines(String.join("\n",
                "rules:",
                "  - |",
                "    first: text",
                "  - name: >-",
                "      # not a comment",
                "      key: text",
                "motd: |2",
                "   indented: text",
                "",
                "after: 1",
                ""));

        assertEquals(List.of(Kind.KEY, Kind.LIST_ITEM, Kind.VALUE, Kind.LIST_ITEM, Kind.VALUE, Kind.VALUE, Kind.KEY,
                Kind.VALUE, Kind.BLANK, Kind.KEY), kinds(lines));
        assertEquals(List.of("rules", "-", "-", "-", "-", "-", "motd", "-", "-", "after"), paths(lines));
    }

    @Test
    void keysInsideListEntriesHaveNoPath() {
        var parsed = YamlLines.parse(String.join("\n",
                "rules:",
                "  - name: a",
                "    max: 1",
                "  - name: b",
                "    sub:",
                "      deep: 2",
                "other: 1",
                ""));
        List<Line> lines = parsed.lines();

        assertEquals(List.of(Kind.KEY, Kind.LIST_ITEM, Kind.KEY, Kind.LIST_ITEM, Kind.KEY, Kind.KEY, Kind.KEY),
                kinds(lines));
        assertEquals(List.of("rules", "-", "-", "-", "-", "-", "other"), paths(lines));
        assertEquals(5, YamlLines.blockEnd(lines, 0));
        assertEquals(0, parsed.indentStep());
    }

    @Test
    void listAtTheKeysOwnIndentationBelongsToTheKey() {
        List<Line> lines = lines("items:\n- X\n- Y\nother: 1\n");

        assertEquals(List.of("items", "-", "-", "other"), paths(lines));
        assertEquals(2, YamlLines.blockEnd(lines, 0));
    }

    @Test
    void byteOrderMarkIsNotPartOfTheFirstKey() {
        List<Line> utf8 = lines("\uFEFFfirst: 1\nsection:\n  one: a\n");
        // The same bytes read as ISO-8859-1, as the raw-text helpers do.
        List<Line> latin1 = lines("\u00EF\u00BB\u00BFfirst: 1\nsection:\n  one: a\n");

        assertEquals(List.of("first", "section", "section.one"), paths(utf8));
        assertEquals("first: 1", utf8.get(0).content());
        assertEquals(0, utf8.get(0).start());
        assertEquals(10, utf8.get(0).end());
        assertEquals(List.of("first", "section", "section.one"), paths(latin1));
        assertEquals("first: 1", latin1.get(0).content());
    }

    @Test
    void quotedKeysAreUnquoted() {
        List<Line> lines = lines("'first': 1\n\"section\":\n  'one two': a\n  \"three\": b\n");

        assertEquals(List.of("first", "section", "section.one two", "section.three"), paths(lines));
    }

    @Test
    void everyPlainYamlKeyIsAKey() {
        List<Line> lines = lines(String.join("\n",
                "Settings:",
                "  maxBlocks: 1",
                "  größe: 2",
                "  my key: 3",
                "  a.b: 4",
                "  http://x: 5",
                "  a#b: 6",
                "  -dash: 7",
                "  \"say \\\"hi\\\"\": 8",
                "  'it''s': 9",
                "  not:a key",
                "  # key: comment",
                ""));

        assertEquals(List.of("Settings", "Settings.maxBlocks", "Settings.größe", "Settings.my key", "Settings.a.b",
                "Settings.http://x", "Settings.a#b", "Settings.-dash", "Settings.say \"hi\"", "Settings.it's", "-",
                "-"), paths(lines));
        assertEquals(Kind.VALUE, lines.get(10).kind());
    }

    @Test
    void aValueCanStartOnTheLineBelowItsKey() {
        List<Line> lines = lines(String.join("\n",
                "warn:",
                "  unsupported:",
                "    \"<yellow>Warning: first line",
                "    second: line</yellow>\"",
                "  next: 1",
                ""));

        assertEquals(List.of(Kind.KEY, Kind.KEY, Kind.VALUE, Kind.VALUE, Kind.KEY), kinds(lines));
        assertEquals(List.of("warn", "warn.unsupported", "-", "-", "warn.next"), paths(lines));
        assertEquals(3, YamlLines.blockEnd(lines, 1));
    }

    @Test
    void keyNamesAreDecodedWithTheFilesCharset() {
        String latin1 = new String("größe:\n  x: 1\n".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);

        assertEquals(List.of("größe", "größe.x"), paths(YamlLines.parse(latin1, StandardCharsets.UTF_8).lines()));
    }

    @Test
    void multiLineStringsAndFlowCollectionsAreText() {
        List<Line> lines = lines(String.join("\n",
                "quoted: \"first line",
                "  second: not a key",
                "end: still text\"",
                "single: 'it''s",
                "  a: b'",
                "flow: {",
                "  a: 1, b: \"}\",",
                "  c: [x, # ] comment",
                "    y]",
                "}",
                "plain: Welcome to",
                "  the: server",
                "next: 1",
                ""));

        assertEquals(List.of(Kind.KEY, Kind.VALUE, Kind.VALUE, Kind.KEY, Kind.VALUE, Kind.KEY, Kind.VALUE, Kind.VALUE,
                Kind.VALUE, Kind.VALUE, Kind.KEY, Kind.VALUE, Kind.KEY), kinds(lines));
        assertEquals(List.of("quoted", "-", "-", "single", "-", "flow", "-", "-", "-", "-", "plain", "-", "next"),
                paths(lines));
        assertEquals(9, YamlLines.blockEnd(lines, 5));
        assertEquals(2, YamlLines.blockEnd(lines, 0));
    }

    @Test
    void theFirstKeyOfAListItemIsASiblingOfTheItemsOtherKeys() {
        List<Line> lines = lines(String.join("\n",
                "rules:",
                "  - max-blocks: 1",
                "    name: a",
                "  - max-blocks: 2",
                ""));

        assertEquals("max-blocks", lines.get(1).key().name());
        assertEquals(4, lines.get(1).key().column());
        assertEquals(lines.get(1).key().owner(), lines.get(2).key().owner());
        assertNotEquals(lines.get(1).key().owner(), lines.get(3).key().owner());
        assertNull(lines.get(1).path());
        assertEquals(Kind.LIST_ITEM, lines.get(1).kind());
    }

    @Test
    void keyTokensAreQuotedOnlyWhenNeeded() {
        assertEquals("max_blocks", YamlLines.keyToken("max_blocks"));
        assertEquals("größe", YamlLines.keyToken("größe"));
        assertEquals("\"true\"", YamlLines.keyToken("true"));
        assertEquals("\"01\"", YamlLines.keyToken("01"));
        assertEquals("\"a: b\"", YamlLines.keyToken("a: b"));
        assertEquals("\"#tag\"", YamlLines.keyToken("#tag"));
    }

    @Test
    void linesOfAHundredThousandCharactersAreParsed() {
        String words = "word ".repeat(20_000).strip();
        String name = "k".repeat(100_000);
        List<Line> lines = lines(String.join("\n",
                "items:",
                "  - " + words,
                "  - \"" + words + "\"",
                "help:",
                "  \"" + words + "\"",
                words,
                name + ": 1",
                "\"" + name + "\": 2",
                "last: 3",
                ""));

        assertEquals(List.of(Kind.KEY, Kind.LIST_ITEM, Kind.LIST_ITEM, Kind.KEY, Kind.VALUE, Kind.OTHER, Kind.KEY,
                Kind.KEY, Kind.KEY), kinds(lines));
        assertEquals(name, lines.get(6).path());
        assertEquals("last", lines.get(8).path());
    }

    @Test
    void crlfBelongsToTheLineButNotToItsContent() {
        String text = "first: 1\r\nsecond: 2\r\n";
        List<Line> lines = lines(text);

        assertEquals("first: 1", lines.get(0).content());
        assertEquals(10, lines.get(0).end());
        assertEquals(10, lines.get(1).start());
        assertEquals("\r\n", YamlLines.lineEndingOf(text));
        assertEquals("\n", YamlLines.lineEndingOf("first: 1\n"));
    }

    @Test
    void commentsAboveAKeyAndTheEndOfItsBlock() {
        List<Line> lines = lines(String.join("\n",
                "# About a",
                "# More",
                "a:",
                "  x: 1",
                "",
                "# About b",
                "b: 2",
                ""));

        assertEquals(0, YamlLines.commentsStart(lines, 2));
        assertEquals(3, YamlLines.blockEnd(lines, 2));
        assertEquals(5, YamlLines.commentsStart(lines, 6));
        assertEquals(6, YamlLines.blockEnd(lines, 6));
    }
}
