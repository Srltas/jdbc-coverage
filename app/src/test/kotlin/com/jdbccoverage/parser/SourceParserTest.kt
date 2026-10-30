package com.jdbccoverage.parser

import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SourceParserTest {

    @TempDir
    lateinit var dir: Path

    // SourceParser configures the shared StaticJavaParser; restore the default for other tests.
    @AfterEach
    fun resetParserConfiguration() {
        StaticJavaParser.setConfiguration(ParserConfiguration())
    }

    @Test
    fun `parses records and instanceof patterns`() {
        Files.writeString(
            dir.resolve("Modern.java"),
            """
            public class Modern {
                record Point(int x, int y) {}

                static int x(Object o) {
                    return o instanceof Point p ? p.x() : 0;
                }
            }
            """.trimIndent(),
        )

        val parser = SourceParser(listOf(dir))
        val units = parser.parseAll()

        assertThat(parser.getParseStats().failures).isEmpty()
        assertThat(units).hasSize(1)
    }
}
