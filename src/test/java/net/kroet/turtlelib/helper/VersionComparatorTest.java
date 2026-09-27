package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins down the ordering rules documented on {@link VersionComparator}, so
 * future changes have to deliberately touch these expectations instead of
 * silently drifting.
 */
class VersionComparatorTest {

    @Test
    void numericSegmentsCompareComponentWise() {
        assertTrue(VersionComparator.isGreater("1.2.0", "1.1.9"));
        assertTrue(VersionComparator.isGreater("1.10", "1.9"));
        assertEquals(0, VersionComparator.compare("1.2", "1.2.0"));
    }

    @Test
    void letterSuffixOnLastSegmentRanksAbovePlainNumber() {
        assertTrue(VersionComparator.isGreater("1.0a", "1.0"));
        assertTrue(VersionComparator.isGreater("1.0b", "1.0a"));
    }

    @Test
    void fullReleaseOutranksAnyPrereleaseOfSameVersion() {
        assertTrue(VersionComparator.isGreater("1.0", "1.0-rc1"));
        assertTrue(VersionComparator.isGreater("1.0", "1.0-alpha"));
        assertTrue(VersionComparator.isGreater("1.0", "1.0-snapshot"));
    }

    @Test
    void knownPrereleaseLabelsRankAlphaBetaRc() {
        assertTrue(VersionComparator.isGreater("1.0-beta", "1.0-alpha"));
        assertTrue(VersionComparator.isGreater("1.0-rc", "1.0-beta"));
        assertTrue(VersionComparator.isGreater("1.0-beta2", "1.0-beta1"));
    }

    @Test
    void documentedReleaseFormatOrdersPrereleaseTrain() {
        assertTrue(VersionComparator.isGreater("1.4.0-beta2", "1.4.0-beta1"));
        assertTrue(VersionComparator.isGreater("1.4.0-beta10", "1.4.0-beta9"));
        assertTrue(VersionComparator.isGreater("1.4.0-rc1", "1.4.0-beta10"));
        assertTrue(VersionComparator.isGreater("1.4.0", "1.4.0-rc2"));
        assertTrue(VersionComparator.isGreater("1.4.0-beta1", "1.3.1"));
    }

    @Test
    void unknownPrereleaseLabelsRankAfterRcButBelowRelease() {
        // Deliberate design: unknown prerelease labels rank after rc, so an
        // unrecognized channel is never mistaken for a full release, but also
        // never ranks below alpha.
        assertTrue(VersionComparator.isGreater("1.0-snapshot", "1.0-rc1"));
        assertTrue(VersionComparator.isGreater("1.0-snapshot", "1.0-alpha"));
        assertTrue(VersionComparator.isGreater("1.0", "1.0-snapshot"));
    }

    @Test
    void unknownPrereleaseLabelsOfEqualRankCompareLexicographically() {
        assertTrue(VersionComparator.isGreater("1.0-nightly", "1.0-dev"));
    }

    @Test
    void nonNumericMainSegmentIsBuildMetadataNotAFakeZero() {
        // "26.3.build.30-alpha" must not parse to the same numeric segments as the
        // unrelated four-component version "26.3.0.30-alpha".
        assertFalse(VersionComparator.compare("26.3.build.30-alpha", "26.3.0.30-alpha") == 0);
        assertTrue(VersionComparator.isGreater("26.3.0.30-alpha", "26.3.build.30-alpha"));
    }

    @Test
    void buildMetadataNumberIsOnlyALastResortTieBreaker() {
        // Deliberate: differs from strict SemVer 2.0, which ignores build metadata for
        // precedence entirely - here a build-only bump is still recognized as newer.
        assertTrue(VersionComparator.isGreater("1.2.build.31", "1.2.build.30"));
        // But it only decides ties - a real numeric difference still wins first.
        assertTrue(VersionComparator.isGreater("1.3.build.1", "1.2.build.99"));
    }

