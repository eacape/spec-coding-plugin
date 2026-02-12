package com.eacape.speccodingplugin.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.Properties

class SpecCodingBundleConsistencyTest {

    @Test
    fun `english and zhCN bundle keys should match`() {
        val en = loadProperties("src/main/resources/messages/SpecCodingBundle.properties")
        val zh = loadProperties("src/main/resources/messages/SpecCodingBundle_zh_CN.properties")

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

