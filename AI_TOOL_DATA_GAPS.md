# Library Data the AI Assistant Cannot See

## Status

Audit, not a plan. Records which indexed data the chat assistant's tools expose today and which
they do not, so the gaps are a deliberate list rather than a rediscovery every time someone asks
the assistant a question it cannot answer.

Nothing here is committed to. Each entry says what it would make answerable, so the decision to add
one is a decision about value, not a fresh investigation.

## Where the whitelist lives

`core.ai.LibraryAiTools` is the whole surface: eight read-only tools over the Room index, plus the
field vocabulary they advertise. A document field is visible to the model only if it is in one of:

- `DEFAULT_FIELD_LIST` — the built-in fields `search_fields` accepts by name. A `const` because
  `@LLMDescription` requires a compile-time constant; the name sets are parsed from it, so a field
  cannot be searchable without being advertised.
  - `USER_FIELD_LIST` — what a person typed. Also what an unqualified `"any"` search scans.
  - `SYSTEM_FIELD_LIST` — what the app maintains. Searchable by name, but **excluded from `"any"`**:
    these vocabularies are short common words, so `ocrEngine` would answer a search for "ai" with
    every AI-transcribed document. A new system field inherits this; it does not re-decide it.
- `defaultFieldValues(doc)` — the actual column reads behind a named search.
- `GetDocumentTool.execute` — the per-document report.
- `docLine(...)` — the one-line rendering of every search result.

The chat system prompt (`AiFacadeImpl.CHAT_SYSTEM_PROMPT`) interpolates `DEFAULT_FIELD_LIST` for the
same reason.

Anything reachable only through `core.app` or a DAO — not through those four — is invisible to the
assistant no matter what the user asks.

## Not in the whitelist

Ordered by how often a plausible user question hits the gap.

### 1. No way to enumerate correspondents or document types

`list_tags` exists; `list_correspondents` and `list_doc_types` do not. "Who sends me the most mail?"
and "what kinds of documents do I have?" cannot be answered without scanning the library.

`DocumentDao.correspondents()` already returns exactly this. Document types have only
`observeDocTypes()` (a `Flow`) and `docTypesForPrefix`, so this needs a one-line suspend variant.

### 2. No counts

There is no tool that answers "how many documents do I have?". Verified against the live endpoint:
the model spent six tool calls (`search_filenames`, `search_fields` ×2, `list_fields`, `list_tags`,
`search_documents`) deducing that the answer was zero.

A `library_stats` tool would answer totals, untagged count, OCR coverage and date range in one call.
`countUntagged()` and `countUnreviewedOrUntagged()` already exist.

### 3. Trashed and missing documents are unreachable

Every tool filters through `LibraryAiTools.isLive()`, so "did I throw away the Vattenfall invoice?"
and "which files went missing?" have no answer. `GetDocumentTool` does report `status: in trash` and
`status: file missing` — but only for a docId the user already has, which no search will produce.

Needs either an `includeTrashed` argument on the search tools or a separate tool.
`observeTrashed()` and `observeMissing()` exist.

### 4. Nothing time-based

`addedAt` is indexed and exposed nowhere, so "what did I add last week?" fails. This is not
`docDate`, which is the date printed *on* the document — a different question, and the one the
assistant will silently answer instead.

Unlike the other entries this is not a substring match: it wants a range, so `search_fields`'
contract does not stretch to cover it.

### 5. `ocrState`

`none | pending | done | failed | adopted`. Would answer "which documents failed OCR?" and "what is
still not OCRed?". Identical plumbing to `ocrEngine`, so it belongs in `SYSTEM_FIELD_LIST`.

Partially covered already: a document with no `ocrEngine` has no indexed text. That distinguishes
read from unread, but not `failed` from `pending`.

### 6. `reviewed`

The manual marker behind the `INBOX_WORKFLOW` review mode. "What still needs reviewing?" is a real
question in that mode, and is unanswerable. Note the marker is ignored in `UNTAGGED_ONLY` mode,
where reviewed is derived as "has any tag" — so a tool exposing it has to respect the configured
mode or it will report a fiction.

### 7. Consistency gaps in result lines

- `docType` is searchable and in `get_document`, but omitted from `docLine`, unlike every other
  built-in field that has a value.
- Tags are in `get_document` only, so seeing the tags of ten search results costs ten extra tool
  calls. Adding them to `docLine` trades tokens for round trips.
- A `fileName` field search matches the file name only, while `search_filenames` matches the name
  *and* the folder path. Same word, two meanings.

### 8. `pageCount`, `size`, `mtime`, duplicates

`pageCount` is in `get_document` but not searchable ("which documents are long?"). `size` and
`mtime` are in neither. `DuplicateEntity` rows — extra paths holding the same content hash — are
not reported by `get_document`.

Low value; listed for completeness.

## Deliberately excluded

Not gaps. These are implementation state, and exposing them invites the model to reason about
plumbing instead of documents:

- `aiEnrichedAt` — device-local spend guard, meaningless to a user question.
- `ocr_queue`, `ai_enrich_queue`, `ai_edit_jobs`, `ai_edit_job_docs` — work lists, with their
  `attempts` / `lastError` / backoff bookkeeping.
- `saved_views`, `thumbnails` — UI configuration and cache.
- `metadata_*`, `kv` — the journal projection's own state. The library folder is the source of
  truth; the assistant reads the projection, and has no business reading how it was built.

## Related

- `core/src/commonMain/kotlin/dev/npn/core/ai/LibraryAiTools.kt` — the tools and the whitelist.
- `core/src/desktopTest/kotlin/dev/npn/core/ai/SearchFieldsToolsTest.kt` — user-field behaviour.
- `core/src/desktopTest/kotlin/dev/npn/core/ai/OcrEngineFieldToolsTest.kt` — how a system field
  differs, including its exclusion from `"any"`.
