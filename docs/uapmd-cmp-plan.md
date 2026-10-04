# `uapmd-cmp` — standing rules and architecture

The rules that constrain `uapmd-cmp`, and the defects still open against it. Outstanding gaps
versus uapmd-app are tracked in `uapmd-cmp-ui-audit.md`; binding gaps in
`uapmd-binding-missing-api.md`.

Reference for every question of behaviour: `external/uapmd/source/tools/uapmd-app/` at the pinned
submodule commit (`ad5a046a`, 0.6.0 development).

---

## 2 · Architecture

`uapmd-cmp` is a thin Compose view layer over the Kotlin binding of `AppModel` — including
audio-engine control. Parity with uapmd-app is structural rather than a chase: both render the
same façade. Re-implementing app logic in Kotlin (what `composeApp` does) is the thing that
produced the drift being corrected, and it structurally cannot reach gain/mute/solo/freeze/graph/
history.

### 2.0 Layering rule: `uapmd-binding` mirrors uapmd, and nothing more

**No type or member may be added to `uapmd-binding` unless it exists in the uapmd API.**
Anything beyond that — aggregation, convenience wrappers, derived/computed data, platform
branching, UI-shaped state — belongs to `uapmd-cmp`.

This is what keeps the binding auditable against upstream: every declaration in it should be
traceable to a C++ declaration in uapmd, and a reader should be able to diff the two. It is also
what went wrong with `composeApp`, one layer up — `UapmdModel.kt` grew into a re-implementation
of AppModel because there was no line saying where binding ends and app begins.

Scope note: the rule governs *what may be added*, not "never edit the module" — binding
`AppModel` necessarily means adding files to `uapmd-binding`. What it forbids is adding anything
that is not in uapmd. Every public member added so far maps 1:1 onto a `uapmd_app_*` /
`uapmd_transport_*` function in `uapmd-c-app.h`.

Three consequences for this plan, all corrections to earlier drafts:

- **Handle-ownership belongs to the app.** `AppModel` owns its `RealtimeSequencer`, but that type
  is `AutoCloseable` and each backend's `close()` destroys the handle, so a borrowed instance
  could be double-freed. The first fix added an `owned` flag to the five `*RealtimeSequencer`
  classes — a member with no counterpart in uapmd, so it was reverted. The safety now lives in
  `uapmd-cmp` as `BorrowedRealtimeSequencer`, which delegates everything and no-ops `close()`.
  "Who owns this handle" is a fact about how this app uses the API, not part of the API.
  (Note the binding does have a pre-existing `owned` idiom on `ClipFragment`, from the 0.5.6
  work — so the pattern was not invented here, but that does not make it uapmd API.)

- **Clip preview data is app-side.** `ClipPreview` is `uapmd-app/gui/ClipPreview.hpp` — GUI code,
  not library API — so it must not appear in the binding. What the binding does provide is the
  raw material, already bound: `TimelineFacade.getMidiClipNotes()` and
  `AudioFileReader.readFrames()`. Waveform peaks and note rectangles are computed in `uapmd-cmp`.
- **The wasm engine-control fallback (§2.4) is app-side.** Choosing between two uapmd entry
  points based on platform is app logic; the `expect`/`actual` seam lives in `uapmd-cmp`, and the
  binding just exposes both calls as uapmd declares them.

For reference, everything else needed is genuine uapmd API and passes the rule:

| Item | Home |
|---|---|
| `trackGain()` / `muted()` / `solo()` | `uapmd-engine` — `SequencerTrack.hpp` |
| `FrozenTrackManager` | `uapmd-engine` |
| `MidiRecorder` | `uapmd-engine` |
| `TempoMap` | `uapmd-data` |
| `AppModel`, `TransportController`, `McpServer` | `tools/uapmd-app-model` — see §5.3 |

### 2.1 Audio: match uapmd-app, not `composeApp`

**The audio device configuration is part of "match uapmd-app".** Leaving the engine's automatic
buffer sizing on makes the Oboe device come up at `internalCapacity=1024 stabilizedBlock=1024`,
and on that block size the engine cannot sustain real time with a six-plug-in project on Android:
measured repeatedly at 87-92% of real time, i.e. the playhead advances ~10.7 s per 12 s of wall
clock, heard as continuous stuttering. uapmd-app runs at `stabilizedBlock=512`; matching it gives
99.95-99.98%. `UapmdHost.applyDefaultAudioBufferSize()` therefore turns auto sizing off and
configures 512 frames, and it must run **after** the engine is enabled - the audio device is
created asynchronously when the engine starts, so configuring earlier silently does nothing.

