# Library Data the AI Assistant Cannot See

## Status

Audit, not a plan. Records which indexed data the chat assistant's tools expose today and which
they do not, so the gaps are a deliberate list rather than a rediscovery every time someone asks
the assistant a question it cannot answer.

Checked against `main` on 2026-11-22. Gaps 2–6 and 10 are now covered; the rest are still open.

Nothing here is committed to. Each entry says what it would make answerable, so the decision to add
one is a decision about value, not a fresh investigation.

## Where the whitelist lives

`dev.npn.core.ai.LibraryAiTools` is the chat assistant's whole library-data surface: ten read-only
tools over the Room index, plus the field vocabulary they advertise. Every registered field is
shown by `list_fields`, and so is every stored key without a definition, as an orphan.
`search_fields` and `get_document` read `document_fields` directly, so they handle registered and
orphan values without a per-field code change; a user-defined field is searchable from the next
chat turn after it exists.

The registered-field vocabulary is rebuilt per turn (`LibraryAiTools.fieldVocabulary(fields)`) in
the tool descriptions and chat system prompt. Orphan keys are discovered only by calling
`list_fields`. The `@LLMDescription` annotations stay generic because annotation arguments must be
compile-time constants.

What is *not* a field still has to be whitelisted by hand:

- `FILE_NAME_FIELD` (`fileName`) — a document column scanned by `"any"` searches like a user
  field.
- `PATH_FIELD` (`path`) — the library-relative path, searchable by name; `"any"` does not scan it.
- `OCR_ENGINE_FIELD` (`ocrEngine`) — what the app maintains. Searchable by name, but **excluded
  from `"any"`**: its vocabulary is short common words, so it would answer a search for "ai" with
  every AI-transcribed document. Adding another system column does not expose it automatically;
  its search and `"any"` semantics require an explicit decision.
- `GetDocumentTool.execute` — the per-document report, including the optional
  `ocrEnginePinnedByUser` value for a user-selected OCR engine.
- `docLine(...)` — the one-line rendering of every search result (still names title, date and
  correspondent by key).

Anything reachable only through `dev.npn.core.app` or a DAO—not through these tools—is invisible
to the assistant no matter what the user asks.

## Not in the whitelist

Ordered by how often a plausible user question hits the gap.

### 1. No way to enumerate or rank a field's values

`list_tags` exists; there is no `list_field_values`. "What kinds of documents do I have?" cannot be
answered directly. `search_fields` with an empty value lists documents, not distinct values, and is
capped at 50 results. There is likewise no way to enumerate folders.

`FieldRegistry.distinctValues(key)` and `DocumentFieldDao.distinctValues(key, limit)` already
provide a bounded distinct-value list for any field key, so simple enumeration would be a thin
wrapper. They do **not** return counts per value. Answering "who sends me the most mail?" or ranking
document types needs a grouped live-document count, not just that wrapper.

### 2. No library or match totals, and no pagination

**Covered.** `library_stats` reports the live-document total, the untagged count, the review-queue
count for the library's review mode, OCR coverage per state, the added and document-date ranges,
and the trashed/missing/deleted totals. `search_documents` says how many matches it left out beyond
its 15. There is still no continuation token: a question that needs the 16th match has to narrow
its filters instead of paging.

### 3. Trashed, missing, and deleted documents cannot be discovered

**Covered.** `search_documents` takes a `lifecycle` argument (`live` by default, else `trashed`,
`missing` or `deleted`), so "did I throw away the Vattenfall invoice?" and "which files went
missing?" are answerable, with each result carrying its docId and status. `library_stats` gives the
same totals. Trash discovery deliberately does not expose `trashedOrigPath`.

### 4. No import-time search

**Covered, with the honest limit the schema now has.** `search_documents` takes `addedAtFrom` /
`addedAtTo` and results carry their added date. Note that `documents.addedAt` was dropped in index
v28: import time is no longer stored. "Added" therefore means what the library's own `added` sort
means — the PDF's own creation date, or the file's modification time when the PDF names none — not
the date printed on the document. It is distinct from `docDate`, but it is not a record of the
import.

### 5. `ocrState`

**Covered.** `search_documents` takes `ocrState` (`none | pending | done | failed | adopted`), and
`library_stats` reports live counts per state. `ocrEngine` remains the separate question of which
engine's text is indexed.

### 6. `reviewed`

