package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LangLoaderLocaleTest {

    @Test
    void acceptsLocaleCodesInTheUsualSpellings() {
        assertEquals("de_DE", LangLoader.normalize("de_DE"));
        assertEquals("de_DE", LangLoader.normalize(" de_DE.yml "));
        assertEquals("de_DE", LangLoader.normalize("de-de"));
        assertEquals("zh_CN", LangLoader.normalize("ZH_cn"));
    }

    @Test
    void rejectsValuesThatCouldLeaveTheLangFolder() {
        assertNull(LangLoader.normalize("../config"));
        assertNull(LangLoader.normalize("../../OtherPlugin/config.yml"));
        assertNull(LangLoader.normalize("de_DE/../../config"));
        assertNull(LangLoader.normalize("C:\\temp\\de_DE"));
        assertNull(LangLoader.normalize("/etc/de_DE"));
    }

    @Test
    void rejectsBlankAndMalformedValues() {
        assertNull(LangLoader.normalize(null));
        assertNull(LangLoader.normalize("   "));
        assertNull(LangLoader.normalize("german"));
        assertNull(LangLoader.normalize("de"));
        assertNull(LangLoader.normalize("de_DE_x"));
        assertNull(LangLoader.normalize("d1_DE"));
    }
}
