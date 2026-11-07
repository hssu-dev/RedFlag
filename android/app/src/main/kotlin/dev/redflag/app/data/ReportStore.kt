package dev.redflag.app.data

import android.content.Context
import dev.redflag.core.model.RedFlagJson
import dev.redflag.core.model.RiskReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Last N reports, kept on-device only (app-private file). */
class ReportStore(context: Context) {
    private val file = File(context.filesDir, "recent_reports.json")
    private val _recent = MutableStateFlow(load())
    val recent: StateFlow<List<RiskReport>> = _recent

    private fun load(): List<RiskReport> = runCatching {
        RedFlagJson.decodeFromString(ListSerializer(RiskReport.serializer()), file.readText())
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(report: RiskReport) {
        val next = (listOf(report) + _recent.value.filter { it.id != report.id }).take(MAX)
        _recent.value = next
        runCatching { file.writeText(RedFlagJson.encodeToString(ListSerializer(RiskReport.serializer()), next)) }
    }

    companion object { const val MAX = 30 }
}
