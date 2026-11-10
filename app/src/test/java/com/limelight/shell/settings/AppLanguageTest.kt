package com.limelight.shell.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {
    @Test
    fun choicesAreTheSupportedTagsOrSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalizeChoice(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalizeChoice(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalizeChoice("default"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalizeChoice("ru"))
        assertEquals("cs", AppLanguage.normalizeChoice("cs"))
        assertEquals("pt-BR", AppLanguage.normalizeChoice("pt-br"))
        assertEquals("pt-BR", AppLanguage.normalizeChoice("pt_BR"))
        assertEquals("zh-CN", AppLanguage.normalizeChoice(" zh-CN "))
        AppLanguage.TAGS.forEach { assertEquals(it, AppLanguage.normalizeChoice(it)) }
    }

    @Test
    fun theChoiceGivesThePlatformNoLocalesOrOne() {
        assertEquals(emptyList<String>(), AppLanguage.tagsFor("default"))
        assertEquals(emptyList<String>(), AppLanguage.tagsFor("xx"))
        assertEquals(listOf("de"), AppLanguage.tagsFor("de"))
        assertEquals(listOf("pt-BR"), AppLanguage.tagsFor("pt-BR"))
    }

    @Test
    fun noAppLocaleMeansFollowTheSystem() {
        assertEquals("default", AppLanguage.choiceFor(emptyList()))
        assertEquals("default", AppLanguage.choiceFor(listOf("")))
    }

    @Test
    fun theAppLocaleBecomesTheMatchingEntry() {
        assertEquals("ja", AppLanguage.choiceFor(listOf("ja")))
        assertEquals("ja", AppLanguage.choiceFor(listOf("ja-JP")))
        assertEquals("pt-BR", AppLanguage.choiceFor(listOf("pt-BR")))
        assertEquals("zh-CN", AppLanguage.choiceFor(listOf("zh-Hans-CN")))
        assertEquals("de", AppLanguage.choiceFor(listOf("de-AT", "en")))
        // an untranslated app locale shows English
        assertEquals("en", AppLanguage.choiceFor(listOf("it")))
        assertEquals("en", AppLanguage.choiceFor(listOf("zh-TW")))
    }

    @Test
    fun supportedTagMatchesLanguageAndRegion() {
        assertEquals("en", AppLanguage.supportedTag("en-GB"))
        assertEquals("en", AppLanguage.supportedTag("en_US"))
        assertEquals("fr", AppLanguage.supportedTag("fr-CA"))
        assertEquals("es", AppLanguage.supportedTag("es-419"))
        assertEquals("uk", AppLanguage.supportedTag("uk-UA"))
        // Brazilian Portuguese and Simplified Chinese entries serve their own region and the bare language
        assertEquals("pt-BR", AppLanguage.supportedTag("pt"))
        assertEquals("zh-CN", AppLanguage.supportedTag("zh"))
        assertEquals("zh-CN", AppLanguage.supportedTag("zh-Hans"))
        assertEquals("zh-CN", AppLanguage.supportedTag("zh-Hans-CN"))
        assertNull(AppLanguage.supportedTag("pt-PT"))
        assertNull(AppLanguage.supportedTag("zh-TW"))
        assertNull(AppLanguage.supportedTag("zh-Hant"))
        assertNull(AppLanguage.supportedTag("zh-Hant-TW"))
        assertNull(AppLanguage.supportedTag("ru"))
        assertNull(AppLanguage.supportedTag(""))
    }

    @Test
    fun followingTheSystemPicksTheFirstTranslatedLanguageElseEnglish() {
        assertEquals("cs", AppLanguage.effectiveTag(listOf("cs-CZ", "en-US")))
        assertEquals("de", AppLanguage.effectiveTag(listOf("it-IT", "de-DE", "en")))
        assertEquals("en", AppLanguage.effectiveTag(listOf("it-IT", "ru-RU")))
        assertEquals("en", AppLanguage.effectiveTag(emptyList()))
    }
}
