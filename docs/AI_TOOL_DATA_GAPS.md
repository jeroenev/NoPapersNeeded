# Library Data the AI Assistant Cannot See

## Status

Audit, not a plan. Records which indexed data the chat assistant's tools expose today and which
they do not, so the gaps are a deliberate list rather than a rediscovery every time someone asks
the assistant a question it cannot answer.

Checked against `main` on 2026-11-03.

Nothing here is committed to. Each entry says what it would make answerable, so the decision to add
one is a decision about value, not a fresh investigation.

## Where the whitelist lives

`dev.npn.core.ai.LibraryAiTools` is the chat assistant's whole library-data surface: eight read-only
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

There is no tool that answers "how many documents do I have?" Search tools return at most 15
(`search_documents`) or 50 (the document-listing tools) results and provide neither a total nor a
continuation token. `list_tags` returns a live-document count per tag, and `list_fields` a
live-document usage count per field key, but neither is the library total. The model therefore
cannot reliably count all documents or all matches.

A `library_stats` tool could answer the live-document total, untagged/review-queue totals, OCR
coverage, and added/document-date ranges in one call. `DocumentDao.countUntagged()` and
`countUnreviewedOrUntagged()` already provide two of those values; nothing counts an arbitrary
`LibraryFilter`.

### 3. Trashed, missing, and deleted documents cannot be discovered

Every search and document-listing tool excludes rows that are trashed, missing, or tombstoned as
deleted, either through a DAO query or `DocumentEntity.isLive` (the `LIVE_DOCUMENT` predicate).
Consequently, "did I throw away the Vattenfall invoice?" and "which files went missing?" have no
answer. There are two narrow exceptions for a docId obtained earlier: `get_document` can fetch any
row and reports `status: in trash`, `status: file missing` and `status: permanently deleted`, while
`get_document_text` can still return retained FTS text.

Discovery needs lifecycle arguments on the search tools or separate lifecycle tools.
`DocumentDao.observeTrashed()` and `observeMissing()` already provide the dedicated lists; deleted
rows would need an additional query. Trash reporting should also decide whether to expose
`trashedOrigPath`, which `get_document` currently omits.

### 4. No import-time search

`addedAt` is an indexed `documents` column but no assistant tool accepts or returns it, so "what did
I add last week?" fails. This is not `docDate`, the date printed *on* the document; using that field
would answer a different question.

Unlike most field searches, this needs an epoch-millisecond range. `search_fields` has `from`/`to`
for date and number *fields*, but `addedAt` is a document column, not a field.
`LibraryFilter.addedFrom`/`addedTo` and the corresponding SQL in `DocumentRepositoryImpl` already
implement the live-document range semantics outside the assistant.

### 5. `ocrState`

`none | pending | done | failed | adopted` (`OcrState`). Exposing it would answer "which documents failed OCR?"
and "what is still not OCRed?". It could use plumbing like `ocrEngine`: another app-maintained
document column, searchable by name and kept out of `"any"`.

Partially covered already: `ocrEngine` identifies the engine recorded for the indexed sidecar,
including `embedded`, but it does not distinguish `none`, `pending`, or `failed`. In the current
pipeline, a null `ocrEngine` does mean that no OCR/embedded sidecar text has been indexed yet; it
does not explain why or whether work is pending.

### 6. `reviewed`

The manual marker behind the `INBOX_WORKFLOW` review mode is not exposed. "What still needs
reviewing?" is therefore unanswerable. The configured `reviewMode` is also absent from the tool
surface. This matters because the stored marker is ignored in `UNTAGGED_ONLY` mode, where reviewed
is derived as "has any tag"; a review tool must read the mode and apply the same queue predicate as
the app. In `INBOX_WORKFLOW`, that predicate is **unreviewed or untagged**, not merely
`reviewed = false`.

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

The app's `DocumentRepository.history(docId)` exposes a document's history (domain
`HistoryEntry`s decoded from its journal events, in HLC order), but no assistant tool does.
Questions such as "when was this tagged?" or "what changed on this document?" are therefore
unanswerable. This is user-facing history, unlike projection-source
bookkeeping; exposing it would need careful output bounds because the event list grows over time.

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
- [ARCHITECTURE.md](../ARCHITECTURE.md) §6.8, "Fields are data, not code" — the field registry the
  tools read.
