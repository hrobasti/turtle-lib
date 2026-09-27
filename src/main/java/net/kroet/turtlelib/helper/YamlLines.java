package net.kroet.turtlelib.helper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Line-level view of a YAML file for the raw-text config helpers: each line
 * with its kind, indentation, dotted key path and character span.
 */
final class YamlLines {
    // Characters a plain key can't start with ('-', '?' and ':' can't either when a
    // blank or a flow indicator follows).
    private static final String NOT_A_KEY_START = ",[]{}#&*!|>'\"%@`";
    private static final Pattern LIST_ITEM = Pattern.compile("^([ \\t]*)-(?:[ \\t]|$)");
    private static final Pattern BLOCK_SCALAR = Pattern.compile("[|>][-+0-9]*[ \\t]*(?:#.*)?");
    private static final String UTF8_BOM_AS_LATIN1 = "\u00EF\u00BB\u00BF";
    private static final Pattern YAML_ERROR_MARK = Pattern.compile("^ in .*, line (\\d+), column (\\d+):$");
    private static final Set<String> YAML_WORDS = Set.of("true", "false", "yes", "no", "on", "off", "null", "y",
            "n", "~");

    // VALUE: a later line of a value that started on an earlier line (block
    // scalar, multi-line string or flow collection); it is text, not a key.
    enum Kind {
        KEY, LIST_ITEM, COMMENT, BLANK, VALUE, OTHER
    }

    // path is null for lines that aren't keys or sit inside a list item; end
    // includes the line break. key is set for key lines and "- key: value" items.
    record Line(Kind kind, int indent, String content, String path, int start, int end, Key key) {
    }

    /**
     * A mapping key: unquoted name, token span in the text, column, id of the block
     * it belongs to (siblings share it) and its parent's path (null inside lists).
     */
    record Key(String name, int start, int end, int column, int owner, String parent) {
    }

    record Parsed(List<Line> lines, int indentStep) {
    }

    private record Open(int indent, int id, String path) {
    }

    private YamlLines() {
    }

    static Parsed parse(String text) {
        return parse(text, null);
    }

