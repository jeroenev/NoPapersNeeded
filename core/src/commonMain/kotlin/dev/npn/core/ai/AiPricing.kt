package dev.npn.core.ai

import dev.npn.core.db.KeyValueDao
import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnJson
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runLogged
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.round

/** One model's list price in US dollars per million tokens. */
@Serializable
data class AiPrice(
    val inputPerMillionUsd: Double,
    val outputPerMillionUsd: Double,
) {
    fun cost(
        inputTokens: Long?,
        outputTokens: Long?,
    ): Double =
        (inputTokens ?: 0L) * inputPerMillionUsd / 1_000_000.0 +
            (outputTokens ?: 0L) * outputPerMillionUsd / 1_000_000.0

    companion object {
        /** A local endpoint: free to call, though not free to run. */
        val FREE = AiPrice(0.0, 0.0)
    }
}

/** What the endpoint of a configuration charges for its model, or null when that is not known. */
fun interface AiPrices {
    suspend fun priceOf(config: AiConfig): AiPrice?

    companion object {
        /** The build's own snapshot only: what tests and a device that never fetched use. */
        val Snapshot: AiPrices = AiPrices { config -> AiPriceTables.lookup(config, AiPriceTables.snapshot) }
    }
}

/** models.dev or OpenRouter prices: catalog provider id → model id → price. */
typealias AiPriceTable = Map<String, Map<String, AiPrice>>

/**
 * Where prices come from and how a configured model finds its own. Prices are estimates from list
 * prices, never billing facts: a provider's own invoice wins.
 *
 * - [AiProvider.OLLAMA] is free ([AiPrice.FREE]); [AiProvider.CUSTOM] is never priced, since a
 *   self-hosted or compatible server's price cannot be known.
 * - [AiProvider.OPENROUTER] is priced from OpenRouter's own model catalog, which lists what it
 *   charges per model; the snapshot carries models.dev's copy of it for offline use.
 * - Every other provider is priced from models.dev's catalog under its own id ([catalogId]).
 *
 * A model is matched by its exact id within its provider, never by a substring or prefix: a
 * family name says nothing about a model's price (`o3-pro` is ten times `o3`). [candidateIds]
 * documents the only normalisation, which is for ids the catalog lists under another spelling.
 */
object AiPriceTables {
    /** The catalog provider id of [provider], or null when no catalog prices it. */
    fun catalogId(provider: AiProvider): String? =
        when (provider) {
            AiProvider.OPENAI -> "openai"
            AiProvider.ANTHROPIC -> "anthropic"
            AiProvider.GOOGLE -> "google"
            AiProvider.MISTRAL -> "mistral"
            AiProvider.DEEPSEEK -> "deepseek"
            AiProvider.OPENROUTER -> "openrouter"
            AiProvider.OPENCODE_GO -> "opencode-go"
            AiProvider.OLLAMA, AiProvider.CUSTOM -> null
        }

    /** The catalog ids the snapshot and a models.dev refresh keep. */
    val catalogIds: Set<String> = AiProvider.entries.mapNotNullTo(LinkedHashSet(), ::catalogId)

    /** The build's snapshot ([AiPriceSnapshot]), parsed once. */
    val snapshot: AiPriceTable by lazy { parseSnapshot(AiPriceSnapshot.TSV) }

    private val DATED = Regex("""^(.+?)-(\d{8}|\d{4}-\d{2}-\d{2})$""")

    /**
     * The ids [model] is looked up under, in order:
     * 1. the id as configured, trimmed;
     * 2. lower-cased, as every catalog spells its ids;
     * 3. without a leading `models/`, Google's resource prefix for the same model;
     * 4. without a trailing release date (`-20250929`, `-2024-08-06`), for a dated snapshot of a
     *    model the catalog lists only under its undated alias. An exact dated entry always wins, as
     *    it comes first: a provider that reprices a model keeps old snapshots at their own price.
     */
    fun candidateIds(model: String): List<String> {
        val trimmed = model.trim()
        val lower = trimmed.lowercase()
        val unprefixed = lower.removePrefix("models/")
        val undated = DATED.matchEntire(unprefixed)?.groupValues?.get(1)
        return listOfNotNull(trimmed, lower, unprefixed, undated).distinct()
    }

