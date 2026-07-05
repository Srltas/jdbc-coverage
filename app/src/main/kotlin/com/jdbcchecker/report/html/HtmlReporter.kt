package com.jdbcchecker.report.html

import com.jdbcchecker.model.AnalysisReport
import com.jdbcchecker.model.ImplementationStatus
import com.jdbcchecker.model.InterfaceResult
import com.jdbcchecker.model.MethodResult
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Generates a self-contained HTML report with embedded Chart.js charts.
 *
 * The output is a single HTML file that can be opened in any browser
 * without external dependencies (Chart.js is loaded from CDN).
 */
class HtmlReporter {

    fun report(result: AnalysisReport, outputPath: Path) {
        val html = generateHtml(result)
        outputPath.writeText(html)
        println("HTML report written to: $outputPath")
    }

    fun generateHtml(result: AnalysisReport): String = createHTML().html {
        head {
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
            title { +"JDBC Compliance Report — ${result.driverName}" }
            script(src = "https://cdn.jsdelivr.net/npm/chart.js@4") {}
            style { unsafe { raw(CSS) } }
        }
        body {
            div("container") {
                renderHeader(result)
                renderOverviewCards(result)
                renderChartRow(result)
                renderVersionBreakdown(result)
                renderInterfaceTable(result)
                renderInterfaceDetails(result)
                renderFooter(result)
            }
            script { unsafe { raw(chartScript(result)) } }
        }
    }

    private fun FlowContent.renderHeader(result: AnalysisReport) {
        header {
            h1 { +"JDBC Compliance Report" }
            p("subtitle") {
                +result.driverName
                span("separator") { +" | " }
                +result.analyzedAt.toString().substringBefore('.')
                result.profileUsed?.let {
                    span("separator") { +" | " }
                    +"profile: $it"
                }
            }
            p("source-path") { +"Source: ${result.sourcePath}" }
        }
    }

    private fun FlowContent.renderOverviewCards(result: AnalysisReport) {
        div("cards") {
            card("Overall", "${"%.1f".format(result.overallCoveragePercent)}%", "card-primary")
            card("Total Methods", "${result.totalMethods}", "card-info")
            card("Implemented", "${result.totalImplemented}", "card-success")
            card("Stub", "${result.totalStub}", "card-warning")
            card("Not Found", "${result.totalNotFound}", "card-danger")
        }
    }

    private fun FlowContent.card(title: String, value: String, cssClass: String) {
        div("card $cssClass") {
            div("card-value") { +value }
            div("card-title") { +title }
        }
    }

    private fun FlowContent.renderChartRow(result: AnalysisReport) {
        div("chart-row") {
            div("chart-container") {
                h3 { +"Status Distribution" }
                canvas { id = "statusChart" }
            }
            div("chart-container") {
                h3 { +"JDBC Version Coverage" }
                canvas { id = "versionChart" }
            }
        }
    }

