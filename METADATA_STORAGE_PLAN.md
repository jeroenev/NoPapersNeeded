# Metadata Storage Redesign Plan

## Status

Proposed implementation plan for replacing full journal refolds with content-addressed,
incrementally applied metadata transactions.

The plan preserves these existing invariants:

- The library folder is the source of truth.
- Synced metadata files are immutable and write-once.
- The Room database is a device-local, rebuildable projection and is never synced.
- Tags retain observed-remove set semantics.
- Single-value metadata retains HLC last-writer-wins semantics.
- Document identity remains the SHA-256 of the PDF bytes.
- Existing journal data remains readable and is never rewritten during migration.

## Goals

1. Make local metadata edits independent of the total journal size and document count.
2. Prevent external sync tools from creating legitimate same-path write conflicts.
3. Never partially apply an incompletely transferred new-format transaction.
4. Ingest remote metadata without walking or hashing the PDF library.
5. Preserve every accepted edit across rapid consecutive field and tag changes.
6. Support mixed storage-v1 and storage-v2 files, and mixed bridge-or-newer clients, during upgrades,
   downgrades, and offline sync.
7. Provide explicit storage and event format versions with deterministic migrations.

## Non-goals

- Replacing the existing CRDT semantics.
- Storing metadata in mutable per-document sidecars.
- Syncing SQLite or any other mutable database file.
- Ordering events by filename or sync arrival order.
- Automatically deleting metadata history without a cross-device acknowledgement protocol.
- Physically rewriting legacy segments merely to make the folder look uniform.

## Current Problems

The current local write path performs more work as the library grows:

1. `JournalCoordinator.commitBuilt` writes a segment and calls `JournalIndex.refresh`.
2. `JournalIndex` creates a new `FoldedState` and rereads all current journal files.
3. `Indexer.applyFold` considers every document known to the journal or database.
4. The coordinator keeps its mutex while the fold and database projection complete.
5. A synced metadata arrival is routed through the same watcher path as PDF changes and can
   trigger a full filesystem rescan.

This serializes consecutive edits behind work unrelated to the edited document. It also couples
metadata latency to sync bursts, PDF enumeration, and the total amount of historical metadata.

The legacy format has additional recovery limitations:

- Sequential filenames and dots depend on a persistent single-writer device identity.
- Local sequence state is stored in the disposable cache.
- Legacy files have no complete-file digest, so a valid-looking prefix can be visible while a
  sync tool is still transferring the rest of the file.
- Malformed lines are silently skipped by `JournalCodec`.
- The `v` field is parsed but does not currently select a version-specific decoder or migration.

## Target Architecture

The synced source remains an immutable event journal. The redesign changes its container naming,
ingestion, local projection, and watcher integration.

### V2 Layout

```text
Library/
  .npn/
    journal/
      <legacy-device-id>/
        <legacy-sequence>.jsonl
        <legacy-range>.snap.jsonl
      objects-v2-00/
        <sha256>.jsonl
      objects-v2-01/
        <sha256>.jsonl
      ...
      objects-v2-ff/
        <sha256>.jsonl
```

The first byte of the file SHA-256 selects one of 256 `objects-v2-xx` directories. These remain
direct children of `.npn/journal/`, and object files remain direct children of their bucket. This
matches the traversal shape understood by the legacy reader while avoiding one unbounded folder.

The filename is the lowercase SHA-256 of the exact file bytes:

```text
<64 lowercase hex characters>.jsonl
```

No sequence, timestamp, device ID, operation type, or document ID is encoded in the filename.
File ordering has no semantic meaning.

### V2 Transaction Content

One file represents one user-level metadata transaction or one explicit bulk operation. Its
content remains JSON Lines, with one `JournalEvent` per operation. The first implementation keeps
the existing event schema version 1 so older readers can decode new object files.

For every transaction:

- Generate a cryptographically random UUIDv4 transaction actor ID.
- Set every event's `dev` and `hlc.deviceId` to that transaction actor ID.
- Assign `seq` values `1..N` within the transaction.
- Generate increasing HLC values for the operations in the transaction.
- Preserve the existing dot representation `Dot(dev, seq)`.
- Encode all lines to an immutable byte array before choosing the path.
- Calculate SHA-256 over those exact bytes.
- Write once to `objects-v2-<hash-prefix>/<hash>.jsonl`.

The random actor ID gives logically distinct events distinct dots, including repeated tag adds.
The content hash identifies and validates the file; it is not the OR-set event identity.

The transaction actor is deliberately not a persistent device writer ID. This removes sequence
recovery and cloned-device collisions from the v2 writer. Stable installation identity may still
be used for OCR sidecars and diagnostics, but is not required for v2 event uniqueness.

Changing the HLC device tiebreak for every transaction requires an actor-parameterized allocator,
not a fresh independent `HlcClock` per transaction. Store two separate values:

- Rebuildable `sourceMaxHlc`, the exact maximum HLC present in active source events.
- Device-local allocator `(physicalMillis, counter)`, advanced from remote events through the
  existing future-clock clamp policy.