    /** [config]'s price in [tables], the first table that knows the model winning. */
    fun lookup(
        config: AiConfig,
        vararg tables: AiPriceTable,
    ): AiPrice? {
        if (config.provider == AiProvider.OLLAMA) return AiPrice.FREE
        val id = catalogId(config.provider) ?: return null
        val ids = candidateIds(config.model)
        return tables.firstNotNullOfOrNull { table ->
            table[id]?.let { models -> ids.firstNotNullOfOrNull(models::get) }
        }
    }

    fun parseSnapshot(tsv: String): AiPriceTable =
        tsv
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val fields = line.split('\t')
                val input = fields.getOrNull(2)?.toDoubleOrNull()
                val output = fields.getOrNull(3)?.toDoubleOrNull()
                if (fields.size != SNAPSHOT_FIELDS || input == null || output == null) {
                    null
                } else {
                    Triple(fields[0], fields[1], AiPrice(input, output))
                }
            }.groupBy({ it.first }, { it.second to it.third })
            .mapValues { (_, models) -> models.toMap() }

    /**
     * models.dev's `api.json`, kept to [catalogIds]: each model whose `cost` carries an input and an
     * output price in US dollars per million tokens. Tiered prices above a context size are left
     * out; the app's prompts stay far below every tier.
     */
    fun parseModelsDev(json: String): AiPriceTable {
        val root = NpnJson.Lenient.parseToJsonElement(json) as? JsonObject ?: return emptyMap()
        return catalogIds
            .associateWith { provider ->
                val models = (root[provider] as? JsonObject)?.get("models") as? JsonObject
                models
                    .orEmpty()
                    .mapNotNull { (id, model) ->
                        val cost = (model as? JsonObject)?.get("cost") as? JsonObject
                        val input = cost?.number("input")
                        val output = cost?.number("output")
                        if (input == null || output == null) null else id to AiPrice(input, output)
                    }.toMap()
            }.filterValues { it.isNotEmpty() }
    }

    /**
     * OpenRouter's `GET /api/v1/models`: `data[].pricing.prompt` and `.completion` are US dollars
     * per token, as strings. A negative price is OpenRouter's "varies by request" (its router
     * models), which is not a price.
     */
    fun parseOpenRouter(json: String): AiPriceTable {
        val root = NpnJson.Lenient.parseToJsonElement(json) as? JsonObject ?: return emptyMap()
        val models =
            runCatching { root["data"]?.jsonArray }.getOrNull().orEmpty().mapNotNull { entry ->
                val model = entry as? JsonObject ?: return@mapNotNull null
                val id = model["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val pricing = model["pricing"] as? JsonObject
                val prompt = pricing?.number("prompt")
                val completion = pricing?.number("completion")
                if (prompt == null || completion == null) {
                    null
                } else {
                    id to AiPrice(perMillion(prompt), perMillion(completion))
                }
            }
        return if (models.isEmpty()) emptyMap() else mapOf("openrouter" to models.toMap())
    }

    /** A per-token price per million tokens, rounded past the floating-point noise of the product. */
    private fun perMillion(perToken: Double): Double = round(perToken * PER_MILLION * ROUNDING) / ROUNDING

    private fun JsonObject.number(key: String): Double? =
        get(key)
            ?.let { runCatching { it.jsonPrimitive }.getOrNull() }
            ?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
            ?.takeIf { it >= 0.0 && it.isFinite() }

    private const val SNAPSHOT_FIELDS = 4
    private const val PER_MILLION = 1_000_000.0
    private const val ROUNDING = 1_000_000_000.0
}

/** Reads a price catalog over the network: a plain GET that carries nothing of the library's. */
interface AiPriceSource {
    /** models.dev's `api.json`. */
    suspend fun modelsDev(): String

    /** OpenRouter's model catalog at [baseUrl] (the provider's server root). */
    suspend fun openRouter(baseUrl: String): String
}

/** [AiPriceSource] over Ktor; throws on a failed request, which [AiPriceBook] logs and retries later. */
class HttpAiPriceSource(
    private val client: () -> HttpClient = { HttpClient() },
) : AiPriceSource {
    override suspend fun modelsDev(): String = get(MODELS_DEV_URL)

    override suspend fun openRouter(baseUrl: String): String = get(baseUrl.trimEnd('/') + "/api/v1/models")

    private suspend fun get(url: String): String =
        client().use { http ->
            val response = http.get(url)
            check(response.status.isSuccess()) { "$url answered ${response.status}" }
            response.bodyAsText()
        }

    companion object {
        const val MODELS_DEV_URL = "https://models.dev/api.json"
    }
}

