# ADR-0051: Where a mission's deliverables land, and a file viewer panel

Date: 2026-11-02 · Status: Accepted (the director approved the two new event types and their point of no return)

## Context
After a document mission, the director said: "when we make a deliverable it does not put it in the project folder". Measured on 1.9.0:
- Agents never work in the director's checkout (ADR-0012). Each producing agent has a git worktree under `<data>/workspaces/M-n-<agent>` (`Workspaces.worktree`), on a branch `floor42/m-n-<agent>` of the project's repository. An idea committee without a project gets a new repository under `<data>/projects/<slug>`.
- What an agent commits stays on that branch. Nothing merges it into the director's branch: the director merges by hand or opens a pull request (ADR-0036). What an agent leaves uncommitted, or ignored by git (`docs/out/*.pdf`), exists only in the worktree.
- The only write Floor42 ever makes to the director's checkout is `DirectorFilesCommitted`. Closing a mission writes no file. ADR-0037 rejected "copy at mission end" in favour of a read-only panel and a ZIP.
- So the project folder never receives a deliverable.

The director also wants to open text, PDF and image files in a panel, "above all on a deliverable", with Download kept. A viewer has existed since ADR-0037, but only inline in the Deliverables panel. It cannot be reached from the workshop, it shows an SVG as text, and when a file is over a cap or of an unknown kind there is simply no View button, with no reason given.

## Options: where the destination is recorded
| Option | What it costs | Verdict |
|---|---|---|
| A. New events `DeliverablesDestinationSet` (at creation) and `DeliverablesCopied` (on accept) | Two types in `event-types.txt`, the renderers and the scenario. A point of no return: 1.9.0 sets these rows aside (`NEWER_VERSION`). After a rollback the mission stays visible with its id, but it has no destination and no copy record | **Taken** |
| B. Optional fields on `MissionCreated` and `MissionCompleted`, left out when null | Broken by the design review (below): 1.9.0 sets the whole `MissionCreated` aside, so the mission's id is free again and gets reused | Rejected |
| C. A field on `WorkflowPinned` | Milder on a rollback, but it mixes the place of delivery into the workflow document that ADR-0050 versions | Rejected |
| D. Kept in the browser, never journaled | The copy would follow a choice the journal cannot show | Rejected |

## Options: what "accepted" writes
| Option | What it costs | Verdict |
|---|---|---|
| E. Commit on the director's current branch | Changes their branch and index. Fails on a dirty tree or a detached HEAD | Rejected |
| F. Copy the documents on `POST /complete` into `<destination>/<mission>/` | Plain files, untracked unless the director adds them; one more event | **Taken** |
| G. A "copy now" route at any time | Several copies of one mission to journal and reconcile | Rejected, to be reopened if asked for |

## Design review (design-refuter, 2026-11-02)
It broke option B: `MissionBook.nextId` counted the missions it could see, so a row set aside freed its number. After a rollback, a new mission took the same id, worktree and branch. Back on 1.10.0, two missions merged under one id, or the replay stopped. It also showed that agent files copied into the project can run (`tests/conftest.py`, `*.test.ts`, `CLAUDE.md`, a PowerShell profile under a home folder), that a crash in the middle of a copy leaves a truncated file under its real name, and that a cancel during a copy leaves files with nothing journaled. Each finding is answered below.

