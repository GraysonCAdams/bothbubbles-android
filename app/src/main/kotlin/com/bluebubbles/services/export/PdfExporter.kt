package com.bluebubbles.services.export

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
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
                    createPdfFromWebView(webView, outputFile) { result ->
                        webView.destroy()
                        continuation.resume(result)
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
        outputFile: File,
        onComplete: (Result<File>) -> Unit
    ) {
        val printAdapter = webView.createPrintDocumentAdapter("BlueBubbles_Export")

        val printAttributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setMinMargins(PrintAttributes.Margins(50, 50, 50, 50)) // 50/1000 inch margins
            .build()

        printAdapter.onLayout(
            null,
            printAttributes,
            null,
            object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                    try {
                        // Ensure parent directory exists
                        outputFile.parentFile?.mkdirs()

                        val fileDescriptor = ParcelFileDescriptor.open(
                            outputFile,
                            ParcelFileDescriptor.MODE_CREATE or
                                    ParcelFileDescriptor.MODE_WRITE_ONLY or
                                    ParcelFileDescriptor.MODE_TRUNCATE
                        )

                        printAdapter.onWrite(
                            arrayOf(PageRange.ALL_PAGES),
                            fileDescriptor,
                            CancellationSignal(),
                            object : PrintDocumentAdapter.WriteResultCallback() {
                                override fun onWriteFinished(pages: Array<out PageRange>?) {
                                    try {
                                        fileDescriptor.close()
                                        onComplete(Result.success(outputFile))
                                    } catch (e: Exception) {
                                        onComplete(Result.failure(e))
                                    }
                                }

                                override fun onWriteFailed(error: CharSequence?) {
                                    try {
                                        fileDescriptor.close()
                                    } catch (_: Exception) { }
                                    onComplete(Result.failure(Exception(error?.toString() ?: "PDF write failed")))
                                }

                                override fun onWriteCancelled() {
                                    try {
                                        fileDescriptor.close()
                                        outputFile.delete()
                                    } catch (_: Exception) { }
                                    onComplete(Result.failure(Exception("PDF write cancelled")))
                                }
                            }
                        )
                    } catch (e: Exception) {
                        onComplete(Result.failure(e))
                    }
                }

                override fun onLayoutFailed(error: CharSequence?) {
                    onComplete(Result.failure(Exception(error?.toString() ?: "PDF layout failed")))
                }

                override fun onLayoutCancelled() {
                    onComplete(Result.failure(Exception("PDF layout cancelled")))
                }
            },
            Bundle()
        )
    }
}
