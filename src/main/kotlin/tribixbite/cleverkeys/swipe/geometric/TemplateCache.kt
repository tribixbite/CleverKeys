package tribixbite.cleverkeys.swipe.geometric

/**
 * The engine's two-tier template cache.
 *
 * **Tier A** — a bounded LRU of eager [TemplateIndex] instances keyed by
 * `(layoutFingerprint, languageCode, dictVersion)`. The fingerprint is
 * geometry-only, so dictionary identity lives entirely in the language/version key
 * fields; a fingerprint miss (edited custom layout, locale-toggled `loc` keys) OR a
 * dictVersion miss (custom word added/disabled) rebuilds cold automatically. Ceiling
 * = `config.indexCacheCapacity` indices.
 *
 * **Tier B** — per-index lazy full-template memo ([WordTemplate]): only pruning
 * survivors are ever materialized, memoized in an LRU of `config.templateMemoCapacity`
 * entries. Eager full materialization (98k × 128 B + overhead ≈ 25 MB) is explicitly
 * rejected; the memo keeps the per-index footprint at ≈ 2.3 MB (NFR-2).
 *
 * **Thread-safety**: cache mutation (put/evict/get-or-build) is synchronized on
 * [lock]; each [CachedIndex]'s Tier-B memo is an independently synchronized LRU. A
 * `decode` racing a `warmUp` for the same key either sees the previous index or takes
 * the synchronous build fallback — never a torn read. The cache spawns no threads and
 * reads no preferences (NFR-3).
 *
 * Purity (NFR-3): `kotlin.*` / `java.util.*` only.
 */
class TemplateCache(private val config: GeometricEngineConfig) {

    /** Coarse monitor guarding the Tier-A LRU map (mutations are cheap/rare). */
    private val lock = Any()

