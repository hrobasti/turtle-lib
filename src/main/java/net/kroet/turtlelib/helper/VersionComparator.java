package net.kroet.turtlelib.helper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Orders versions like "1.4.0", "1.4.0-beta2" or "26.3.build.30". Format and
 * ordering rules: https://github.com/hrobasti/turtle-lib/wiki/VersionComparator
 */
public final class VersionComparator {
    // Prerelease labels that also count as one when glued to the number (1.0rc1).
    private static final Set<String> GLUED_LABELS = Set.of("alpha", "beta", "pre", "rc");

    private VersionComparator() {
    }

    public static int compare(String a, String b) {
        Version va = Version.parse(a);
        Version vb = Version.parse(b);
        return va.compareTo(vb);
    }

    public static boolean isGreater(String a, String b) {
        return compare(a, b) > 0;
    }

    // Whether the version carries a prerelease label, like 1.4.0-beta2 or 1.4.0rc1.
    static boolean isPrerelease(String version) {
        return Version.parse(version).pre != null;
    }

    // Numbers are kept as digit strings without leading zeros, so any length
    // compares
    // without overflow.
    static int compareNumbers(String a, String b) {
        if (a.length() != b.length())
            return Integer.compare(a.length(), b.length());
        return a.compareTo(b);
    }

    static String normalizeNumber(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0')
            i++;
        return digits.isEmpty() ? "0" : digits.substring(i);
    }

