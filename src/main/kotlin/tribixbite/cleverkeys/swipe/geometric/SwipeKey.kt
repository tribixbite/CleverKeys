package tribixbite.cleverkeys.swipe.geometric

/**
 * One key node in a [LayoutGeometry].
 *
 * Key identity is the dense int [id] (row-major over the FINAL modified layout),
 * NOT the label — labels may repeat across a layout, and a codepoint-only key map
 * would drop multi-codepoint payloads like `لا` and collide on duplicate labels
 * (the exact defect of the live `getRealKeyPositions()` `Map<Char, PointF>`).
 *
 * Two flavours of node exist (see the Geometry & Script Abstraction section):
 *  - **letter nodes** ([isLetterNode] == true): the center label is 1+ codepoints
 *    that are all `Character.isLetter`; its codepoints enter the projection
 *    `chars` map.
 *  - **alias-host-only nodes** ([isLetterNode] == false, [label] == ""): a key
 *    with a non-letter center that nonetheless hosts a letter corner label. Its
 *    centroid is reachable via the tier-4 alias table, but its (non-letter) center
 *    does NOT enter `chars`.
 *
 * All spatial fields are in NORMALIZED [0,1]² space (fraction of the key area),
 * so they are DPI- and orientation-independent up to [LayoutGeometry.aspect].
 *
 * @param id dense, row-major key id (0-based).
 * @param label center label, `Locale.ROOT`-lowercased, 1+ codepoints; "" for
 *   alias-host-only nodes.
 * @param cx normalized centroid x ∈ [0,1].
 * @param cy normalized centroid y ∈ [0,1].
 * @param w normalized hit-box width.
 * @param h normalized hit-box height.
 * @param row source row index.
 * @param col source column index within the row.
 * @param isLetterNode false for alias-host-only inclusion.
 */
data class SwipeKey(
    val id: Int,
    val label: String,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val row: Int,
    val col: Int,
    val isLetterNode: Boolean,
)