    // For text holding a file's raw bytes as ISO-8859-1 chars: key names and paths
    // are decoded with the file's charset.
    static Parsed parse(String text, Charset charset) {
        List<Line> lines = new ArrayList<>();
        Deque<Open> open = new ArrayDeque<>();
        int indentStep = 0;
        int nextId = 0;
        Value value = null; // a value continuing on the following lines
        for (int start = 0; start < text.length();) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline + 1;
            String raw = text.substring(start, newline < 0 ? end : newline);
            if (raw.endsWith("\r")) {
                raw = raw.substring(0, raw.length() - 1);
            }
            int offset = start;
            if (start == 0) {
                int bom = bomLength(raw);
                raw = raw.substring(bom);
                offset += bom;
            }
            String trimmed = raw.strip();
            int indent = raw.length() - raw.stripLeading().length();
            if (value != null) {
                Kind kind = value.take(raw, indent, trimmed);
                if (kind != null) {
                    lines.add(new Line(kind, indent, trimmed, null, start, end, null));
                    value = value.next();
                    start = end;
                    continue;
                }
                value = null;
            }
            Kind kind;
            String path = null;
            Key key = null;
            Matcher item = LIST_ITEM.matcher(raw);
            KeyMatch keyLine;
            if (trimmed.isEmpty()) {
                kind = Kind.BLANK;
            } else if (trimmed.startsWith("#")) {
                kind = Kind.COMMENT;
            } else if (item.lookingAt()) {
                kind = Kind.LIST_ITEM;
                popTo(open, indent);
                int itemId = nextId++;
                open.push(new Open(indent, itemId, null));
                String content = raw.substring(item.end());
                KeyMatch itemKey = matchKey(content);
                if (itemKey != null) {
                    int column = item.end() + itemKey.tokenStart();
                    key = new Key(name(itemKey.token(content), charset), offset + item.end() + itemKey.tokenStart(),
                            offset + item.end() + itemKey.tokenEnd(), column, itemId, null);
                    open.push(new Open(column, nextId++, null));
                    value = Value.open(content.substring(itemKey.end(content)), column);
                } else if (!LIST_ITEM.matcher(content.strip()).lookingAt()) {
                    value = Value.open(content, indent);
                }
            } else if ((keyLine = matchKey(raw)) != null) {
                kind = Kind.KEY;
                popTo(open, indent);
                String name = name(keyLine.token(raw), charset);
                Open parent = open.peek();
                String parentPath = parent == null ? "" : parent.path();
                if (parentPath != null) {
                    path = parentPath.isEmpty() ? name : parentPath + "." + name;
                    if (parent != null && indentStep == 0) {
                        indentStep = indent - parent.indent();
                    }
                }
                int id = nextId++;
                key = new Key(name, offset + keyLine.tokenStart(), offset + keyLine.tokenEnd(), indent,
                        parent == null ? -1 : parent.id(), parentPath);
                open.push(new Open(indent, id, path));
                value = Value.open(raw.substring(keyLine.end(raw)), indent);
            } else {
                // Deeper than a key with nothing after its colon: that key's value,
                // written on the next line (e.g. a long quoted text).
                popTo(open, indent);
                Open owner = open.peek();
                kind = owner == null ? Kind.OTHER : Kind.VALUE;
                value = owner == null ? null : Value.open(trimmed, owner.indent());
            }
            lines.add(new Line(kind, indent, trimmed, path, start, end, key));
            start = end;
        }
        return new Parsed(lines, indentStep);
    }

    // What follows a key's colon or a list dash when it goes on past its line.
    private abstract static class Value {
        final int ownerIndent;

        Value(int ownerIndent) {
            this.ownerIndent = ownerIndent;
        }

        // The kind of the line if it still belongs to the value, else null.
        abstract Kind take(String raw, int indent, String trimmed);

        Value next() {
            return this;
        }

        static Value open(String rest, int ownerIndent) {
            String value = rest.strip();
            value = value.substring(propertiesEnd(value));
            if (value.isEmpty() || value.startsWith("#")) {
                return null; // a nested block (section or list) follows, if anything
            }
            if (BLOCK_SCALAR.matcher(value).matches()) {
                return new Indented(ownerIndent, true);
            }
            char first = value.charAt(0);
            if (first == '"' || first == '\'') {
                Quoted quoted = new Quoted(ownerIndent, first);
                return quoted.scan(value, 1) ? new Indented(ownerIndent, false) : quoted;
            }
            if (first == '[' || first == '{') {
                Flow flow = new Flow(ownerIndent);
                return flow.scan(value) >= 0 ? new Indented(ownerIndent, false) : flow;
            }
            return new Indented(ownerIndent, false);
        }
    }

    // Block scalar text, or the deeper lines after a single-line value
    // (continuation lines of a plain multi-line scalar).
    private static final class Indented extends Value {
        private final boolean blockScalar;

        Indented(int ownerIndent, boolean blockScalar) {
            super(ownerIndent);
            this.blockScalar = blockScalar;
        }

        @Override
        Kind take(String raw, int indent, String trimmed) {
            if (trimmed.isEmpty()) {
                return Kind.BLANK;
            }
            if (indent <= ownerIndent) {
                return null;
            }
            return !blockScalar && trimmed.startsWith("#") ? Kind.COMMENT : Kind.VALUE;
        }
    }

    private static final class Quoted extends Value {
        private final char quote;
        private boolean closed;

        Quoted(int ownerIndent, char quote) {
            super(ownerIndent);
            this.quote = quote;
        }

        // True if the closing quote is on this line (from index from on).
        boolean scan(String text, int from) {
            for (int i = from; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quote == '"' && c == '\\') {
                    i++;
                } else if (c == quote) {
                    if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        closed = true;
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        Kind take(String raw, int indent, String trimmed) {
            scan(raw, 0);
            return trimmed.isEmpty() ? Kind.BLANK : Kind.VALUE;
        }

        @Override
        Value next() {
            return closed ? new Indented(ownerIndent, false) : this;
        }
    }

    private static final class Flow extends Value {
        private int depth;
        private char quote;
        private char previous = '[';

        Flow(int ownerIndent) {
            super(ownerIndent);
        }

        // Index right after the bracket that closes the collection, or -1 if it goes
        // on past this text.
        int scan(String text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quote != 0) {
                    if (quote == '"' && c == '\\') {
                        i++;
                    } else if (c == quote) {
                        if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                            i++;
                        } else {
                            quote = 0;
                        }
                    }
                    continue;
                }
                if (c == '#' && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) {
                    break; // comment up to the end of the line
                }
                if ((c == '"' || c == '\'') && "[{,:".indexOf(previous) >= 0) {
                    quote = c;
                } else if (c == '[' || c == '{') {
                    depth++;
                } else if ((c == ']' || c == '}') && --depth == 0) {
                    return i + 1;
                }
                if (!Character.isWhitespace(c)) {
                    previous = c;
                }
            }
            return -1;
        }

        @Override
        Kind take(String raw, int indent, String trimmed) {
            if (depth > 0) {
                scan(raw);
            }
            return trimmed.isEmpty() ? Kind.BLANK : Kind.VALUE;
        }

        @Override
        Value next() {
            return depth > 0 ? this : new Indented(ownerIndent, false);
        }
    }

    // Index right after the flow collection ([...] or {...}) the value starts with,
    // or -1 if it isn't closed on this line.
    static int flowEnd(String value) {
        return new Flow(0).scan(value);
    }

    // First line of the comment run directly above a key (the key itself if none).
    static int commentsStart(List<Line> lines, int keyIndex) {
        int i = keyIndex;
        while (i > 0 && lines.get(i - 1).kind() == Kind.COMMENT) {
            i--;
        }
        return i;
    }

    // Last line of a key's block: deeper-indented lines, the rest of its value and
    // list items at the key's own indentation, without trailing blank or comment
    // lines.
    static int blockEnd(List<Line> lines, int keyIndex) {
        return blockEnd(lines, keyIndex, lines.get(keyIndex).indent());
    }

    // Same for a key at the given column, e.g. the key of a "- key: value" item.
    static int blockEnd(List<Line> lines, int keyIndex, int keyIndent) {
        int end = keyIndex;
        for (int i = keyIndex + 1; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.kind() == Kind.BLANK || line.kind() == Kind.COMMENT) {
                continue;
            }
            boolean deeper = line.indent() > keyIndent;
            boolean sameLevelItem = line.kind() == Kind.LIST_ITEM && line.indent() == keyIndent;
            if (!deeper && !sameLevelItem && line.kind() != Kind.VALUE) {
                break;
            }
            end = i;
        }
        return end;
    }

    static int indexOfPath(List<Line> lines, String path) {
        for (int i = 0; i < lines.size(); i++) {
            if (path.equals(lines.get(i).path())) {
                return i;
            }
        }
        return -1;
    }

    // Index right after the key's colon in a key line (no line break), or -1.
    static int afterColon(String rawLine) {
        KeyMatch key = matchKey(rawLine);
        return key == null ? -1 : key.colon() + 1;
    }

    // A key token at the start of a line (after its indentation): tokenEnd is where
    // the name ends, colon the index of the ':' that follows it.
    record KeyMatch(int tokenStart, int tokenEnd, int colon) {
        String token(String line) {
            return line.substring(tokenStart, tokenEnd);
        }

        // After the colon and the blank behind it, where the value starts.
        int end(String line) {
            return colon + 1 < line.length() ? colon + 2 : colon + 1;
        }
    }

    // "key: value", "'key': value" or "\"key\": value" with any YAML plain key;
    // scanned char by char, so any line length works.
    static KeyMatch matchKey(String line) {
        int length = line.length();
        int start = 0;
        while (start < length && isBlank(line.charAt(start))) {
            start++;
        }
        if (start == length) {
            return null;
        }
        char first = line.charAt(start);
        if (first == '\'' || first == '"') {
            int close = closingQuote(line, start);
            return close < 0 ? null : colonAt(line, start, close);
        }
        boolean indicator = first == '-' || first == '?' || first == ':';
        if (isSpace(first) || NOT_A_KEY_START.indexOf(first) >= 0 || (indicator && (start + 1 == length
                || isSpace(line.charAt(start + 1)) || ",[]{}".indexOf(line.charAt(start + 1)) >= 0))) {
            return null;
        }
        // The name ends at the first ':' that a blank or the line end follows.
        for (int i = start + 1;;) {
            KeyMatch match = colonAt(line, start, i);
            if (match != null) {
                return match;
            }
            if (i == length) {
                return null;
            }
            char c = line.charAt(i);
            if (isBlank(c)) {
                int next = i;
                while (next < length && isBlank(line.charAt(next))) {
                    next++;
                }
                if (next == length || isSpace(line.charAt(next)) || line.charAt(next) == '#') {
                    return null; // trailing blanks or a comment, no colon
                }
                i = next;
            } else if (isSpace(c) || (c == ':' && (i + 1 == length || isSpace(line.charAt(i + 1))))) {
                return null;
            } else {
                i++;
            }
        }
    }

    // Index right after the quote that closes the one at start, or -1.
    private static int closingQuote(String line, int start) {
        char quote = line.charAt(start);
        for (int i = start + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote == '"' && c == '\\') {
                if (++i == line.length()) {
                    return -1;
                }
            } else if (c == quote) {
                if (quote == '\'' && i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    // The key match if blanks, a ':' and a blank or the line end follow tokenEnd.
    private static KeyMatch colonAt(String line, int tokenStart, int tokenEnd) {
        int i = tokenEnd;
        while (i < line.length() && isBlank(line.charAt(i))) {
            i++;
        }
        boolean colon = i < line.length() && line.charAt(i) == ':'
                && (i + 1 == line.length() || isBlank(line.charAt(i + 1)));
        return colon ? new KeyMatch(tokenStart, tokenEnd, i) : null;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    // Whitespace as YAML and the old regex \s saw it.
    private static boolean isSpace(char c) {
        return isBlank(c) || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
    }

    // Length of the anchors and tags (&name, !tag) a value starts with.
    static int propertiesEnd(String value) {
        int i = 0;
        while (i < value.length() && (value.charAt(i) == '&' || value.charAt(i) == '!')) {
            int end = i;
            while (end < value.length() && !isSpace(value.charAt(end))) {
                end++;
            }
            if (end < value.length() && !isBlank(value.charAt(end))) {
                break;
            }
            while (end < value.length() && isBlank(value.charAt(end))) {
                end++;
            }
            i = end;
        }
        return i;
    }

    // The value text of a key line without anchors, tags and comment ("" if none).
    static String valueOf(String keyLineContent) {
        int colon = afterColon(keyLineContent);
        String value = colon < 0 ? "" : keyLineContent.substring(colon).strip();
        value = value.substring(propertiesEnd(value));
        return value.startsWith("#") ? "" : value;
    }

    // True if the name can be written as a plain key that reads back as the same
    // text (not a number, boolean or null).
    static boolean isPlainKey(String name) {
        KeyMatch key = matchKey(name + ":");
        return key != null && key.tokenStart() == 0 && key.tokenEnd() == name.length() && !name.startsWith("'")
                && !name.startsWith("\"") && !name.matches("[-+.]?[0-9].*")
                && !YAML_WORDS.contains(name.toLowerCase(Locale.ROOT));
    }

    // The key as a YAML token: plain if possible, else double-quoted.
    static String keyToken(String name) {
        return isPlainKey(name) ? name : quotedKey(name, null);
    }

    // Double-quoted key; code points the encoder (if any) can't store are escaped.
    static String quotedKey(String name, CharsetEncoder encoder) {
        StringBuilder quoted = new StringBuilder("\"");
        name.codePoints().forEach(codePoint -> {
            if (codePoint == '"' || codePoint == '\\') {
                quoted.append('\\').appendCodePoint(codePoint);
            } else if (codePoint < 0x20 || codePoint == 0x7F
                    || (encoder != null && !encoder.canEncode(Character.toString(codePoint)))) {
                quoted.append(codePoint > 0xFFFF
                        ? String.format("\\U%08X", codePoint)
                        : String.format("\\u%04X", codePoint));
            } else {
                quoted.appendCodePoint(codePoint);
            }
        });
        return quoted.append('"').toString();
    }

    static String parentOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(0, dot);
    }

    static String lineEndingOf(String text) {
        int newline = text.indexOf('\n');
        return newline > 0 && text.charAt(newline - 1) == '\r' ? "\r\n" : "\n";
    }

    static String stripBom(String line) {
        if (line.startsWith(UTF8_BOM_AS_LATIN1)) {
            return line.substring(UTF8_BOM_AS_LATIN1.length());
        }
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }

    // Length of a UTF-8 byte order mark at the start of the text (read as UTF-8 or
    // ISO-8859-1); new text must go after it.
    static int bomLength(String text) {
        if (text.startsWith(UTF8_BOM_AS_LATIN1)) {
            return UTF8_BOM_AS_LATIN1.length();
        }
        return !text.isEmpty() && text.codePointAt(0) == 0xFEFF ? 1 : 0;
    }

    static boolean isValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** Returns {@code null} if the text isn't valid YAML. */
    static YamlConfiguration parseYaml(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(stripBom(text));
            return yaml;
        } catch (InvalidConfigurationException e) {
            return null;
        }
    }

    /** Why the text isn't valid YAML (see {@link #describeYamlError}), or null. */
    static String yamlError(String text) {
        try {
            new YamlConfiguration().loadFromString(stripBom(text));
            return null;
        } catch (InvalidConfigurationException e) {
            return describeYamlError(e);
        }
    }

    /**
     * Short form of a YAML parse error for console warnings, e.g. "line 5, column
     * 28: expected &lt;block end&gt;, but found '&lt;scalar&gt;'".
     */
    static String describeYamlError(InvalidConfigurationException e) {
        Throwable source = e.getCause() != null ? e.getCause() : e;
        String[] lines = String.valueOf(source.getMessage()).strip().split("\\R");
        // SnakeYAML puts the problem on the line right above its position mark.
        for (int i = lines.length - 1; i > 0; i--) {
            Matcher mark = YAML_ERROR_MARK.matcher(lines[i]);
            if (mark.matches()) {
                String problem = lines[i - 1].strip();
                String position = "line " + mark.group(1) + ", column " + mark.group(2);
                return problem.isEmpty() || problem.equals("^") ? position : position + ": " + problem;
            }
        }
        return lines[0].strip();
    }

    private static void popTo(Deque<Open> open, int indent) {
        while (!open.isEmpty() && open.peek().indent() >= indent) {
            open.pop();
        }
    }

    private static String name(String token, Charset charset) {
        return unquote(charset == null ? token : new String(token.getBytes(StandardCharsets.ISO_8859_1), charset));
    }

    private static String unquote(String token) {
        if (token.length() < 2) {
            return token;
        }
        String inner = token.substring(1, token.length() - 1);
        if (token.charAt(0) == '\'') {
            return inner.replace("''", "'");
        }
        return token.charAt(0) == '"' ? unescape(inner) : token;
    }

    private static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char escaped = text.charAt(++i);
            int hexDigits = escaped == 'x' ? 2 : escaped == 'u' ? 4 : escaped == 'U' ? 8 : 0;
            if (hexDigits > 0 && i + hexDigits < text.length()) {
                String hex = text.substring(i + 1, i + 1 + hexDigits);
                if (hex.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
                    out.appendCodePoint(Integer.parseInt(hex, 16));
                    i += hexDigits;
                    continue;
                }
            }
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case '0' -> out.append('\0');
                case '"', '\\', '/', ' ' -> out.append(escaped);
                default -> out.append('\\').append(escaped);
            }
        }
        return out.toString();
    }
}
