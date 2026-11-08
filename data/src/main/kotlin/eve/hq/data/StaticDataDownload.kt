package eve.hq.data

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

enum class StaticDataDownloadResult { IMPORTED, NOT_MODIFIED }

fun downloadStaticData(url: String, dataDir: Path, importer: StaticDataImporter): StaticDataDownloadResult {
    val validators = importer.validatorsFor(url)
    val request = HttpRequest.newBuilder(URI.create(url)).GET().apply {
        validators?.etag?.let { header("If-None-Match", it) }
        validators?.lastModified?.let { header("If-Modified-Since", it) }
    }.build()
    val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        .send(request, HttpResponse.BodyHandlers.ofInputStream())
    response.body().use { body ->
        if (response.statusCode() == 304 && validators != null && (validators.etag != null || validators.lastModified != null)) {
            return StaticDataDownloadResult.NOT_MODIFIED
        }
        check(response.statusCode() == 200) { "Static-data download failed: HTTP ${response.statusCode()}" }
        val archive = Files.createTempFile(dataDir, "static-data-", ".zip")
        try {
            Files.copy(body, archive, StandardCopyOption.REPLACE_EXISTING)
            importer.importDownloadedArchive(
                archive,
                url,
                StaticDataValidators(
                    response.headers().firstValue("ETag").orElse(null),
                    response.headers().firstValue("Last-Modified").orElse(null),
                ),
            )
            return StaticDataDownloadResult.IMPORTED
        } finally {
            Files.deleteIfExists(archive)
        }
    }
}