Compare the two apps' `OboeAudioIODevice: opened stream` log lines when this is in doubt; they
should agree on `internalCapacity` and `stabilizedBlock`.

`composeApp`'s audio layer works, but working is not the same as being good enough, and it has
never been shown to be. **uapmd-app's behaviour is the target.** That means adopting AppModel's
audio entry points rather than reimplementing them.

The gap is not cosmetic. Turning the engine **off**:

| | `composeApp` | `AppModel::setAudioEngineEnabled(false)` |
|---|---|---|
| | `engine.setActive(false)` then `sequencer.stopAudio()` | stop transport → mute output → background worker polls the output analyser until the signal falls below threshold (max 8 s) so release/reverb tails render out **inaudibly** → on the main thread: `setEngineActive(false)`, sleep ~2 buffer periods so the hardware ring drains with silence, `stopAudio()`, `stopProcessing()` on every instance, `resetProcessingState()`, unmute |

The comment in `AppModel.cpp` explains why the drain exists: several formats (VST3 notably)
preserve DSP state across deactivation, so a tail cut off here **resumes on restart**. Turning
the engine back on is equally careful — it guarantees starting from a deactivated state, finishing
an interrupted shutdown synchronously first, and reverts `audioEngineEnabled_` if `startAudio()`
fails. Plugin deactivation is explicitly enqueued on the main thread (VST3 `setActive()`) and
never blocked on, so the worker can be joined from the main thread without deadlocking.

`composeApp` does none of this. It is not wrong so much as unfinished.

So, adopted from AppModel — the reverse of what an earlier draft of this plan said:

- `uapmd_app_instantiate()` and its defaults: **1024 frames / 65536 UMP bytes / 48000 Hz**.
  These are what uapmd-app ships; `composeApp`'s 512 / 8192 carry no evidence behind them and
  are simply dropped. No parameterised `instantiate_with(...)` is needed.
- `uapmd_app_set_audio_engine_enabled` / `uapmd_app_toggle_audio_engine`
- `uapmd_app_update_audio_device_settings`
- `uapmd_app_set_auto_buffer_size_enabled` / `uapmd_app_auto_buffer_size_enabled`
  (auto buffer size has no `composeApp` equivalent at all)

### 2.3 Event-loop ordering

remidy marshals engine completions through an `EventLoop`. A host must install one
(`initJvmEventLoop()` / `initAndroidEventLoop()`) **before** creating any engine or sequencer, or
async completions silently never fire — `addEmptyTrack` still creates the track, but its callback
never runs. On Android the loop must also not be the main looper; see `AndroidEventLoop.kt`.

**On macOS the loop's main thread must be the AppKit main thread, not the AWT event queue**
(`JvmEventLoop.kt`). remidy marshals `clap_plugin_factory.create_plugin` to whatever the loop
calls the main thread, and a JUCE-based plug-in binds its MessageManager to the thread that runs
it. With the AWT event queue in that role, the plug-in's message thread is a thread with no
CFRunLoop — and one AWT retires when it goes idle — so the `MessageManagerLock` the same plug-in
takes in `guiCreate` is never granted: creating a CLAP UI hangs, permanently, with the AppKit
thread blocked inside `juce::MessageManager::Lock::tryAcquire`. This was originally hidden behind
6.2: the floating path crashed before ever reaching `guiCreate`.

**And it must reach that thread through the main *run loop*, not the main *dispatch queue*.**
Running on the main thread and running inside the main queue are not the same thing, and remidy
depends on the difference. The main queue is serial and non-reentrant: while it drains one item
nothing else on it runs, not even from a nested run loop. `PluginFormatAU.mm`'s `createInstance`
spins `while (!instantiationCompleted) CFRunLoopRunInMode(...)` waiting for a completion
`AVAudioUnit` delivers *through the main queue*, so a task that got there by `dispatch_async` or
`dispatch_sync` deadlocks it outright — the AppKit thread parked in `mach_msg` forever and the
caller parked in `EventLoop::runTaskOnMainThread`. Reproduced with Mela; remidy's own comment at
`PluginFormatAU.mm:277` says AUv3 instantiation "relies on the main run loop".

