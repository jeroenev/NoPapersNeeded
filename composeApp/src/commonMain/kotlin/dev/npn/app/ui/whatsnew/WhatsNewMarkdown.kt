package dev.npn.app.ui.whatsnew

/**
 * One block of the bundled release notes. The file is deliberately a tiny Markdown subset so it
 * stays readable in a text editor and renders without a Markdown dependency:
 *
 * - `# ` is the title, `## ` a section heading;
 * - `- ` (or `* `) is a bullet;
 * - any other non-blank line is a paragraph.
 */
internal sealed interface WhatsNewBlock {
    data class Title(
        val text: String,
    ) : WhatsNewBlock

    data class Section(
        val text: String,
    ) : WhatsNewBlock

    data class Bullet(
        val text: String,
    ) : WhatsNewBlock

    data class Paragraph(
        val text: String,
    ) : WhatsNewBlock
}

/** The parser behind [WhatsNewBlock]; pure, so the bundled file can be tested without Compose. */
internal object WhatsNewMarkdown {
    /** The one inline style the notes use: bold markers are dropped rather than shown literally. */
    private fun inline(text: String): String = text.replace("**", "").trim()

    fun parse(markdown: String): List<WhatsNewBlock> =
        markdown
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { line ->
                when {
                    line.startsWith("## ") -> WhatsNewBlock.Section(inline(line.removePrefix("## ")))
                    line.startsWith("# ") -> WhatsNewBlock.Title(inline(line.removePrefix("# ")))
                    line.startsWith("- ") || line.startsWith("* ") -> WhatsNewBlock.Bullet(inline(line.drop(2)))
                    else -> WhatsNewBlock.Paragraph(inline(line))
                }
            }.filter { block ->
                when (block) {
                    is WhatsNewBlock.Title -> block.text.isNotEmpty()
                    is WhatsNewBlock.Section -> block.text.isNotEmpty()
                    is WhatsNewBlock.Bullet -> block.text.isNotEmpty()
                    is WhatsNewBlock.Paragraph -> block.text.isNotEmpty()
                }
            }.toList()
}