Before writes are enabled, observe `sourceMaxHlc` through the clamp to seed the allocator. Every
accepted local operation increments the allocator before attaching the current transaction actor as
the HLC device tiebreak. It must receive an HLC strictly greater than the preceding accepted local
operation, even when transactions use different random actor IDs or the wall clock does not advance.

### V2 Object Invariants

1. The hash in the path must equal the SHA-256 of the complete file bytes.
2. All events must decode successfully under a supported event schema.
3. All events must use the same transaction actor in `dev` and `hlc.deviceId`.
4. Event sequences must be exactly `1..N`, and HLCs must increase in sequence order.
5. The same `(dev, seq)` must never identify different event content.
6. A transaction actor ID maps to exactly one object hash and must never be reused for different
   bytes. A second path is accepted only when it validates to the same object hash.
7. Existing identical bytes are an idempotent successful retry.
8. Hash mismatch, conflicting event identity, or structural parse failure applies zero events.
9. A valid published object is never modified, renamed, compacted, or automatically deleted.
10. A canonical object path becomes visible locally only after the complete staged bytes have been
    closed and verified.

### V2 Publication

Add a platform operation with a stronger contract than the current generic `writeBytesOnce`:

```text
publishVerifiedObjectOnce(stagingPath, canonicalPath, expectedHash)
```

The writer creates an ignored `.part` file in the target bucket, writes and closes it, reads it back
or otherwise verifies its hash, and then publishes it under the canonical hash filename without
replacement. The staging name includes the stable installation ID and a random nonce. A device
cleans only staging files carrying its own installation ID; it never deletes another device's
staging entry.

Publication requires a proven atomic create/no-replace primitive. A plain existence check followed
by `Files.move(ATOMIC_MOVE)` is insufficient because replacement behavior is provider-dependent.
Desktop may use an atomic hard-link/create primitive followed by staging cleanup when supported.
SAF may use rename only when that document provider is proven to provide atomic no-replace behavior.

Staging entries are not metadata objects, may be safely deleted by the creating device, and are
ignored by all readers. Abandoned staging entries are cleaned at startup. After publication, verify
the canonical bytes again before reporting success. If a platform provider cannot guarantee that
publication either exposes complete bytes or fails without a canonical target, storage-v2 writing
must remain disabled on that provider and the storage-v1 writer remains supported there. A
hash-invalid canonical path is corruption, not an object; it is quarantined for maintenance rather
than overwritten.

## Format Versioning

Storage layout and event schema are separate version axes and must not share one ambiguous
`CURRENT_VERSION` constant.

### Storage Versions

| Version | Detection | Integrity | Writer |
|---|---|---|---|
| Storage v1 | Legacy device directory and segment filename | Write-once assumption; no digest | Read-only; no longer produced. `SegmentWriter` retained only as a fallback for providers without verified publication and as a test utility. |
| Storage v2 | `objects-v2-xx/<sha256>.jsonl` | Mandatory full-file SHA-256 | Content-addressed writer (sole write format on every provider that supports verified publication) |

There is no mutable global format marker. A library may contain all supported storage versions at
the same time. Each file is routed to a reader by its path shape, which prevents a marker file from
becoming another sync conflict point.

Reserve the directory pattern `objects-v<positive integer>-<two hex digits>` for versioned object
stores. Discovering an unsupported object-store version must prevent metadata writes and surface
an "upgrade required" error. Known files may still be displayed from the local projection, but an
older build must not produce writes while it knows it cannot observe all metadata semantics.

Storage-v2 writing requires a reader-only bridge release first. That release recognizes v2 bucket
paths, validates the complete file hash and all lines before folding, but continues writing storage
v1. It becomes the oldest supported rollback version. Builds older than the bridge reader can fold
complete event-v1 object contents, but cannot enforce v2 all-or-nothing ingestion and are not safe
participants after v2 writing is enabled.

There is no protocol an already-released pre-bridge client understands that can exclude it from the
library. Storage-v2 writing is now unconditional on every provider that supports verified
publication (the per-library `storageV2Confirmed` opt-in and its Settings toggle have been removed);
the bridge reader shipped ahead of this change, so all supported clients recognize and validate
content-addressed objects. Pre-bridge clients are outside the supported mixed-client set.
Event-schema compatibility makes eventual recovery likely, but cannot provide the all-or-nothing
guarantee to those clients.

### Event Schema Versions

`JournalEvent.v` becomes an actual event schema selector:

- Event v1 is the existing envelope and operations.
- A missing `v` is treated as v1 only by the storage-v1 legacy reader.
- Storage-v2 objects require an explicit `v` on every event.
- An unknown event version is not decoded as the current version.
- Unknown versions quarantine the entire v2 object and disable writes until a supporting reader is
  installed.
- Unknown operation names remain forward-compatible only when their specification says older
  readers may safely ignore them. A semantic change to an existing operation requires a new event
  schema version.

An explicit unsupported event version in a storage-v1 segment also disables metadata writes for the
library. Supported lines may be projected for read-only display, but the source remains unsupported
and migration cannot reach `READY_V2`. It must not be treated as an ordinary malformed line.

