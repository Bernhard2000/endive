# Designed audit edition

The canonical content remains in [the Markdown report](../../../../../FORK_AUDIT_2026-10-25.md).
The [PDF edition](../../../../../output/pdf/Endive_Fork_Audit_2026-10-25.pdf) is designed for
reading, printing, and presenting: cover, executive briefing, findings register, one page per
finding, validation, reproduction instructions, work packages, and a speaking outline.

`build_report.py` creates the PDF from the current Markdown report (fourteen findings and the
fix status of section 9) and four matching Markdown figures: the fix status, which heads the
report, and the loop, cache-key and pipeline diagrams. Vector SVG and high-resolution PNG versions
are kept under `figures/`. `build_report.py --fix-status` redraws only the fix-status figure. The
builder does not build Endive or rerun the audit.

Dependencies: Python 3, `reportlab`, `pypdf`, Poppler's `pdftoppm`, and the Roboto, Liberation Serif,
and DejaVu Sans Mono TrueType fonts. The default font directory is `/usr/share/fonts/truetype`;
override it with `ENDIVE_REPORT_FONTS` when necessary. Override the renderer command with
`ENDIVE_REPORT_PDFTOPPM`.

From the checkout root, with those dependencies available:

```sh
python3 compiler/docs/data/fork-audit-2026-10-25/presentation/build_report.py
```

Markdown additions are enclosed in `DESIGN` comment markers. Removing them gives the report text
as written, which since the first pass has grown by the second pass (F13, F14) and the fix status. Rebuilding is idempotent for the Markdown content.
The original report hash, current Markdown hash, rendered PDF hash, and presentation-asset hashes
are recorded in the audit manifest. The PDF has its own bookmarks and retains the audit's explicit
scope and validation limits. After a future rebuild, rerender and inspect the PDF, then refresh its
presentation hashes and layout-check record in the manifest; PDF metadata can change the file hash
even when the rendered pages look identical.
