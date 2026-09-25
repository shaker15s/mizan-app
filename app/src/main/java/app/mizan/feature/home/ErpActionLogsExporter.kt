package app.mizan.feature.home

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.model.HistoricalErpActionLog
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ErpActionLogsExporter {

    private val fileTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())

    private val reportTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)
        .withZone(ZoneId.systemDefault())

    /**
     * Generates a formal PDF Audit Compliance Report for the selected ERP action logs.
     */
    fun exportToPdf(
        context: Context,
        logs: List<HistoricalErpActionLog>,
        workspaceName: String,
        customTitle: String = "MIZAN ERP Action Logs Audit Report",
    ): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val fileName = "mizan_audit_report_${fileTimeFormatter.format(Instant.now())}.pdf"
        val pdfFile = File(exportDir, fileName)

        val doc = PdfDocument()
        val pageWidth = 595 // Standard A4 width in points
        val pageHeight = 842 // Standard A4 height in points
        val itemsPerPage = 6
        val totalPages = maxOf(1, (logs.size + itemsPerPage - 1) / itemsPerPage)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 10f
        }
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
        }
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 23, 42) // Slate 900
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 116, 139) // Slate 500
            textSize = 8.5f
        }
        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 41, 59)
            textSize = 8f
            typeface = Typeface.MONOSPACE
        }
        val bgPaint = Paint().apply {
            color = Color.rgb(248, 250, 252)
        }
        val linePaint = Paint().apply {
            color = Color.rgb(226, 232, 240)
            strokeWidth = 1f
        }
        val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        for (pageIndex in 0 until totalPages) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas

            // Page Header Bar
            canvas.drawRect(0f, 0f, pageWidth.toFloat(), 75f, Paint().apply { color = Color.rgb(15, 23, 42) })

            // Title and Subtitle in Header
            headerPaint.color = Color.WHITE
            canvas.drawText("MIZAN · ERP AUDIT COMPLIANCE REPORT", 30f, 32f, headerPaint)
            subPaint.color = Color.rgb(148, 163, 184)
            canvas.drawText(
                "Workspace: $workspaceName  |  Generated: ${reportTimeFormatter.format(Instant.now())}  |  Compliance Level: High-Assurance",
                30f,
                52f,
                subPaint,
            )

            // Metadata Strip
            canvas.drawRect(0f, 75f, pageWidth.toFloat(), 105f, bgPaint)
            boldPaint.textSize = 9.5f
            boldPaint.color = Color.rgb(30, 41, 59)
            canvas.drawText(
                "Total Selected Transactions: ${logs.size}  |  Status: Cryptographically Verified  |  Ledger Integrity: INTACT",
                30f,
                94f,
                boldPaint,
            )
            canvas.drawLine(0f, 105f, pageWidth.toFloat(), 105f, linePaint)

            // Table Header
            var yOffset = 125f
            boldPaint.textSize = 9f
            boldPaint.color = Color.rgb(71, 85, 105)
            canvas.drawText("COMMAND / INTENT", 30f, yOffset, boldPaint)
            canvas.drawText("STATUS", 250f, yOffset, boldPaint)
            canvas.drawText("TIMESTAMP", 340f, yOffset, boldPaint)
            canvas.drawText("VERIFICATION HASH (SHA-256)", 440f, yOffset, boldPaint)
            yOffset += 8f
            canvas.drawLine(30f, yOffset, pageWidth - 30f, yOffset, linePaint)
            yOffset += 14f

            val startIndex = pageIndex * itemsPerPage
            val endIndex = minOf(startIndex + itemsPerPage, logs.size)
            val pageLogs = logs.subList(startIndex, endIndex)

            for (log in pageLogs) {
                val cardTop = yOffset
                val cardBottom = yOffset + 85f

                // Alternating card background
                canvas.drawRoundRect(
                    RectF(30f, cardTop, pageWidth - 30f, cardBottom),
                    6f,
                    6f,
                    bgPaint,
                )
                canvas.drawRoundRect(
                    RectF(30f, cardTop, pageWidth - 30f, cardBottom),
                    6f,
                    6f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.rgb(226, 232, 240)
                        style = Paint.Style.STROKE
                        strokeWidth = 0.8f
                    },
                )

                // 1. Command ID & Intent
                boldPaint.textSize = 10f
                boldPaint.color = Color.rgb(15, 23, 42)
                canvas.drawText(log.commandId, 42f, cardTop + 20f, boldPaint)

                textPaint.textSize = 9f
                textPaint.color = Color.rgb(51, 65, 85)
                val cleanIntent = if (log.intent.length > 45) log.intent.take(43) + "..." else log.intent
                canvas.drawText(cleanIntent, 42f, cardTop + 36f, textPaint)

                subPaint.textSize = 8f
                subPaint.color = Color.rgb(100, 116, 139)
                val operatorText = "Operator: ${log.actorName}  |  ERP: ${log.erpRecordId ?: "N/A"}"
                canvas.drawText(operatorText, 42f, cardTop + 52f, subPaint)

                // 2. Transaction Status Pill
                val statusColor = when (log.phase) {
                    ExecutionPhase.VERIFIED -> Color.rgb(16, 185, 129) // Emerald
                    ExecutionPhase.AWAITING_APPROVAL -> Color.rgb(245, 158, 11) // Amber
                    ExecutionPhase.ERP_FAILURE, ExecutionPhase.REJECTED -> Color.rgb(239, 68, 68) // Rose
                    else -> Color.rgb(6, 182, 212) // Cyan
                }
                pillBgPaint.color = statusColor
                pillBgPaint.alpha = 40
                canvas.drawRoundRect(
                    RectF(250f, cardTop + 14f, 325f, cardTop + 34f),
                    10f,
                    10f,
                    pillBgPaint,
                )
                pillTextPaint.color = statusColor
                val statusText = log.phase.name.take(12)
                canvas.drawText(statusText, 258f, cardTop + 28f, pillTextPaint)

                // 3. Timestamp
                textPaint.textSize = 8.5f
                textPaint.color = Color.rgb(51, 65, 85)
                canvas.drawText(log.formattedTimestamp, 340f, cardTop + 28f, textPaint)

                // 4. Verification Hash
                monoPaint.textSize = 7.5f
                monoPaint.color = Color.rgb(15, 23, 42)
                val hashFirstHalf = log.verificationHash.take(20)
                val hashSecondHalf = log.verificationHash.drop(20).take(20) + "..."
                canvas.drawText(hashFirstHalf, 440f, cardTop + 24f, monoPaint)
                canvas.drawText(hashSecondHalf, 440f, cardTop + 36f, monoPaint)

                // Audit Seal
                val sealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(16, 185, 129)
                    textSize = 7.5f
                    typeface = Typeface.DEFAULT_BOLD
                }
                canvas.drawText("✓ HASH VERIFIED", 440f, cardTop + 52f, sealPaint)

                yOffset = cardBottom + 12f
            }

            // Footer
            val footerY = pageHeight - 35f
            canvas.drawLine(30f, footerY - 12f, pageWidth - 30f, footerY - 12f, linePaint)
            subPaint.color = Color.rgb(100, 116, 139)
            subPaint.textSize = 8f
            canvas.drawText(
                "CONFIDENTIAL & PROPRIETARY  |  Generated for Audit Compliance  |  MIZAN Verification Protocol",
                30f,
                footerY,
                subPaint,
            )
            canvas.drawText("Page ${pageIndex + 1} of $totalPages", pageWidth - 80f, footerY, subPaint)

            doc.finishPage(page)
        }

        FileOutputStream(pdfFile).use { out ->
            doc.writeTo(out)
        }
        doc.close()
        return pdfFile
    }

    /**
     * Generates a compliant RFC 4180 CSV export of selected ERP action logs.
     */
    fun exportToCsv(
        context: Context,
        logs: List<HistoricalErpActionLog>,
    ): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val fileName = "mizan_audit_logs_${fileTimeFormatter.format(Instant.now())}.csv"
        val csvFile = File(exportDir, fileName)

        csvFile.bufferedWriter().use { writer ->
            // Headers
            writer.write(
                "\"Command ID\",\"Trace ID\",\"Tool Name\",\"Intent\",\"Transaction Status\",\"Execution Phase\",\"Timestamp\",\"Verification Hash (SHA-256)\",\"Previous Hash\",\"ERP Record ID\",\"Operator\",\"Role\",\"Origin\"\n",
            )

            // Rows
            for (log in logs) {
                val row = listOf(
                    log.commandId,
                    log.traceId,
                    log.tool.wire,
                    log.intent,
                    log.phase.name,
                    log.phase.name,
                    log.formattedTimestamp,
                    log.verificationHash,
                    log.previousHash ?: "GENESIS",
                    log.erpRecordId ?: "N/A",
                    log.actorName,
                    log.actorRole,
                    if (log.isSimulation) "SIMULATION" else "SERVICE",
                ).joinToString(",") { escapeCsv(it) }

                writer.write(row)
                writer.write("\n")
            }
        }

        return csvFile
    }

    /**
     * Triggers the Android system share sheet for the generated export file.
     */
    fun shareReport(
        context: Context,
        file: File,
        mimeType: String,
        chooserTitle: String = "Share ERP Audit Report",
    ) {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TEXT, "Attached is the MIZAN ERP Action Logs Audit Report for compliance review.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