    @Test
    void vPrefixIsIgnored() {
        assertEquals(0, VersionComparator.compare("v2.0.0", "2.0.0"));
        assertTrue(VersionComparator.isGreater("v2.0.0", "1.0.0"));
        assertTrue(VersionComparator.isGreater("V1.4.0-beta1", "v1.3.0"));
    }

    @Test
    void prereleaseNumberMayFollowASeparator() {
        assertEquals(0, VersionComparator.compare("1.0.0-beta.2", "1.0.0-beta2"));
        assertEquals(0, VersionComparator.compare("1.0.0-beta-2", "1.0.0-beta2"));
        assertEquals(0, VersionComparator.compare("1.0.0-beta_2", "1.0.0-beta2"));
        assertTrue(VersionComparator.isGreater("1.0.0-beta.2", "1.0.0-beta.1"));
        assertTrue(VersionComparator.isGreater("1.0.0-beta-2", "1.0.0-beta"));
        assertTrue(VersionComparator.isGreater("1.0.0-rc.1", "1.0.0-beta.10"));
    }

    @Test
    void numericPrereleaseLabelsCompareNumerically() {
        assertTrue(VersionComparator.isGreater("1.0.0-10", "1.0.0-2"));
        assertTrue(VersionComparator.isGreater("1.0.0", "1.0.0-10"));
        assertTrue(VersionComparator.isGreater("2024-01-16", "2024-01-15"));
    }

    // Like SemVer, "+" starts build metadata; its numbers only break a tie.
    @Test
    void plusStartsBuildMetadata() {
        assertTrue(VersionComparator.isGreater("1.0.0.4", "1.0.0+build.5"));
        assertTrue(VersionComparator.isGreater("1.0.0+5", "1.0.0"));
        assertTrue(VersionComparator.isGreater("1.0.1+1", "1.0.0+99"));
        assertTrue(VersionComparator.isGreater("1.0.0-rc1", "1.0.0-beta2+7"));
        assertTrue(VersionComparator.isGreater("1.0.0+build.31", "1.0.0+build.30"));
    }

    @Test
    void largeNumbersDoNotOverflow() {
        assertTrue(VersionComparator.isGreater("1.0.3000000000", "1.0.2"));
        assertTrue(VersionComparator.isGreater("1.0.99999999999999999999", "1.0.3000000000"));
        assertTrue(VersionComparator.isGreater("1.0-beta3000000000", "1.0-beta2"));
        assertTrue(VersionComparator.isGreater("1.2.build.3000000000", "1.2.build.2"));
        assertEquals(0, VersionComparator.compare("1.02", "1.2"));
    }

    @Test
    void knownLabelGluedToTheNumberIsAPrerelease() {
        assertEquals(0, VersionComparator.compare("1.0.0beta2", "1.0.0-beta2"));
        assertEquals(0, VersionComparator.compare("1.0.0beta.2", "1.0.0-beta2"));
        assertTrue(VersionComparator.isGreater("1.0.0", "1.0.0beta"));
        assertTrue(VersionComparator.isGreater("1.0.0-rc1", "1.0.0beta"));
        assertTrue(VersionComparator.isGreater("1.0.0", "1.0.0rc1"));
        assertTrue(VersionComparator.isGreater("1.0.0rc2", "1.0.0rc1"));
    }

    // Minecraft's order, as in 26.3-pre1 < 26.3-rc1 < 26.3.
    @Test
    void preRanksBetweenBetaAndRc() {
        assertTrue(VersionComparator.isGreater("1.0-pre1", "1.0-beta3"));
        assertTrue(VersionComparator.isGreater("1.0-pre2", "1.0-pre1"));
        assertTrue(VersionComparator.isGreater("1.0-rc1", "1.0-pre2"));
        assertTrue(VersionComparator.isGreater("26.3-rc1", "26.3-pre1"));
        assertTrue(VersionComparator.isGreater("26.3", "26.3-pre1"));
        assertEquals(0, VersionComparator.compare("1.0pre1", "1.0-pre1"));
        assertTrue(VersionComparator.isGreater("1.0-snapshot", "1.0-rc1"));
    }
}