uapmd-app is immune only by accident of shape: choc's `postMessage` is the same
`dispatch_async_f(dispatch_get_main_queue(), ...)`, but uapmd-app calls `createInstance` from the
main thread already (ImGui frame under `[NSApp run]`), so `runningOnMainThread()` is true and the
work runs *inline* as a run-loop callout. A host that always calls in from a background thread
always takes the enqueue path, so the enqueue mechanism has to be run-loop based:
`uapmd_internal_enqueue_on_main_thread` in the C API does `CFRunLoopPerformBlock` +
`CFRunLoopWakeUp` with `kCFRunLoopCommonModes`, which is the mode set remidy's nested
`CFRunLoopRunInMode(kCFRunLoopDefaultMode, ...)` will run. `on_native_ui_thread` uses the same
mechanism for the same reason.


### 2.4 Fallback rule: where AppModel is unreachable, do what `composeApp` does

The two rules compose into one policy:

> Match uapmd-app by default. Where a platform cannot reach AppModel, fall back to
> `composeApp`'s path — it is known to work.

So the audio path depending on AppModel is **not** a blocker for any target. Were Emscripten
support to be lost, Wasm would keep engine control through the engine-level route `composeApp`
uses on wasmJs today — `uapmd_engine_set_active` plus `RealtimeSequencer.startAudio()`/`stopAudio()`.
What Wasm loses in that case is shutdown *quality* (the muted tail drain of §2.1), not engine
control.

One qualification on "known to work": on **wasm** that phrase carries much less history than on
desktop. `composeApp`'s wasm build had unresponsive plugin scanning until `aaed96b`, fixed only
just now. The good news is where that fix landed — `c-api/src/uapmd-c-tooling.cpp`,
`WasmJsBridge.kt` and `webMain/cpp/CMakeLists.txt`, i.e. **entirely below the app layer**, so
`uapmd-cmp` inherits it for free through `:uapmd-binding` with nothing to port. But treat wasm
claims as provisional and verify them on the target rather than by analogy with desktop.

Two conditions on this, so it does not quietly become the "two models" problem:

- It lives behind one `expect`/`actual` function — engine enable/disable — **in `uapmd-cmp`, not
  in the binding** (§2.0): the binding exposes both uapmd entry points as declared, and the app
  picks. The wasm `actual` carries a comment saying why it differs and what it gives up. One
  narrow seam, not a parallel model.
- It is expected to be temporary. AppModel's shutdown worker spawns a plain `std::thread` with no
  Emscripten guard, and our wasm build already runs with pthreads (the emitted
  `uapmd-c-api.js` carries the `ENVIRONMENT_IS_PTHREAD` / `em-pthread` worker
  machinery), so it should work once compiled. Upstream expects it to: uapmd-app's own
  `web_main.cpp:293` calls `setAudioEngineEnabled` on the web build.

Targets, matching the existing `composeApp`: `android`, `jvm`, `iosArm64`, `iosSimulatorArm64`,
`wasmJs`. (`composeApp` also carries a dead `jsMain` source set with no `js()` target — do not
copy that into the new module.)

---

### 2.5 We do not run uapmd-app's `main()` — so its setup is ours to reproduce

uapmd-cmp replaces `main_common.cpp`, and anything that entry point does silently becomes ours to
do, with no compile error when we skip it. The order is load-bearing:

1. install the platform event loop **before** AppModel exists (§2.3)
2. `uapmd_app_instantiate()`, then `notifyUiReady()`, then `notifyPersistentStorageReady()`
3. bring the audio engine to its per-platform initial state (desktop and mobile on, web off —
   browsers require a user gesture)
4. configure the audio device once the engine is up (§2.1)
5. teardown in reverse: engine off, flush the event loop, then `cleanupAppModel()`

`BootstrapProbeMain` exercises this headlessly; on web the persistent-storage step is what mounts
the IDBFS the plug-in list cache lives in.


### 2.6 Verify UI and behaviour headlessly, not by eye

Three harnesses exist so a claim about the UI can be checked rather than asserted. Use them
before reporting a UI change as working.

