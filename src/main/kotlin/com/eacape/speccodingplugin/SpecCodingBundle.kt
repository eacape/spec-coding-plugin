package com.eacape.speccodingplugin

import com.eacape.speccodingplugin.i18n.LocaleManager
import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.MissingResourceException
import java.util.ResourceBundle

private const val BUNDLE_NAME = "messages.SpecCodingBundle"

object SpecCodingBundle : DynamicBundle(BUNDLE_NAME) {
    fun message(@PropertyKey(resourceBundle = BUNDLE_NAME) key: String, vararg params: Any): String {
        val locale = runCatching { LocaleManager.getInstance().currentLocale() }.getOrNull()
        if (locale == null) return getMessage(key, *params)

        return try {
            val bundle = ResourceBundle.getBundle(BUNDLE_NAME, locale, javaClass.classLoader)
            val pattern = bundle.getString(key)
            MessageFormat(pattern, locale).format(params)
        } catch (_: MissingResourceException) {
            getMessage(key, *params)
        }
    }

    fun messageOrDefault(key: String, defaultValue: String): String {
        return runCatching { message(key) }.getOrDefault(defaultValue)
    }
}