Introduce version-specific wire DTOs rather than deserializing every version directly into the
current `JournalEvent` model:

```text
raw JSON
  -> detect event version
  -> decode VersionedJournalEvent
  -> apply ordered event migrations
  -> CanonicalJournalEvent
  -> CRDT reducer
```

Every migration must be pure and deterministic. It must preserve event identity, source HLC, and
document identity. It must not read local state, allocate a new HLC, or infer ordering from the
filesystem.

The versioned DTO must preserve the complete operation payload and any unknown fields needed for
future migration or collision diagnosis. `JournalOp.Unknown` cannot discard its raw payload in the
versioned decoding layer.

Suggested interfaces:

```kotlin
interface JournalEventDecoder {
    val version: Int
    fun decode(raw: JsonObject): VersionedJournalEvent
}

interface JournalEventMigration {
    val fromVersion: Int
    val toVersion: Int
    fun migrate(event: VersionedJournalEvent): VersionedJournalEvent
}
```

The registry must prove at startup that there is an unbroken migration chain from every supported
event version to the canonical version. Readers for old versions are permanent because synced
legacy files are immutable.

### Local Projection Version

Track a separate local `metadataProjectionVersion`. This is not synced and may be rebuilt at any
time. Increment it whenever raw CRDT tables or projection behavior changes.

If the projection version is old or missing:

1. Stop metadata writes.
2. Create an empty shadow accumulator and shadow effective projection generation.
3. Reingest every supported storage source through the current event migration registry.
4. Reproject effective metadata into the shadow generation.
5. Verify the shadow generation against the full-fold oracle.
6. Atomically activate the verified generation's source markers, provenance, processed events, raw
   CRDT facts, HLC state, and effective metadata, then store the current projection version.
7. Enable writes and remove obsolete staging generations.

The Room schema version remains independent. A Room migration may create the new tables, while the
projection-version rebuild populates them from the library source.

The bridge release must include an explicit Room schema migration from the current schema version 3
to the new schema, even though the bridge does not yet enable the reducer or v2 writer. It creates
empty accumulator, provenance, migration, and shadow-projection tables without dropping the existing
effective rows or `kv` state. Keeping the same Room schema through the v2-writer rollout makes
rollback to the bridge release possible. Do not rely on destructive fallback for this transition:
retaining the active projection is what allows shadow verification and safe failure. Fresh databases
and installations that legitimately take destructive fallback rebuild from the library before
enabling metadata writes. Any later Room schema bump requires a tested downgrade migration or a
tested destructive downgrade followed by a strict source rebuild before writes are enabled.

## Local CRDT Projection

The database must store enough raw CRDT state to apply events in any order without rereading old
files. Add rebuildable tables for:

- Ingested source files, including path, storage version, fingerprint, content hash, status, and
  diagnostic error.
- Processed events keyed by `(dev, seq)`, including canonical event bytes or their hash for collision
  detection and an active-source reference count.
- Transaction actors keyed to their one permitted storage-v2 object hash.
- Source-to-event provenance, because legacy originals and snapshots may contain the same event and
  legacy compaction may replace one source set with another.
- Tag additions keyed by dot, with document, literal tag, and add HLC.
- Removed tag dots as permanent tombstones.
- Tag rename and merge facts keyed by event identity and HLC.
- LWW registers for document properties, custom fields, tag styles, settings, and trash state.
- Permanent document delete tombstones.
- Relink claims keyed by event identity.
- Exact rebuildable `sourceMaxHlc`, including supported-but-ignored active events, plus separate
  device-local allocator state produced by clamped observation.
- Local migration and verification state.

Canonical event bytes use RFC 8785 JSON Canonicalization Scheme over the complete migrated envelope
and operation payload. Reject duplicate JSON object keys before canonicalization, require integers
to fit the declared event DTO ranges, and preserve unknown payload fields. Raw source-line bytes are
not suitable because legacy snapshots may re-encode the same event. Two equal identities with equal
canonical bytes are idempotent. Two equal identities with different canonical bytes quarantine their
sources, disable writes, and prevent migration completion.

The existing `documents`, `tags`, `document_tags`, `document_fields`, and FTS tables remain the
effective read model used by the UI.

For a custom field clear, the raw LWW register retains a null winning value while the effective
`document_fields` row is removed. This prevents an older set event arriving later from resurrecting
the field.

The reducer must be commutative and idempotent:

```text
reduce(shuffle(events)) == reduce(events)
reduce(events + events) == reduce(events)
```

Distinct active event identities sharing the same complete HLC indicate a broken legacy writer
identity. Reject them before reduction, regardless of operation type, so LWW registers, ordered tag
rewrites, and relink histories cannot depend on ingestion order. Quarantine the affected sources and
prevent migration completion. Repeated copies of the same event identity remain idempotent.

### Projection Scope

Most operations have bounded projections:

| Operation | Projection scope |
|---|---|
| Property or field set/clear | One document and key |
| Tag add | One document and tag |
| Tag remove | Documents owning the referenced dots |
| Tag style | One tag |
| Setting | One setting key |
| Trash, restore, or delete | One document lifecycle row |
| Tag rename or merge | Indexed connected tag-name component and affected documents |
| Relink | Connected relink component and affected documents |

