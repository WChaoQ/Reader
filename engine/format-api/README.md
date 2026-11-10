# Format API contract

This module is the only format-neutral boundary between document storage and format adapters.

## Ownership

- `DocumentHandle` is a borrowed, read-only description of the user's original document. It does not copy or persist the file.
- Every `openInputStream()` / `openFileDescriptor()` call returns a new handle owned by the caller. The caller must close it.
- `FormatHandler` is stateless and reusable. It must not retain per-document mutable state.
- `FormatHandler.open()` creates one independent `BookSource`. On success, ownership of all adapter resources transfers to that source.
- If `FormatHandler.open()` fails or is cancelled before returning a source, the handler must close every partially acquired resource before propagating the failure.
- `BookSource.close()` is idempotent and releases every resource it owns. The caller closes a successfully returned source exactly once.
- Streams returned by `BookSource.openResource()` are caller-owned and must be closed by the caller. A source must not close a stream after returning it.

## Index and content semantics

- `chapterCount` is non-negative.
- Chapter indexes are zero-based and valid in `0 until chapterCount`.
- `loadChapter(index)` returns a `Chapter` whose `index` equals the requested index. Invalid indexes fail explicitly; adapters must not silently clamp.
- A `Chapter` is a format-neutral semantic tree. It must not expose Readium, PDFium, parser AST, Android View, Compose, or database types.
- Paragraph and Heading spans use UTF-16 code-unit offsets into the exact final text string, with half-open ranges `[start, end)`.
- Resource references are document-relative identifiers, not filesystem paths or network URLs.
- TOC locators use the project's `Locator`; adapter-native locator objects must remain private to the adapter.

## Concurrency and cancellation

- `metadata`, `toc`, `loadChapter`, and `openResource` may be called from different coroutines while a source is open.
- An adapter backed by a non-thread-safe library must serialize access internally; it must not rely on callers to know the third-party library's locking rules.
- Long-running operations must be cancellable. `CancellationException` must propagate unchanged.
- A source must remain valid until its owner closes it. Closing a source must not race an in-flight operation; the owner is responsible for cancelling and joining session work first.
- Format handlers must not perform network access or materialize a private full-document copy.

## Detection

- `canHandle` must be fast, deterministic, and side-effect free. It may inspect the filename, MIME type, and bounded magic bytes only.
- `FormatRegistry` rejects ambiguous matches rather than depending on set iteration order.
- When an expected format is supplied, the selected handler must match it. The returned source format must equal the handler's declared format.
- Empty, corrupt, unsupported, or mismatched documents fail explicitly; they must not produce a fake empty source.

## Compatibility

Changes to these rules require updates to this document, the architecture baseline, and contract tests. Third-party types must never cross this API boundary.
