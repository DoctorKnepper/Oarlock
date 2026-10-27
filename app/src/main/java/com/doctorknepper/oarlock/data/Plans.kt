package com.doctorknepper.oarlock.data

import com.doctorknepper.oarlock.Ark

/** DEFCON tiers and the "what fits on my phone" planner. */
object Plans {
    fun coreIds(): List<String> = Ark.settings.defconCore.value ?: Ark.catalog.defaultCore
    fun fullExtraIds(): List<String> = Ark.settings.defconFull.value ?: Ark.catalog.defaultFullExtra

    fun core(): List<PackDef> = coreIds().mapNotNull { Ark.catalog[it] }
    fun full(): List<PackDef> = (coreIds() + fullExtraIds()).distinct().mapNotNull { Ark.catalog[it] }

    /** Space Oarlock may use for new downloads now: free space minus the reserve and what's already queued. */
    fun budget(): Long {
        val queued = Ark.engine.state.value.queue.filter { it.action == "install" }
            .sumOf { j -> ((Ark.engine.defOf(j)?.size ?: 0) - (Ark.store[j.packId]?.bytes ?: 0)).coerceAtLeast(0) }
        return Ark.storage.current().free() - Ark.storage.reserve - queued
    }

    private fun need(p: PackDef) = (p.size + p.extraSpace - (Ark.store[p.id]?.bytes ?: 0)).coerceAtLeast(0)

    /** True when a more detailed map of the same area is already on the phone. */
    fun covered(p: PackDef): PackDef? {
        val g = p.group ?: return null
        return Ark.store.packs.value.values.firstOrNull { it.state == PackState.INSTALLED && it.id != p.id && Ark.engine.groupOf(it) == g && Ark.engine.maxZoomOf(it) >= p.maxZoom }
            ?.let { Ark.catalog[it.id] }
    }

    data class Tier(val name: String, val packs: List<PackDef>, val ready: List<PackDef>, val fits: List<PackDef>, val skipped: List<PackDef>, val free: Long) {
        val totalBytes get() = packs.sumOf { it.size }
        val toDownload get() = fits.sumOf { it.size }
        val complete get() = fits.isEmpty() && skipped.isEmpty()
    }

    /** Which packs of a tier still need downloading, and which of those fit in free space (in priority order). */
    fun tier(name: String, packs: List<PackDef>): Tier {
        val ready = packs.filter { Ark.store.isInstalled(it.id) || covered(it) != null }
        var room = budget()
        val fits = ArrayList<PackDef>(); val skipped = ArrayList<PackDef>()
        for (p in packs - ready.toSet()) {
            if (Ark.engine.state.value.queue.any { it.packId == p.id }) continue
            val n = need(p)
            if (n <= room) { fits.add(p); room -= n } else skipped.add(p)
        }
        return Tier(name, packs, ready, fits, skipped, Ark.storage.current().free())
    }

    fun coreTier() = tier("Core", core())
    fun fullTier() = tier("Full", full())

    private val categoryOrder = listOf("Medical", "Survival", "Maps", "Books", "Repair & Skills", "Encyclopedias", "Image Packs", "Education")

    /**
     * The best set of packs that fits in [budget] bytes: DEFCON Core first, then Full, then everything else by
     * how useful it is in an emergency. Whole-library shelves are only suggested if there's lots of room.
     * For maps of the same area, the most detailed one that fits is chosen.
     */
    fun suggest(budget: Long): List<PackDef> {
        val installed = Ark.store.packs.value.keys
        val queued = Ark.engine.state.value.queue.map { it.packId }.toSet()
        val rest = Ark.catalog.packs.filter { it.id !in coreIds() && it.id !in fullExtraIds() }
            .sortedWith(compareBy({ categoryOrder.indexOf(it.category).let { i -> if (i < 0) 99 else i } }, { if (it.id.startsWith("pg-")) 1 else 0 }, { it.size }))
        val order = (core() + full() + rest).distinctBy { it.id }.filter { it.id !in installed && it.id !in queued && covered(it) == null }
        var room = budget
        val picked = ArrayList<PackDef>()
        val groupsTaken = HashSet<String>()
        for (p in order) {
            if (p.group != null && p.group in groupsTaken) continue
            val n = need(p)
            if (n <= room) { picked.add(p); room -= n; p.group?.let { groupsTaken.add(it) } }
        }
        // Upgrade grouped maps to the most detailed version the leftover room allows.
        for (g in groupsTaken) {
            val cur = picked.first { it.group == g }
            val better = Ark.catalog.packs.filter { it.group == g && it.maxZoom > cur.maxZoom }.sortedByDescending { it.maxZoom }
                .firstOrNull { need(it) <= room + need(cur) }
            if (better != null) { room += need(cur) - need(better); picked[picked.indexOf(cur)] = better }
        }
        return picked
    }
}
