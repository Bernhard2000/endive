# Designed audit edition

The canonical content remains in [the Markdown report](../../../../../FORK_AUDIT_2026-10-25.md).
The [PDF edition](../../../../../output/pdf/Endive_Fork_Audit_2026-10-25.pdf) is designed for
reading, printing, and presenting: cover, executive briefing, findings register, one page per
finding, validation, reproduction instructions, work packages, and a speaking outline.

`build_report.py` creates the PDF and four matching Markdown figures. Vector SVG and high-resolution
PNG versions of the figures are kept under `figures/`. It does not build Endive or rerun the audit.

Dependencies: Python 3, `reportlab`, `pypdf`, Poppler's `pdftoppm`, and the Roboto, Liberation Serif,
and DejaVu Sans Mono TrueType fonts. The default font directory is `/usr/share/fonts/truetype`;
override it with `ENDIVE_REPORT_FONTS` when necessary. Override the renderer command with
`ENDIVE_REPORT_PDFTOPPM`.

From the checkout root, with those dependencies available:

```sh
python3 compiler/docs/data/fork-audit-2026-10-25/presentation/build_report.py
```

Markdown additions are enclosed in `DESIGN` comment markers. Removing those marked additions
reproduces the original audit text byte for byte. Rebuilding is idempotent for the Markdown content.
The original report hash, current Markdown hash, rendered PDF hash, and presentation-asset hashes
are recorded in the audit manifest. The PDF has its own bookmarks and retains the audit's explicit
scope and validation limits. After a future rebuild, rerender and inspect the PDF, then refresh its
presentation hashes and layout-check record in the manifest; PDF metadata can change the file hash
even when the rendered pages look identical.