| Task | What it does |
|---|---|
| `:uapmd-cmp:renderUiSnapshot` | Renders a view off-screen to a PNG at device density. `-Duapmd.cmp.snapshotView=` picks `timeline` (default), `selector`, `graph`, `instance` or `pianoroll`; `-Duapmd.cmp.snapshotSize=WxH` and `-Duapmd.cmp.snapshotDensity=` set the frame. This is how a clipped legend, an unreadable label or a link that never draws gets caught. |
| `:uapmd-cmp:runPluginUiProbe` | Creates one plug-in's UI, shows it, hides it and shows it again, reporting visibility at each step. `-Duapmd.probe.uiPlugin=<name substring>` and `-Duapmd.probe.uiFormat=<CLAP\|LV2\|VST3\|AU>` pick the plug-in, by exact display name — a substring is only accepted when it names one plug-in, because `ADLplug` and `ADLplug-AE` are different plug-ins sharing a prefix. Not headless — the UI is a real native window — but unattended, and the only coverage the per-format UI paths have. |
| `:uapmd-cmp:runBootstrapProbe` | Drives AppModel headlessly - audio start/stop, tracks, plug-ins, clips, graph, tempo map, piano-roll edits - and fails on the first broken check. |
| Serving the wasm build | The dev server injects COOP/COEP itself, so the service-worker path in `index.html` never runs there and a dev-server load proves nothing about a static deploy. To exercise what users get, build `:uapmd-cmp:wasmJsBrowserDistribution` and serve `build/dist/wasmJs/productionExecutable` with COOP/COEP headers of your own; the two builds have already differed in practice. |
| `:uapmd-cmp:runPianoRollScrollProbe` | Scrolls the piano roll with the wheel through `ImageComposeScene` and compares renders. One notch versus twelve: a viewport that moves once and stops renders them the same. |
| `:uapmd-cmp:runScanPollProbe` | Times what the UI poll costs while a plug-in scan runs, reporting first/median/p95/max per call. Use it before adding anything to the poll. |
| `:uapmd-cmp:runKeyboardDragProbe` | Drags a pointer across the on-screen keyboard through `ImageComposeScene` and reports the notes produced, for touch and for mouse separately. |

**"Scrolling" means the scroll machinery, not drag-to-pan.** A viewport the wheel,
a trackpad and a scrollbar can move - `horizontalScroll`/`verticalScroll` over content
sized to the whole document - is what an editor means by scrolling, and it is what
uapmd-app's roll has (a scrolled child with `hScroll`/`vScrollPx`). Panning by
dragging the canvas is not a substitute: in an editor a drag belongs to the notes,
and inside a floating window it fights the window's own gestures. Sizing the content
also removes the scroll arithmetic - pointer coordinates arrive in content space, so
hit testing needs no offsets.

**Every preview note-on needs its note-off.** The synth holds a note until it is
released, so auditioning on click without releasing leaves notes sounding and
eventually jams every voice. Audition on press, release on lift
(`detectTapGestures(onPress = { … tryAwaitRelease(); … })`).

**Never key a `pointerInput` on state the gesture itself writes.** Compose restarts
the detector when a key changes, so the first delta lands and the gesture is then
cancelled: the view jumps once and stops following the pointer. This has now caused
three separate "it does not work" reports - the timeline navigator's zoom, and the
piano roll's vertical and horizontal scrolling. Read the value inside the handler
instead, and take callbacks through `rememberUpdatedState`.

A harness must not set up the state the feature under test is supposed to establish. The graph
snapshot used to call `ensureTrackUsesEditorGraph()` itself, which hid the fact that opening the
editor did not - the window opened on the simple chain and drew every node unconnected.

### 2.7 The startup scan is fast-only; the catalog arrives later

`AppModel::maybeStartInitialPluginScan` runs with `requireFastScanning = true`
(`AppModel.cpp:430`), so on a cold cache it legitimately finds **nothing** and the
selector opens empty. The full sweep is the slow scan the "Scan Plugins" button
runs, which on this machine takes a while and finds ~287 entries where the fast scan
found 0. Two consequences worth keeping in mind:

- An empty selector on first run is not a scanning failure, and `isScanning` alone
  cannot distinguish a long scan from a stuck one - which is why the progress counts
  and `lastPluginScanError` are bound and shown, as uapmd-app shows them.
- The catalog must be re-read when a scan *finishes*. Reading it only while it is
  empty means a rescan never reaches the list, so pressing Scan appears to do
  nothing whether or not the scan worked.

### 2.8 Scan out of process wherever the platform allows it

An in-process scan runs every plug-in's entry code inside the app, so one bad
plug-in takes the app down partway through - which is why uapmd-app defaults
`useRemoteScanner_` and `forceRescan_` to **true** (`PluginSelector.hpp:39-42`) and
offers the remote scanner everywhere `kRemoteScannerSupported` holds: desktop only,
never Android, iOS or the browser.

uapmd-cmp cannot use it the way uapmd-app does. Remote scanning relaunches the
host's own executable with `--scan-only --ipc-client …`; on the JVM that executable
is `java`, which serves no scanner, so the scan dies with "Remote scanner failed to
connect". uapmd's standalone `uapmd-scan` already understands those arguments
(`tools/uapmd-scan/main.cpp:74`), so the missing piece was a way to point the
launcher at it - added upstream as `setRemoteScannerExecutable`
(`RemoteScannerServer.hpp`), exposed as `uapmd_set_remote_scanner_executable`.