/**
 * The prices this device uses, kept fresh: a catalog fetched within the last [MAX_AGE_MS] from
 * `device.db`'s key-value store, falling back to the build's snapshot for a model the catalog does
 * not list and for a device that never fetched one.
 *
 * Fetching is the price of an accurate estimate, so it happens only for a configured cloud endpoint
 * ([AiProvider.OLLAMA] and [AiProvider.CUSTOM] have no catalog), only from the provider's catalog,
 * at most once a [MAX_AGE_MS] per catalog, and never while a call waits: [priceOf] answers from what
 * is stored and starts a refresh on [scope] when that is stale. A failed fetch is retried after
 * [RETRY_MS]. The request is a plain GET; no key, model, prompt or library data goes with it.
 */
internal class AiPriceBook(
    private val store: KeyValueDao,
    private val source: AiPriceSource,
    private val clock: EpochClock,
    private val scope: CoroutineScope,
    private val log: NpnLog = NpnLog.None,
    /** Runs a store write as work the session owns. */
    private val ownWrite: suspend (suspend () -> Unit) -> Unit = { it() },
) : AiPrices {
    private val mutex = Mutex()
    private val cached = HashMap<String, StoredPrices?>()
    private val attempted = HashMap<String, Long>()
    private var refreshing: Job? = null

    override suspend fun priceOf(config: AiConfig): AiPrice? {
        val catalog = catalogFor(config) ?: return AiPriceTables.lookup(config, AiPriceTables.snapshot)
        val stored = stored(catalog)
        refreshIfStale(catalog, stored, config)
        return AiPriceTables.lookup(config, stored?.prices.orEmpty(), AiPriceTables.snapshot)
    }

    /** The catalog [config] is priced from, or null when it fetches none. */
    private fun catalogFor(config: AiConfig): String? =
        when {
            !config.configured || AiPriceTables.catalogId(config.provider) == null -> null
            config.provider == AiProvider.OPENROUTER -> OPENROUTER_KEY
            else -> MODELS_DEV_KEY
        }

    private suspend fun stored(key: String): StoredPrices? =
        mutex.withLock {
            if (key !in cached) {
                cached[key] =
                    store.get(key)?.let { runCatching { json.decodeFromString<StoredPrices>(it) }.getOrNull() }
            }
            cached[key]
        }

    private suspend fun refreshIfStale(
        key: String,
        stored: StoredPrices?,
        config: AiConfig,
    ) {
        val now = clock.nowMillis()
        val due =
            mutex.withLock {
                val fresh = stored != null && now - stored.fetchedAt < MAX_AGE_MS
                val tried = attempted[key]?.let { now - it < RETRY_MS } == true
                (!fresh && !tried).also { if (it) attempted[key] = now }
            }
        if (!due) return
        refreshing =
            scope.launch {
                log.runLogged("refresh AI model prices") {
                    val prices =
                        if (key == OPENROUTER_KEY) {
                            AiPriceTables.parseOpenRouter(source.openRouter(config.effectiveBaseUrl))
                        } else {
                            AiPriceTables.parseModelsDev(source.modelsDev())
                        }
                    check(prices.isNotEmpty()) { "the price catalog listed no prices" }
                    val fresh = StoredPrices(fetchedAt = clock.nowMillis(), prices = prices)
                    // In use at once; stored for the next session.
                    mutex.withLock { cached[key] = fresh }
                    ownWrite { store.put(key, json.encodeToString(fresh)) }
                }
            }
    }

    /** Wait for the refresh [priceOf] last started, for tests. */
    internal suspend fun awaitRefresh() {
        refreshing?.join()
    }

    @Serializable
    private data class StoredPrices(
        val fetchedAt: Long,
        val prices: AiPriceTable,
    )

    companion object {
        /** How long a fetched catalog is used before it is fetched again: a week. */
        const val MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** How long after a failed fetch the next one may start. */
        const val RETRY_MS: Long = 60L * 60 * 1000

        const val MODELS_DEV_KEY = "ai.prices.modelsDev"
        const val OPENROUTER_KEY = "ai.prices.openRouter"

        private val json = NpnJson.Persisted
    }
}
