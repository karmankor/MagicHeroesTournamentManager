package cz.magicheroes.tournamentmanager.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Publishes the public projection through Supabase Auth + PostgREST.
 * Only the public publishable/anon key is used in the app.
 */
object PublicSitePublisher {
    fun publish(projectUrl: String, publishableKey: String, email: String, password: String, snapshot: JSONObject) {
        val baseUrl = projectUrl.trim().trimEnd('/')
        require(baseUrl.startsWith("https://", ignoreCase = true)) {
            "Adresa Supabase musí začínat https://."
        }
        require(publishableKey.isNotBlank()) { "Doplň veřejný Supabase publishable/anon klíč." }
        require(email.isNotBlank() && password.isNotBlank()) { "Zadej přihlašovací e-mail a heslo správce." }

        val authBody = JSONObject().put("email", email.trim()).put("password", password)
        val authResponse = request(
            url = "$baseUrl/auth/v1/token?grant_type=password",
            method = "POST",
            publishableKey = publishableKey,
            accessToken = null,
            body = authBody.toString(),
            prefer = null
        )
        if (authResponse.first !in 200..299) {
            throw IllegalStateException("Přihlášení do Supabase se nezdařilo: " + errorMessage(authResponse.second, authResponse.first))
        }

        val auth = runCatching { JSONObject(authResponse.second) }.getOrElse {
            throw IllegalStateException("Supabase vrátil neočekávanou odpověď při přihlášení.")
        }
        val accessToken = auth.optString("access_token")
        require(accessToken.isNotBlank()) { "Přihlášení nevrátilo přístupový token." }

        val payload = JSONObject()
            .put("id", 1)
            .put("snapshot", snapshot)
            .put("published_at", snapshot.optString("publishedAt"))

        val publishResponse = request(
            url = "$baseUrl/rest/v1/public_site_snapshot?on_conflict=id",
            method = "POST",
            publishableKey = publishableKey,
            accessToken = accessToken,
            body = payload.toString(),
            prefer = "resolution=merge-duplicates,return=minimal"
        )
        if (publishResponse.first !in 200..299) {
            throw IllegalStateException("Data se nepodařilo zveřejnit: " + errorMessage(publishResponse.second, publishResponse.first))
        }
    }

    private fun request(
        url: String,
        method: String,
        publishableKey: String,
        accessToken: String?,
        body: String,
        prefer: String?
    ): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("apikey", publishableKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
            if (prefer != null) setRequestProperty("Prefer", prefer)
        }
        return try {
            connection.outputStream.use { stream -> stream.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            status to responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(body: String, status: Int): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        return listOf("msg", "message", "error_description", "error")
            .firstNotNullOfOrNull { field -> json?.optString(field)?.takeIf { it.isNotBlank() && it != "null" } }
            ?: "server vrátil HTTP $status. Zkontroluj nastavení Supabase a RLS politiky."
    }
}