Tag rewrites and relinks must retain their complete raw histories. A late event can precede an
already-ingested event by HLC, so mutating only the current winner without retained facts is not
correct. The initial implementation may conservatively recompute an entire affected tag or relink
component, but it must never reread historical journal files or scan unrelated PDFs.

## Write Path

Replace the fold-after-every-write behavior with this pipeline:

1. Serialize local and remote metadata changes through a metadata coordinator.
2. Give local commands priority over large remote-ingestion batches.
3. Read observed tag dots from the raw CRDT tables when building a remove.
4. Allocate the transaction actor and operation sequences, then obtain HLC values from the shared
   actor-parameterized watermark allocator.
5. Encode and freeze the complete transaction bytes.
6. Compute the content hash, write and verify a staging file, then publish the v2 object once.
7. Apply the canonical events, raw CRDT facts, effective projection, FTS changes, and processed
   markers in one Room transaction.
8. Publish the resulting Room flows and complete the command.

No local edit may list journal directories, read historical journal files, walk the PDF tree, or
project unrelated documents.

The durable boundary is the successful object write. If the process dies before database apply,
startup reconciliation discovers and applies the object. If database apply fails while the process
continues, queue that same frozen object for ingestion; do not create a different transaction for
the same successful write.

UI-level metadata mutexes should be removed only after the coordinator guarantees command ordering.
The UI may display an optimistic pending state so sync-provider filesystem latency does not block
further input. A failed write must remain visible and retryable rather than appearing saved.

## Ingestion Path

### Storage V2

For a candidate v2 object:

1. Route every regular file directly inside a recognized v2 bucket to the v2 reader.
2. Read bytes without accepting individual lines yet.
3. For a canonical lowercase hash filename, verify SHA-256 against the filename. For a recognized
   conflict-copy name, extract the original unambiguous 64-hex digest from the decorated name and
   validate bytes against that digest. If the original digest cannot be recovered, quarantine and
   retry rather than deriving a new content identity from a possibly incomplete prefix.
4. Decode every line and run event migrations to the canonical version.
5. Validate transaction-wide invariants.
6. Apply all previously unseen events and the source-file marker in one Room transaction.
7. Mark the object applied only after projection succeeds.

Hash mismatch or parse failure is retryable because a sync tool may still be transferring the file.
A later watcher modification or periodic reconciliation retries it. A stable repeated failure is
reported by maintenance tooling but is never partially folded.

A previously applied v2 object disappearing is not compaction. After a source-set stabilization
period, check whether another validated canonical or conflict-copy path still references the same
content identity. If none does, mark the library damaged, disable metadata writes, and ask for
restoration or an explicit authoritative rebuild that accepts the deletion. Do not silently remain
in ready state with events that can no longer be rebuilt from the library.

If a sync tool renames a valid object as a conflict copy, validate it against the recovered original
digest, ingest by event identity, and report the noncanonical path for cleanup. Do not discard valid
metadata because its filename was decorated by Dropbox, Syncthing, or another provider.

### Storage V1

Legacy files lack a complete-file digest, so retain compatibility behavior while improving retry:

- Route legacy path shapes to the v1 source reader.
- Track path, size, mtime, and a locally computed content hash as the source fingerprint.
- Reread a legacy file whenever that fingerprint changes.
- Deduplicate original and snapshot copies by `(dev, seq)`.
- Preserve tolerant handling of historical malformed lines, but record diagnostics instead of
  silently losing evidence.
- Apply valid lines idempotently. If a partially visible file later grows, its new valid events are
  applied on the next fingerprint.
- Treat an explicit unsupported event version as an unsupported source, project supported lines
  read-only if useful, and disable writes globally.

Track source-to-event provenance so legacy source removal can eventually deactivate events that no
longer exist anywhere in the source of truth. Do not unapply a removal immediately during sync
churn. First debounce the source-set change and run a journal-only reconciliation. If a valid
snapshot or replacement still references the event, only its provenance changes. If events become
unreferenced, mark the legacy device source set inconsistent, disable writes, and recompute that
device's contribution from its stable current files before returning to ready state. This preserves
the current full-refold-on-segment-set-change semantics without allowing transient sync ordering to
erase metadata from the UI.

For every stable legacy fingerprint, atomically replace that path's complete source-to-event
provenance set rather than only adding newly observed events. This handles files that shrink or are
replaced during transfer. Deactivate an event only after stabilization and only when no current
source references it, then recompute the affected device contribution before returning to ready.

## Filesystem Watch Routing

Replace the single watch-to-rescan route with path-aware dispatch:

| Changed path | Action |
|---|---|
| Any regular file in a supported v2 bucket | Validate and enqueue canonical or conflict-copy ingestion |
| Legacy journal file | Fingerprint and enqueue legacy ingestion |
| OCR sidecar | Enqueue OCR adoption |
| PDF, trash, or document directory | Debounced filesystem reconciliation |
| Unsupported object-store version | Disable writes and report upgrade requirement |
| Watch overflow or unknown path | Metadata-directory reconciliation plus filesystem rescan |