    private fun FlowContent.renderVersionBreakdown(result: AnalysisReport) {
        div("section") {
            h2 { +"JDBC Version Breakdown" }
            table("data-table") {
                thead {
                    tr {
                        th { +"JDBC Version" }
                        th { +"Coverage" }
                        th { +"Implemented" }
                        th { +"Stub" }
                        th { +"Not Found" }
                        th { +"Total" }
                    }
                }
                tbody {
                    for ((version, cov) in result.versionBreakdown) {
                        tr {
                            td { +"JDBC ${version.display}" }
                            td {
                                div("progress-bar-container") {
                                    div("progress-bar") {
                                        style = "width: ${cov.coveragePercent.coerceIn(0.0, 100.0)}%"
                                    }
                                    span("progress-text") { +"${"%.1f".format(cov.coveragePercent)}%" }
                                }
                            }
                            td("num") { +"${cov.implemented}" }
                            td("num") { +"${cov.stub}" }
                            td("num") { +"${cov.notFound}" }
                            td("num") { +"${cov.total}" }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.renderInterfaceTable(result: AnalysisReport) {
        div("section") {
            h2 { +"Interface Summary" }
            table("data-table") {
                thead {
                    tr {
                        th { +"Interface" }
                        th { +"Implementing Class" }
                        th { +"Coverage" }
                        th { +"Impl" }
                        th { +"Stub" }
                        th { +"N/A" }
                        th { +"Total" }
                    }
                }
                tbody {
                    for (iface in result.interfaces.sortedByDescending { it.coveragePercent }) {
                        val simpleName = iface.interfaceName.substringAfterLast('.')
                        val implName = iface.implementingClass?.substringAfterLast('.') ?: "—"
                        tr {
                            td {
                                a(href = "#iface-${simpleName}") { +simpleName }
                            }
                            td("impl-class") { +implName }
                            td {
                                div("progress-bar-container") {
                                    div("progress-bar") {
                                        style = "width: ${iface.coveragePercent.coerceIn(0.0, 100.0)}%"
                                    }
                                    span("progress-text") { +"${"%.1f".format(iface.coveragePercent)}%" }
                                }
                            }
                            td("num") { +"${iface.implemented}" }
                            td("num") { +"${iface.stub}" }
                            td("num") { +"${iface.notFound}" }
                            td("num") { +"${iface.total}" }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.renderInterfaceDetails(result: AnalysisReport) {
        div("section") {
            h2 { +"Method Details" }
            for (iface in result.interfaces.sortedByDescending { it.coveragePercent }) {
                val simpleName = iface.interfaceName.substringAfterLast('.')
                details("interface-detail") {
                    id = "iface-$simpleName"
                    summary {
                        span("interface-name") { +simpleName }
                        span("interface-coverage") {
                            +"${"%.1f".format(iface.coveragePercent)}% (${iface.implemented}/${iface.total})"
                        }
                    }
                    table("method-table") {
                        thead {
                            tr {
                                th { +"Method" }
                                th { +"JDBC" }
                                th { +"Status" }
                            }
                        }
                        tbody {
                            for (method in iface.methods.sortedWith(
                                compareBy<MethodResult> { statusOrder(it.status) }
                                    .thenBy { it.specMethod.jdbcVersion }
                                    .thenBy { it.specMethod.methodName },
                            )) {
                                tr("status-${statusCss(method.status)}") {
                                    td {
                                        code { +"${method.specMethod.methodName}(${method.specMethod.parameterTypes.joinToString(", ")})" }
                                    }
                                    td { +method.specMethod.jdbcVersion.display }
                                    td {
                                        span("badge badge-${statusCss(method.status)}") {
                                            +method.status.label
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.renderFooter(result: AnalysisReport) {
        footer {
            p {
                +"Generated by "
                strong { +"JDBC Compliance Checker v0.2.0" }
                +" | ${result.analyzedAt.toString().substringBefore('.')}"
            }
        }
    }

    private fun statusCss(status: ImplementationStatus): String = when (status) {
        is ImplementationStatus.FullyImplemented -> "implemented"
        is ImplementationStatus.Delegates -> "delegates"
        is ImplementationStatus.Partial -> "partial"
        is ImplementationStatus.ThrowsUnsupported -> "stub"
        is ImplementationStatus.ThrowsSqlException -> "stub"
        is ImplementationStatus.ReturnsDefault -> "stub"
        is ImplementationStatus.NotFound -> "notfound"
    }

    private fun statusOrder(status: ImplementationStatus): Int = when (status) {
        is ImplementationStatus.NotFound -> 0
        is ImplementationStatus.ThrowsUnsupported -> 1
        is ImplementationStatus.ThrowsSqlException -> 2
        is ImplementationStatus.ReturnsDefault -> 3
        is ImplementationStatus.Partial -> 4
        is ImplementationStatus.Delegates -> 5
        is ImplementationStatus.FullyImplemented -> 6
    }

    private fun chartScript(result: AnalysisReport): String {
        val statusLabels = result.statusDistribution.keys.joinToString(",") { "'$it'" }
        val statusValues = result.statusDistribution.values.joinToString(",")
        val statusColors = result.statusDistribution.keys.joinToString(",") { key ->
            when (key) {
                "Fully Implemented" -> "'#22c55e'"
                "Delegates" -> "'#84cc16'"
                "Partial" -> "'#eab308'"
                "Throws Unsupported" -> "'#f97316'"
                "Throws SQLException" -> "'#ef4444'"
                "Returns Default" -> "'#fb923c'"
                "Not Found" -> "'#94a3b8'"
                else -> "'#6b7280'"
            }
        }

        val versionLabels = result.versionBreakdown.keys.joinToString(",") { "'JDBC ${it.display}'" }
        val versionImpl = result.versionBreakdown.values.joinToString(",") { "${it.implemented}" }
        val versionStub = result.versionBreakdown.values.joinToString(",") { "${it.stub}" }
        val versionNotFound = result.versionBreakdown.values.joinToString(",") { "${it.notFound}" }

        return """
            new Chart(document.getElementById('statusChart'), {
                type: 'doughnut',
                data: {
                    labels: [$statusLabels],
                    datasets: [{
                        data: [$statusValues],
                        backgroundColor: [$statusColors],
                        borderWidth: 2,
                        borderColor: '#fff'
                    }]
                },
                options: {
                    responsive: true,
                    plugins: {
                        legend: { position: 'right', labels: { padding: 12, usePointStyle: true } }
                    }
                }
            });

            new Chart(document.getElementById('versionChart'), {
                type: 'bar',
                data: {
                    labels: [$versionLabels],
                    datasets: [
                        { label: 'Implemented', data: [$versionImpl], backgroundColor: '#22c55e' },
                        { label: 'Stub', data: [$versionStub], backgroundColor: '#f97316' },
                        { label: 'Not Found', data: [$versionNotFound], backgroundColor: '#94a3b8' }
                    ]
                },
                options: {
                    responsive: true,
                    scales: { x: { stacked: true }, y: { stacked: true } },
                    plugins: { legend: { position: 'top' } }
                }
            });
        """.trimIndent()
    }

    companion object {
        private val CSS = """
            :root {
                --primary: #3b82f6;
                --success: #22c55e;
                --warning: #f97316;
                --danger: #ef4444;
                --gray: #94a3b8;
                --bg: #f8fafc;
                --card-bg: #ffffff;
                --border: #e2e8f0;
                --text: #1e293b;
                --text-muted: #64748b;
            }
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                   background: var(--bg); color: var(--text); line-height: 1.6; }
            .container { max-width: 1200px; margin: 0 auto; padding: 2rem; }

            header { text-align: center; margin-bottom: 2rem; }
            header h1 { font-size: 2rem; margin-bottom: 0.5rem; }
            .subtitle { font-size: 1.2rem; color: var(--text-muted); }
            .separator { margin: 0 0.5rem; }
            .source-path { font-size: 0.85rem; color: var(--text-muted); font-family: monospace; }

            .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
                     gap: 1rem; margin-bottom: 2rem; }
            .card { background: var(--card-bg); border-radius: 8px; padding: 1.25rem;
                    text-align: center; border: 1px solid var(--border); box-shadow: 0 1px 3px rgba(0,0,0,0.05); }
            .card-value { font-size: 1.8rem; font-weight: 700; }
            .card-title { font-size: 0.85rem; color: var(--text-muted); margin-top: 0.25rem; }
            .card-primary .card-value { color: var(--primary); }
            .card-success .card-value { color: var(--success); }
            .card-warning .card-value { color: var(--warning); }
            .card-danger .card-value { color: var(--danger); }
            .card-info .card-value { color: var(--text); }

            .chart-row { display: grid; grid-template-columns: 1fr 1fr; gap: 1.5rem; margin-bottom: 2rem; }
            .chart-container { background: var(--card-bg); border-radius: 8px; padding: 1.5rem;
                              border: 1px solid var(--border); }
            .chart-container h3 { margin-bottom: 1rem; font-size: 1rem; color: var(--text-muted); }

            .section { margin-bottom: 2rem; }
            .section h2 { font-size: 1.3rem; margin-bottom: 1rem; padding-bottom: 0.5rem;
                          border-bottom: 2px solid var(--border); }

            .data-table { width: 100%; border-collapse: collapse; background: var(--card-bg);
                         border-radius: 8px; overflow: hidden; border: 1px solid var(--border); }
            .data-table th { background: #f1f5f9; padding: 0.75rem 1rem; text-align: left;
                            font-size: 0.85rem; color: var(--text-muted); text-transform: uppercase;
                            letter-spacing: 0.05em; }
            .data-table td { padding: 0.6rem 1rem; border-top: 1px solid var(--border); font-size: 0.9rem; }
            .data-table .num { text-align: center; font-family: monospace; }
            .data-table .impl-class { font-family: monospace; font-size: 0.8rem; color: var(--text-muted); }
            .data-table a { color: var(--primary); text-decoration: none; }
            .data-table a:hover { text-decoration: underline; }

            .progress-bar-container { position: relative; height: 22px; background: #e2e8f0;
                                     border-radius: 4px; overflow: hidden; min-width: 120px; }
            .progress-bar { height: 100%; background: var(--success); border-radius: 4px;
                           transition: width 0.3s; }
            .progress-text { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);
                            font-size: 0.75rem; font-weight: 600; color: var(--text); }

            .interface-detail { background: var(--card-bg); border: 1px solid var(--border);
                               border-radius: 8px; margin-bottom: 0.75rem; }
            .interface-detail summary { padding: 0.75rem 1rem; cursor: pointer; display: flex;
                                       justify-content: space-between; align-items: center; }
            .interface-detail summary:hover { background: #f1f5f9; }
            .interface-name { font-weight: 600; }
            .interface-coverage { font-size: 0.85rem; color: var(--text-muted); font-family: monospace; }

            .method-table { width: 100%; border-collapse: collapse; }
            .method-table th { background: #f1f5f9; padding: 0.5rem 1rem; text-align: left;
                              font-size: 0.8rem; color: var(--text-muted); }
            .method-table td { padding: 0.4rem 1rem; border-top: 1px solid var(--border); font-size: 0.85rem; }
            .method-table code { font-size: 0.8rem; background: #f1f5f9; padding: 2px 6px; border-radius: 3px; }

            .badge { display: inline-block; padding: 2px 8px; border-radius: 12px;
                    font-size: 0.75rem; font-weight: 500; }
            .badge-implemented { background: #dcfce7; color: #166534; }
            .badge-delegates { background: #ecfccb; color: #3f6212; }
            .badge-partial { background: #fef9c3; color: #854d0e; }
            .badge-stub { background: #ffedd5; color: #9a3412; }
            .badge-notfound { background: #f1f5f9; color: #475569; }

            .status-notfound { opacity: 0.6; }

            footer { text-align: center; padding: 2rem 0; color: var(--text-muted); font-size: 0.85rem; }

            @media (max-width: 768px) {
                .chart-row { grid-template-columns: 1fr; }
                .cards { grid-template-columns: repeat(2, 1fr); }
            }
        """.trimIndent()
    }
}
