# Reader Engine Architecture

## Goals

- Keep the existing Compose reader screen and its Google Books-style browse interaction unchanged.
- Keep all third-party reading libraries behind project-owned interfaces.
- Allow EPUB and PDF implementations to be replaced independently without changing feature UI, progress storage, bookmarks, or navigation.
- Keep the selected document URI as the source of truth. Do not copy a publication into permanent app storage.

## Stable project-owned boundaries

`engine/format-api` is the format boundary:

- `DocumentHandle`: read-only access to a selected SAF document.
- `FormatHandler`: format detection and opening.
- `BookSource`: metadata, table of contents, chapters, and resource streams.

A third-party library must be adapted to `BookSource`; it must not escape into `core:data`, `feature:reader`, or the Compose UI.

`engine/render/ReaderEngine` is the rendering boundary:

- `ReaderEngine`: page count, readiness, project-owned `Locator`, drawing, text extraction, and lifecycle.
- `ReflowEngine`: current reflow pagination implementation.
- `FixedEngine`: current fixed-layout PDF bitmap renderer.

The feature layer depends on these project-owned contracts, not on Readium or AndroidX PDF classes.

## Planned adapters

### EPUB

Use Readium Kotlin Toolkit 3.4.0 behind the existing `FormatHandler` / `BookSource` boundary. `ReadiumEpubBookSource` now opens the selected SAF URI through `AssetRetriever(ContentResolver, HttpClient)` and `PublicationOpener`. Readium owns EPUB package parsing, metadata, reading order, navigation data, and publication resource access. The project continues to own:

- chapter-to-page pagination;
- page cache and page rendering;
- the existing reader screen and page-browse interaction;
- persisted project `Locator` conversion.

Do not embed Readium's Navigator UI. Do not expose Readium `Publication`, `Link`, or `Locator` types outside the EPUB adapter.

### PDF

Keep PDF rendering behind `ReaderEngine`. The current `PdfRenderer` implementation is an adapter, not a UI contract. Evaluate AndroidX PDF and other rendering backends against the existing page-canvas contract before replacing it. Do not embed a viewer fragment if doing so changes the current Compose interaction.

### TXT and Markdown

Move text measurement and pagination toward Compose `TextMeasurer` behind the same reflow-engine boundary. The UI remains Compose either way; the pagination implementation is replaceable.

## Migration rules

1. No third-party types in `feature:reader`, `core:model`, or persisted database models.
2. Persist only project-owned locator data. Convert to/from library locators inside adapters.
3. Keep source URI permission and lifecycle handling in the data/format boundary.
4. Do not permanently extract or duplicate the original book.
5. Preserve the current full-screen reading mode, center-tap browse mode, horizontally arranged neighboring pages, and swipe navigation.
6. Replace one format backend at a time; keep a working fallback until the replacement passes import, open, navigation, resource, and lifecycle tests.

## Current migration status

- [x] Existing format and rendering contracts identified.
- [x] PDF opening readiness waits for the first requested page bitmap.\n- [x] PDF page navigation waits for the destination bitmap before changing the visible page, preserving the previous page during rendering.
- [ ] Readium EPUB adapter and compatibility tests.
- [ ] Random-access fallback policy for SAF providers that expose only streams/pipes.
- [ ] Replace StaticLayout pagination with TextMeasurer.
- [ ] Evaluate AndroidX PDF backend without changing the reader UI.