The desktop app resolves the binary from `-Duapmd.cmp.scannerExe`,
`UAPMD_SCAN_EXECUTABLE`, or beside the native library, and reports
`platformSupportsRemoteScanner` only when it found one. **A build that ships no
scanner must not default to remote**: a scan that runs in process and risks a crash
still beats one that cannot start.

Two consequences of getting this wrong, both seen in practice. With no scanner found
the app silently scans in process, where formats that must instantiate on the UI
thread block it - the window freezes mid-scan and coroutines pile up suspended - and
a single bad plug-in kills the app outright. So the Gradle build forwards the built
`uapmd-scan` path to the desktop app and to every probe that scans; without that
forwarding the flag is false and the default silently degrades.

Measured cost of the UI poll during a real 164-bundle remote scan
(`:uapmd-cmp:runScanPollProbe`, 167 samples): `slowScanProgress` median 157µs / p95
570µs, `lastPluginScanError` median 16µs, a full 287-entry catalog read median 2.5ms
/ p95 6.4ms. First calls cost 11-13ms on JNA layout setup, which is why the probe
reports medians rather than maxima - judging this on a handful of samples points at
the wrong thing.

### 2.9 WebCLAP scanning needs the audio worklet, and a truthful main-thread check

Two things have to hold before a plug-in scan finds anything in a browser.

**The audio engine must be running.** WebCLAP bundles are fetched and inspected by
the AudioWorklet; the bridge that carries a scan request only has a transport once
`WebAudioWorkletIODevice::start()` has created the worklet node. Scanning with the
engine off queues a request nothing delivers, and the scan then sits at 0 bundles -
uncancellable, because `shouldCancel` is only polled between bundles. The engine
cannot simply be started at load either: browsers require a user gesture. So the
selector disables Scan and says why while the engine is off.

**`EventLoop::runningOnMainThread()` must tell the truth.**
`AppModel::performPluginScanning` runs the scan on a `std::thread` - a Web Worker,
with its own JS scope, no `document`, no worklet node and its own copy of `Module`.
A check that answers `true` unconditionally makes `runTaskOnMainThread()` run the
bridge code *inside the worker*, which builds a second, unreachable bridge with
`node: null` and queues the request into it forever while the main-thread bridge sits
idle. `EventLoopEmscripten` must therefore use `emscripten_is_main_browser_thread()`
and proxy a task enqueued from a worker with
`emscripten_async_run_in_main_runtime_thread`; this now lives upstream in
`external/uapmd` (see 2.10).

Note for future debugging: the worklet's fetches do **not** appear in the page's
network log, because a worker issues them. `performance.getEntriesByType('resource')`
does show them, and an empty page-level log means nothing here.

### 2.10 Local uapmd patches: none

uapmd-kmp no longer patches `external/uapmd`. The last carried change (the public
`uapmd_augene2::Integration` model) is upstream, so `cmake/UapmdPatches.cmake` and
`patches/uapmd/` are gone. Changes uapmd-kmp needs go upstream directly.

### 2.11 Addin wiring (uapmd `f5d490d5`)

`UapmdHost.initAddins()` reproduces the extension points uapmd-app's `MainWindow`
publishes before `AddinManager::initialize()`. Leaving one out is not a missing
menu item, it is an addin that fails to load:

| Extension point | Who needs it |
|---|---|
| `/uapmd/app/command/v1` | Virtual MIDI Devices, Augene2 Integration (System menu) |
| `/uapmd/app/project-command/v1` | MIR analyses, pitch/basic-pitch/DrumScript transcription (Project menu) |
| `/uapmd/app/panel/v1` | Augene2 Integration |
| `/uapmd/app/model/v1` | Virtual MIDI Devices, which also needs `registerVirtualMidiDevicesAddin()` |

`uapmd_augene2::registerProjectService()` is called too, so Augene2 project data
loads and saves whether or not its addin is enabled.

Augene2 is built on every target. Its ANTLR C++ runtime is pinned (in augene2
`d3ce24bb`, picked up by uapmd `010813ac`) past 4.13.2, whose missing standard
includes broke MSVC 14.51 and NDK r28 in C++23 mode. uapmd's own CI does not build
Augene2 (`UAPMD_ENABLE_AUGENE2` defaults off), so uapmd-kmp is where such breakage
shows first.

