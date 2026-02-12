package com.eacape.speccodingplugin.i18n

import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.util.messages.MessageBus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Locale

class LocaleManagerTest {

    @Test
    fun `currentLocale should use system locale when AUTO`() {
        val settings = SpecCodingSettingsState().apply {
            interfaceLanguage = InterfaceLanguage.AUTO.code
        }
        val messageBus = mockk<MessageBus>(relaxed = true)
        val manager = LocaleManager(
            messageBus = messageBus,
            settingsProvider = { settings },
            systemLocaleProvider = { Locale.JAPAN },
            clock = { 123L },
        )

        assertEquals(Locale.JAPAN, manager.currentLocale())
    }

    @Test
    fun `setLanguage should publish event when value changed`() {
        val settings = SpecCodingSettingsState().apply {
            interfaceLanguage = InterfaceLanguage.AUTO.code
        }
        val listener = mockk<LocaleChangedListener>(relaxed = true)
        val messageBus = mockk<MessageBus>()
        every { messageBus.syncPublisher(LocaleChangedListener.TOPIC) } returns listener

        val manager = LocaleManager(
            messageBus = messageBus,
            settingsProvider = { settings },
            systemLocaleProvider = { Locale.ENGLISH },
            clock = { 1000L },
        )

        val event = manager.setLanguage(InterfaceLanguage.ZH_CN, reason = "test")

        assertEquals(InterfaceLanguage.ZH_CN.code, settings.interfaceLanguage)
        assertEquals(InterfaceLanguage.AUTO, event?.previousLanguage)
        assertEquals(InterfaceLanguage.ZH_CN, event?.currentLanguage)
        assertEquals(Locale.SIMPLIFIED_CHINESE, event?.resolvedLocale)
        verify(exactly = 1) { messageBus.syncPublisher(LocaleChangedListener.TOPIC) }
        val slot = slot<LocaleChangedEvent>()
        verify(exactly = 1) { listener.onLocaleChanged(capture(slot)) }
        assertEquals("test", slot.captured.reason)
    }

    @Test
    fun `setLanguage should return null when unchanged`() {
        val settings = SpecCodingSettingsState().apply {
            interfaceLanguage = InterfaceLanguage.ENGLISH.code
        }
        val messageBus = mockk<MessageBus>(relaxed = true)
        val manager = LocaleManager(
            messageBus = messageBus,
            settingsProvider = { settings },
            systemLocaleProvider = { Locale.ENGLISH },
            clock = { 1L },
        )

        val event = manager.setLanguage(InterfaceLanguage.ENGLISH, reason = "same")

        assertNull(event)
        verify(exactly = 0) { messageBus.syncPublisher(any()) }
    }
}

