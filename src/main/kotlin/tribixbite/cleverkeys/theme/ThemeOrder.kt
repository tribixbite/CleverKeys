package tribixbite.cleverkeys.theme

/**
 * Stepping through Theme Manager's themes (the `theme_next` / `theme_previous` commands,
 * maintainer 2026-11-11: "theme + and - … moves and applies the currently selected theme").
 * Pure: the order itself is Theme Manager's ([tribixbite.cleverkeys.ThemeSettingsActivity.themesInOrder]).
 */
object ThemeOrder {

    /**
     * The theme id [delta] places from [current] in [order], wrapping at both ends; a [current]
     * not in the list (a deleted custom theme, an old id) starts from the first theme going
     * forward and the last going back. Null only for an empty [order].
     */
    fun step(order: List<String>, current: String, delta: Int): String? {
        if (order.isEmpty()) return null
        val at = order.indexOf(current)
        if (at < 0) return if (delta >= 0) order.first() else order.last()
        return order[Math.floorMod(at + delta, order.size)]
    }
}