Every poll tick runs `UapmdHost.tickModelServices()`, which is what uapmd-app's
`MainWindow::update()` does per frame: `PanelRegistry::update()` (Augene2 applies
compilations there) and the AppModel document provider's `tick()` (addin file picks
complete there). Both run on the model thread, which is the thread that created
AppModel — the Compose main thread.

Virtual MIDI 2.0 devices are off by default now. Instances get no endpoint until one
is enabled in the Virtual MIDI Devices window (opened by the addin's command) or
automatic creation is turned on there.

`./gradlew :uapmd-cmp:runAddinProbe [-Duapmd.probe.instantiate=CLAP]` checks all of
this headlessly: every addin Active, both command registries populated, both windows
opening from their commands, audio worker resizing, and enabling/disabling a device.

### 2.12 Stopping a scan at teardown

A plug-in scan runs on an AppModel worker thread. Since uapmd `ad5a046a` the worker is
joinable and `AppModel::stopPluginScanning()` cancels it and waits; `~AppModel()` calls it
first, and `UapmdHost.shutdown()` calls it before tearing down addins, as uapmd-app does.
Before that fix, quitting mid-scan aborted with "mutex lock failed".

The wait runs `EventLoop::processQueuedTasks()`, because an in-process scan loads bundles
through tasks it queues on the main thread and blocks on. The C API's event-loop adapter
(`CApiEventLoop`, `c-api/src/uapmd-c-engine.cpp`) implements that itself: it keeps every
task it hands the host and runs the pending ones when asked on the main thread, each
exactly once. Without it, teardown on the loop's main thread (macOS Cmd+Q runs
`shutdown()` on the AppKit thread; elsewhere the AWT event thread) deadlocks whenever an
in-process scan is waiting there — the host's queue cannot drain while that thread waits.

## 3 · Window model

### 3.1 Floating, in-scene windows

uapmd-app is a multi-window application and several of its windows are *multi-instance*: instance
details, the track graph and the MIDI dump are per id, and more than one can be open at once.
Compose Multiplatform's `Window` is desktop-only, so the app draws its own draggable, resizable,
stackable windows inside the scene, addressed by a string key (`details:<id>`, `graph:<track>`,
`dump:<track>:<clip>`). Everything from the plug-in selector onwards depends on it.


## 4 · Risks

- **Main-thread blocking is the recurring failure mode, on wasm and on Android.** `aaed96b`'s
  defect was a synchronous C API call (`uapmd_scan_tool_perform_scanning` ->
  `InProcessScanSessionManager::runScan`) blocking on a condition variable while the completion it
  waited for could only be delivered by the thread it had blocked — the page stopped compositing
  permanently. Android has the same shape: an AAP plug-in bind waited on from the main thread can
  never be satisfied, because `onServiceConnected` is delivered on the main looper. AppModel has
  it too: `joinAudioShutdownWorker()` joins from the main thread and
  `completeAudioEngineShutdown()` sleeps on it for ~2 buffer periods. Anything that blocks or
  reaches a plug-in goes through `backgroundDispatcher()`; wasm must be verified **in a browser**,
  not assumed working because it compiles and pthreads are enabled.
- **Event-loop ordering.** AppModel's shutdown enqueues plugin deactivation on the main thread;
  if `initJvmEventLoop()` / `initAndroidEventLoop()` has not run first, that work never executes
  and the engine appears to hang on stop. See §2.3.
- **Five backends per binding addition.** Every new C API surface costs work in JNA, JNI,
  cinterop and the Emscripten bridge.
- **No ImGui equivalents.** ImGui's *windowing* (§3.1), ImNodes (graph) and ImTimeline (timeline)
  and the immediate-mode interaction model all have to be rebuilt in Compose. The timeline and
  piano roll are where this stays expensive rather than merely tedious.
- **Testing.** There is no CI environment with plugins installed; verification is manual on
  desktop, and iOS is, per the guide, "tested only on simulators / rarely tested".
- **Moving target.** uapmd is under heavy development. Pin the submodule and re-baseline
  deliberately.

---

## 5 · Decisions (settled)

1. **Wasm is a target.** Not "if it works out" — it is in scope, so enabling `uapmd-app-model`
   for Emscripten is required work, not an experiment. The §2.4 fallback is insurance, not an exit.
2. **Buffer sizes**: take AppModel's 1024 / 65536 / 48000. No parameterised
   `uapmd_app_instantiate_with(...)`.