Self-write filtering becomes an optimization rather than a correctness requirement. If the watcher
reports a locally written object, processed-event and content-hash checks make ingestion harmless.

Desktop performs targeted watcher ingestion plus periodic metadata reconciliation. Android performs
metadata reconciliation at session open, resume, explicit refresh, and its existing periodic work,
because SAF does not provide the desktop watcher flow.

## Migration From Storage V1

Migration is logical and non-destructive. It converts legacy events into the current local CRDT
projection but does not rewrite or delete legacy source files.

### Migration State Machine

```text
NOT_STARTED
  -> DISCOVERING
  -> INGESTING_V1
  -> VERIFYING
  -> READY_V2

Any state may transition to FAILED and resume from processed source fingerprints.
```

The migration state is device-local and rebuildable. Reinstalling or deleting the cache simply
reruns the migration.

### V1-to-V2 Migration Algorithm

1. Open the existing database without enabling the v2 writer.
2. Discover every legacy segment and snapshot plus any already-synced v2 objects.
3. Decode v1 events through the v1 decoder and migration registry.
4. Insert canonical events, source provenance, and raw CRDT facts idempotently into a shadow
   generation in bounded Room transactions.
5. Ingest v2 objects through the strict hash-validated reader.
6. Build a shadow effective projection from the accumulated active CRDT state.
7. Compare it with a clean `FoldedState` result over all supported active v1 and v2 events.
8. Verify effective tags, properties, fields, lifecycle, tag styles, settings, relinks, delete
   tombstones, and the maximum HLC of the same active source set. Event counts are diagnostic only
   because legacy compaction may replace a history with an equivalent reduced snapshot.
9. Pause metadata ingestion and metadata-owned projection writes, coordinate with the filesystem,
   OCR, and FTS gates, and rebase any source changes observed since discovery.
10. Atomically activate one generation ID covering source markers, provenance, processed events,
   raw CRDT facts, HLC state, and effective metadata. Preserve non-metadata document and OCR columns.
11. Persist `metadataProjectionVersion` and migration state `READY_V2` in that transaction.
12. Enable the v2 writer for subsequent local edits.

Steps 3 through 5 are resumable using source fingerprints and processed event IDs. A crash cannot
lose source data because no synced file is modified.

### Migration Failure

If verification fails:

- Discard or retain only for diagnostics the shadow generation; do not publish any of its effective
  rows.
- Keep the pre-migration effective projection or transactionally restore a clean legacy fold before
  serving the session.
- Keep writing disabled or use the existing v1 writer behind an explicit temporary rollout flag.
- Preserve migration diagnostics with the mismatched documents and source files.
- Allow the user to retry after upgrading or repairing corrupt files.
- Never mark the migration complete merely because all files were enumerated.

During development and staged rollout, prefer disabling writes on migration failure. A fallback v1
writer is useful for rollback, but silently continuing normal writes after a projection mismatch
would make diagnosis harder. Never enable the fallback writer when failure is caused by an unknown
storage/event version, conflicting event identity, or other evidence that the application cannot
observe the complete source state.

### Mixed Versions and Downgrade

- New readers permanently support legacy v1 segments and snapshots.
- New writers produce only storage-v2 objects after local migration succeeds.
- Old writers may continue adding v1 segments; new readers ingest them normally.
- V2 object contents initially use event schema v1. Pre-bridge readers can decode complete files but
  are not safe rollback targets because they cannot hash-validate objects before folding.
- No dual-write is required and no logical operation is duplicated into both storage formats.
- Before rollout, compatibility tests must run both the pre-bridge reader and the bridge reader
  against complete and partial `objects-v2-xx/<hash>.jsonl` files.
- Rollback is supported only to the bridge reader or newer after v2 writing is enabled.
- The bridge release carries the same Room schema as the initial v2 writer; rollback performs a
  strict source reconciliation before enabling metadata writes.

The reserved bucket name cannot equal a generated legacy UUID device ID, so an old writer or
compactor will not mistake an object bucket for its own device directory.

### Future Migrations

For storage v3 or event schema v2:

1. Implement and ship the new reader and migration chain before enabling the writer.
2. Keep the previous readers permanently.
3. Verify old-to-current and mixed-version convergence with golden libraries.
4. Gate the new writer on successful local projection migration.
5. Use a new `objects-vN-xx` namespace for incompatible container semantics.
6. Do not replace old objects or introduce a mutable global current-version file.

If a future event schema cannot be interpreted safely by older applications, it requires an
explicit compatibility decision. The format version must not be bumped while pretending that an
older reader can silently treat the event as v1.

## Compaction and Retention

Storage-v2 objects are compacted by publishing a **supersession snapshot**: one content-addressed
object that carries the full surviving event stream of the objects it replaces plus a manifest of
their hashes. Deletion of the superseded objects follows, gated by the relaxation below. This
replaces the earlier "do not compact" stance now that snapshot-reading ships ahead of
snapshot-writing (the same bridge model used for v2 itself).

### Snapshot format and bucket

