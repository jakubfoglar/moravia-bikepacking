package cz.tripcompanion

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Sends ride events to the follow website.
 *
 * Uses the Karoo system service's HTTP (not HttpURLConnection) so posts go out over the
 * phone's Bluetooth Companion link — no WiFi needed on the bike. Payloads must stay under
 * OnHttpResponse.MAX_REQUEST_SIZE (100_000 bytes); a sketch is a few KB, so only photos
 * would ever break that, and photos come from the phone instead.
 *
 * The secret is entered once in the app and lives in prefs — never in this repo, which is public.
 */
object TripPoster {
    private const val URL = "https://nqtvcxztuuoywznxrxga.supabase.co/functions/v1/ingest"
    private const val PREFS = "trip"
    private const val KEY_SECRET = "ingestSecret"
    private const val TIMEOUT_MS = 90_000L
    const val FLAG_POSTING = "postingEnabled"

    fun secret(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SECRET, "") ?: ""

    fun setSecret(ctx: Context, v: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SECRET, v.trim()).apply()

    fun postingEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(FLAG_POSTING, true)

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastOkAt: Long = 0L
        private set

    data class Result(val ok: Boolean, val caption: String?, val error: String?)

    /**
     * One request. Suspends until the Karoo reports the response.
     *
     * Always returns within TIMEOUT_MS. That matters more than it looks: waitForConnection
     * queues a request while the Companion link is down, and a queued request that never
     * completes would suspend forever — and because RideNarrator ticks serially, one hang
     * would silently stop every future post for the rest of the trip.
     */
    suspend fun send(karoo: KarooSystemService, ctx: Context, payload: JSONObject): Result =
        withTimeoutOrNull(TIMEOUT_MS) { sendInner(karoo, ctx, payload) }
            ?: Result(false, null, "timeout after ${TIMEOUT_MS / 1000}s").also {
                lastError = it.error
                Logger.log("poster", "timeout — link down?")
            }

    private suspend fun sendInner(karoo: KarooSystemService, ctx: Context, payload: JSONObject): Result {
        val sec = secret(ctx)
        if (sec.isEmpty()) {
            lastError = "no secret set"
            return Result(false, null, lastError)
        }
        val body = payload.toString().toByteArray()
        if (body.size > OnHttpResponse.MAX_REQUEST_SIZE) {
            lastError = "payload ${body.size}B > ${OnHttpResponse.MAX_REQUEST_SIZE}B"
            return Result(false, null, lastError)
        }

        return suspendCancellableCoroutine { cont ->
            var done = false
            val id = karoo.addConsumer<OnHttpResponse>(
                OnHttpResponse.MakeHttpRequest(
                    method = "POST",
                    url = URL,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "x-trip-secret" to sec,
                    ),
                    body = body,
                    // Queue it rather than drop it — the Companion link comes and goes.
                    waitForConnection = true,
                ),
                onError = { err ->
                    if (!done) {
                        done = true
                        lastError = err
                        Logger.log("poster", "error: $err")
                        cont.resume(Result(false, null, err))
                    }
                },
            ) { event: OnHttpResponse ->
                val st = event.state
                if (st is HttpResponseState.Complete && !done) {
                    done = true
                    val txt = st.body?.toString(Charsets.UTF_8) ?: ""
                    if (st.statusCode in 200..299) {
                        lastOkAt = System.currentTimeMillis()
                        lastError = null
                        val cap = try { JSONObject(txt).optString("caption", null) } catch (e: Exception) { null }
                        Logger.log("poster", "ok ${st.statusCode} ${cap ?: ""}")
                        cont.resume(Result(true, cap, null))
                    } else {
                        lastError = "HTTP ${st.statusCode} ${st.error ?: txt.take(80)}"
                        Logger.log("poster", "fail: $lastError")
                        cont.resume(Result(false, null, lastError))
                    }
                }
            }
            cont.invokeOnCancellation { karoo.removeConsumer(id) }
        }
    }

    fun autoEvent(event: String, facts: Map<String, Any?>, lat: Double?, lon: Double?, day: Int): JSONObject =
        JSONObject().apply {
            put("kind", "auto")
            put("event", event)
            put("facts", JSONObject(facts.filterValues { it != null }))
            put("lat", lat); put("lon", lon); put("day", day)
        }

    fun state(
        day: Int, lat: Double?, lon: Double?, place: String?,
        dayKm: Double, dayTotalKm: Double, movingSecs: Int, cars: Int,
        stoppedSinceIso: String?, trainVerdict: String?,
    ): JSONObject = JSONObject().apply {
        put("type", "state")
        put("day", day); put("lat", lat); put("lon", lon); put("place", place)
        put("day_km", dayKm); put("day_total_km", dayTotalKm)
        put("moving_secs", movingSecs); put("cars", cars)
        put("stopped_since", stoppedSinceIso); put("train_verdict", trainVerdict)
    }
}