    /**
     * Tier-A LRU: access-ordered so the least-recently-used index is evicted first at
     * capacity. Guarded by [lock]; never iterated outside a synchronized block.
     */
    private val indices = object : LinkedHashMap<IndexKey, CachedIndex>(
        /* initialCapacity = */ config.indexCacheCapacity + 1,
        /* loadFactor = */ 1f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IndexKey, CachedIndex>): Boolean =
            size > config.indexCacheCapacity
    }

    /** The composite cache key: geometry fingerprint + language + dictionary version. */
    data class IndexKey(val fingerprint: String, val language: String, val version: Long)

    /**
     * A cached Tier-A index bundled with the layout + dictionary it was built from
     * (needed to lazily materialize Tier-B templates) and its own Tier-B memo LRU.
     */
    class CachedIndex(
        val index: TemplateIndex,
        val layout: LayoutGeometry,
        val dictionary: GeometricDictionary,
        private val generator: TemplateGenerator,
        private val memoCapacity: Int,
    ) {
        // Tier-B memo: ordinal → materialized template, access-ordered LRU. Guarded by
        // its own monitor so template lookups don't contend on the Tier-A lock.
        private val memo = object : LinkedHashMap<Int, WordTemplate>(
            memoCapacity + 1, 1f, /* accessOrder = */ true,
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, WordTemplate>): Boolean =
                size > memoCapacity
        }

        /**
         * The materialized [WordTemplate] for word [ordinal] — from the memo, or built
         * on demand (via projection) and memoized. `null` only for an untypeable word
         * (which the pruner never proposes, but the guard keeps this total).
         */
        fun template(ordinal: Int): WordTemplate? {
            synchronized(memo) { memo[ordinal] }?.let { return it }
            // Build outside the lock is unsafe for the LRU accounting; templates are
            // cheap (µs) so build under the memo monitor to keep the LRU consistent.
            synchronized(memo) {
                memo[ordinal]?.let { return it } // double-check after acquiring
                val built = generator.generate(dictionary.word(ordinal), ordinal, layout) ?: return null
                memo[ordinal] = built
                return built
            }
        }

        /** Current number of memoized templates (diagnostic / memory accounting). */
        fun memoSize(): Int = synchronized(memo) { memo.size }

        /**
         * Sum of the actual quantized-coordinate payload bytes across memoized
         * templates (ShortArray = 2 B/short). Used by [TemplateCache.estimatedBytes].
         */
        fun memoPayloadBytes(): Long = synchronized(memo) {
            var total = 0L
            for (t in memo.values) total += t.points.size.toLong() * 2
            total
        }
    }

    /**
     * Return the cached index for `(layout.fingerprint, dictionary.language,
     * dictionary.version)`, building and caching it synchronously on a miss (the
     * documented caller-contract-violation fallback when [warmUp] was skipped).
     */
    fun getOrBuild(layout: LayoutGeometry, dictionary: GeometricDictionary): CachedIndex {
        val key = IndexKey(layout.fingerprint(), dictionary.language, dictionary.version)
        synchronized(lock) { indices[key] }?.let { return it }
        // Build OUTSIDE the lock (a 98k pass is ~hundreds of ms; holding the coarse
        // lock would serialize unrelated layouts). A concurrent builder for the same
        // key is tolerated — the last put wins and both are structurally identical.
        val built = buildCached(layout, dictionary)
        synchronized(lock) {
            // Re-check: another thread may have built the same key meanwhile.
            indices[key]?.let { return it }
            indices[key] = built
        }
        return built
    }

    /**
     * Build (or refresh) the Tier-A index for `(layout, dictionary)` and cache it,
     * returning coverage. Idempotent for an unchanged key (returns the cached
     * coverage without rebuilding).
     */
    fun warmUp(layout: LayoutGeometry, dictionary: GeometricDictionary): WarmUpResult {
        val key = IndexKey(layout.fingerprint(), dictionary.language, dictionary.version)
        synchronized(lock) { indices[key] }?.let { cached ->
            return coverageOf(cached.index)
        }
        val start = System.currentTimeMillis()
        val built = buildCached(layout, dictionary)
        val elapsed = System.currentTimeMillis() - start
        synchronized(lock) {
            // Prefer an index another thread already cached; else install ours.
            val existing = indices[key]
            if (existing != null) return coverageOf(existing.index)
            indices[key] = built
        }
        val idx = built.index
        return WarmUpResult(
            typeableFraction = if (idx.size > 0) idx.typeableCount.toFloat() / idx.size else 0f,
            typeableWordCount = idx.typeableCount,
            buildMillis = elapsed,
            deadLayout = idx.deadLayout,
        )
    }

    /** Evict all cached indices for `(fingerprint, language)` regardless of version. */
    fun evict(layoutFingerprint: String, language: String) {
        synchronized(lock) {
            val it = indices.entries.iterator()
            while (it.hasNext()) {
                val k = it.next().key
                if (k.fingerprint == layoutFingerprint && k.language == language) it.remove()
            }
        }
    }

    /** Number of Tier-A indices currently retained (diagnostic / eviction tests). */
    fun indexCount(): Int = synchronized(lock) { indices.size }

    /** True iff an index for this exact `(fingerprint, language, version)` is cached. */
    fun contains(fingerprint: String, language: String, version: Long): Boolean =
        synchronized(lock) { indices.containsKey(IndexKey(fingerprint, language, version)) }

    /** Drop every cached index (test hygiene). */
    fun clear() = synchronized(lock) { indices.clear() }

    /**
     * Honest structural byte estimate of the WHOLE cache (NFR-2): every Tier-A index's
     * [TemplateIndex.estimatedBytes] plus each Tier-B memo's payload AND documented
     * per-entry object/map overhead. Does NOT count the caller-owned dictionary
     * strings (the index holds zero String refs by construction).
     */
    fun estimatedBytes(): Long {
        synchronized(lock) {
            var total = 0L
            for (cached in indices.values) {
                total += cached.index.estimatedBytes()
                total += memoBytes(cached)
            }
            return total
        }
    }

    /**
     * Byte cost of one index's Tier-B memo: the live payload (2 B/short) plus the
     * per-entry overhead the spec pins — array header 16 B + `WordTemplate` object
     * ~32 B + `pathLengthsKw` FloatArray (header 16 B + ≤ 2 floats) + LRU map entry
     * ~48 B ≈ 112 B/entry of fixed overhead on top of the ~128 B/variant payload.
     */
    private fun memoBytes(cached: CachedIndex): Long {
        val entries = cached.memoSize().toLong()
        return cached.memoPayloadBytes() + entries * MEMO_ENTRY_OVERHEAD_BYTES
    }

    /** Build a fresh [CachedIndex] (Tier-A + an empty Tier-B memo). */
    private fun buildCached(layout: LayoutGeometry, dictionary: GeometricDictionary): CachedIndex {
        val generator = TemplateGenerator(config)
        val index = TemplateIndex.build(layout, dictionary, config, generator)
        return CachedIndex(index, layout, dictionary, generator, config.templateMemoCapacity)
    }

    private fun coverageOf(idx: TemplateIndex): WarmUpResult = WarmUpResult(
        typeableFraction = if (idx.size > 0) idx.typeableCount.toFloat() / idx.size else 0f,
        typeableWordCount = idx.typeableCount,
        buildMillis = 0L, // cache hit — no build time
        deadLayout = idx.deadLayout,
    )

    companion object {
        /**
         * Fixed per-memo-entry overhead beyond the ShortArray payload (bytes), from the
         * spec's Cache Design: ShortArray header (16) + `WordTemplate` object (~32) +
         * `pathLengthsKw` FloatArray (header 16 + payload, negligible) + `Integer` key
         * box + `LinkedHashMap.Entry` (~48). Rounded to a documented constant so
         * `estimatedBytes()` counts overhead, not just payload (spec requirement).
         */
        const val MEMO_ENTRY_OVERHEAD_BYTES = 112L
    }
}
