package com.jdbcchecker.cli

import com.jdbcchecker.report.dashboard.DashboardData
import com.jdbcchecker.report.dashboard.DashboardRenderer
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "dashboard",
    description = ["Render a self-contained trend dashboard HTML from a history directory."],
    mixinStandardHelpOptions = true,
)
class DashboardCommand : Callable<Int> {

    @Parameters(
        index = "0",
        description = ["History directory (contains history/*.jsonl and latest/*.json)."],
    )
    lateinit var historyDir: Path

    @Option(names = ["-o", "--output"], required = true, description = ["Output HTML file path."])
    lateinit var output: Path

    override fun call(): Int {
        val drivers = DashboardData.load(historyDir)
        if (drivers.isEmpty()) {
            System.err.println("Error: No history entries found under ${historyDir.resolve("history")}")
            return 1
        }
        output.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(output, DashboardRenderer().render(drivers))
        println("Dashboard written to: $output (${drivers.size} drivers)")
        return 0
    }
}
