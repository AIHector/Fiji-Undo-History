<h1 align="center">Undo History for Fiji</h1>

<p align="center"><b>Multi-level undo and redo for Fiji / ImageJ 1.x, with a history panel that lets you jump back to any step since the image was opened.</b></p>

<p align="center">
  <img alt="Tested with Fiji 2.18 / ImageJ 1.54p" src="https://img.shields.io/badge/tested%20with-Fiji%202.18%20%2F%20ImageJ%201.54p-1f6feb">
  <img alt="Java 8 or later" src="https://img.shields.io/badge/Java-8%2B-e76f00">
  <a href="LICENSE"><img alt="MIT License" src="https://img.shields.io/badge/license-MIT-2ea043"></a>
  <img alt="Developed on macOS" src="https://img.shields.io/badge/developed%20on-macOS-555555">
  <img alt="Windows: tested by a user; Linux: test suite passes" src="https://img.shields.io/badge/Windows%20%26%20Linux-tested-2ea043">
  <a href="../../releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/AIHector/Fiji-Undo-History?color=8250df"></a>
</p>

<p align="center">
  <a href="#why">Why</a> ·
  <a href="#highlights">Highlights</a> ·
  <a href="#install">Install</a> ·
  <a href="#use">Use</a> ·
  <a href="#how-it-stores-the-history-memory-first-then-disk">Memory and disk</a> ·
  <a href="#tips-for-big-images">Big images</a>
</p>

![The Undo History window: every step of the active image, with the current state marked](docs/history-window.png)

## Why

ImageJ 1.x, the engine underneath Fiji, keeps one undo buffer. Undo reverts only the last filter or edit, and only on the image you edited last. Edit ▸ Options ▸ Memory & Threads ▸ *Keep multiple undo buffers* only gives each image its own single buffer. It does not add more levels. Undo History keeps a full history for every open image, starting from the state the image had when you opened it.

## Highlights

| Feature | What you get |
|---|---|
| **Undo as far back as you need** | **⌘Z** / **Ctrl+Z** steps back again and again; **⌘⇧Z** / **Ctrl+Shift+Z** (or **⌘⌥Z** / **Ctrl+Alt+Z**) steps forward |
| **A panel with every step** | click any step to go back to it; copy a step as a new image to compare before and after |
| **Steps become a macro** | each step keeps the macro code that repeats it, values included; **Macro** saves the steps as an `.ijm` file that runs on any image |
| **Delete a step, not just undo it** | select a step in the middle and press **Delete**: its effect is removed and the later steps run again |
| **More than pixels** | selections, overlays, display range and LUTs, calibration, the ROI Manager list and the Results table are steps too |
| **Closed by mistake?** | *Edit ▸ Reopen Closed Image* brings an image back with its whole history |
| **Light on memory** | a step that changes no pixels shares the planes of the step before; when the memory budget is full, older steps can move to disk (off until you turn it on) |
| **On when you want it** | *Start/Stop* in the window or the Edit menu: pause (the history is kept) or stop (it is forgotten, its memory and disk space freed) |
| **Works with macros** | each `run("…")` call in a macro is one step |

## Install

1. Download `Undo_History-<version>.zip` from the [Releases](../../releases) page and unzip it.
2. Copy the two files into your Fiji folder (the one that contains `plugins/` and `macros/`: `Fiji.app` on Windows and Linux; on macOS, `Fiji.app` or the folder that contains it):
   - `Undo_History.jar` → `plugins/`
   - `Undo_History_Startup.ijm` → `macros/AutoRun/` (this starts Undo History every time Fiji launches)
3. Restart Fiji.

When you update from an earlier version, replace both files: the startup macro changed in 1.2.0.

To uninstall, delete those two files.

## Three plugins that work together

Each plugin works on its own; installed together, they share the screen and each other's commands.

All three are written in Java 8 for any system Fiji runs on, and their shortcuts follow your system: **Cmd** on macOS, **Ctrl** on Windows and Linux. Undo History has been tried on Windows by a user and its test suite passes on Linux; the other two have been tested on macOS only. Reports are welcome in each repository's *Issues*.