3. **`composeApp`** stays until it is removed at some later stage; it does not need freezing and
   does not gate anything. This holds on one condition — see §5.1.
4. **No tab navigation.** Confirmed: it brings in a lot of nonsense. The main UI is the timeline,
   always visible.
5. **Piano roll is deferred** to a late stage of the migration, not part of the first parity
   pass. Everything else in the clip-editor group is not deferred.

### 5.1 The one constraint that keeps decision 3 true

`composeApp` is unaffected by this work **only for as long as every `uapmd-binding` change is
additive**. The binding work does change that module — substantially — but it adds new
interfaces (`AppModel`, `TransportController`) and new `expect`/`actual` pairs rather than
altering existing signatures, so `composeApp` keeps compiling and can be deleted whenever convenient.

Where 0.4 touches an *existing* declaration — for instance adding `restoreNodeId` to
`addPluginToTrack()` — give it a default value so the change stays source-compatible. If some
gap genuinely cannot be filled additively, that is the moment to remove `composeApp` rather than
to contort the binding around it.

### 5.2 Do not diverge from uapmd-app UI behaviour

Two rules an earlier tracker carried are **wrong** and must not come back. They were
divergences, and divergence is the thing to avoid:

- The plugin list **is a floating window**, not a side panel. A side panel wrecks the flow of
  picking a plugin to add to an already-selected track.
- Instance details **are floating windows**, plural — more than one can be open at a time, which
  a single panel cannot express at all.

Generalised: where this plan and uapmd-app disagree about UI behaviour, uapmd-app wins unless
there is a platform reason that makes its behaviour impossible. See §3.1 — this has real
structural consequences, and they land early.

(Native OS windows on desktop were considered as a sanctioned exception — ImGui's in-window
placement being a constraint artifact rather than a design choice — but dropped; see §3.1.)

### 5.3 Scope of "the uapmd API" — settled

Does "the uapmd API" include `tools/uapmd-app-model`? `AppModel`, `TransportController` and
`McpServer` all live there rather than beside the libraries, and the whole architecture in §2
rests on binding them.

**Yes.** `uapmd-binding` already includes API bindings for AppModel, so it covers `tools/` by
construction.


---

---

## 6 · Open bugs in `external/uapmd`

All were found while building `uapmd-cmp`, and none is a binding or app defect. `external/uapmd`
is a submodule whose commits are yours, so they are recorded here rather than fixed, and each one
affects uapmd-app itself and not only uapmd-cmp. Defects in anything else uapmd-cmp depends on
belong in `uapmd-cmp-ui-audit.md` under Known defects.

### 6.0 Audio never recovers from an output route change

`OboeAudioIODevice` reports `stream error ErrorDisconnected`, logs `reopening stream after error`,
and then `restart after close failed: ErrorClosed` - after which audio never returns. Reproduced
twice on Android by starting an audio capture while a stream was open; any route change
(headphones, Bluetooth, another app capturing) takes the same path. The restart logic is in
`uapmd-engine/src/devices/OboeAudioIODevice.cpp`.

### 6.1 Null deref in `completeAudioEngineShutdown()` after removing a plug-in

`uapmd-app-model/src/AppModel.cpp:783`

```cpp
auto* host = sequencer_.engine()->pluginHost();
for (auto id : host->instanceIds())
    host->getInstance(id)->stopProcessing();   // no null check
```

After `uapmd_app_remove_plugin_instance()`, `instanceIds()` still reports the removed id while
`getInstance()` returns `nullptr`.

Deterministic: instantiate → remove → engine off ⇒ `SIGSEGV`, `si_addr: 0x0`, in
`uapmd_app::AppModel::completeAudioEngineShutdown()+0x110`, reached from the event-loop task.
Skipping only the removal leaves teardown clean. Reproduce with `-Duapmd.probe.removeInstance=1`;
the probe keeps it opt-in so default runs stay green.

`tests/uapmd-app-shutdown-crash.c` is a started pure-C repro but **does not work yet**: a bare C
host installs no remidy `EventLoop`, so instantiation never completes and it stalls before the
interesting part.

### 6.2 CLAP plug-in UI creation passes a null api string

`remidy/src/clap/PluginInstanceCLAP.UI.cpp:72` calls `tryCreateWith(nullptr, true)` as a fallback,
and `tryCreateWith` null-guards only the support check:

```cpp
if (api && !owner->plugin->guiIsApiSupported(api, floating)) return;
if (!owner->plugin->guiCreate(api, floating)) return;   // api may be nullptr
```