    static boolean isNumber(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    static final class Version implements Comparable<Version> {
        final List<String> nums;
        final String letterSuffix; // e.g., "a", "b" from main segment like 1.0a
        final List<String> buildNums; // numbers of "build.30" or "+build.30" -> [30]
        final Pre pre; // prerelease like alpha, beta, rc with optional number

        Version(List<String> nums, String letterSuffix, List<String> buildNums, Pre pre) {
            this.nums = nums;
            this.letterSuffix = letterSuffix;
            this.buildNums = buildNums;
            this.pre = pre;
        }

        static Version parse(String s) {
            String lower = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
            if (lower.length() > 1 && lower.charAt(0) == 'v' && Character.isDigit(lower.charAt(1)))
                lower = lower.substring(1);

            String metadata = null;
            int plus = lower.indexOf('+');
            if (plus >= 0) {
                metadata = lower.substring(plus + 1);
                lower = lower.substring(0, plus);
            }
            String main = lower;
            String pre = null;
            int dash = lower.indexOf('-');
            if (dash >= 0) {
                main = lower.substring(0, dash);
                pre = lower.substring(dash + 1);
            }

            // A segment that doesn't start with a digit starts build metadata, instead of
            // being coerced into a fake "0" segment.
            String[] segs = main.split("\\.");
            List<String> nums = new ArrayList<>();
            List<String> buildNums = new ArrayList<>();
            String letterSuffix = null;
            String glued = null;
            for (int i = 0; i < segs.length; i++) {
                String seg = segs[i];
                if (seg.isEmpty()) {
                    nums.add("0");
                    continue;
                }
                if (!Character.isDigit(seg.charAt(0))) {
                    collectNumbers(segs, i, buildNums);
                    break;
                }
                int j = 0;
                while (j < seg.length() && Character.isDigit(seg.charAt(j)))
                    j++;
                nums.add(normalizeNumber(seg.substring(0, j)));
                String tail = seg.substring(j);
                if (GLUED_LABELS.contains(leadingLetters(tail))) {
                    List<String> rest = new ArrayList<>(List.of(tail));
                    rest.addAll(Arrays.asList(segs).subList(i + 1, segs.length));
                    glued = String.join(".", rest);
                    break;
                }
                if (i == segs.length - 1 && !tail.isEmpty() && allLetters(tail)) {
                    letterSuffix = tail;
                }
            }
            if (nums.isEmpty())
                nums.add("0");
            if (glued != null)
                pre = pre == null ? glued : glued + "." + pre;
            if (metadata != null)
                collectNumbers(metadata.split("[._-]"), 0, buildNums);

            return new Version(nums, letterSuffix, buildNums, Pre.parse(pre));
        }

        /**
         * Collects the purely numeric segments of a build-metadata tail (e.g.
         * {@code ["build", "30"]} -> {@code [30]}); marker segments like
         * {@code "build"} only mark the tail's start.
         */
        static void collectNumbers(String[] segs, int from, List<String> out) {
            for (int i = from; i < segs.length; i++) {
                if (isNumber(segs[i]))
                    out.add(normalizeNumber(segs[i]));
            }
        }

        static String leadingLetters(String s) {
            int i = 0;
            while (i < s.length() && Character.isLetter(s.charAt(i)))
                i++;
            return s.substring(0, i);
        }

        static boolean allLetters(String s) {
            for (int i = 0; i < s.length(); i++)
                if (!Character.isLetter(s.charAt(i)))
                    return false;
            return true;
        }

        @Override
        public int compareTo(Version o) {
            // 1) compare numeric parts
            int c = compareNumberLists(this.nums, o.nums);
            if (c != 0)
                return c;
            // 2) compare letter suffix on main version (e.g., 1.0a > 1.0)
            boolean aHas = this.letterSuffix != null && !this.letterSuffix.isEmpty();
            boolean bHas = o.letterSuffix != null && !o.letterSuffix.isEmpty();
            if (aHas && !bHas)
                return 1;
            if (!aHas && bHas)
                return -1;
            if (aHas && bHas) {
                c = this.letterSuffix.compareTo(o.letterSuffix);
                if (c != 0)
                    return c;
            }
            // 3) compare prerelease (release > prerelease)
            if (this.pre == null && o.pre != null)
                return 1;
            if (this.pre != null && o.pre == null)
                return -1;
            if (this.pre != null) {
                c = this.pre.compareTo(o.pre);
                if (c != 0)
                    return c;
            }
            // 4) numeric build metadata only breaks ties, so a build-only bump
            // ("build.31", "+31") still counts as newer.
            return compareNumberLists(this.buildNums, o.buildNums);
        }

        private static int compareNumberLists(List<String> a, List<String> b) {
            int max = Math.max(a.size(), b.size());
            for (int i = 0; i < max; i++) {
                int c = compareNumbers(i < a.size() ? a.get(i) : "0", i < b.size() ? b.get(i) : "0");
                if (c != 0)
                    return c;
            }
            return 0;
        }

        static final class Pre implements Comparable<Pre> {
            final String label; // alpha, beta, rc, others; null if it starts with a number
            final List<String> parts; // what follows the label, e.g. beta.2 -> [2]

            Pre(String label, List<String> parts) {
                this.label = label;
                this.parts = parts;
            }

            // Splits at ".", "-", "_" and wherever letters and digits meet, so beta2,
            // beta.2, beta-2 and beta_2 read alike.
            static Pre parse(String s) {
                if (s == null || s.isEmpty())
                    return null;
                List<String> tokens = new ArrayList<>();
                StringBuilder token = new StringBuilder();
                for (int i = 0; i < s.length(); i++) {
                    char ch = s.charAt(i);
                    boolean letter = Character.isLetter(ch);
                    if (!letter && !Character.isDigit(ch)) {
                        flush(token, tokens);
                        continue;
                    }
                    if (!token.isEmpty() && Character.isLetter(token.charAt(0)) != letter)
                        flush(token, tokens);
                    token.append(ch);
                }
                flush(token, tokens);
                if (tokens.isEmpty())
                    return new Pre(s, List.of());
                if (isNumber(tokens.get(0)))
                    return new Pre(null, tokens);
                return new Pre(tokens.get(0), tokens.subList(1, tokens.size()));
            }

            private static void flush(StringBuilder token, List<String> tokens) {
                if (!token.isEmpty())
                    tokens.add(token.toString());
                token.setLength(0);
            }

            // Minecraft's order: alpha < beta < pre < rc.
            static int rank(String name) {
                if (name == null)
                    return 4;
                switch (name) {
                    case "alpha" :
                        return 0;
                    case "beta" :
                        return 1;
                    case "pre" :
                        return 2;
                    case "rc" :
                        return 3;
                    default :
                        // Unrecognized prerelease labels rank after rc, still below a full
                        // release, so they never outrank a real release.
                        return 4;
                }
            }

            @Override
            public int compareTo(Pre o) {
                int ra = rank(this.label);
                int rb = rank(o.label);
                if (ra != rb)
                    return Integer.compare(ra, rb);
                if (!Objects.equals(this.label, o.label)) {
                    // same rank, different labels: a bare number first, then by name
                    if (this.label == null)
                        return -1;
                    if (o.label == null)
                        return 1;
                    return this.label.compareTo(o.label);
                }
                int max = Math.max(this.parts.size(), o.parts.size());
                for (int i = 0; i < max; i++) {
                    int c = compareParts(i < this.parts.size() ? this.parts.get(i) : "0",
                            i < o.parts.size() ? o.parts.get(i) : "0");
                    if (c != 0)
                        return c;
                }
                return 0;
            }

            // Numbers compare numerically and before words, words by name.
            private static int compareParts(String a, String b) {
                boolean aNum = isNumber(a);
                boolean bNum = isNumber(b);
                if (aNum && bNum)
                    return compareNumbers(normalizeNumber(a), normalizeNumber(b));
                if (aNum != bNum)
                    return aNum ? -1 : 1;
                return a.compareTo(b);
            }
        }
    }
}
