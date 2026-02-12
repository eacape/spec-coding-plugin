package com.eacape.speccodingplugin.i18n

import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.Topic
import java.util.Locale

enum class InterfaceLanguage(
    val code: String,
    val labelKey: String,
    private val locale: Locale?,
) {
    AUTO("AUTO", "settings.language.auto", null),
    ENGLISH("ENGLISH", "settings.language.english", Locale.ENGLISH),
    ZH_CN("ZH_CN", "settings.language.zhCn", Locale.SIMPLIFIED_CHINESE),
    ;

    fun resolve(systemLocale: Locale): Locale = locale ?: systemLocale

    companion object {
        fun fromCode(value: String?): InterfaceLanguage {
            return entries.firstOrNull { it.code.equals(value, ignoreCase = true) } ?: AUTO
        }
    }
}

data class LocaleChangedEvent(
    val previousLanguage: InterfaceLanguage,
    val currentLanguage: InterfaceLanguage,
    val reason: String,
    val changedAt: Long,
    val resolvedLocale: Locale,
)

interface LocaleChangedListener {
    fun onLocaleChanged(event: LocaleChangedEvent) {}

    companion object {
        val TOPIC: Topic<LocaleChangedListener> = Topic.create(
            "SpecCoding.LocaleChanged",
            LocaleChangedListener::class.java,
        )
    }
}

@Service(Service.Level.APP)
class LocaleManager internal constructor(
    private val messageBus: MessageBus,
    private val settingsProvider: () -> SpecCodingSettingsState,
    private val systemLocaleProvider: () -> Locale,
    private val clock: () -> Long,
) {

    constructor() : this(
        messageBus = ApplicationManager.getApplication().messageBus,
        settingsProvider = { SpecCodingSettingsState.getInstance() },
        systemLocaleProvider = { Locale.getDefault() },
        clock = { System.currentTimeMillis() },
    )

    fun selectedLanguage(): InterfaceLanguage {
        return InterfaceLanguage.fromCode(settingsProvider().interfaceLanguage)
    }

    fun currentLocale(): Locale {
        return selectedLanguage().resolve(systemLocaleProvider())
    }

    fun setLanguage(language: InterfaceLanguage, reason: String = "unspecified"): LocaleChangedEvent? {
        val settings = settingsProvider()
        val previous = InterfaceLanguage.fromCode(settings.interfaceLanguage)
        if (previous == language) {
            return null
        }

        settings.interfaceLanguage = language.code
        val event = LocaleChangedEvent(
            previousLanguage = previous,
            currentLanguage = language,
            reason = reason,
            changedAt = clock(),
            resolvedLocale = currentLocale(),
        )
        messageBus.syncPublisher(LocaleChangedListener.TOPIC)
            .onLocaleChanged(event)
        return event
    }

    companion object {
        fun getInstance(): LocaleManager = service()
    }
}