| Plugin | What it gives you |
|---|---|
| [**Side Toolbar**](https://github.com/AIHector/Fiji-Side-Toolbar) | Fiji's toolbar as a vertical bar you can reshape freely, plus a shelf for open tools, live image previews and macro buttons |
| [**Window Layouts**](https://github.com/AIHector/Fiji-Window-Layouts) | Ten numbered window layouts, snap zones and exact saving of any arrangement, with a keyboard shortcut for each |
| **Undo History** (this one) | Multi-level undo and redo with a history panel: pixels, selections, overlays, display, calibration, ROI Manager and Results |

- **Window Layouts** keeps every layout clear of Side Toolbar's bars and status strip, and re-arranges the windows when a bar changes size or place.
- **Side Toolbar**'s image previews offer Window Layouts' placements (halves, thirds, quarters, other display, layouts 0 to 9) and Undo History's steps in their right-click menu.
- **Side Toolbar** adds an *Undo History* button to the bar when Undo History is installed, and **Window Layouts** places the Undo History window like any other tool window.


## Use

| Action | How |
|---|---|
| Step back | **Edit ▸ Undo**, **⌘Z** on macOS, **Ctrl+Z** on Windows/Linux. Each press goes back one step |
| Step forward (redo) | **⌘⇧Z** / **Ctrl+Shift+Z**, **⌘⌥Z** / **Ctrl+Alt+Z**, or **Edit ▸ Redo** |
| See every step, jump to any of them | **Edit ▸ Undo History…** and click a row |
| Select steps without moving the image | **⌘**-click / **Ctrl**-click, or **Shift**-click for a range; **Esc** unselects |
| Open steps as new images (compare before/after) | **Copy** (the selected steps, or the current one) |
| Never drop a step when space runs out | **Keep** (the selected steps, or the current one) |
| Delete steps and their effect | select them, then **Delete** (the button or the Delete key); with nothing selected, the Delete key deletes the current step |
| Forget the steps of one image, keeping the image as it is | **Clear** (with nothing selected) |
| Make a macro of the steps | **Macro** (the selected steps, or every step up to the current one) |
| Bring back an image you closed, with its history | **Edit ▸ Reopen Closed Image** |
| Pause, stop (forget every history, free its memory and disk), resume or start again | **Stop / Resume / Start** at the top of the window, or **Edit ▸ Start/Stop Undo History** |
| Settings | **Options…**, or **Edit ▸ Undo History Options…** |

Undo History starts with Fiji and shows its window; both can be turned off in the [Options](#options).

**Stop** asks whether to pause or to stop. Your images stay exactly as they are either way: only the history is affected. *Pause* keeps the history and its memory; when you resume, whatever changed in the meantime becomes one step, *Changes while paused*, so undo can still go back past it. *Stop and forget* drops the history and frees its memory and disk space. While paused or stopped, Edit ▸ Undo is ImageJ's own single undo and **⌘⇧Z** / **Ctrl+Shift+Z** opens the Channels Tool again.

**Keep** marks a step as one that is never dropped: when memory and disk are full, the oldest steps go first, but never the opened state, saved states and kept steps.

On Windows and Linux the AltGr key is reported as Ctrl+Alt, and on some keyboards AltGr+Z types a letter (ż in Polish). While a text field has the focus, Ctrl+Alt+Z is therefore left to the field: click the image first, or use **Edit ▸ Redo**.

Each of these counts as one step:

- a menu command (Gaussian Blur, Invert, 8-bit, Subtract Background, …)
- each `run("…")` call inside a macro
- each brush, pencil or flood-fill stroke (one per mouse press)
- an "Apply" from a tool window such as Brightness/Contrast
- a change that touches no pixels: a selection drawn, moved or removed ("Selection"), an overlay edit (Add Selection, Remove Overlay, labels...), the display range or LUT ("Display"), the calibration (Set Scale, Properties...), the ROI Manager's list ("ROI Manager", also from its own buttons) and the Results table (Measure, Clear Results...). Such a step shares all its pixels with the step before, so it costs only the part that changed. A run of selection changes, or of display changes, is merged into one step.

In the window, `▶` marks the current state, and grey italic rows are steps you can redo. If you undo and then make a new change, the redo steps are discarded, the same as in any editor.

After File ▸ Save, the current step is marked **[saved]**. Going back to the opened or the saved state clears the "unsaved changes" flag.

ImageJ's own undo still handles what the history does not cover, such as a selection change on an unedited image, or a paste that is still floating.

## Macros from the history

Each step keeps the macro code that repeats it. Hover a step in the window to see it.

- **A menu command** gets exactly the line ImageJ's Macro Recorder would write, with the values typed in its dialog: `run("Gaussian Blur...", "sigma=2");`. Undo History keeps ImageJ's Recorder running out of sight for this, because ImageJ's dialogs report their values only while a Recorder is running. If you open *Plugins ▸ Macros ▸ Record…*, your Recorder takes over and is read instead.
- **A macro's `run()` call** keeps the macro's own line, values included.
- **A selection** drawn with a tool: `makeRectangle(…)`, `makeOval(…)` and the like, as the Recorder writes them. ROI Manager actions and Measure are recorded too.
- **A display change** with no command: `setMinAndMax(…)`.
- **A brush or pencil stroke**: ImageJ records none, so Undo History draws a line of the brush's width along the mouse path. This is close to the stroke, but not identical, and the step is marked as approximate.
- **A flood fill**: `floodFill(x, y)`, exact.
- Steps with no code: *Changes while paused*, and edits made by a plugin without a menu command.

**Macro** writes the selected steps (or every step up to the current one) as an `.ijm` file that runs on the active image. Where it goes is set in the Options:

| Macro button | What happens |
|---|---|
| Save in the macro folder (default) | saved at once in the macro folder (`Fiji.app/macros/Undo History/` unless you choose another) |
| Ask, starting in the macro folder | a Save dialog opens in the macro folder |
| Ask, starting in the image's folder | a Save dialog opens where the image was opened from |
| Ask, starting in the last folder used | a Save dialog opens where the last macro went |
| Export | a Save dialog opens in the image's folder; the file is only exported |

Except for an export, the macro then opens in the editor (an option), and, when [Side Toolbar](https://github.com/AIHector/Fiji-Side-Toolbar) is installed, gets a button in the macro section of its bar.

## Delete a step

Each step stores the image as it was, together with the code that produced it. **Delete** uses both:

- **Deleting the current step** works like Undo, and the step is discarded.
- **Deleting an earlier step** removes its effect: the image goes back to the step before it, and each later step runs again from its macro code, becoming a new step. Select several steps to delete them together. If you delete a Crop, the later filters now run on the whole image.
- **When a later step cannot run again exactly** (it has no code, or a brush stroke that is only approximate), Undo History lists those steps and offers *Forget only*: the deleted steps' memory is freed, but their effect stays in the later steps. For approximate steps alone, it also offers to run them again anyway.
- If a step fails to run again, everything is put back as it was.
- The opened state cannot be deleted. Deleting cannot be undone.

## How it stores the history: memory first, then disk

Each step stores only the planes that changed, and unchanged planes are shared between steps. A brush stroke on 1 plane of a 500-plane stack costs 1 plane. A filter applied to the whole stack costs a full copy.

- **Memory.** By default the history may use up to **33% of Fiji's maximum memory** (Edit ▸ Options ▸ Memory & Threads). In addition, **20% of Fiji's memory always stays free** for your own work, so the history never causes an out-of-memory error. Copies are stored in small chunks, which avoids a Java effect that can double the real cost of large planes (see [Tips](#tips-for-big-images)).
- **Disk (off by default).** When it is on and memory is full, older steps move to a disk folder, and they are read back when you undo to them. The folder is the system temp folder unless you choose another, and the history may use up to **33% of the free space** on that disk. At least 5 GB always stays free there. A fast SSD (internal or USB-C/Thunderbolt) makes a big difference.
  - The first time memory runs out and a change cannot be recorded, Undo History asks whether to keep older steps on disk. Answer *Keep older steps on disk* and the change is recorded at once; the choice is saved in the Options.
- **When both are full,** the oldest steps of any image are dropped first. The opened state, saved states and steps you marked **Keep** are never dropped. If even that is not enough, the change is not recorded and the window says so in red.
  - In that case, Undo asks before going back, because going back would throw your latest, unrecorded changes away. You can cancel, then save a copy or give the history more room.
- **Not tracked:**
  - images larger than the memory plus disk budget
  - virtual stacks (their planes already live on disk)
  - batch-mode images that are never displayed
- **Cleanup.** Spill files are deleted when an image is closed, when you stop Undo History, and when Fiji quits. Folders left behind by a crash are cleaned at the next start.

The footer of the window shows how much memory and disk the history is using.

### Options

*Edit ▸ Undo History Options…*

| Option | Default |
|---|---|
| Memory for history | 33% of Fiji's memory |
| When memory is full, keep older steps on disk | off |
| Disk folder | system temp folder `/FijiUndoHistory` |
| Disk space for history | 33% of the free space on that disk |
| Redo also with ⌘⇧Z / Ctrl+Shift+Z | on |
| Start a new history when an image is saved | off |
| Record from the moment Fiji starts | on |
| Show the Undo History window when Fiji starts | on |
| Macro button | Save in the macro folder |
| Macro folder | `Fiji.app/macros/Undo History/` |
| Open a new macro in the editor | on |

Everything is recorded while Undo History is on: pixels, selections, overlays, display, calibration, the ROI Manager list and the Results table. There is no limit on the number of steps; the memory and disk budgets are the only limits.

**⌘⇧Z / Ctrl+Shift+Z.** ImageJ uses that key to open *Image ▸ Color ▸ Channels Tool…*; Undo History gives it to *Edit ▸ Redo*, and the Channels Tool still opens from its menu. If you prefer the old key, untick *Redo also with ⌘⇧Z* (⌘⌥Z / Ctrl+Alt+Z still redoes). Changes made in the Channels Tool (mode, colours, ticked channels) are *Display* steps, so they are undone and redone like any other step.

**Start a new history when an image is saved.** Off: you can go back past a save, to the state the image had when it was opened. On: saving forgets the steps before it.

## Performance with big images

These numbers were measured on an Apple Silicon Mac with 24 GB of RAM and Fiji's memory set to 18 GB. The test images are 16-bit stacks of 2048×2040 planes. Each run records two whole-stack filters and one brush edit, then undoes everything; every restore was checked pixel for pixel.

| Image | History kept | Recording a whole-stack step | Undo / jump to a step |
|---|---|---|---|
| 1 GB | 3 steps, all in memory | 0.8 s | 0.1–0.4 s |
| 2 GB | 3 steps (6 GB memory + 71 MB disk) | 1.5–1.9 s | 0.6–0.9 s |
| 4 GB | 3 steps (6 GB memory + 6.2 GB disk) | 6.3–7.8 s | 1.5–2.1 s |
| 6 GB (history memory set to 20%) | opened state + 1 whole-stack step. The 2nd was refused (12.8 GB disk budget full) and reported | 12–15 s | 1.2–5.7 s |

A brush stroke is recorded in about 0.5 s at every size: there is a 0.3 s wait for the stroke to end, and the check itself takes about 1 ms, because on stacks over 256 MB only the touched planes are compared.

- **What runs in the background.** Recording happens on a background thread, and brush strokes and the interface stay responsive. A new menu command waits until the previous step has been recorded.
- **What limits the time.** Most of the time goes into disk writes. Writes reached 1 GB/s normally, but only 0.3 GB/s while macOS was swapping, because Fiji's memory setting plus open apps exceeded the physical RAM.
- **How big images can get.** The limit comes from memory plus disk space, not from the plugin. Each whole-stack step of a 6 GB image needs 6 GB somewhere. For images of several GB, point the disk folder to an external SSD with plenty of space.

### Cost of the non-pixel steps

Measured with the test suite (Apple Silicon Mac, Java 21):

| Change | Memory per step |
|---|---|
| Selection, overlay edit, display range, calibration | no pixel memory (the planes are shared with the step before) |
| ROI Manager with 5000 ROIs: first copy | about 1.6 MB, once |
| ...then each added or changed ROI | about 80 KB (one ROI plus the list of references) |
| Results table of 20000 rows × 20 columns: first copy | about 3.1 MB, once |
| ...then each new measurement | about 85 KB (one 1024-row block per column) |
| Reopening a closed image, a command replacing an image | nothing extra (the steps are the ones already stored) |

## Tips for big images

- **Some plane sizes cost more heap than their size in Fiji, and there is no free fix.** With an 18 GB heap, Java's default G1 collector reserves whole 16 MB regions for planes of 8 MB or more. 2048×2048 16-bit planes take 2.0× their size, 2304×2304 take 1.6×, and 4096×4096 16-bit take 1.5×. Smaller planes are unaffected. Undo History's own copies avoid this (they are stored in small chunks), but the images themselves are not. A churn test (keep 6 GB of 1 GB stacks, create and drop 40 more) compared the alternatives:

  | JVM setting | 2048² 16-bit cost | Time | Longest pause | Memory returned to the OS |
  |---|---|---|---|---|
  | G1 (Fiji default) | 2.0× | 3.8 s | 13 ms | yes |
  | `-XX:G1HeapRegionSize=32m` | 1.0× | 8.6 s | 1.1 s | yes |
  | `-XX:+UseZGC -XX:+ZGenerational` | 1.25× (but 2048² 8-bit 1.5×) | 4.3 s | < 1 ms | only after ~5 min |
  | `-XX:+UseParallelGC` | 1.0× | 8.3 s | 1.3 s | no |

  Fiji's default is the best all-round choice. Change it only if you always work with one plane size and are short of memory rather than time.
- **Don't give Fiji more memory than the machine can spare.** If Fiji's maximum plus your other apps exceed physical RAM, macOS swaps and everything slows down, history included.
- **Use Keep on important steps** so they survive when space runs short.

## What it does not track

- **Plugins with their own data and undo** (SNT paths, TrackMate tracks, Labkit labels, the 3D Viewer). Those keep their own undo.
- **Results tables other than the main "Results" window** (for example tables named by a plugin), and the ROI Manager's selection or display options.
- **Commands that make a new image from an image that stays open** (Duplicate, Z Project, Split Channels...): the new image starts its own history, the original keeps its own.

Commands that **replace** an image by a new window with the same title (Make Composite, for example) are followed: the new window continues the old window's history. Undoing past that step brings the old kind of image back in its place.

**Closed images.** The last 5 images you closed keep their whole history, so *Edit ▸ Reopen Closed Image* brings an image back with all its steps. Their memory is the first to be given back when the history needs room, and a sixth closed image pushes out the oldest one.

**Shared windows.** The ROI Manager and the Results table belong to no single image: a change to them becomes a step of the active image. Undo restores them only when they changed within that image's own history.

## Build and test

You need a Fiji installation; its bundled Java is used.

```bash
./build.sh /path/to/Fiji.app            # builds build/Undo_History.jar and installs it + the startup macro
./build.sh /path/to/Fiji.app --no-install
./test.sh  /path/to/Fiji.app            # end-to-end test in a separate ImageJ (opens a few windows briefly)
./bench.sh 1024 2048 4096               # size ladder in MB (needs RAM; HEAP=18g and PCT=20 are optional)
```

`test.sh` checks 133 behaviours:

- menu and macro commands
- brush strokes
- multi-level undo, redo and branch truncation
- stacks with plane sharing
- type conversion
- save marks
- memory limits and dropping
- the disk tier: exact restores from disk, hash detection of unchanged planes
- composite images, including the channels ticked in the Channels Tool
- the optional ⌘⇧Z / Ctrl+Shift+Z redo key, taken from the Channels Tool and given back
- the prompt before discarding unrecorded changes, and retrying an image that had no room when it opened
- no wasted re-check after an undo
- brush strokes on a 300 MB stack checking only the touched plane
- a command run the instant an image opens
- the window
- selections, overlays, display range and calibration as steps (no pixel memory; a 300-plane stack's selection step takes 0.1 ms and reads no planes)
- the ROI Manager list and the Results table as steps, restored by undo
- closed images reopened with their history, and closed images giving their memory back first
- Make Composite: the new window continues the history, and undo brings back the RGB image
- no step limit (105 commands keep 105 steps)
- memory full with the disk off: the disk is offered, and accepting records the refused change
- Stop: every history forgotten, memory and spill folder freed, Undo and Ctrl+Shift+Z back to ImageJ; Start again, and the startup macro's option
- each step's macro code: menu commands, macro commands with their values, display changes, brush strokes; a command that changes nothing (Duplicate) leaves no code behind
- Create Macro: the file is saved, and running it on the opened image gives the same pixels
- Delete: an earlier step's effect is removed by running the later steps again (pixels checked); the current step; *Forget only*
- Pause and Resume: *Changes while paused*, undo back past it
- Plugins ▸ Macros ▸ Record…: the user's Recorder takes over and is read, the hidden one comes back when it closes

Tested with ImageJ 1.54p and Java 21 (Fiji 2.18) on macOS; the test suite also passes on Linux (ImageJ 1.54f, Java 21). A user has tried it on Windows. The jar targets Java 8. Reports are welcome.

## License and credits

MIT License, © 2026 Ettore Colombo. See [LICENSE](LICENSE).

Developed with the help of Claude (Anthropic), from the author's design and requirements, then tested on real data.