A snapshot lives in a **distinct bucket namespace** so pre-snapshot readers ignore it:

```text
.npn/journal/objects-snap-v2-<xx>/<sha256>.jsonl
```

The bucket name `objects-snap-v2-<xx>` does NOT match the `objects-v<N>-<xx>` pattern, so an older
build classifies the file as `Unknown` and skips it — it never tries to fold it (a snapshot's
multi-actor events would fail v2 transaction validation) and never treats it as an unsupported
object store that blocks writes. This is the deliberately-graceful degradation: old readers keep
reading the original objects (until those are deleted; see the relaxation) and never corrupt state.
A future incompatible snapshot layout uses `objects-snap-v3-<xx>`, which current builds surface as
`UnsupportedObjectStore` and refuse to write past.

The file bytes are JSONL:

```text
{"snap":2,"supersedes":["<hash>", ...]}   # manifest record (line 1)
{"v":1,"seq":..,"hlc":..,"dev":..,..}     # surviving events (lines 2..N), VERBATIM
```

The surviving events are the exact events (any device, any actor) from the superseded objects,
deduped by `(dev, seq)` — NOT a folded projection. So the CRDT fold of `{snapshot + newer objects}`
is byte-for-byte the same event multiset as before compaction. `SnapshotV2Reader` hash-verifies the
file, parses the manifest, and returns `Retryable` on a torn/mismatched file (never a partial fold).

### Trigger, stability window, ping-pong

During the periodic reconcile (under the `JournalCoordinator` single-writer mutex) compaction fires
when the live (non-superseded) content-addressed object count exceeds a threshold (default **64**;
constant `DEFAULT_COMPACTION_THRESHOLD`). Only objects whose mtime is older than a stability window
(default **24h**; `DEFAULT_COMPACTION_STABILITY_WINDOW_MS`) are folded, a cheap heuristic against
racing an in-flight sync. Snapshots are themselves supersedable (chain compaction), but to avoid
ping-pong we only compact when **stable non-snapshot objects alone exceed the threshold OR at least
two snapshots exist**. A single fresh snapshot below threshold never re-snapshots itself.

### Deletion and the validation relaxation

After the snapshot is published AND applied locally, the superseded object files are deleted
(best-effort, through the coordinator). `validatePublishedObjects` is relaxed for content-addressed
sources: a previously-applied source that is now missing is OK when its content hash is listed as
superseded by any **applied** snapshot (tracked in `metadata_superseded`). The hard block for a
hash-mismatched *replacement* is retained unchanged.

### Failure ordering (deletion before snapshot)

If a deletion arrives before its justifying snapshot (sync reordering), the deleted source's events
are still in the local DB (they were applied), so we treat it as **pending supersession**: a
retryable warning that does NOT block writes and is re-checked each reconcile. It escalates to the
existing hard block only if it stays unexplained past `PENDING_SUPERSESSION_LIMIT` reconciles
(default 8 ≈ a two-hour grace window at the 15-minute cadence). This narrow relaxation is required
for compaction; a genuinely deleted-with-no-snapshot object still eventually latches the write block.

### Concurrency

Two devices may compact concurrently and publish different snapshots (different hashes). Both are
valid because the event multiset union is unchanged; a later snapshot supersedes both. Ingesting two
overlapping snapshots is idempotent (event dedupe by `(dev, seq)`; supersession rows keyed by
`(snapshotPath, hash)`).

### Ingestion path

