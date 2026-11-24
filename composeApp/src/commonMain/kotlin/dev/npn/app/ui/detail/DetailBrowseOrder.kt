package dev.npn.app.ui.detail

/**
 * The compact detail's browse order: the documents the library currently shows, filtered and
 * sorted as the list is, with the one the detail opened on deciding the first page. [startDocId]
 * missing from [ids] (a document opened on its own from Activity or the assistant) leaves a
 * single-page order, so the pager never claims a list it was not handed.
 */
internal class DetailBrowseOrder(
    /** The order's document ids, never empty. */
    val ids: List<String>,
    startDocId: String,
) {
    init {
        require(ids.isNotEmpty()) { "A document pager needs at least one document" }
    }

    /** The page to start on: [startDocId]'s, or the first when the order does not hold it. */
    val initialPage: Int = ids.indexOf(startDocId).takeIf { it >= 0 } ?: 0

    fun idAt(page: Int): String? = ids.getOrNull(page)

    fun hasPrevious(page: Int): Boolean = page > 0

    fun hasNext(page: Int): Boolean = page < ids.lastIndex

    companion object {
        /** [browseOrder] when it holds [startDocId], else an order of just that document. */
        fun of(
            browseOrder: List<String>,
            startDocId: String,
        ): DetailBrowseOrder =
            DetailBrowseOrder(
                browseOrder.takeIf { startDocId in it } ?: listOf(startDocId),
                startDocId,
            )
    }
}
