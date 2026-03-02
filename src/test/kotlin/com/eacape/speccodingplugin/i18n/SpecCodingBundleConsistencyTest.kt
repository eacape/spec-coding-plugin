package com.eacape.speccodingplugin.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.Properties

class SpecCodingBundleConsistencyTest {

    @Test
    fun `english and zhCN bundle keys should match`() {
        val en = loadProperties("src/main/resources/messages/SpecCodingBundle.properties")
        val zh = loadProperties("src/main/resources/messages/SpecCodingBundle_zh_CN.properties")

        assertTrue(en.containsKey("toolwindow.title"), "English bundle must contain key 'toolwindow.title'")
        assertTrue(zh.containsKey("toolwindow.title"), "zhCN bundle must contain key 'toolwindow.title'")
        assertFalse(
            en.stringPropertyNames().any { it.startsWith("\uFEFF") },
            "English bundle contains BOM-prefixed key; please save properties as UTF-8 without BOM",
        )
        assertFalse(
            zh.stringPropertyNames().any { it.startsWith("\uFEFF") },
            "zhCN bundle contains BOM-prefixed key; please save properties as UTF-8 without BOM",
        )

        assertEquals(
            en.stringPropertyNames().toSortedSet(),
            zh.stringPropertyNames().toSortedSet(),
            "Bundle key sets must stay consistent between SpecCodingBundle.properties and SpecCodingBundle_zh_CN.properties",
        )
    }

    private fun loadProperties(relativePath: String): Properties {
        val path = Paths.get(relativePath)
        val properties = Properties()
        path.toFile().inputStream().use { input ->
            input.reader(StandardCharsets.UTF_8).use { reader ->
                properties.load(reader)
            }
        }
        return properties
    }
}