**Covered.** `search_documents` takes `reviewed`, interpreted the way the review screens interpret
it: the stored marker in INBOX_WORKFLOW, "has any tag" in UNTAGGED_ONLY. `library_stats` reports the
review queue with the mode named, so the model sees which predicate produced the count.

### 7. Consistency gaps in result lines

- Generic `docLine` results always include cached title, document date (rendered as `date`) and
  correspondent when present. Every other field, including `docType` and user-defined ones, appears
  in a result line only when it is the matched field of a `search_fields` hit. `get_document` is
  required to see the complete field map.
- The complete tag set is available only from `get_document`. `documents_with_tag` establishes
  membership in the one requested tag, but generic search results carry no tags; seeing all tags of
  ten results costs ten extra calls. Adding them to `docLine` trades tokens for round trips.
- `search_filenames` matches a substring of either the base name or `relPath`; `search_fields`
  searches them apart, as `fileName` (aliases `file`, `filename`, `name`) and `path`.

### 8. Physical-file facts and duplicates

`pageCount` is returned by `get_document` when known but is not searchable ("which documents are
long?"). `size` and `mtime` are neither returned nor searchable. `DuplicateEntity` rows—extra
paths holding the same content hash—are not reported by `get_document`, and there is no
duplicate-listing tool. `DocumentRepository.observeDuplicates()` already exposes those rows to the
app.

Low value; listed for completeness.

### 9. Field and tag definitions are only partially visible

`list_fields` exposes each field's key, label, type, usage count, and `showByDefault = true` state
(as "shown on every document"). It omits the field's `hint`, `aiEnabled` and `perDocument`
settings. A hint can be important for interpreting a user-defined value, while `aiEnabled` answers
configuration questions such as "which fields may automatic enrichment fill?" Registry position is
conveyed only by output order.

Likewise, `list_tags` exposes names and counts but omits the stored color and icon. These omissions
may be intentional token/presentation choices, but they should not be mistaken for complete
definition metadata.

### 10. No document history

**Covered.** `get_document_history` returns the newest 20 journal events recorded against one
docId — tag changes, field edits, review marks, OCR engine pin, trash/restore/delete — with values
truncated and older events only counted. It reads only that document's events, so another
document's content cannot appear. An import itself leaves no journal event, so the tool says so
rather than inventing one.

## Deliberately excluded

Not gaps. These are implementation state, and exposing them invites the model to reason about
plumbing instead of documents:

- Everything in `device.db`: `ai_enriched` (the device-local spend guard), `ocr_queue`,
  `ocr_page_cache`, `ai_enrich_queue`, `ai_edit_jobs`, `ai_edit_job_docs` (work lists, with their
  `attempts` / `lastError` / backoff bookkeeping), and its `kv` (device id, chats). None of it is
  library data, and it is meaningless to a user question.
- Saved views and auto-match rules (journal settings; `auto_match_rules` in the index only caches
  them) and `thumbnails` — UI/automation configuration and cache.
- `metadata_sources`, `metadata_source_events`, `metadata_transaction_actors`,
  `metadata_projection_state`, `metadata_superseded`, `fts_keys`, and the index's `kv` — the
  journal projection's own source tracking and local state. The library folder is the source of
  truth; the assistant reads the projection and has no business reasoning from how it was built.
  `metadata_events` is not in this exclusion because its decoded per-document subset powers the
  user-facing history gap above.

## Related

- [`LibraryAiTools.kt`](../core/src/commonMain/kotlin/dev/npn/core/ai/LibraryAiTools.kt) — the tools
  and the whitelist.
- [`LibraryAiToolsConsistencyTest.kt`](../core/src/desktopTest/kotlin/dev/npn/core/ai/LibraryAiToolsConsistencyTest.kt)
  — live-only counts and results, the `path` field, the deleted status.
- [`SearchFieldsToolsTest.kt`](../core/src/desktopTest/kotlin/dev/npn/core/ai/SearchFieldsToolsTest.kt)
  — user-field behaviour.
- [`OcrEngineFieldToolsTest.kt`](../core/src/desktopTest/kotlin/dev/npn/core/ai/OcrEngineFieldToolsTest.kt)
  — how a system column differs, including its exclusion from `"any"`.
- [`LibraryStatsAndLifecycleToolsTest.kt`](../core/src/desktopTest/kotlin/dev/npn/core/ai/LibraryStatsAndLifecycleToolsTest.kt)
  — the stats, filter and bounded-history tools.
- [ARCHITECTURE.md](../ARCHITECTURE.md) §6.8, "Fields are data, not code" — the field registry the
  tools read.
