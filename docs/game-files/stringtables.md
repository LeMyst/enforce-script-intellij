---
title: Stringtables
parent: Game files
nav_order: 4
---

# Stringtables

**Stringtable editor** — a `stringtable.csv` opens as text with a *Table* tab next to it: one
row per key and one column per language, named from the file's own header, with the key and
`original` columns kept on the left while the languages scroll. Editing a cell edits the text, so
undo, the text tab and version control all see the change, and only that cell's characters change.

## What still needs translating

**What still needs translating stands out:** an empty cell, and a cell that still holds exactly
the `original` text (never `english`, which normally equals it), each with its own background
and a tooltip. Each language's header shows its coverage (`french 412/430`), and *Show* lists
only the rows missing a translation in one language. A search field filters by key or text.

## Keys and languages

**Add Key…** writes a correctly quoted row from one field per language. The key starts with the
prefix most of the file's keys share and must be new in every stringtable, ignoring case. **Add
Language…** adds a column to the header and every row, empty or filled from `original`;
**Remove Language…** takes one out; **Fill from Original…** copies `original` into the empty
cells of a language for a first pass.

## Changing original

**Changing `original` updates its copies:** the cells of that row that still held the old
`original`, `english` included, follow it in the same undoable edit, while real translations are
left alone and tinted *original changed since this was translated* for the rest of the session.
A notification counts both. *Update Copies of Original* in the toolbar turns this off.

## Text and table

*Show in Text* opens the selected row in the text tab; switching back to *Table* selects the row
under the caret.

## Malformed rows

**Malformed rows are errors** in the text: a row with more or fewer cells than the header (which
shifts every translation after it into the wrong language), an unclosed quote, text after a
closing quote, and a duplicate key. The table marks the key of such a row in red. Two keys
differing only in case are not reported as duplicates, though the game takes them for one key:
the vanilla stringtable itself has three such pairs.

## From the code

**From the code:** *Localization key with no stringtable row* offers *Create stringtable row
for 'STR_…'*. Only `STR_…` keys are reported: most other `"#…"` strings are keys too, but not
surely. The row goes into one of the project's own stringtables, never a library's.

When the file's own addon (the nearest folder with `config.cpp`) has one, that is used directly.
Otherwise a list offers every stringtable of the project, plus a new `stringtable.csv` in the
file's addon. It is ranked by the new key's prefix, so for `STR_AUTORUN_…` the table already
holding `STR_AUTORUN_` keys comes first. A new stringtable gets the vanilla header. The caret
lands in the new row's `original` cell.

Ctrl+Click on a key goes to its row: see [String references](../scripts/string-references.md).

## Not supported

Column colouring and the column name at the caret in the text tab, and translation hints on
`"#STR_…"` strings. The *original changed* tint is not saved: it is gone after a restart, since the
CSV has no room for it. A cell holding a line break shows it as `⏎` in the table and is edited on
one line. The IDE writes a file back with one line separator throughout, so in a CRLF stringtable
that has line breaks *inside* cells (the vanilla one has 250), saving turns those into CRLF too.
Library stringtables, such as the vanilla one, open read-only.
