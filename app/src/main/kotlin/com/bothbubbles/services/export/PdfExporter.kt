package com.bothbubbles.services.export

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class PdfExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val htmlExporter: HtmlExporter
) : MessageExporter {

    override val format: ExportFormat = ExportFormat.PDF
    override val fileExtension: String = "pdf"
    override val mimeType: String = "application/pdf"

    override suspend fun export(
        chats: List<ExportableChat>,
        style: ExportStyle,
        outputFile: File
    ): Result<File> {
        // First generate HTML using the HtmlExporter
        val tempHtmlFile = File(context.cacheDir, "export_temp.html")
        val htmlResult = htmlExporter.export(chats, style, tempHtmlFile)

        if (htmlResult.isFailure) {
            return Result.failure(htmlResult.exceptionOrNull() ?: Exception("HTML generation failed"))
        }

        val htmlContent = tempHtmlFile.readText()

        // Convert HTML to PDF using WebView
        return generatePdfFromHtml(htmlContent, outputFile).also {
            // Clean up temp file
            tempHtmlFile.delete()
        }
    }

    private suspend fun generatePdfFromHtml(
        htmlContent: String,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    setSupportZoom(false)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    try {
                        val result = createPdfFromWebView(webView, outputFile)
                        webView.destroy()
                        continuation.resume(result)
                    } catch (e: Exception) {
                        webView.destroy()
                        continuation.resume(Result.failure(e))
                    }
                }
            }

            webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)

            continuation.invokeOnCancellation {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    private fun createPdfFromWebView(
        webView: WebView,
        outputFile: File
    ): Result<File> {
        return try {
            // Page dimensions in points (1/72 inch) - A4 size
            val pageWidth = 595 // ~8.27 inches
            val pageHeight = 842 // ~11.69 inches

            // Measure the WebView content
            webView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(pageWidth, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            )
            val contentHeight = webView.measuredHeight

            // Calculate number of pages needed
            val pageCount = (contentHeight + pageHeight - 1) / pageHeight

            val pdfDocument = PdfDocument()

            try {
                for (pageNum in 0 until pageCount) {
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum + 1).create()
                    val page = pdfDocument.startPage(pageInfo)

                    val canvas = page.canvas

                    // Translate canvas to draw the appropriate portion of the WebView
                    canvas.translate(0f, -(pageNum * pageHeight).toFloat())

                    // Scale WebView to fit page width if needed
                    val scale = pageWidth.toFloat() / webView.width.coerceAtLeast(1)
                    canvas.scale(scale, scale)

                    // Draw WebView content
                    webView.draw(canvas)

                    pdfDocument.finishPage(page)
                }

                // Ensure parent directory exists
                outputFile.parentFile?.mkdirs()

                // Write PDF to file
                FileOutputStream(outputFile).use { outputStream ->
                    pdfDocument.writeTo(outputStream)
                }

                Result.success(outputFile)
            } finally {
                pdfDocument.close()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