## Decision
- **Chosen at creation.** `POST /api/missions` takes an optional `deliverTo`. If it is absent or blank, nothing is copied (today's behaviour; scripts unchanged). The form fills it with `deliverables`, a folder of its own at the project root. It is neither the root itself, where files would mix with the sources, nor `docs/out/`, which is often ignored by git and is one mission's build output. `DeliverablesDestinationSet` is recorded in the same command as `MissionCreated`.
- **Confinement, by code** (`DeliveryDestination`, `DocumentWriter`). Revised after the counter-check of 838c37b, which found a junction alias of the project, `~/.claude` and another repository's `.claude/agents` accepted.
  - A share or device path (`\\server\x`, `//server/x`, `\\?\`) is refused from its text, before any file call, so no server is contacted.
  - The folder's real path is resolved first: links and junctions are followed, for the nearest existing parent when the folder does not exist yet. Every check runs on it.
  - Inside the project, the real path goes through `Confinement.relative(project, path, WRITE)`. The project root itself is refused, and so is a relative folder that leaves the project.
  - Anywhere on disk, inside the project or not, a folder under `.claude`, `.git`, `.github`, `.vscode`, `.idea`, `.mcp.json`, `node_modules`, `.floor42` or `.office` is refused (compared without case), as are a disk root, the home folder itself, the system folders (`SystemRoot`, `ProgramFiles`, `ProgramData`; `/etc`, `/usr` and their kind), Floor42's data folder and Office's former one. An absolute folder outside the project must already exist.
  - The same checks run again at copy time. The mission's folder must be exactly `<real destination>/<mission>`. Each file goes through `Confinement.relative(<mission folder>, path, WRITE)`, and once its folder is created, that folder's real path must be exactly the expected one: a link or junction appearing anywhere below the mission's folder is refused.
  - Only the director's browser or a token of scope `all` may give a folder other than the default `deliverables`. A `missions:write` token keeps the default or gives none (403 `SCOPE`).
- **Documents only.** Copied: md, markdown, txt, csv, tsv, pdf, png, jpg, jpeg, gif, webp, docx, xlsx, pptx, odt, ods, odp and rtf.
  - Never copied: SVG or HTML (both can carry scripts); code, tests, configuration or scripts; agent instructions (`CLAUDE.md`, `AGENT.md`, `AGENTS.md` and their kind); anything under an `agents`, `commands`, `skills` or `hooks` folder or a dot folder; Windows device names, including those with trailing spaces or dots (`CON .md`).
  - The bytes must match the name: magic bytes for pdf, png, jpeg, gif and webp, zip for the office files, `{\rtf` for rtf, and no NUL byte in the first 8 KB of a text.
  - Each file left out is journaled with its reason. A file both committed and changed afterwards in the worktree is copied once, as the worktree has it.
- **Never overwritten.** Each file is written to `.floor42-<uuid>.part`, then moved with `Files.move` and no option: no `REPLACE_EXISTING`, and no `ATOMIC_MOVE`, which replaces the target on Windows.
  - Same bytes already there: `SAME`, nothing written.
  - Other bytes there: written beside them as `name (2).ext` (`KEPT_BOTH`), and the screen says so.
  - No question is asked at close time: keeping both loses nothing.
- **Order and lock.** `POST /complete` first asks the domain what it would refuse (`refusalToComplete`, and the summary). Only then does it copy. `DeliverablesCopied` and `MissionCompleted` are then recorded in one command, with the mission's origin. `DeliverablesHome.closing` lets one close or one cancel of a mission run at a time; a second gets 409 `CLOSING`.
- **A copy that stops** (an error no check foresaw) keeps what it wrote: the file that failed and the rest are journaled as left out, `not copied: the copy stopped`, so the record never claims more than the disk holds, and the mission completes.
- **Caps.** 25 MB a file and 512 MB per mission. The journal row lists at most 1,000 files, and the rest are counted (`leftOut`). A listing cut at its cap is flagged (`truncated`).
- **Mission ids never reused.** `nextId` follows the highest number ever created, not the count. After the replay, `JournalReplay` reserves the mission id of every row set aside. `on(MissionCreated)` refuses an id that already exists, so the replay stops instead of merging two missions.
- **The viewer is a panel** (`FileViewerPanel`, id `viewer`). It is a main panel, and a child of `deliverables` and `workshop`: closing it with Escape or ×, brings the list back as it was (section, options, open files), and the focus goes back to the View button that opened it. It reads only through `GET .../deliverables/file`, so ADR-0037's whitelist still decides what may be read.
  - Text and Markdown go to `TerminalBlock`, with Markdown escaped by `ui/markdown.ts`.
  - Images go only into an `<img>`. That now includes SVG: it is served as `image/svg+xml` only if its first 4 KB are text holding `<svg`, with `attachment` and `sandbox; default-src 'none'`.
  - PDFs go in an iframe without a `sandbox` attribute. Measured on 2026-11-02 in Chrome (`missionDeliverables.e2e.ts`): with `sandbox=""`, `allow-scripts`, `allow-same-origin`, or both, Chromium refuses the PDF and shows an error page. The bytes stay guarded by the daemon: `%PDF-` checked, `nosniff`, `default-src 'none'`, `frame-ancestors 'self'`.
  - For a file it does not show (too big, unknown kind, a link, deleted), the panel says why and keeps Download.

## Consequences
- **Point of no return.** On a rollback to 1.9.0, the two new rows are set aside: the mission keeps its id and its work, but loses its destination and copy record until 1.10.0 runs again. `CHANGELOG.md` says so. 1.9.0 itself still counts visible missions, so a `MissionCreated` it cannot read (from a later version) could still lead it to reuse an id. 1.10.0 no longer creates such rows.
- If the journal write fails after the copy, files are left with no journal entry; the daemon's log names the folder.
- Files an agent writes after the close are not copied; the ZIP and the panel still show them.
- The director's own pending files (Mission control) get no viewer: they are on the director's disk already, and once given they show on the branches. Reversed by the amendment of 2026-11-03 below.
- Not covered: Java has no `openat`, so a process with the director's rights could swap a junction in between the check and the write. That process can already write anywhere (ADR-0012). Firefox's PDF viewer was not checked.

## Amendment 2026-11-03: media in the viewer, and the director's own files
The director: "we cannot open a photo, a file or a PDF in a panel; we should be able to." Deliverables already reached the viewer; the director's own pending files did not, an image could not be zoomed, and a file the browser could not draw left an empty panel.

| Option for PDF | What it costs | Verdict |
|---|---|---|
| The browser's own viewer in an iframe of the daemon's origin | No dependency. Paging, zoom and search are the browser's. Firefox not checked | **Taken** (unchanged) |
| Bundled pdf.js | A new npm dependency of several MB to keep patched, a worker to allow in the page's policy, its own history of script injection | Rejected: nothing the built-in viewer lacks was asked for |

- **One way to send a file** (`FileResponses`), used by `.../deliverables/file` and the new `.../files/file`. The type comes from the bytes: PNG, JPEG, GIF and WebP by their magic bytes, whatever the name; a PDF only when it starts with `%PDF-`; an SVG only when its first 4 KB hold `<svg`. A file named `.png` holding HTML is refused (415 `NOT_VIEWABLE`), never sent as HTML nor as an image. Every answer carries `nosniff`.
- **Policy per type**, as narrow as the type allows. Text and downloads: `sandbox; default-src 'none'`. Images, SVG included: `sandbox; default-src 'none'; img-src 'self' blob: data:; style-src 'unsafe-inline'`; an SVG still goes out as an attachment, so opened on its own it downloads, and the viewer puts it only in an `<img>`, where no script of it runs and nothing it names is loaded. PDF: unchanged (`default-src 'none'; object-src 'self'; frame-ancestors 'self'`, no sandbox, which Chromium refuses).
- **Opened once** (`BlobBytes`, after the counter-check). The file is checked (no link, regular, real path inside), opened once without following a link, and checked again once open. Its size and first bytes are measured on that channel, every later read (the NUL scan, the SVG root, the body, the ZIP entry) goes through it, and the body stops at the measured size: a file swapped or grown after the check is not what goes out. A swap between the path checks and the open stays possible (no `openat` in Java, as above).
- **HEAD.** RESTEasy routes a HEAD to the GET method and never writes its body, so the file opened for the check stayed open. Both file routes answer HEAD themselves (`FileResponses.head`): the same headers, no body, the file released before the answer leaves, a committed blob's temporary copy deleted. `BlobBytes.openNow()` counts the files open, for the tests.
- **Framing fixed.** `send` set a `Content-Length` on a streamed body, which goes out chunked: both headers at once, which Node's parser refuses (RFC 9112). The length is no longer set.
- **The director's own files.** `GET /api/missions/{id}/files/entry` (kind, size, link, caps) and `GET .../files/file` (shown, or `download=true`). Only a path `GET .../files` lists as pending; confined by `Confinement.relative` on the project (no climbing out, no `.git`), then the real path must stay inside, and a link is never followed. Scope `all` only (the director's browser or such a token): a checkout can hold a stray `.env`, and a `read` token lists the paths but never gets their bytes. Each pending path on the desk has a View button; closing the viewer brings the desk back on its Mission tab with the focus on that button.
- **In the panel.** An image is fitted to the panel, with actual size and zoom steps (25 % to 800 %). A file of no known kind, above a cap, a link, deleted, or an image the browser cannot draw (refused, or bytes that are no image, or bytes the daemon refuses, a `.pdf` of HTML for one) gets a card. Before an image goes into an `<img>` or a PDF into a frame, the panel asks the same route with `check=true`, which answers 200 `{viewable, type, code, message}` without the bytes: a refusal is never a failed load (a console error) nor the daemon's JSON inside the frame. Should the bytes change after the check, the frame's document is read (same origin) and anything but `application/pdf` replaces it with the card: "No preview", why, and Download.
- Tests: `BlobBytesTest` (a file grown or swapped after the check), `FileResponsesTest` (exact policies, types from bytes, HTML named `.png`, `.jpg`, `.pdf` or `.svg` refused, SVG with a script, caps), `DirectorFileViewApiTest` (pending only, outside and `.git` refused, a pending file whose folder is swapped for a junction inside the project, refused by the read's link guard (`LINK_REFUSED`), a junction out of the project (`OUTSIDE_WORKSPACE`), an alternate data stream (`OUTSIDE_WORKSPACE`), each also with `check=true`, HEAD leaving no file open, a PNG header followed by HTML served as `image/png`, `read` and `missions:write` tokens refused, one framing), `fileViewer.component.test.ts`, `zoom.test.ts`, `viewerMedia.e2e.ts` (photo zoomed, PDF in Chromium's viewer, unknown kind, an SVG whose script and onload never run and whose `<image href>` is never fetched, with a positive control: the same SVG put in a page as a document runs and fetches, a PDF whose /OpenAction calls `app.launchURL`, `submitForm` and `getURL` with no request to the daemon from the frame, no navigation and no window, a `.pdf` of HTML shown as the card).