clap-helpers' `clapGuiCreate` then `strlen()`s it: `SIGSEGV`, `si_addr: 0x0`, in `_platform_strlen`
under `clap::helpers::Plugin<...>::clapGuiCreate`. Reproduced with Dexed.

Plug-in dependent — plug-ins that tolerate a null api do not crash, which is why `composeApp`
looked fine on desktop with VST3/AU. Reproduce with `-Duapmd.probe.pluginUi=1`.

**Unreachable from this host, not fixed (2026-09-16).** `uapmd_instance_create_ui_presentation`
never asks remidy for a floating UI, for any format: a floating request gets a
`remidy::gui::ContainerWindow` of our own and the embedded path, which only ever passes a real api
name. That is uapmd-app's policy too, which is why uapmd-app never hit this. The null-api fallback
is untouched and still bites anyone who calls `uapmd_instance_create_ui()` with `is_floating=true`.

### 6.6 An LV2 UI asked to float is created and never shown

`remidy/src/lv2/PluginInstanceLV2.UI.cpp`

An LV2 UI is a bare widget — `CocoaUI`, `X11UI`, `WindowsUI`. `UISupport::create()` embeds it only
on the `!is_floating && parent_widget` path; asked to float, it instantiates the widget, parents it
to nothing, and returns true. `show()` then also returns true (`show_interface` is null for a
widget UI, so there is nothing to call), and no window ever appears. Nothing reports a failure at
any point.

Out of reach for the same reason as 6.2, and by the same single rule: no floating request ever
leaves `uapmd_instance_create_ui_presentation`. Still a defect on remidy's floating path — an LV2
UI that cannot be embedded should report that, not succeed silently.

### 6.3 A graph connection naming a built-in node is always refused

`TimelineFacadeImpl::resolvePluginInstanceId` (`TimelineFacadePlugins.cpp:637`) walks
`track->orderedInstanceIds()` and matches `getPluginNode(id)->nodeId()`, so it resolves plugin
nodes and nothing else. A `Plugin` endpoint naming a built-in node - the track's own
`builtin:track_gain`, say - resolves to -1, and `TimelineFacadeMixer.cpp:376` then rejects the
connection with "A plug-in graph endpoint no longer exists". uapmd-app's graph editor draws pins
for those nodes too and hits the same refusal, so neither app can wire them.

### 6.4 A freeze render's held notes become audible when the render ends

`uapmd-engine/src/sequencer/SequencerEngine.cpp`, `finishOfflineTrackRender`

The render drives the same plugin instances that feed live output, and is inaudible only because
the render exclusion suspends audio callbacks. Teardown calls `stopProcessing`, `loadStateSync`,
`startProcessing` and `resetProcessingState`, but never silences the instances, so a render that
ends between a note-on and its note-off - which is every cancelled one - leaves those voices held
and they sound as soon as callbacks resume. Symptom: starting playback during a freeze plays the
freezing track's ongoing note. Playback is not the source; ending the render is.

`Stop`, `Pause` and `Jump` all call `requestAllNotesOff()` here; ending a render does not.

Silencing needs both available mechanisms, and neither alone is sufficient: `requestStopFlush()`
emits note-offs only for notes tracked in `active_notes_`
(`uapmd-graph/src/node-graph/AudioPluginNodeImpl.hpp:36-39`), so it misses anything the render
sent by a path that bypassed that tracking, while `sendAllNotesOff()` emits CC 120 directly but
cannot stop held voices in formats without an All Sound Off event. Adding both at teardown did not
resolve the symptom, so which path the render's events actually take is still unknown and is the
next thing to establish.

### 6.5 A queued freeze track can be stranded by a playback request

`uapmd-engine/src/sequencer/FrozenTrackManager.cpp`, `requestPlaybackAfterBusyTrackRestored`

The function marks `render_deferred_until_transport_quiet` only on tracks whose state is
`Rendering`. A queued track sits in `RuntimeState::Live`, so it is skipped, and the following
`queued_renders_.clear()` drops it from the queue. It then belongs to neither the queue nor the
deferred set, and `transportBecameQuiet()` revisits only the deferred set, so the track keeps
`FreezePolicy::On` and never renders. Symptom: freezing several tracks leaves exactly one stalled
after playback is started and stopped - the rendering one recovers, a queued one does not.

Unconfirmed, and reproducing it needs real plugins: without them a render finishes in about a
second so nothing ever queues, and a clip long enough to queue trips `kMaximumFrozenTrackBytes`
and lands in `Error` instead.
