package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class StartupBannerTest {

    // ASCII art keeps every character, backslashes and tag-like text included.
    @Test
    void linesPrintExactlyAsWritten() {
        for (String line : List.of("a\\\\b", "x\\<y", " |_|\\_\\ ", "<red>not red</red>", "\\", "C:\\path\\")) {
            assertEquals(line, plain(StartupBanner.render("<gold>", line)), line);
        }
    }

    @Test
    void nullLinesAreSkipped() {
        StartupBanner banner = StartupBanner.linesBuilder()
                .lines(Arrays.asList("one", null, "two"))
                .addLine(null)
                .build();

        assertEquals(List.of("one", "two"), banner.lines());
    }

    @Test
    void onlyExistingOpeningTagsAreAColor() {
        for (String tag : List.of("<gold>", "<bold><aqua>", "<gradient:#ff7e5f:#feb47b>", "<#FF8800>", "<!italic><gold>",
                "<GOLD>", "<color:gold>")) {
            assertTrue(StartupBanner.isColorTag(tag), tag);
        }
        for (String tag : List.of("gold", "<glod>", "<gold> x", "x<gold>", "</gold>", "<#FF88>", "<gold", "")) {
            assertFalse(StartupBanner.isColorTag(tag), tag);
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