Snapshot objects are routed to `SnapshotV2Reader` and folded through the normal accumulator/refold
path (a snapshot's events already exist in the DB, so the projection is unchanged); the incremental
reconcile path is preserved for non-snapshot arrivals. The `metadata_superseded` table (schema v6)
records supersession facts; the DB is a disposable cache so its migration is a simple additive
`AutoMigration(5, 6)`.

## Implementation Phases

### Expected Code Areas

The implementation is expected to evolve these existing areas:

- `journal/JournalEventSerializer.kt` and `JournalCodec.kt` for explicit event-version decoding.
- `journal/SegmentWriter.kt` for legacy hardening only.
- `fs/NpnPaths.kt`, `FsSegmentStoreFiles.kt`, and `LibraryFileSystem.kt` for versioned path routing
  and verified object publication.
- Desktop and SAF `LibraryFileSystem` implementations for staging and publish semantics.
- `app/JournalCoordinator.kt` for prioritized incremental command serialization.
- `index/Indexer.kt` and `LibrarySessionImpl.kt` for separating metadata ingestion from PDF scans.
- `db/Entities.kt`, DAOs, `NpnDatabase.kt`, exported schemas, and an explicit Room 3-to-next
  migration for the new local projection.

New components should separate concerns rather than extend `JournalIndex` into another global
coordinator:

- Storage-format detector and source-reader registry.
- Event decoder and migration registry.
- Content-addressed transaction writer.
- Persisted CRDT reducer and affected-scope projector.
- Migration coordinator and semantic verifier.
- Filesystem watch router and metadata reconciliation queue.

### Phase 0: Baseline and Legacy Hardening

1. Add a committed golden storage-v1 library fixture.
2. Add instrumentation for journal files read, events decoded, documents projected, filesystem
   scans, edit latency, and watcher-triggered work.
3. Add crash injection after legacy segment write and before local sequence persistence.
4. Fix stale-but-non-null legacy sequence recovery so a crash cannot reuse `(dev, seq)`.
5. Add diagnostics for malformed legacy lines and changing source fingerprints.
6. Add rapid-edit and sync-storm reproductions before changing the architecture.

Exit gate: current behavior is reproducible, golden v1 data folds correctly, and legacy sequence
reuse is impossible.

### Phase 1: Versioned Readers and Migration Registry

1. Split storage-version detection from event-version decoding.
2. Add storage-v1 and storage-v2 source reader interfaces.
3. Change `JournalEventSerializer` handling so event `v` selects an explicit decoder.
4. Implement the v1 decoder and identity migration to the canonical event model.
5. Reject unsupported event and object-store versions with actionable diagnostics.
6. Add golden tests for missing v1 version, explicit v1, unknown versions, and future unknown ops.
7. Implement strict storage-v2 hash validation in reader-only mode while writes remain storage v1.
8. Add the Room 3-to-next migration and reserve the complete rollout schema.
9. Ship this bridge reader as the minimum supported rollback version before Phase 5 is enabled.

Exit gate: all current files decode through the registry with an unchanged `FoldedState` result,
and the shipped bridge reader never partially folds a v2 object.

### Phase 2: Incremental CRDT Accumulator

1. Add Room entities and DAOs for processed sources, processed events, raw CRDT facts, exact source
   max HLC, allocator state, source provenance, projection version, shadow generations, and migration
   state.
2. Verify the bridge release's Room migration preserves current effective rows and rollback state.
3. Implement a reducer that accepts canonical events in arbitrary order.
4. Implement affected-scope projection into existing effective tables and FTS.
5. Run the reducer in shadow mode while the current full fold remains authoritative.
6. Add property tests comparing shuffled incremental ingestion with `FoldedState`.

Exit gate: zero shadow divergence for generated histories, legacy snapshots, multi-device histories,
tag rewrites, relinks, null field clears, and delete tombstones.

### Phase 3: Non-destructive V1 Projection Migration

1. Implement the resumable migration state machine.
2. Populate the new accumulator from all legacy sources.
3. Verify the new projection against a clean full fold.
4. Rebuild automatically when `metadataProjectionVersion` changes.
5. Keep the v2 writer disabled until state reaches `READY_V2`.

Exit gate: deleting the local database and reopening any golden or generated v1 library recreates
the exact same effective metadata.

### Phase 4: Local Incremental Write Path

1. Initially feed events returned by the existing legacy writer directly into the reducer.
2. Remove `JournalIndex.refresh` and whole-library `Indexer.applyFold` from normal local commits.
3. Replace in-memory `FoldedState` dot lookups with raw projection queries.
4. Keep the full fold available for verification, maintenance, and recovery.
5. Introduce prioritized metadata command serialization and remove redundant viewmodel mutexes only
   after coordinator ordering tests pass.

Exit gate: a local field, property, or tag edit reads no historical segment and projects no
unrelated document.

### Phase 5: Content-addressed V2 Writer

1. Implement UUIDv4 transaction actor generation and transaction-local sequences.
2. Implement the shared actor-parameterized HLC watermark allocator.
3. Encode frozen JSONL bytes using event schema v1.
4. Implement hash calculation, bucket selection, same-bucket staging, verified publication, and
   idempotent retry validation on desktop and SAF.
5. Apply the successfully written transaction through the incremental reducer.
6. Enable the writer on every provider satisfying the verified-publication contract. (The former
   per-library confirmation opt-in has been removed now that the bridge reader is the minimum
   supported release; v2 is the unconditional write format wherever verified publication is
   supported.)
7. Retain the storage-v1 writer only as a fallback for providers that cannot meet the publication
   contract (an unknown future backend) and as a test utility for modeling remote v1 devices.

Exit gate: old-reader compatibility, new-reader validation, retry, collision detection, crash
recovery, mixed-writer, and rollback tests all pass.

### Phase 6: Watcher Routing and Remote Ingestion

1. Route journal, OCR, and document paths independently.
2. Ingest specific v2 objects without journal enumeration.
3. Fingerprint and reingest changed legacy sources.
4. Add bounded remote batches that yield to local commands.
5. Add periodic metadata reconciliation and watcher-overflow recovery.
6. Ensure journal-only changes never invoke PDF hashing or a full document rescan.

Exit gate: remote sync storms converge while local edits remain responsive and no metadata event
triggers unrelated PDF work.

### Phase 7: Remove the Steady-state Full Fold

1. Make the incremental accumulator authoritative after at least one release of shadow validation.
2. Remove folded-segment tracking from the normal path.
3. Retain legacy full fold code as a migration oracle and maintenance verifier.
4. Update README layout and architecture documentation.
5. Remove stale references to the deleted historical plan from touched code.

Exit gate: all supported libraries rebuild from source, steady-state paths are incremental, and the
full CI sequence passes.

## Testing Strategy

### Format and Migration

- Golden storage-v1 library migrated to the current projection.
- Mixed legacy segments, snapshots, v2 objects, and duplicate event copies.
- Missing v1 event version accepted only by the legacy reader.
- Unknown event version rejects the entire v2 object and disables writes.
- Unsupported event version in a legacy segment disables writes and blocks `READY_V2`.
- Unknown object-store version disables writes.
- Interrupted migration resumes without duplicate effects.
- Projection-version bump performs a complete deterministic rebuild.
- Bridge rollback reader folds storage-v2 event-schema-v1 objects and opens the rollout Room schema.
- Migration failure never records `READY_V2`.
- Pre-bridge clients are rejected by the v2 activation checklist as unsupported participants.

### Sync Faults

- V2 file appears with incomplete bytes and later completes.
- Hash mismatch never applies a valid-looking prefix.
- File create and modify watcher events are duplicated or omitted.
- Files arrive in reverse hash, HLC, and transaction order.
- Same valid object appears under canonical and conflict-copy paths.
- Conflict-copy names without a recoverable original digest never establish a new content identity.
- Legacy segment grows after an initially visible valid prefix.
- Replacing or shrinking a legacy source atomically replaces its provenance set.
- Legacy snapshot arrives before source deletions and after source deletions.
- V2 source deletion remains harmless while another validated path references the same hash.
- Watcher overflow recovers through reconciliation.

### CRDT and Concurrency

- Concurrent add/remove/re-add of the same tag.
- Remove arrives before the referenced add.
- Concurrent field set and clear from multiple transaction actors.
- Late tag rename or merge changes already-projected additions correctly.
- Relink claims arrive in every order and preserve losers.
- Duplicate `(dev, seq)` with different content is reported as corruption.
- Distinct event identities with an equal complete HLC block migration for every operation type.
- A v2 transaction actor cannot map to multiple object hashes or use a partial sequence range.
- Consecutive local transactions receive strictly increasing HLCs despite different actor UUIDs.
- One hundred rapid local edits preserve every accepted command and final state.
- Local commands remain serviceable while thousands of remote objects are queued.

### Crash Recovery

- Process death before object write creates no event.
- Process death while writing staging leaves only an ignored, own-device-cleanable `.part` file.
- Process death during publication never leaves a hash-invalid path that is treated as an object.
- Process death after object write and before database apply recovers on startup.
- Process death during Room apply rolls back and retries the complete object.
- Existing identical target is accepted only when its bytes hash to the requested path.
- Local database deletion rebuilds from mixed storage versions.
- Rollback to the bridge release performs source reconciliation before allowing a write.

## Performance Budgets

Measure budgets on representative 1,000, 10,000, and 50,000-document libraries with at least
100,000 historical metadata events.

Required steady-state properties:

- Local field/property/tag edits perform zero historical journal reads.
- Local metadata edits perform zero PDF filesystem scans and hashes.
- Work is proportional to operations and affected documents, not total history.
- A v2 watcher event reads only the changed object in the normal path.
- Remote ingestion yields often enough that a local command is not queued behind the full sync
  backlog.
- Optimistic UI state appears within one display frame.
- Filesystem persistence latency is measured separately and shown as pending if it exceeds the UI
  budget.

Set concrete p95 and p99 persistence budgets after Phase 0 records realistic desktop and Android
storage behavior. Do not hide provider stalls by dropping or coalescing accepted tag operations.

## Rollout and Rollback

Use feature flags for:

- Shadow incremental projection.
- Incremental projection as authoritative.
- Storage-v2 writing.
- Path-aware watcher routing.
- Temporary storage-v1 writer fallback.

Roll out readers and migration support before enabling the v2 writer. Because v2 initially retains
the v1 event schema and legacy directory depth, pre-bridge readers can decode complete v2 objects,
but they cannot guarantee atomic ingestion during transfer. Ship and bake the bridge reader before
enabling v2 writes, and test rollback using that complete previous application build rather than
only its decoder.

Never remove storage-v1 or event-v1 readers. Storage-v2 writing is now the unconditional write
format on every provider that supports verified publication; the per-library opt-in has been
removed. Storage-v1 writing is retained only as a fallback for providers that cannot satisfy the v2
publication contract and as a test utility. The steady-state full-fold path may still be retired.

## Definition of Done

The redesign is complete when:

1. Existing libraries migrate without modifying legacy source files.
2. Format readers and deterministic migration chains are explicit and tested.
3. V2 objects are content-addressed, hash-validated, immutable, and path-conflict-resistant.
4. Local and remote events update a persisted incremental CRDT accumulator.
5. Normal metadata changes never fold all history or scan PDFs.
6. Rapid edits remain ordered, visible, and recoverable under active external sync.
7. Unknown newer formats prevent unsafe writes and produce actionable diagnostics.
8. Cache deletion rebuilds the same state from every supported storage and event version.
9. Legacy and previous-release compatibility tests pass.
10. Detekt, core tests, Compose viewmodel tests, Android and desktop compilation, packaging, and the
    external-sync simulation suite pass.
