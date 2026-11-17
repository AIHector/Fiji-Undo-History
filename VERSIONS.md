# Undo History version checkpoints

Version tags identify source snapshots that can be downloaded or checked out independently. The development labels are intentional; a tag is a rollback point, not a new claim of stable-release status. Historical experiments retain their original behavior.

The current implementation remains on `main`. Missing historical checkpoints were recovered on archive branches without rewriting existing published history. Recovered code was compiled against ImageJ 1.54p targeting Java 8; this does not repeat its original physical-input acceptance tests.

| Version tag | Commit | Checkpoint |
|---|---|---|
| [v1.0.0](https://github.com/AIHector/Fiji-Undo-History/tree/v1.0.0) | [dcdc3de](https://github.com/AIHector/Fiji-Undo-History/commit/dcdc3ded3f3f01363bd56daca2583bd770aef99a) | previously published version |
| [v1.1.0](https://github.com/AIHector/Fiji-Undo-History/tree/v1.1.0) | [48ca4cb](https://github.com/AIHector/Fiji-Undo-History/commit/48ca4cbb05f73616a43e3c1f3ceebfc14d0f2dca) | previously published version |
| [v1.2.0](https://github.com/AIHector/Fiji-Undo-History/tree/v1.2.0) | [cc18930](https://github.com/AIHector/Fiji-Undo-History/commit/cc1893077ab5f94e203d75956209da880a435579) | historical original 1.2.0 line |
| [v1.3.0-dev1](https://github.com/AIHector/Fiji-Undo-History/tree/v1.3.0-dev1) | [48d0c97](https://github.com/AIHector/Fiji-Undo-History/commit/48d0c972d736874b82df63951732658d5d31acb2) | historical macro creation candidate; superseded |
| [v1.3.0](https://github.com/AIHector/Fiji-Undo-History/tree/v1.3.0) | [1cc396e](https://github.com/AIHector/Fiji-Undo-History/commit/1cc396eba5db4686399d512eaea2e2c1e8663c02) | accepted selected-step macro creation |
| [v1.4.0-dev1](https://github.com/AIHector/Fiji-Undo-History/tree/v1.4.0-dev1) | [f84d94d](https://github.com/AIHector/Fiji-Undo-History/commit/f84d94db56afdaac4c38332e125dede4f7113d9e) | batch/context history actions and recording controls |
| [v1.5.0-dev1](https://github.com/AIHector/Fiji-Undo-History/tree/v1.5.0-dev1) | [686835e](https://github.com/AIHector/Fiji-Undo-History/commit/686835e036eeb26b52aede5615b730f68a6f75b2) | previous installed reference reset and control layout |

| [v1.6.0-dev1](https://github.com/AIHector/Fiji-Undo-History/tree/v1.6.0-dev1) | version tag | current installed history table, image-scoped macro journal, shared context, previews/export and configurable toolbar |

## Alternative or same-number snapshots

These tags distinguish different implementations that shared a VERSION value. They are separate development lines.

| Archive tag | Commit | Checkpoint |
|---|---|---|
| [archive/v1.2.0-working-20261111](https://github.com/AIHector/Fiji-Undo-History/tree/archive/v1.2.0-working-20261111) | [8de4797](https://github.com/AIHector/Fiji-Undo-History/commit/8de4797cce4feac5bb415f353eca7889aecfedb5) | historical working snapshot; same VERSION, different code |
| [archive/v1.3.0-replay-delete](https://github.com/AIHector/Fiji-Undo-History/tree/archive/v1.3.0-replay-delete) | [81090a2](https://github.com/AIHector/Fiji-Undo-History/commit/81090a2ab8c7ad134eff233800b362adf26e2b70) | alternative historical design; not the main 1.3.0 line |

## Returning to a version

1. Follow a tag above, then use **Code > Download ZIP**, or check out that tag in a separate local clone.
2. Build from that version’s source using its included instructions and a compatible Fiji installation. Tags preserve source, guides, startup macros and available tests; they do not automatically attach an installable jar. Historical build scripts can install directly, so save your work and quit Fiji before running a script that copies files into Fiji.
3. Preserve the currently installed plugin jar and startup macro, then replace them with the chosen build and reopen Fiji. Keep your image files and preferences. Companion plugins have independent version tags.

For a recently installed development version, an exact local jar/settings backup can also be restored with Fiji closed. Switching a Git tag alone does not change an installed Fiji.

## 1.6.0-dev1

Adds the configurable history table, default-on macro capture, source-image routing, separately labelled shared context, output-based preview reuse, image/icon export and a configurable single-row overflow toolbar. The v1.6.0-dev1 source tag preserves this tested development checkpoint; the v1.5.0-dev1 tag is unchanged. Source tags do not change an installed version by themselves. Existing explicit recorder preferences are respected. Rolling back means replacing the installed plugin with the preserved tested 1.5.0-dev1 binary while Fiji is closed; checking out a source tag alone does not change installed Fiji.
