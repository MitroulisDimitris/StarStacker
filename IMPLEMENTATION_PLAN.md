# StarStacker — Implementation Plan

**Companion to:** [astro-camera-app-requirements.md](astro-camera-app-requirements.md) (v0.1 draft) and
[astro-app-ui-prototype.html](astro-app-ui-prototype.html)
**Plan version:** 1.5 · **Created:** 2026-08-16 · **Last updated:** 2026-08-19
**Target device:** Nothing Phone (3a) Pro (see §1.5)
**Repo state at creation:** requirements + UI prototype only. No Android project, no code, no git repo.
**Repository:** <https://github.com/MitroulisDimitris/StarStacker> — history is committed one phase
per commit, so a phase's whole diff reads as a unit.

---

## 0. How to use this document

This is the single tracking surface. Three things live here and nowhere else:

1. **Tasks** — one line per unit of work, with a stable ID (`T-1.4`), a status box, and an
   acceptance criterion. Tick the box when the acceptance criterion is demonstrably met on a
   real device, not when the code compiles.
2. **Decisions** (`D-n`) — architectural commitments. Each has a rationale and a reversal cost.
   If you change one, edit it in place and note the date; don't leave the old text.
3. **Open issues** (`OI-n`) — anything unresolved that will change the shape of the code.
   Each has a *needed-by* phase. An issue whose needed-by phase is current is a blocker.

**Status legend:** `[ ]` not started · `[~]` in progress · `[x]` done · `[!]` blocked (name the OI) ·
`[-]` cut / deferred out of v1

**Conventions**
- `FR-x.y` references point at the requirements doc, which stays authoritative for *what*.
  This document is only *how* and *in what order*.
- Every phase ends with a **checkpoint**: a thing you can do with the phone in your hand.
  If the checkpoint can't be demonstrated, the phase is not done regardless of ticked boxes.

---

## 1. Ordering — and where it departs from the requirements

The requirements' milestone list (§13) runs M1 instrumentation → M2 calibration → M3 capture.
**This plan reorders to put a working shooting mode first**, per the stated priority. Calibration
moves behind capture and stacking.

That reorder is safe, and the requirements already justify it: **FR-3.1.1** commits the app to
being genuinely useful at *Functional* tier — no calibration at all — with the exposure engine
falling back to `SENSOR_NOISE_PROFILE` and focus found by live HFR sweep each session. Every
calibration master is therefore optional input to a pipeline that must tolerate its absence.
Building the pipeline calibration-free first is the cheapest way to guarantee that property
instead of retrofitting it.

Two consequences to accept deliberately:

- **Per-session darks are not calibration.** FR-4.2.1 darks are captured by the capture engine at
  the end of every session. They ship in Phase 1C, not in the calibration phase.
- **The exposure engine runs on OEM noise data until Phase 6.** If Phase 1C measurement on the real
  device shows `SENSOR_NOISE_PROFILE` is too far off to pick a sane ISO, promote the noise-model
  step (§4.1.1 — 3 minutes, indoors) forward into Phase 1C. See **OI-9** for the trigger.

### Phase map

| Phase | Name | Requirements | Checkpoint |
|---|---|---|---|
| **0** | Foundations | §12 | App installs, night theme, session root picked, screens navigable |
| **1A** | First light | M1, §3, FR-6.1 | Tap a button → a valid DNG lands in the session folder |
| **1B** | Framing & focus | FR-6.3, §4.1.4 | Point the phone at the sky in the dark and see stars; focus locks |
| **1C** | Unattended session | M3, §5, §6, §9 | **Press start, walk away, come back to a folder of good subs** |
| **1D** | The interface | §1.15, prototype | Someone who has never seen it can start a session without reading a paragraph |
| **1E** | The second walkthrough | §1.17, prototype | Sessions can be found, named and deleted in the app; nothing fires the camera unasked |
| **2** | Registration & live gating | M4 (reg), M5 | Live per-frame accept/reject with real transforms; common-area readout |
| **3** | Stacking | M4 | Linear master out, comparable to Siril on the same subs |
| **4** | Session management | M5.5 | Capture and stacking fully decoupled; restack, multi-night |
| **5** | Auto-edit | M6 | Shareable stretched JPEG without a desktop |
| **6** | Calibration library | M2 | Flats, noise model, hot pixels, intrinsics; Full tier reachable |
| **7** | Wide-field & second camera | M7 | De-project/re-project; per-camera calibration; recommendation |
| **8** | Post-v1 | §14 deferred | Dithering, star trails, framing assistance |

**Phases 0 → 1C are the priority.** Everything after 1C is sequenced but not yet scheduled.

### Progress

| Phase | Tasks | Ticked | Status |
|---|---|---|---|
| 0 | 9 | 1 | **8 built and demonstrated**, ticked only where §0's bar is met. SAF throughput still unmeasured (T-0.5 / OI-5) |
| 1A | 6 | 6 | **complete 2026-08-18.** Probe, qualification, camera lifecycle, first light, DNG reader — the leak loop closed T-1.3 (§1.16) |
| 1B | 7 | 1 | **hardware-verified except what needs darkness** — see §5 and §1.7 |
| 1C | 17 | 1 | **every task built.** Blocked on the field, not on code: darks have never once executed, and no 45-minute session has been shot |
| 1D | 9 | 0 | **all nine built and photographed 2026-08-18** (§1.15). T-3.21's last piece waits on the session pane, which is now T-3.27 rather than T-6.1 |
| 1E | 10 | 2 | **all ten built and walked on the phone 2026-08-19** (§1.18–§1.20). **T-3.28** and **T-3.36** met their acceptances whole; T-3.33 and T-3.35's hard parts are demonstrated. Four defects fixed, two of them predating the phase: `KeyValue` crushed its label whenever a value got long (§1.19), and **the sensor's exposure ceiling was being enforced when the hardware does not enforce it** (§1.20, **D-28**). Unwalked: the naming prompt, a failing sweep |
| 2 | 7 | 0 | **T-4.0, T-4.1 and T-4.2 built 2026-08-19** (§1.22–§1.24) — the synthetic sky with ground truth, the analytic drift seed, and asterism matching. 53 tests between them. T-4.3 fits the correspondences; none of it has met a real star field |
| 3+ | outlined | 0 | not started |

> **The `Ticked` column counts `[x]` only.** §0 ties that to an acceptance demonstrated on a real
> device, and most of what is built is demonstrated in part — the count understates the app
> considerably and is meant to. What it measures is how much has been *proven*, not how much
> exists.
>
> **Phase 1B has now met every acceptance that does not require a night sky** (2026-08-17). The
> camera half is demonstrated: the device confirms the two-stream configuration by name, the
> repeating one-second RAW loop delivers at a metronomic 1000 ms, and the focus sweep steps the
> lens across its full range and reports each position correctly. Doing so found four HAL
> behaviours that were silently breaking the phase — §1.7 — of which the worst produced *plausible
> numbers from a saturated frame* rather than an error.
>
> What is left is genuinely the sky: stars visible in the preview (T-2.2), a focus curve with a
> real minimum and repeat sweeps agreeing (T-2.4), deliberate defocus recovered (T-2.5), and
> alt/az checked against a known star (T-2.6). Those boxes stay `[~]`, per §0.

---

## 1.5 Target device — Nothing Phone (3a) Pro

Chosen 2026-08-16 (OI-6). **Probed on real hardware 2026-08-16 — it qualifies.**
Model `A059P`, device `Asteroids`, **Android 16 (API 36)**, `arm64-v8a`.
Accelerometer, magnetometer, gyroscope and GPS all present.

### Measured qualification — all four hard requirements pass

| Check | Measured | Verdict |
|---|---|---|
| Hardware level | **LEVEL_3** (every camera) | Pass — the best tier Camera2 defines |
| RAW capability | Present, with `MANUAL_SENSOR`, `MANUAL_POST_PROCESSING`, `READ_SENSOR_SETTINGS`, `BURST_CAPTURE` | Pass |
| Manual exposure + ISO | ISO 50–12800, exposure 42 µs–49.6 s | Pass |
| **Max exposure** | **49.64 s** | Pass by a wide margin — FR-3.2.2's "warn under 10 s" is nowhere near triggering |

Tier: **FUNCTIONAL** (the ceiling until calibration exists in Phase 6).

### The cameras — five HAL devices, two published

`dumpsys media.camera` reports *five* camera devices; `getCameraIdList()` publishes **two**.

| ID | Discovery | Focal | Aperture | Sensor | RAW | Pitch | Focus | Max exp | Identity |
|---|---|---|---|---|---|---|---|---|---|
| **0** | listed | 5.56 mm | f/1.88 | 8.192 × 6.144 mm | 4096×3072 (12.6 MP) | **2.00 µm** | motor | **49.6 s** | **Main — the astro camera** |
| 1 | listed | 3.61 mm | f/2.2 | 5.22 × 3.93 mm | 4080×3072 | 1.28 µm | fixed | 0.48 s | Front — fails on exposure, correctly |
| 2 | **hidden** | 1.64 mm | f/2.2 | 3.67 × 2.76 mm | 3280×2464 | 1.12 µm | fixed | 34.9 s | Ultrawide |
| 3 | **hidden** | 13.30 mm | f/2.55 | 6.55 × 4.92 mm | 4096×3072 | 1.60 µm | motor | 36.1 s | Tele (periscope) |
| 4 | **hidden** | 5.56 mm | f/1.88 | 8.192 × 6.144 mm | 4096×3072 | 2.00 µm | motor | 34.9 s | Logical multi-camera fronting [2, 0, 3] |

The ultrawide and tele are **not absent — they are unpublished**. They sit behind logical camera 4,
which is itself unpublished, so neither `getCameraIdList()` nor a walk of published cameras'
physical children finds them. A direct ID probe does, and their characteristics read fine.
**All five open successfully** (T-1.3, 2026-08-16) — being unpublished does not make them
unreachable. Whether they also *capture* is OI-19.

### What the measurements settle

- **OI-17 resolved, favourably.** The main sensor bins 2×2 internally
  (`SENSOR_INFO_BINNING_FACTOR = [2,2]`): a 50 MP array delivered as a 12.6 MP Bayer frame. The
  platform reports the *already-binned* array as `SENSOR_INFO_PIXEL_ARRAY_SIZE` while
  `SENSOR_INFO_PHYSICAL_SIZE` covers the whole sensor, so pitch works out to exactly **2.00 µm** —
  double the native ~1.0 µm, with the full-well and read-noise benefits that implies. The trap
  didn't bite here, but the guard stays: `rawSmallerThanPixelArray` and `sensorBinsInternally` are
  now reported separately, because they are different questions and only one of them was ever true.
- **OI-3 confirmed on-device.** Among the guaranteed combinations: *"Preview with in-app processing
  and DNG capture: PRIV@1920×1080 + YUV@1920×1080 + RAW_SENSOR@4096×3072"*. Both the D-9 direct-RAW
  path and the YUV analysis fallback are available on the main camera.
- **OI-8 confirmed a non-issue.** `SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME`, so frame timestamps
  and `SensorEvent` timestamps share a clock. No offset estimation needed.
- **OIS is disableable.** `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION = [0, 1]` — mode 0 is OFF, as
  FR-6.1 requires for tripod use.
- **Sensor basics:** CFA `GRBG`, white level 1023 (10-bit), black level 64 on all channels,
  max frame duration 49.6 s.
- **The ultrawide is genuinely the weak one here** — 1.12 µm pixels at f/2.2 against 2.00 µm at
  f/1.88 on the main. FR-11.2's speculation that the ultrawide might be the best astro camera does
  not hold on this device. Its fixed focus remains a real advantage (immune to drift).
- **The tele is a real astro option** if it can be opened: 13.3 mm at f/2.55 with 1.60 µm pixels
  and a 36 s exposure ceiling. That is a longer focal length than most phone teles.

---

## 1.6 First light — measured DNG structure (2026-08-16)

A 10 s ISO 800 frame from camera 0, written by `DngCreator`. This is the ground truth behind
**D-13**, and the specification the T-1.6 reader is written against.

| Tag | Value | Consequence |
|---|---|---|
| Byte order / magic | `II` (little endian), 42 | Standard TIFF |
| `Compression` | **1 — uncompressed** | **OI-1 closed.** No lossless-JPEG decoder needed |
| `BitsPerSample` | 16 | One `ShortArray`, no bit unpacking |
| `PhotometricInterpretation` | 32803 (CFA) | Raw Bayer, not demosaiced |
| `ImageWidth` × `ImageLength` | 4096 × 3072 | Matches the probe's RAW size |
| `RowsPerStrip` | **1** | **3072 strips**, one row each — the reader must walk the strip table, not assume a single blob |
| `StripByteCounts` | 8192 each | 4096 px × 2 B — exactly one row, no padding |
| `StripOffsets[0]` | 30052 | Pixel data starts here and runs contiguously |
| `CFAPattern` | 1,0,2,1 = **GRBG** | Agrees with `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` |
| `BlackLevel` / `WhiteLevel` | 64 / 1023 | 10-bit data in a 16-bit container |
| `ActiveArea` | 0,0,3072,4096 | Whole frame is active — no margin to crop |
| `ExposureTime` | 2500000000/250000000 = **10.0 s** | The request was honoured exactly |
| `DNGVersion` | 1.4.0.0 | |
| File size | 25,195,876 B | 25,165,824 B of pixels + 30,052 B of metadata — confirming uncompressed |

The CFA data lives in **IFD0 itself**, not a SubIFD, which makes the reader simpler than D-13
originally assumed.

**Storage implication:** 24.0 MiB per frame. A 150-frame session is **3.6 GB**, and an hour of
12 s subs is ≈ **7.2 GB** — at the top of the requirements' "5–6 GB per hour" estimate (FR-5.4).
The planner's storage budget should use the measured figure, not the estimate.

---

## 1.7 The lens and the request pipeline — measured 2026-08-17

Phase 1B was written with no device attached. Running it against the real camera produced four
facts that no amount of reading the Camera2 documentation would have supplied, and three of them
were breaking the phase outright.

| What | Measured | Consequence |
|---|---|---|
| **Request pipeline depth** | A change to `LENS_FOCUS_DISTANCE` takes **9–10 frames** to appear in the capture results — independent of exposure length, and independent of how many requests are issued meanwhile | Every wait must be budgeted in *frames*, not seconds. `FramingSession.PIPELINE_DEPTH_FRAMES` |
| **Focus quantisation** | The VCM moves in steps of **~0.0374 dioptres**. A request lands on the nearest step | Sweep positions closer together than one step measure the same physical place twice |
| **Focus request of exactly 0.0** | Answered with the **hyperfocal** position (0.1216 dioptres), *not* the far stop. A request of 0.05 reaches 0.0468, and 0.010 reaches 0.009 — so 0.0 is a special case, not a limit | `FocusSweep.NEAR_INFINITY` — no sweep asks for exactly zero |
| **`LENS_STATE`** | Reports `STATIONARY` at intermediate positions **mid-move** | Arrival cannot be detected from `LENS_STATE`. `awaitStableFrame` requires two consecutive settled frames at the same position |

**The bug all four combined into.** `FramingFrame.generation` was stamped when the *pixels*
arrived, so with a ten-frame pipeline it named a request ten generations newer than the one that
actually produced the frame; `settled` then compared that number against an equally arbitrary one
and additionally demanded `appliedFocus == requestedFocus`, which on this lens is false forever.
The visible result was `settled=false` on every frame ever taken — which would have timed out
every position of every focus sweep, reported the sky as starless, and left the framing preview
permanently captioned *settling*.

The fix is not a widened tolerance. Requests now carry their generation on
`CaptureRequest.setTag()` and it is read back off the result, so a frame is judged against the
request that made it whatever the HAL's internal queue is doing. Exposure is still verified
exactly (D-21 — a frame that lies about its exposure is undetectable downstream). Focus is not:
the applied position is *recorded* rather than demanded, because with quantisation and a
hyperfocal special case, "where the lens actually is" is the useful fact and "where we asked it
to be" is not.

### The saturation trap

Indoors, a fully clipped frame reported **24–41 stars with a median HFR of 0.95 px** — numbers
indistinguishable from a well-focused sky. Saturation flattens the frame, the MAD noise estimate
collapses to zero, and a threshold expressed as a multiple of the noise collapses with it; the
detection floor was `1e-6` ADU, which is not a guard but a licence to detect everything.

`FrameStars.saturatedFrame` is now a **third answer**, distinct from "stars" and "no stars",
because the remedies are opposite: a starless dark sky means cloud and the advice is to wait
(FR-7.5), while a clipped frame means the exposure is far too high and waiting will not help.
The threshold floor is now half an ADU — a statement about the sensor's quantisation rather than
about floating point. This matters well past the focus sweep: FR-7.5 diagnoses a *collapse* in
star count as cloud, and phantom stars would have kept that collapse from ever being visible.

### Screen-off: D-22's argument is right and incomplete

Measured: the framing loop runs at a metronomic 1000 ms with the screen off — for about five
seconds. Then it stops. The process is **still alive** (Android's cached-app freezer, not a
crash), and 20/20 frames complete with the screen on as a control.

D-22 argued the screen-off case dissolves by construction, since no display surface is ever in
the capture session. That is true *about surfaces* and it is not what stops the loop — **process
lifecycle** is. Nothing an Activity owns survives the screen going off, however few surfaces it
holds. T-2.1's screen-off acceptance is therefore not achievable in Phase 1B at all; it belongs
to the T-3.6 foreground service, and is re-filed there (**OI-20**).

One number worth keeping for the Phase 1C budget: with the screen off, per-frame analysis rose
from ~80 ms to ~340 ms as the CPU clocked down. Still small against a 12 s sub, but it is 4×.

---

## 1.8 The sensor's own noise figures — measured 2026-08-17

The exposure engine runs on `SENSOR_NOISE_PROFILE` at Functional tier (T-3.1), so the first
question is whether this device reports anything real. It does. Swept across the ISO ladder with
the framing loop and read out of each frame's metadata:

| ISO | Full scale (e⁻) | Read noise (e⁻) | e⁻/ADU |
|---|---|---|---|
| 50 | 3785 | 5.64 | 3.95 |
| 100 | 3540 | 5.28 | 3.69 |
| 200 | 3134 | 4.70 | 3.27 |
| 400 | 2550 | 3.89 | 2.66 |
| 800 | 1858 | 3.01 | 1.94 |
| 1600 | 1204 | 2.35 | 1.26 |
| 3200 | 707 | 2.07 | 0.74 |
| 6400 | 387 | 2.03 | 0.40 |
| 12800 | 203 | 2.07 | 0.21 |

Read noise falls smoothly by a factor of 2.7 across the range and then flattens above ISO 3200 —
the ordinary "ADC noise divided by the gain in front of it" curve. **No dual conversion gain step
is visible**: the largest ratio between adjacent stops is 1.29 (ISO 400 → 800) against a
neighbourhood of 1.14–1.28, which is a trend and not a switch. `dualGainIso()` correctly reports
none, and FR-5.2's "ISOs at or above the dual-gain point" is therefore unconstrained here.

Two cautions recorded rather than resolved:

- **Full scale does not go as 1/ISO.** From ISO 50 to 12800 is 256×, but the reported full scale
  falls only 18.6×. A pure analog-gain model would predict the former. Either the OEM's profile
  is a fit that includes more than shot noise, or the reported figures are not what the derivation
  assumes. It does not affect the *relative* comparison the solver makes between ISOs, which is
  why it is a caution and not a blocker — but it is the reason OI-9 stays open until Phase 6 has
  a measured series to check the absolutes against.
- **Short test exposures broke frame/metadata pairing.** `RESULT_CACHE` was sized in entries, so
  at a 20 ms request it held 160 ms of history and nothing ever paired — every frame reported
  `settled=false` and the sweep collected nothing. Raised to 64.

### What the solver does with it

Run indoors under room light — a "sky" of 3659 e⁻/s, which is what a very light-polluted sky
looks like arithmetically — the engine lands where it should: **clipping-limited**, recommending
ISO 50 at 341 ms, with every candidate reporting the same 1.6 stops of headroom because they are
all held at the same background fraction. That degeneracy exposed a real gap: with headroom tied,
the choice was falling out of list order. The tiebreak is now explicit — longest sub wins, which
is the same light in fewer frames.

**And a bug of the same family as the saturation trap.** With a clipped test frame the solver
returned a confident `ISO 50 · 1.5 s` while the advisory printed beside it read *"the test frame's
own background is clipped — nothing can be measured from it"*. Every number in a solve descends
from the sky rate, and a sky rate read off a clipped frame is a lower bound with unknown slack, so
the recommendation was not uncertain but unfounded. A clipped measurement now yields no
recommendation at all. The diagnostic had the matching flaw — it measured the sky from whichever
frame came last, which is the *highest* ISO of the sweep and so the likeliest to be saturated; it
now takes the brightest unclipped frame.

---

## 1.9 First unattended session — measured 2026-08-17

The capture engine ran end to end on the reference device: **53 frames (50 lights + 3 darks) at
ISO 400 / 1 s, screen off for most of it, session complete and logged.**

| What | Measured | Consequence |
|---|---|---|
| **Per-frame overhead** | **2 ms** beyond the exposure — 53 frames in 52.0 s at a 1 s sub | The 25 MB DNG write is entirely hidden behind the next exposure. Capture runs at essentially 100% duty cycle, so `SessionPlanner`'s `overheadSeconds` is ~0 rather than the seconds it was allowing for |
| Sustained write | 25 MB/frame at 1 frame/s, no back pressure | Storage is not the bottleneck at these rates |
| DNG structure | Byte-identical in size and structure to §1.6's ground truth | The `SessionFolder` stream path writes valid DNGs |
| Battery temperature | 31 → 32 °C over the first minute | D-16's monotonic warming curve is real and visible per frame |
| Thermal headroom | 0.67 → 0.72 across a minute | Nowhere near the 0.85 pacing floor; OI-11 still needs a full session |
| Live gating | All 50 lights correctly rejected `SATURATED` | The phone was face-up under room light, so they genuinely were clipped — the §1.7 saturation guard working in the capture path, not just the framing one |

**T-3.7 and T-3.13 demonstrated by killing the process.** A 30-frame session was killed outright
with `am force-stop` 14 seconds in: the log said `CAPTURING` with **12 frames recorded and exactly
12 DNGs on disk**, which is T-3.7's acceptance on a real kill rather than a simulated one.
Restarting with a resume then continued *the same folder* from frame 13 through to 30 — indices
contiguous 1..30, one session directory, state `DONE`. The engine needed no separate resume path
because it starts from `lights.size + 1`, so resuming is the ordinary code path given a log that
already has frames in it.

Three structural points that fell out of building it:

- **Capture needs its own session class.** `SequenceSession` is not `FramingSession` with a flag.
  Framing calls `acquireLatestImage()` and drops frames when analysis is busy, which is right for
  a preview and silently shortens the integration the planner promised. Capture uses
  `acquireNextImage()`, applies back pressure instead of dropping, and keeps the whole
  `TotalCaptureResult` because `DngCreator` needs the result object itself.
- **The image is handed to the writer still open**, so a 25 MB frame goes from the sensor buffer
  to the file without an intermediate copy. It is held only for the duration of the write, since
  the camera is one buffer short until it is released. Three RAW buffers: one being written, one
  just captured, one for the sensor to fill.
- **Metrics are measured after the bytes are down, and the record is amended.** The frame is
  written and logged first, then analysed, then the log entry is updated with HFR, star count and
  the gate's verdict. The alternative — analyse first, then write — holds a sensor buffer across
  130 ms of detection for no benefit, and risks logging a frame that then fails to write.

---

## 1.10 The bump detector was measuring the wrong thing — 2026-08-17

> **Superseded 2026-08-18 (§1.13).** The conclusion below — accelerometer, 1.0° — was still
> measuring the wrong thing, one level down. An accelerometer cannot separate rotation from
> translation either, and on a tripod extension arm it rejected 49 of 105 frames while flagging
> the sharpest ones. It now reads the **gyroscope**, which is blind to translation, at 0.5°.
> The reasoning below is kept because its arithmetic is still right and its error is instructive.

The first live capture screen showed six of seven frames rejected as **BUMPED** while the phone
lay untouched on a desk. The numbers in the log said `0.4 m/s² of movement (limit 0.4)` — the
threshold was sitting exactly on the sensor's noise floor.

Raising it would have hidden a deeper error. The detector was tracking the **magnitude** of the
accelerometer vector, and `|a|` is very nearly invariant to rotation: gravity is 9.81 m/s²
whichever way the phone faces. So the measure was almost blind to *tilt*, which is the motion that
moves the star field, while being sensitive to linear shake, which mostly does not. It now
measures the **angle between the current gravity vector and a smoothed one**, in degrees, which is
the quantity that corresponds to the field moving.

**And the honest conclusion that fell out of doing the arithmetic:** at the reference camera's
plate scale, the trailing tolerance of 1.5 px is **0.031°** — far below the accelerometer's own
noise. The accelerometer therefore *cannot* see the motion that matters. It can only see a tripod
being knocked, so the threshold is set for that (1.0°) and nothing finer, and the sub-pixel case
belongs to registration residuals in Phase 2 (FR-7.2), which measure the frame instead of the
phone. Pretending otherwise would have produced a gate that rejected good frames and missed the
drift it was supposed to catch.

Re-run with the phone untouched: nine frames correctly diagnosed `TRAILED` (face-down in a dark
room, so the detections are hot pixels, which are genuinely elongated) and one `BUMPED` on the
frame during which the phone was actually disturbed at launch.

> **A note for Phase 6.** A 1.5 s dark frame on this sensor yields ~1300 detections. At a 5σ
> threshold over 786k pixels, chance alone predicts under one, so these are **hot pixels** — which
> is FR-4.1.2's map arriving unbidden, and a useful sanity check that the detector finds them.

---

## 1.11 Getting it ready for a field test — 2026-08-17

The owner asked whether the app was ready to take out, and what to press. Answering that
honestly meant walking the flow as a user rather than as its author, and **it found three real
defects that no test would have caught, because none of them is a wrong answer — each is a
missing question.**

| Found | Why it mattered |
|---|---|
| **Nothing prompted the user to cover the lens** before darks (T-3.12) | The sequence rolled straight from lights into darks. An unattended session would have filled `darks/` with light frames, and **nothing downstream can tell a light frame in a darks folder from a dark** — it would have quietly poisoned every master built from that session |
| **Resume was unreachable from the UI** (T-3.13) | The machinery worked and was wired only to the `adb` diagnostic. A session dying at 03:40 was unrecoverable without a laptop — which is the exact and only situation the feature exists for |
| **The dark-sky branch chose the quietest ISO** rather than the ISO-invariance point (T-3.3) | The branch a *genuinely dark sky* takes. On this sensor read noise is flat above ISO 3200 — 2.07, 2.03, 2.07 e⁻ — while full scale halves at every step. Choosing the minimum outright picks ISO 6400, at 387 e⁻ of well, clipping every star of any brightness to save **0.04 e⁻**. Once read noise stops improving, more gain buys nothing and costs all the highlight range there is |

The pattern is worth naming. Phase 1B's bugs were found by *running* code against hardware
(§1.7–§1.10); these were found by asking **"what does the user do next, and what happens if it
fails?"** The first kind is caught by tests and instruments. The second kind is only caught by
someone walking the path — a test suite cannot fail an assertion about a prompt that was never
written.

### Two things added because failing well matters more than working

- **`AWAITING_DARKS` is a session state, not a UI flag.** The session can be killed while waiting
  and has to come back knowing it owes darks and not lights. The sensor is stopped during the
  wait, and the wait times out after 15 minutes: waiting forever holds the camera and the wake
  lock all night for someone who has gone to bed, while skipping instantly throws away the darks
  of someone standing right there. Finishing cleanly and recording *why* there are no darks is
  the only behaviour that is honest in both cases.
- **Focus can be set by hand** (T-2.4). The sweep needs measurable stars at several lens
  positions; under thin cloud or a bright sky it correctly returns `TOO_FEW_STARS` and there is
  nothing to store. That was an honest failure and a dead end. The lens can now be walked one
  measured motor step (0.0374 dioptres) at a time against the live HFR readout.

**The fallback nobody has to think about:** with no stored focus the capture request passes 0.0
dioptres, which this HAL answers with the hyperfocal position — and hyperfocal has infinity
inside its depth of field by definition. A session shot with no focus step at all is *soft, not
ruined*. That is the right failure mode for something that can go wrong at 1 a.m., and it is
worth stating because it is the difference between abandoning a clear night and shooting it.

---

## 1.12 The DNGs carry sensor truth and no session truth — 2026-08-18

The owner noticed the frames are thin on metadata. They are, and the shape of the gap is precise:
**everything `DngCreator` derives for itself is present; everything it has to be told is absent.**

`SequenceSession.writeDng` constructs `DngCreator(chars, result)` and calls `writeImage`. That
yields the sensor's own description of the frame — geometry, `CFAPattern`, black and white levels,
`ActiveArea`, exposure, ISO, colour matrices, noise profile, make and model (§1.6 measured the
ones the reader depends on). What it does not yield is any statement of *which session this frame
belongs to and what was happening when it was taken*.

`DngCreator` exposes exactly four metadata levers, and the app currently uses **none** of them:

| Lever | Tag written | Status |
|---|---|---|
| `setDescription(String)` | `ImageDescription` (270) — free text | Never called |
| `setLocation(Location)` | GPS IFD | Never called |
| `setOrientation(int)` | `Orientation` (274) | Parameter exists on `writeDng`; `CaptureEngine` passes nothing |
| `setThumbnail(...)` | Embedded preview | Never called |

Everything else it computes from `CameraCharacteristics` and the `TotalCaptureResult` and will not
let you override. **There is no API for arbitrary TIFF tags**, so anything astro-specific — frame
kind, sensor temperature, sky background, HFR, star count, target, gate verdict — has nowhere
structured to go. DNG has no tag for most of them in the first place; FITS is the format with that
vocabulary, and these are not FITS files.

**Why this has not hurt yet, and when it will.** D-5 makes `session.json` the source of truth, and
it already holds every one of those numbers per frame. Inside this app's own pipeline the DNGs do
not need to be self-describing. The moment they leave — handed to someone else, imported into
Siril or PixInsight next winter, or simply separated from their folder — a frame that cannot say
what it is becomes a frame you have to guess about. `lights/` and `darks/` convey frame kind by
directory alone, which survives exactly as long as nobody moves a file.

**The fix is cheap and it is the free-text one.** `setDescription` takes a string; a compact
`key=value` record costs nothing per frame, is read by exiftool, Siril and PixInsight alike, and
carries the whole per-frame log entry. Injecting real TIFF tags instead would mean rewriting all
**3072 `StripOffsets`** when the IFD grows (§1.6: `RowsPerStrip` is 1) — a genuine risk of
corrupting the pixel data to gain a tidier place to put text. Not worth it.

**Not yet audited:** no exhaustive tag dump of a real capture exists. §1.6 listed the tags the
reader consumes, not everything present. T-3.16 starts by dumping one, because this document's
rule is that measured beats assumed.

---

## 1.13 What is actually in a DNG, and what the gate did on sky — 2026-08-18

### The tag dump T-3.16 asked for

54 tags in IFD0, and **no Exif sub-IFD and no GPS IFD at all**. §1.6 listed the ten the reader
consumes; four of the rest change what was assumed:

| Tag | Measured | Why it matters |
|---|---|---|
| `ImageDescription` (270) | **present, empty** | Not absent as §1.12 assumed. `setDescription` fills a tag that is already there |
| `Orientation` (274) | **9** | TIFF defines 1–8. An undefined value leaves every reader free to invent one; now set to 1 |
| `BlackLevel` (50714) | **6425/100 = 64.25**, not 64 | §1.6 recorded 64. A quarter-ADU pedestal across 12.6 M pixels is not nothing when the sky background is ~81 ADU |
| `DefaultCropOrigin`/`Size` | **8,8** and **4080×3056** | §1.6 said "whole frame is active — no margin to crop" from `ActiveArea` alone. There *is* an 8 px margin the DNG asks readers to trim, and a stacker that honours it while our own code does not would be working on a different frame |
| `OpcodeList2` (3908 B), `OpcodeList3` (88 B) | present | Lens-shading gain map and warp, to be applied on read. **A stacker that honours OpcodeList2 already flat-fields the frame** — FR-4.1.3's own flats would then be a second correction on top of the first |

The colour description is complete and does not need help: `ColorMatrix1/2`,
`CameraCalibration1/2`, `ForwardMatrix1/2`, `AsShotNeutral`, `NoiseProfile`, both calibration
illuminants. What was missing was never the sensor's account of itself — it was the session's.

### The gate, re-measured on sky

Session `2026-08-18_0123` ran on the rebuilt gate, and both fixes hold:

| | Session `0050` (before) | Session `0123` (after) |
|---|---|---|
| Accepted | **0 of 105** | **42 of 49** |
| `TRAILED` | 56 | **0** |
| `BUMPED` | 49 | 7 |

The `TRAILED` rejections vanished while the eccentricity itself did not change at all — median
0.873 against 0.855, still far over the 0.6 limit, still meaningless at HFR 0.99. The check is
skipped rather than passed, which is the honest outcome for a measurement the sampling cannot
support.

**The gyro's seven rejections are the interesting part, because they are all real.** Frames 1–4
(2.80°, 0.90°, 0.69°, 0.54°) are the phone settling after the start button; frames 47–49 (34.13°,
19.17°, 38.50°) are it being picked up at the end. The 42 frames in between are clean. The
accelerometer it replaced flagged the *sharpest* frames in the session.

> **One residual, and it is the window rather than the sensor.** Frames 47–49 report tens of
> degrees while carrying 93–200 stars at HFR ~1.0 — pixels that a 34° rotation could not leave
> behind. The peak is accumulated between *consume* calls, so it spans the readout and the DNG
> write as well as the exposure. Motion during the gap rejects a frame whose pixels are fine.
> The fix is available and cheap: the device profile reports `timestampSource: REALTIME`, which
> means `SENSOR_TIMESTAMP` and `SensorEvent.timestamp` share a clock, so the peak can be queried
> over exactly `[timestamp, timestamp + exposure]`. Without REALTIME the two clocks would not be
> comparable and this would not be possible at all.

---

## 1.14 SENSOR_TIMESTAMP is not what the documentation says — 2026-08-18

Scoping the bump check to the exposure (§1.13's residual) needed the exposure's start and end on
the gyro's clock. Two device facts had to be measured before that was possible, and one of them
contradicts the API documentation outright.

**The gyro's delivery rate is not the constant you name.** `SENSOR_DELAY_GAME` delivered at about
**400 Hz**, not the ~50 Hz the name suggests, so a 4096-sample ring buffer spanned under ten
seconds. Every query for a 7.4 s sub — asked after the readout and the 25 MB write — fell off the
back of the record. The listener now registers an explicit sampling period.

**`SENSOR_TIMESTAMP` is the end of exposure on this device, not the start.** The documentation
says "time at start of exposure of first row". Measured with 7.4 s subs:

| | Measured |
|---|---|
| Analysis, relative to the frame's own timestamp | **+3.35 s, +3.36 s, +3.38 s** — stable |
| Exposure length | 7.40 s |
| Gap between consecutive timestamps | 7.399 s — exactly one exposure |

A frame cannot be analysed 3.36 s after its exposure *started* when the exposure lasts 7.4 s. Both
figures fit a timestamp taken at the end of exposure and nothing else, so the window is
`[timestamp - exposure, timestamp]`.

**Why this mattered more than it should have.** With the sign wrong the window sat entirely in the
future, the gyro record could not reach it, and every query returned "unmeasured" — which
[FrameGate] correctly treats as "skip the check". The bump detector was **silently off**, and an
accepted frame looks identical whether the check passed or never ran. It now logs the rotation per
frame, because "unmeasured" and "did not move" are the same verdict and very different facts, and
`DeviceEnvironment` warns with both intervals when a window falls outside the record.

### What the detector is actually worth, now that it measures the right interval

A phone lying still, 7.4 s subs: **0.013°, 0.020°, 0.021°, 0.017°**. That is the noise floor of the
whole chain — sensor, zero-rate estimate and integration — over a real sub.

Two consequences. The 0.5° threshold has **25× margin** over it, so false rejections are not a
risk that needs managing. And the floor sits just under the 1.5 px trailing budget of 0.031°,
which means a threshold at the trailing tolerance itself is *almost* but not quite supportable —
worth revisiting with a warm phone and a longer sub before tightening, since 0.5° currently passes
24 px of real rotation.

> **The bias estimate is the mean of the settling window, not the first sample.** Seeding from one
> sample assumes the phone is still at the instant the service starts, which is exactly when it is
> not — the user has just pressed Start. Measured that way, a stationary phone integrated **110°**
> on its first frame and decayed 13° → 7.5° → 6.7° → 6.5° as the estimate crawled toward the truth
> with a 30 s time constant. Every degree of it was phantom.

---

## 1.15 The interface drifted off the prototype — 2026-08-18

The owner walked the app against `astro-app-ui-prototype.html` and the gap is structural, not
cosmetic. Worth writing down *how* it happened, because the mechanism will repeat otherwise.

**The main screen was never built.** What sits there is the **capability probe** — device model,
qualification verdict, per-camera capability tables, sensor list, camera-open tests, profile export.
That screen was correct for Phase 1A, when the only question was "does this device work at all",
and it was never replaced. The prototype's main screen is a different thing entirely: *Start a
session*, a calibration banner, recent sessions with thumbnails, and a free-space / temperature /
moon strip. The probe is a diagnostic that ended up wearing the front door's clothes.

**Everything since has been bolted to it.** T-0.5 put the session-root card there, T-0.6 the field
log, because each needed somewhere to live. T-0.9 moved them into Settings, which was right, and
then Settings inherited the same disease.

### The correction, stated as a rule

> **The UI explains what the user must decide. Everything else is a comment.**

The settings screen shipped with a paragraph justifying why the app is dark, a paragraph on why the
camera permission is needed, and a paragraph on where files live and what happens on uninstall.
None of that is a decision the user makes. Dark is self-evident once seen; a camera app needing the
camera is self-evident; and storage has a sensible default that most users will never change. The
reasoning belongs in KDoc and in this document, which is where the rest of it already is — writing
it into the interface put the author's thinking in front of someone standing in a field at 2 a.m.

Consequence is not the same as justification, and survives: "without notifications the darks prompt
never appears" is a fact the user cannot deduce and would be hurt by not knowing (T-0.4). "The app
is dark because dark adaptation takes 25 minutes" is a fact they can see.

### What the prototype actually specifies

| Screen | Shape |
|---|---|
| **Main** | `Start a session` as the one bright element · calibration banner *below* it so it can never read as a gate · recent sessions as rows with thumbnail, target, `142/150 · 28m 24s` and an **action** badge (`Stack now`) rather than a status word · `All sessions · 12` · bottom strip of free space, device temperature, moon |
| **Setup** | camera chooser with a one-line reason per camera · the solve as **one line** with `Show work` a tap deeper · common-area warning stated *before* committing · `Adjust` / `Start` foot |
| **Live** | preview with `Stack` / `Last sub` tabs · **per-frame tick ring**, one tick per frame coloured kept / rejected / remaining, with a leading-edge dot · stats beside it · metric grid · recent-frame log with reasons · `Pause` / `End & take darks` foot |

The current ring is a single arc. The prototype's is 150 individual ticks, which is a different
object: it shows *where the rejections fell*, not just how many.

---

---

## 1.16 Fifty opens and closes, and a leak check that had to be fixed first — 2026-08-18

T-1.3 has claimed since 2026-08-16 that `CameraAccess` releases the camera on every path, and the
claim was reasonable: `withDevice` closes in a `finally`. It had never been run more than a handful
of times in a row. Running it fifty times found nothing wrong with the wrapper and three things
wrong with the *measurement*, which is the more useful outcome — a leak check that cannot tell a
leak from a warm-up is worse than no leak check, because it is believed.

`diag/CameraLifecycleCheck.kt` runs four phases, since the path that was always going to work is
not where a leak lives: **open/close** through `withDevice`, **configured sessions** (a whole
`FramingSession`, two `ImageReader`s and a capture session, streamed and closed), **throw** inside
the block, and **cancel** around the moment the device arrives. The evidence is per-cycle file
descriptor and thread counts from `/proc/self`, plus `CameraManager.AvailabilityCallback` — the
camera *service's* account of whether the camera is free, which is the question the acceptance asks
and not the one our own `close()` answers.

### The result

| Phase | Cycles | Descriptors | Threads | Verdict |
|---|---|---|---|---|
| open/close | 50 | 173 → 173 across the warm 25 | 37 → 37 | none |
| configured sessions | 30 | 173 → 173 | 44 → 45 (+0.07/cycle) | none |
| throw inside `withDevice` | 25 | 173 → 173 | 45 → 45 | none |
| cancelled mid-open | 36 | 173 → 173 | 45 → 45 | none |

Open costs **10/16/35 ms** (min/median/max). The camera service reported the camera available again
after **all 141 opens**, usually within a millisecond of `close()` returning and occasionally
*before* it — the release happens inside the call, not after it.

### Three ways the measurement lied before the code could

**Warm-up is shaped exactly like a leak.** A clean loop climbs 132 → 173 descriptors over its first
eight cycles — the vendor camera stack starting its own threads, once — and is then flat for the
remaining forty-two. Measured end to end that is +0.8 descriptors per cycle, which is a confident
report of a leak that does not exist. Dropping the first sample was not enough: at six cycles the
session phase convicted on +1.00 threads per cycle, and a thirty-cycle run then showed that same
count flat at 46 from cycle sixteen onwards. The rule now needs **twenty settled cycles** before it
will judge at all, and judges on two statistics — the rate across the warm tail, and the *median*
per-cycle step, because a leak costs its descriptor on every cycle while a warm-up is a few large
steps among zeroes. A phase too short to outlast its own warm-up reports `INCONCLUSIVE`, which
fails the run: the fix is more cycles, not a lower bar.

**Re-opening the camera proves nothing.** A second `openCamera` for a device this process already
holds does not fail — the framework disconnects the first client and hands the camera over. A leak
detector built on "can we open it again" would pass a process that had leaked all fifty devices.
Only the descriptor counts and the service's own callback can see a leak from inside the leaker.

**`resolveActivity` answers with the chooser.** The handoff dutifully started
`com.android.internal.app.ResolverActivity` and then waited 25 s for a dialog to open a camera.
Choosing the component explicitly fixed that, and the fixed version *still* reported nothing —
because this phone's camera app opens **camera 4**, the unpublished logical device, which is absent
from `getCameraIdList()` and so never appears in an availability callback. The camera service's own
client log is the authority, and it is unambiguous:

```
18:27:48 : DISCONNECT device 0 client for package com.starstacker
18:27:49 : CONNECT    device 4 client for package com.nothing.camera
```

One second after the loop's last close, with our process still running, the phone's own camera app
had a camera. That is the acceptance's second clause, demonstrated rather than argued.

### One thing fixed in code, and one left open

Two `resume` sites in `CameraAccess` checked `isActive` and then resumed, leaving a window in which
cancellation lands between the check and the resume and **nobody owns the device** — which locks
the camera for the whole phone until the process dies. Both are now `resume(value) { release }`,
which the coroutine machinery resolves atomically. Thirty-six cancellations did not hit that
window; it is microseconds wide, so this is a correctness argument rather than a measurement, and
it is recorded as one. `warmUp` now also stops its repeating request when cancelled, which it did
not: the sensor stayed hot for a caller that had gone away.

**New OI-22.** One configured session in seventy-eight delivered nothing at all — 0 of 2 frames
against a 12.4-second budget, immediately after the fifty-cycle open/close loop, where every other
session configured in ~100 ms and delivered at once. The session opened, configured and closed
cleanly; only the frames never came. In the field that is a framing preview which stays black for
twelve seconds after the camera has been busy.

---

---

## 1.17 The second walkthrough — 2026-08-18

Phase 1D put the three main screens back on the prototype. Walking the result found a different
class of problem: not screens built to the wrong shape, but controls that are in the wrong place,
answer a question nobody asked, or quietly do something expensive on their own. Ten tasks, Phase
1E, and two decisions that reverse things written earlier in this document.

**Two reversals, both because the earlier reasoning was about the app rather than the person.**

- **T-3.25 solved on arrival** because "solving is what this screen is *for*, so it does not wait to
  be asked". That is true of the *screen* and false of the *cost*: the measurement fires the camera
  and spends frames the moment the screen appears, so a mistaken tap costs a sky measurement and
  the user cannot tell what the app is doing or why the phone got warm. **D-27** generalises the
  correction rather than patching the one screen.
- **`MAX_STOPS = 2.0`** was justified as "past that the solve is not being adjusted, it is being
  ignored". The premise was that a large override is a mistake. But the histogram sits directly
  above the control and shows the consequence — clipped, or read-noise limited — so the range can
  be as wide as a camera's is and the picture does the arguing. ±4 stops, marked in whole stops
  like every exposure-compensation dial ever made.

**One decision genuinely changes.** D-10 says nothing is ever deleted by the app, which was written
about *the app's own judgement*: a frame the gate rejected stays on disk, because the app does not
get to decide someone's data is worthless. A person deleting their own session is not that, and a
phone that fills up with 3.6 GB sessions and offers no way to clear them is not honouring D-10, it
is hiding behind it. **D-26** states the distinction.

**The rest is placement.** `All sessions` opened a file manager, which is where the folders are but
not where the *sessions* are. Focus by hand was a permanent card scrolled far from the preview it
has to be judged against — so it is open when it is not needed, and out of sight at the one moment
it is (a sweep that just failed). Nothing said what continuing without focus costs, though the app
walks to setup perfectly happily without it. The histogram had no title, so the one picture that
makes "sky-limited" checkable was unlabelled. And the exposure readout said `as solved` where the
useful thing to say is the number itself, and then both numbers when it changes.

---

## 1.18 Phase 1E built — three defects, one of them found by a test — 2026-08-19

All ten tasks of §1.17 are built. None has run on the phone, and that is the entire remainder: the
device was not attached, so nothing below is a hardware claim.

**The two defects the plan predicted were both real, and a third was not predicted.** T-3.35's pair
came out as described — the session-length bound was computed from the uncompensated sub, and the
compensated sub was never clamped to the sensor's ceiling. Both are now in
`exposure/ExposureCompensation.kt` rather than in `SetupController`, and that move is the point:
the controller needs a `Context` and cannot be unit-tested, while these two defects look exactly
like ordinary numbers on screen. A frame bound that is 4× too generous and an exposure the HAL
quietly truncates are not things a screenshot shows.

The third came out of a test written for T-3.30, and it is the more interesting one. The rule that
stops a folder carrying its date twice was `cleaned.startsWith("$day-")`, which is true of
`2026-08-18` and `2026-08-18-2` — the generated defaults — and **also true of `2026-08-18-comet`**,
a name a person chose. That name would have been dropped from the folder entirely, surviving only
in `session.json`, leaving a folder on disk that looked like an unnamed session. The suffix now has
to be all digits. Nobody would have found this by using the app; it needed a test that asked what
happens to a name that merely *begins* like the default.

**One deviation from §1.17, and one thing left genuinely open.**

- **T-3.30's `Not now` does not start the session.** The task says a cancelled prompt still starts,
  named for the day. But a naming step with no way back makes a mistaken Start unrecoverable, which
  is the class of problem §1.17 exists to correct — so cancelling returns to setup, and *clearing
  the field and starting* is the route to the default name. The field is pre-filled with that
  default, so the alternative is visible rather than described. This inverts in one line if the
  owner prefers the literal reading.
- **T-3.31 answered the placement, not the control.** "The focus by hand is as is, useless" was read
  as the sentence that follows it — the disclosure — and the ±1-motor-step buttons are untouched. If
  the control itself is the complaint, that is still open and still wants a number: which step, and
  judged against what.

**What `[~]` is carrying here.** Four things can only be settled with the phone: that arriving at
setup really leaves the camera closed (T-3.33 — the one acceptance a laptop cannot even partly
answer), that a failing sweep opens the disclosure (T-3.31, which needs a sky that will not
converge), what the root scan costs on a real root (**OI-5**, now self-timing every time the pane
opens), and whether a document provider honours `deleteDocument` (T-3.28, which reports a refusal
per session rather than assuming success, but has never met a provider).

---

## 1.19 Walked on the phone, and a layout defect the owner found first — 2026-08-19

The device came back, so §1.18's "none of it has run on the phone" is now partly answered. What was
walked: the main screen, the session pane, a session's detail, framing, setup, the sky measurement,
the exposure dial and the plan. What was **not**: starting a session through the naming prompt,
completing a deletion, and a sweep that fails.

**The owner found a layout defect before any of that, and it was mine but not new.** In session
setup, with the storage warning showing, `Storage` rendered **one letter per line, stacked
vertically**. The cause is in `KeyValue`, which every readout card in the app is built from and
which has been there since Phase 1C: the value was **unweighted** and the label carried
`weight(1f)`. A `Row` measures unweighted children *first*, against the full width, and divides
what survives among the weighted ones — so a value like `needs 399.9 GB, 46.1 GB free — this session
will not fit` took the entire row and the label was measured at a few pixels.

Two things about that are worth keeping.

- **It only appears when it matters most.** Short values — `ISO 800`, `done`, `A059P` — leave plenty
  of room, so every screen looked correct for three phases. The strings long enough to trigger it
  are exactly the ones the storage and battery budgets emit *when the session will not fit*: the
  layout broke precisely when it had something urgent to say.
- **The rule is now stated where the component is**: the row degrades by wrapping the value, never
  by crushing the label. Both sides are weighted, 1 : 1.7, and the value is end-aligned so short
  values sit exactly where they always did.

Reproduced on the device by compensating to −3 5/6 stops and dragging the session length to its end:
17010 × 519 ms wants 399.9 GB against 46.1 GB free. Both the `Storage` and `Battery` rows now wrap
onto two right-aligned lines with their labels intact, and the red banner below Start says the same
thing in prose.

**Two defects of my own in the new pane, found by looking rather than by testing.** An unnamed
session is *named for its start time*, and the row then printed `started 20:39` under a title
reading `20:39` — one fact, stated twice. And the `Captured` badge was centred against a
description that wraps to two lines on a long session, so it floated halfway down the row belonging
to neither. The badge is top-aligned now and the redundant line is suppressed when the label already
is the clock. The detail screen had the same disease in miniature: `Tue 18 Aug 2026, 20:39 · 18 Aug
· 0/14 · …`, dating the session twice in one breath.

**What the walk confirmed.** Arriving at setup takes no frames and leaves the camera closed —
`dumpsys media.camera` reports no open device — and the cost is stated first: *"9 test frames of
0.25 s — one per ISO from 50 to 12800"* (**T-3.33**, the acceptance §1.18 said a laptop could not
even partly answer). The dial moves in sixths and reads `−3 5/6 stops`, the scale is marked −4…+4,
and **the session-length bound follows the compensated sub** — `1 frame to 147 min` at 519 ms, where
the old code would have offered a bound computed from 7.4 s (**T-3.35**). The pane lists all four
sessions with their real sizes (1.0 GB, 96 MB, 1.1 GB, 2.5 GB), a row opens to its frame log with
the rejections and their numbers intact, long press selects and the count tracks it, and the
deletion confirmation names `19:09 · 4 lights · 96 MB` before anything happens.

**Nothing was deleted.** The confirmation was opened and cancelled: the four sessions on that phone
are real captures from the field, and T-3.28's remaining acceptance is worth less than they are. It
wants a session nobody minds losing.

---

## 1.20 The sensor's stated exposure ceiling is not enforced — measured 2026-08-19

`SENSOR_INFO_EXPOSURE_TIME_RANGE` on the rear camera reports an upper bound of **49.6406 s**. T-3.35
clamped to it, on the reasoning that asking for more "gets silence or a truncated frame, not an
error — D-21's whole family". **That reasoning was an assumption from the Camera2 contract, which
says out-of-range values are clamped, and the device disagrees with it.**

Asked for 120 s through the capture path:

```
requested   120.000000000 s
APPLIED     119.999987713 s      <- 12 us short, honestly reported
25 MB DNG, background 165 ADU, gate accepted it
```

Repeated at 90 s after the change below: **89.999999662 s**. The ceiling is advertised, not
enforced, and it was refusing exposures the hardware was perfectly willing to take.

### This is not academic, because the trailing limit diverges at the pole

The trailing limit scales as 1/cos(declination). On this lens — 2.0 µm pixels at 5.6 mm, so
73.7 arcsec/px, 1.5 px of tolerance — it runs:

| declination | trailing limit |
|---|---|
| 0° | 7.3 s (and the solver picked 7.4 s, which is the check that the arithmetic is right) |
| 70° | 21.5 s |
| **81.5°** | **49.7 s — crosses the stated ceiling** |
| 85° | 84 s |
| 88° | 210 s |

**Above about dec 81.5° the sky permits longer subs than the stated ceiling allowed**, so every
circumpolar target — a normal thing to shoot from northern latitudes — was capped by a number the
sensor ignores. And the cap was in two places, not one: the compensation dial, and
`SetupController.resolve`'s `maxExposureSeconds`, which capped the *automatic solve* as well.

### What replaces the clamp

**D-28**: ask, then verify. The clamp is gone from both places; the solver's ceiling becomes
`max(stated, 240 s)` — a sanity bound about *dark current, aeroplanes and field rotation*, not about
the sensor — and the other half of the solver's `min` is the trailing limit, which is the real
constraint.

The guarantee moves to where it can be measured rather than predicted: **`nextVerifiedFrame` already
checked every frame's own metadata against the request** (D-21), and now, for requests past the
stated ceiling, gives up with `ExposureRefused` instead of skipping. That distinction is the whole
point. Skipping is right while the sensor settles and catastrophic if it never will — at 120 s a
clamping device would discard a two-minute frame, then another, until the session budget was gone,
and then report a **timeout**, which names the wrong problem entirely.

`ExposureAttempts` holds the rule, in pure Kotlin with no Android imports, because it decides
whether a night is abandoned. It carries one subtlety worth stating: **a frame skipped for its
generation is not evidence of refusal.** The generation guard exists for darks — after the sensor
restarts with the lens covered, frames from before the cover are still in flight, right exposure,
wrong generation. Counting those would abandon every session that takes darks, which is precisely
the opposite of the failure being guarded against.

**No warning on screen.** A line about crossing a ceiling that is not enforced would be warning
about nothing; the check that matters measures what the sensor did.

### Where the real ceiling is — measured 2026-08-19, and there isn't one within reach

| requested | applied | error | verdict |
|---|---|---|---|
| 90 s | 89.999999662 s | −0.3 µs | frame accepted |
| 120 s | 119.999987713 s | −12 µs | frame accepted |
| 150 s | 149.999969845 s | −30 µs | frame accepted |
| 240 s | 239.999975426 s | −25 µs | frame accepted |
| **320 s** | **319.999983017 s** | −17 µs | exposure honoured; frame rejected `SATURATED` at 1023 ADU, which is 320 s at ISO 800 in a lit room behaving exactly as it should |

**No wall below 320 s — 6.4× the stated ceiling** — and every request honoured to within 30 µs. Two
structural guesses died on the way: 2²³ rows × 18.5 µs = 155.2 s (150 s and 240 s both passed it)
and 2²⁴ rows = 310.4 s (320 s passed it). The applied values do not land on the 18.5 µs row quantum
either, so whatever governs the extended range is not the register arithmetic that explains the
*stated* ceiling. That is a question about the driver, and it no longer blocks anything: **the app's
own 240 s sanity bound is below the hardware's capability**, so the operative limit is the one we
chose for astronomical reasons rather than one the sensor imposes.

**One methodological trap, recorded because it produced a wrong answer first.** The 240 s probe was
called a failure after 7 minutes of no frame — "not clamping, not honouring, just never delivering".
That was premature. Single-frame probes land at roughly **2× the requested exposure**, so 240 s needs
~13 minutes of wall clock; re-run with a proper budget it succeeded. The lesson is the same one §1.16
learned about leaks: *a phase too short to judge is inconclusive, not negative.*

That 2× is itself worth a look. At the 7.4 s subs of a real session the measured per-frame overhead
is 2 ms (§1.9), so this is not the steady-state cost — most likely the first frame after a
configuration change is discarded and the second is the keeper, which a one-frame probe pays in full
and a 150-frame session pays once. **Unmeasured**, and it matters to `SessionPlanner`: if it were
per-frame rather than per-session, every long-sub plan would be out by a factor of two.

---

## 1.21 `maxFrameDuration` is the number that *is* enforced — measured 2026-08-19

**OI-24, answered, and it changes a shipped calculation.** §1.20 found that single-frame probes
land at roughly twice their own exposure and left open whether that cost is per-session or
per-frame. It is per-frame — but only past a boundary, and the boundary is a number already in the
device profile.

Cadence between consecutive frames, from `capturedAt` in `session.json`:

| sub | source | gap between frames |
|---|---|---|
| 0.951 s | real session, 14 lights | **1.00×** |
| 7.399 s | real session, 105 lights | **1.00×** |
| 7.399 s | real session, 49 lights | **1.00×** |
| 40 s | probe, 3 frames | **1.00×** |
| 60 s | probe, 4 frames | **2.89×, 2.01×, 2.87×** |

The crossover sits between 40 s and 60 s, and `SENSOR_INFO_MAX_FRAME_DURATION` is **49.6408 s** —
squarely inside it.

**So the vendor's numbers were not meaningless after all; they describe two different things.**
`SENSOR_INFO_EXPOSURE_TIME_RANGE` bounds a *single* exposure and is not enforced — 320 s works
(§1.20). `SENSOR_INFO_MAX_FRAME_DURATION` bounds a *sustained repeating stream* and is enforced,
as a cadence rather than a refusal: past it the sensor spends two or three periods per delivered
frame. One governs whether a frame can be taken; the other governs how often.

### Why this had to be fixed rather than noted

**D-28** lets a user ask for subs past the exposure ceiling, which puts them past the frame-duration
limit as well. A plan at 60 s subs counted 60 s a frame and would have taken 156 — so the session
length, the end time, the storage rate and the battery estimate were all out by the same 2.6×. That
is the same defect T-3.35 fixed for the frame-count bound, arriving through a different door: a
number that is right in the regime it was measured in and silently wrong outside it.

`ExposureCompensation.frameCostSeconds` now returns `sub + 10 ms` below the limit and
`sub × 2.6` above it. The factor is the measured mean of 2.89, 2.01 and 2.87 rather than a round
number, and it is deliberately not rounded down: a planner that under-promises the clock finishes
early, one that over-promises it runs into the dawn.

**What stays true** is the original measurement it appeared to contradict. §1.9's 2 ms per-frame
overhead is confirmed, not overturned — three real sessions at 0.951 s and 7.399 s run at exactly
1.00×, so every session shot so far, and every session anyone will shoot untracked, is unaffected.
The correction only bites where the app newly allows people to go.

---

## 1.22 A synthetic sky, and the trap inside it — 2026-08-19

T-4.0 is built. It matters more than a test fixture usually would, because **every unticked box in
Phases 0, 1B, 1C and 1E is blocked on the same thing — a clear night** — and registration cannot be
developed that way even with one. You cannot measure a 0.2 px residual against a real star field;
you can only look at the stack and decide whether it seems sharp. Ground truth is the point, and a
real sky is the one place it can never come from.

**The generator has its own tests, and that is not ceremony.** Phase 2 will assert that registration
recovers a transform to a fraction of a pixel. If the frames carry a placement bias, a correct
registrator looks broken — or, far worse, a broken one looks correct and ships. So the fixture is
checked first: stars land where the truth says, a pure translation moves every detected centroid by
exactly that much, a rotation about the centre leaves the centre still, a sequence accumulates its
drift instead of repeating it, hot pixels stay put across frames while noise does not, and the same
seed renders the same bytes.

**Shot noise is √N in electrons**, which is why the scene is accumulated in electrons and converted
to ADU exactly once at the end. Applying it after the conversion would be wrong by the gain, and
the gain moves with ISO. There is a test that four times the signal gives twice the noise, because
a synthetic frame with the wrong noise is worse than none — every threshold tuned against it is
tuned against a fiction.

### The trap: a small frame does not test the pipeline, it tests the pipeline's failure mode

The first version rendered 256×192 to keep tests quick, and found **5 of 20 stars**. The same field
at 512×384 found 15.

`StarDetector` fits its background on **64 px tiles** of the binned plane and estimates noise from
the residual. A plane one or two tiles across cannot follow the light-pollution gradient, so the
gradient lands in the *noise* estimate instead: 33 ADU measured against a true pixel noise of 17.
That doubles the 5σ threshold and silently loses every faint star — no error, no warning, just a
sparse field that looks like a detector problem.

It is worth recording because it inverts the usual instinct. The economical choice — render less,
run faster — produced a fixture that exercised a degenerate path and would have sent someone
hunting a registration bug that was never there. `MIN_USEFUL_WIDTH` is now the default and the
class note says why.

Two smaller calibrations, both arrived at by measuring rather than guessing: the star brightness
power law is bounded **below** so its faint end clears the 5σ threshold and **above** so the
brightest peak plus the sky stays under the 1023 ADU white level — a fixture whose brightest stars
saturate hands registration a biased centroid to chase. And `StarDetector`'s `saturationLevel`
defaults to `Double.MAX_VALUE`, so a fully clipped frame is *not* flagged unless the white level is
passed in, as the app does and as the first draft of the test did not.

---

## 1.23 The seed, and two sign conventions that had to be pinned — 2026-08-19

T-4.1 exists because asterism matching has two failure modes and a seed fixes both: it is slow when
the search range is wide, and it fails outright when the frame is star-starved — thin cloud, a
bright moon, an aircraft. Those are the frames least worth losing, being in the middle of a session
that would otherwise be continuous.

The insight is that **the sky's motion is not unknown**. It is the Earth turning, at a rate known to
nine figures, and the phone already measures everything else: where it is, where it points, which
way up it is, and when each frame was taken. So the transform can be computed and matching only has
to refine it.

Numbers for the reference device at 73.7 arcsec/px: between consecutive 7.4 s subs the field moves
**about a pixel**, but across a 45-minute session it moves **hundreds of pixels and rotates by more
than a degree**. That spread is the whole argument — it is why a blind matcher has to search so
wide, and why a seed turns an expensive hunt into a cheap refinement.

### What was already there, and what had been thrown away

The rotation rate was already in `Astro` (§7.1) and is reused rather than copied. The missing half
was the drift, which is two more formulas. But the real gap was an *input*: `PointingFix` carried
altitude and azimuth and **discarded roll**, though the rotation matrix containing it sat three
lines away in `PointingSource`. Pointing says how fast the sky drifts and along which horizon
direction; roll says where that direction lands in the picture. Without it the seed knows the size
of the shift and not its sign, which is worse than not knowing at all.

### Two sign conventions, and why they were tested before they were trusted

**A seed that points the wrong way is worse than no seed**, because matching will converge
confidently on the wrong star and the frame will be accepted. And a flipped axis produces numbers of
exactly the right *magnitude*, so it cannot be caught by looking at the output. Both conventions are
therefore pinned by cases whose answers are known without any of the code:

- **The drift rates**, against four sanity cases — at the north pole nothing rises or sets and
  azimuth advances at exactly ω; at the equator due east a star climbs at the full rate and does not
  drift sideways; on the meridian altitude is stationary; and a star crossing the southern meridian
  moves *west*, which is the single case that distinguishes the correct azimuth formula from its
  sign-flipped twin. Plus an independent identity: total speed is ω·cos δ wherever you stand, so
  recovering declination from the rates must agree with `Astro.declinationDeg`'s spherical
  trigonometry. It does, to 1e-6.
- **The roll**, against phone positions anyone can picture. **This one was wrong, and the test
  caught it**: the cross product `(skyUp × deviceUp) · opticalAxis` has the handedness of someone
  standing *in front of* the lens looking back, and the useful convention is the opposite —
  anticlockwise **in the image**, which is what the person holding the phone sees. Lens north, top
  of the phone west, is a left turn from behind and therefore +90°. All three failures were pure
  sign with exact magnitudes, which is precisely the defect that survives every check except an
  explicit one.

The other roll case worth having is *roll is measured about the lens, not the horizon*: a phone
tilted up 45° but not rolled must read zero, because the lens axis moved and "up on the sky" moved
with it. Getting that wrong would put a phantom rotation into every tilted pointing — which is to
say, into every real one.

---

## 1.24 Asterism matching, and a threshold that had to be measured — 2026-08-19

Registration is two problems and T-4.2 is the hard one. Once you know that star 7 here is star 12
there, fitting the transform is least squares. Working out *which star is which*, from two lists of
unlabelled dots, is the part that needs an idea.

The idea is that shapes survive what positions do not. `astroalign` (MIT) is the reference, and the
invariant is the same: the two sorted side ratios of a triangle, unchanged by translation, rotation
and scale.

**T-4.0 is what made this testable honestly, and this is the first task to prove it.** Correctness
here is not "the stars look lined up" — it is "star 7 is star 12", and only a synthetic field knows
the answer. Against a real sky the best available check is to stack and squint, which cannot
distinguish a correct matcher from one that is right most of the time. Most of the time is exactly
what quietly ruins a stack.

### The threshold, which guessing got wrong by two orders of magnitude

The first version accepted any pair proposed by two or more triangles. That let **fifteen
correspondences through between two completely unrelated fields** — the worst possible failure,
since a confident wrong answer is indistinguishable downstream from a right one.

Measuring the vote distributions rather than guessing again showed why, and gave the fix for free:

| | pairs | votes per pair |
|---|---|---|
| true match, 24 stars | 24 of 24 correct | **251 – 277** |
| unrelated fields | 15, all wrong | 14 – 35 |
| mirrored field | 11, 9 wrong | 11 – 28 |

A star in a 24-star field belongs to 253 triangles, so a true correspondence is confirmed by
**very nearly every triangle containing it**. That is what being right looks like, and it is an
order of magnitude clear of coincidence.

The lesson is in the normalisation rather than the number. **A vote count means nothing on its own;
it has to be measured against how many chances the pair had.** A flat threshold tuned for 24 stars
rejects true pairs in an 8-star field, where the same certainty earns only 21 votes; one tuned for 8
lets every coincidence through at 24. The ratio is scale-free, and with 1.09 against 0.14 the exact
cut is not delicate — a quarter, chosen low rather than central because the errors are not
symmetric: a missing pair costs one star out of dozens, a rejected frame costs the whole exposure.

**This is a filter, not a verdict.** T-4.3's RANSAC is the real guard against a set that agrees with
itself but with no rigid transform. The job here is to keep obvious rubbish out of it, and to make a
failed match *look* like a failure rather than like fifteen confident pairs.

### One test failed because the test was wrong

The ambiguity guard rejects a seeded pair when the nearest candidate is not clearly nearer than the
runner-up. The case written to exercise it put candidates 0 px and 4 px from the prediction, which
is not ambiguous at all — it is a clear winner — so the guard correctly accepted it and the test
correctly failed. Fixed by making the two candidates equidistant, which is what the sentence in the
test name always meant.

---

---

## 2. Decisions

| ID | Decision | Rationale | Reversal cost |
|---|---|---|---|
| **D-1** | Single Gradle module `:app`, package-by-feature (`capture/`, `stack/`, `calib/`, `session/`, `ui/`, `device/`) | Solo project, side-loaded. Multi-module buys parallel builds you don't need yet and costs a wiring tax on every change. | Low — split when build time hurts |
| **D-2** | Jetpack Compose, no XML layouts | The prototype is a design system (tokens, one accent per screen); Compose expresses that directly. | High — don't revisit |
| **D-3** | Camera2 directly, **not** CameraX | CameraX's RAW + full manual control story is thin, and FR-6.1 needs per-frame manual ISO/exposure/focus/WB with OEM processing off. | High |
| **D-4** | Manual DI container (`AppContainer`) + ViewModel factories, no Hilt | ~30 injectables in the whole app. Avoids the annotation-processor build cost. | Low |
| **D-5** | **No database.** `session.json` on disk is the source of truth; a cached index file speeds the session list | FR-10.6.4 requires sessions to be discovered by *scanning the root*, including folders copied back from a PC. A DB then becomes a second source of truth that is wrong whenever the folder changes externally. | Medium |
| **D-6** | Kotlin coroutines + `StateFlow`. Capture engine lives in a foreground service and exposes one `SessionState` flow; UI is a pure function of it | Screen-off, backgrounded, process-death-resumable capture cannot be driven from a ViewModel. | Medium |
| **D-7** | OpenCV added as a dependency **at Phase 3**, not before | Keeps the Phase-1 APK small and the build fast while shooting is the only feature. Star detection (§12.1) is Kotlin anyway. | Low |
| **D-8** | `arm64-v8a` only, minSdk 30, targetSdk current | FR-3.1 | — |
| **D-9** | Live analysis reads the **RAW buffer directly** (green channel, 4×4 binned to ~1 MP), not a separate YUV stream | One less stream to configure, no OEM ISP in the analysis path, and the numbers then describe the data actually being stacked. Confirmed viable by OI-3: the YUV path remains available as a guaranteed fallback, so this is a free choice rather than a bet. | Medium |
| **D-10** | Rejected frames are written to `lights/` like any other frame and flagged in `session.json`. **The app never deletes anything of its own accord** — amended 2026-08-18 to say *of its own accord*, since **D-26** now lets the user delete a session outright | FR-7.5, FR-10.6.3 | — |
| **D-11** | Fonts bundled, not fetched (Space Grotesk + IBM Plex Mono, both OFL) | Offline in a field with no signal is the normal case. | — |
| **D-12** | **Two separate foreground services.** Capture = `camera` type (no time limit). Stacking = `mediaProcessing` on API 35+, `dataSync` on API 34 — both carry a 6 h / 24 h budget, both must implement `onTimeout()` → `stopSelf()` | Resolves OI-2. Capture must be able to run for hours; only the `camera` type allows that. Stacking is minutes, so the 6 h budget is ample, but the callback is mandatory or the system throws `RemoteServiceException`. | Medium |
| **D-13** | **DNG readback via a minimal TIFF/DNG reader written in Kotlin**: header → IFD0, tags `StripOffsets`/`StripByteCounts`/`RowsPerStrip`/`BitsPerSample`/`Compression`/`CFAPattern`/`BlackLevel`/`WhiteLevel`, strips copied into a `ShortArray` | Resolves OI-1. **Confirmed against a real capture 2026-08-16** — see §1.6. No SubIFD walk is needed: `DngCreator` puts the CFA data in IFD0 itself. | Medium |
| **D-20** | **Every capture session configures a second surface alongside the RAW stream**, even with the screen off (a `SurfaceTexture` needs no display) | Measured: this HAL never delivers a frame on a RAW-only session, in any request profile, despite "No-preview DNG capture" being in its own guaranteed-combination list. A guaranteed *configuration* is not a guaranteed *stream*. | Low |
| **D-21** | **Requests are built from `TEMPLATE_MANUAL`, and every capture's metadata is verified against what was asked** before the frame is accepted | Measured: with `TEMPLATE_STILL_CAPTURE` the HAL silently ignored `SENSOR_EXPOSURE_TIME` and returned 30 ms frames for a 10 s request. Nothing downstream can detect a frame that lied about its exposure — it just quietly poisons the stack. | Low |
| **D-14** | **No bias frames and no dark scaling in v1.** Darks are captured per session at matched ISO, exposure and temperature, which already contains the bias signal | Resolves OI-10 by the requirements' own conditional (§4.2.2: bias is needed *only* if dark scaling/optimisation is implemented). Not implementing dark scaling removes the need. | Low — additive if ever wanted |
| **D-15** | **Two reference frames, not one.** Live registration references the first accepted frame; the deferred stack picks the *best-quality* frame as its reference in a first pass over the frame log | Resolves OI-14. Because capture and stacking are decoupled (FR-10.1), the second pass is free — the log already holds HFR, star count and background for every frame, so choosing the best reference costs one sort, not a re-read. | Low |
| **D-16** | **Temperature signal chain:** vendor sensor-temperature key if the device exposes one → battery temperature (`ACTION_BATTERY_CHANGED`, tenths °C) → `PowerManager.getThermalHeadroom()`. All available signals are logged per frame; battery temperature is the dark-matching key | Resolves OI-7. Camera2 has no standard sensor-temperature key. Darks are captured at the end of the same session, so matching is by proximity in time along a monotonic warming curve — an absolute sensor temperature is not required. | Low |
| **D-17** | **No light-pollution input in v1** — no Bortle picker, no GPS dataset lookup | Resolves OI-12. The exposure engine measures sky background directly from a test frame (T-3.1); a manual estimate would be a less accurate input to the same calculation, and UI that changes nothing is UI that misleads. | Low |
| **D-18** | **Live preview stack = capped running mean** of aligned, binned (~1 MP) frames, autostretched for display, with no rejection logic of its own | Resolves OI-13. §14.4's own reasoning: framing confidence is the job, and anything heavier competes with capture for thermal budget — which directly degrades the frames still being taken. | Low |
| **D-22** | **The framing preview is rendered from the RAW stream, not from a display surface.** A repeating ~1 s exposure is read as RAW, binned, star-detected and autostretched; the screen shows that raster. No display surface is ever part of a capture session | The preview a user frames on is then literally the data that will be stacked, through the same pipeline, so what looks framed *is* framed. It also dissolves T-2.1's screen-off requirement rather than handling it: nothing in the session belongs to the display, so there is no surface to lose when the screen goes off and nothing to reconfigure on wake. | Medium |
| **D-23** | The second stream demanded by **D-20** is a **drained YUV `ImageReader`**, not an unconsumed `SurfaceTexture` | A `SurfaceTexture(0)` with no EGL context cannot be drained — `updateTexImage()` needs a bound GL texture — so its buffer queue fills and stalls a *repeating* request. It survived T-1.4 only because that stopped after one frame. A YUV reader drains with `acquireLatestImage().close()`, and `YUV(PREVIEW) + RAW(MAXIMUM)` is on the device's own guaranteed list. Free side effect: OI-3's YUV analysis fallback is now always configured. | Low |
| **D-24** | **A minimal JSON reader is owned in-tree** (`json/Json.kt`), extending the writer that already existed | D-5 makes `session.json` the source of truth, so the app must *read* what it wrote — including folders copied back from a PC (FR-10.6.4). `org.json` is a stub in JVM unit tests, which would push every session-log test onto a device; a serialization library is an annotation processor on the build for a handful of flat records. | Low |
| **D-25** | **The UI explains only what the user must decide.** Reasoning lives in KDoc and this document, never on screen. Consequence the user cannot deduce is not reasoning and stays | §1.15. Written after the settings screen shipped with paragraphs justifying dark mode, the camera permission and the storage location — none of them a decision anyone makes. The author's thinking in front of someone in a field at 2 a.m. is a cost with no reader | Low |

| **D-26** | **The app deletes nothing on its own; the user may delete a session outright.** Deletion is explicit, confirmed, and names what is being lost | Amends **D-10**, which is about the app's own *judgement* — a rejected frame stays on disk because the app does not get to decide someone's data is worthless. A person clearing their own 3.6 GB session is not that, and a capture app with no way to free space is not honouring D-10 but hiding behind it | Low |
| **D-28** | **The sensor's stated exposure ceiling is advertised, not enforced — so ask, then verify.** No clamp to `SENSOR_INFO_EXPOSURE_TIME_RANGE`; instead every frame's metadata is checked against the request, and past the stated ceiling a run of wrong exposures fails the session by name rather than being skipped | Measured 2026-08-19 (§1.20): the device returned 119.999987713 s for a 120 s request against a stated 49.6406 s maximum. Clamping refused exposures the hardware would take, and above dec 81.5° the *sky* permits longer subs than the ceiling allowed. Verification is also portable in a way a constant is not — it is correct on the phone that honours the request and on the one that does not, with neither special-cased. Leans on **D-21**, which already verifies every frame | Low — the clamp is two lines to restore |
| **D-27** | **Nothing that costs frames or opens the camera starts because a screen appeared.** Measurements are begun by a control, and the screen says what one will cost before it is pressed | Reverses T-3.25's auto-solve. Arriving somewhere is not consent to spend the sensor: the frames are real, the phone warms, and an unexplained camera indicator on a screen the user is only passing through reads as the app misbehaving. The rule generalises, because every future screen with a measurement behind it meets the same temptation | Low |

---

## 3. Phase 0 — Foundations

Goal: an installable shell with the real visual language and the real storage model, so no Phase-1
work has to be redone.

> ~~Do T-1.1 (the probe) before the rest of Phase 0.~~ **Done 2026-08-16 — the device qualifies
> (§1.5).** Phase 0 can now proceed on the assumption that there is hardware to run on.

- [x] **T-0.1** Android Studio project skeleton — Kotlin, `minSdk 30`, `targetSdk` current,
  `ndk.abiFilters = ["arm64-v8a"]`, Compose BOM, Gradle version catalog, `git init` + `.gitignore`.
  *Accept:* debug APK installs and launches on the test device.
  **Done:** AGP 8.11.1 / Kotlin 2.2.20 / Gradle 8.14.3, compileSdk 36, daemon pinned to the Studio
  JBR (JDK 21) because the system JDK is 23. `:app:assembleDebug` produces a 25 MB debug APK.
  **Installs and launches on the device (2026-08-16).**
- [~] **T-0.2** Night theme ported from the prototype — colour tokens (`void`/`surface`/`hot`/`warn`/
  `txt1-3`), the two type families, and the shared components: `NightButton` (primary/quiet),
  `Card`, `Eyebrow`, `KeyValueGrid`, `Badge`, `Banner`, `StatStrip`.
  *Accept:* a gallery screen renders every component; **exactly one** full-intensity element per
  screen is enforceable by review.
  **Done:** the palette (`ui/theme/Theme.kt`) and `ui/Components.kt` — `Card`, `Eyebrow`, `Mono`,
  `HotButton`, `QuietButton`, `KeyValue`, `Metric`, `Badge`, `Banner`. Extracted out of
  `ProbeScreen` when the framing screen needed the same parts, which is the right moment for it:
  two call sites make the shared contract real. `HotButton` is the one full-intensity control, so
  the "one per screen" rule is now a grep, not a judgement.
  **Remaining:** the bundled fonts (D-11 — still on the platform families) and the gallery screen.
- [~] **T-0.3** Navigation skeleton: Main → Session setup → Live → Session detail → Settings.
  Screens are stubs with the prototype's static content.
  *Accept:* all five reachable, back stack correct, screen rotation locked to portrait.
  **Done 2026-08-18.** The screens stopped being stubs long ago, so what was left was the part the
  task named and nobody had built: **the back stack**.
  **This was a missing feature, not a simplification.** Navigation was a single `var screen`, so
  the system back gesture was never handled and therefore *left the app* from any screen,
  including mid-session. Found by tripping over it — a back press during testing dropped straight
  out of the app onto the launcher. It is the kind of defect nobody files, because it looks like
  the phone behaving normally right up until it loses your place.
  `ui/Navigation.kt` is plain data so the two rules that matter are tested rather than clicked:
  pushing the screen you are already on is a no-op (automatic navigation fires from a state change,
  and a flow can emit twice), and **entering capture resets the stack to `[PROBE, CAPTURE]`** —
  backing out of a running session onto Setup would show a Start button for a session already
  running, which invites starting a second on top of the first. The session belongs to the service
  and survives the screen (D-6), so leaving the capture screen is safe; it just must not lead back
  into the flow that began it.
  A list, not a navigation library: the flow is a stack of five, this codebase adds dependencies
  reluctantly (D-7, D-11), and neither rule above is one a library would have got right for us.
  The stack survives process death, since the app is designed to sit backgrounded for 45 minutes
  and the system may kill it meanwhile.
  **Verified on device:** launch to probe, tap to settings, back to probe rather than out of the
  app. Rotation is locked to portrait in the manifest.
  **Two honesties.** `Session detail` in the task's list does not exist; the five are probe,
  framing, setup, capture and settings. It was Phase 4's T-6.3 when this was written and is now
  **T-3.27** (§1.17), which adds the sixth and seventh screens to this stack. And only the probe-settings leg was walked
  on hardware today — the other three rest on unit tests and prior sessions, not a fresh walk.
- [~] **T-0.4** Permission flow: `CAMERA`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`. Rationale UI in plain language; denial is
  survivable (location denied → pointing unavailable → exposure engine falls back, and says so).
  *Accept:* cold install → grant flow → no crash on any denial combination.
  **Done 2026-08-18, acceptance demonstrated across all eight combinations** of camera /
  notifications / location: no `FATAL EXCEPTION` and no ANR in any of them, and with everything
  denied the app still renders its landing screen and states what it cannot do.
  **`POST_NOTIFICATIONS` was never requested at all**, which was not cosmetic: the prompt to cover
  the lens for darks is delivered *only* as a notification, and the wait behind it times out after
  15 minutes. Refusing notifications silently cost the session its darks. That consequence is now
  what the permission screen says, in those words.
  `ui/Permissions.kt` holds the wording as pure data, so it is unit-tested — including that no
  optional permission is allowed to hide behind "reduced functionality". The consequence is shown
  whether or not the permission is granted: someone deciding needs to know what they are giving
  up, and finding that out should not require revoking something to make a warning appear.
  **Notifications are asked for on the way into framing**, not at cold start. A prompt fired at
  launch is answered before the user knows what the app does, and asked once only — Android
  silently ignores the request after two refusals, so re-firing it would turn a decision into a
  loop. The screen offers a route to the system page for exactly that reason.
  **Remaining:** the Allow / App settings row has not been seen rendered since permissions were
  restored on the test device, so its layout fix is compiled and conventional but unphotographed.
- [~] **T-0.5** Storage layer over SAF — `ACTION_OPEN_DOCUMENT_TREE`, persisted URI permission,
  a `SessionStore` interface that hides `DocumentsContract` behind create/open/write/list.
  **Do not use `DocumentFile`** for per-frame work (`findFile()` is O(n) per call and will crawl at
  150 frames). Cache child document IDs; write through `ParcelFileDescriptor`.
  *Accept:* write 200 × 25 MB files into a subtree; measure and record throughput and the cost of
  a full-root scan. Numbers go in **OI-5**.
  **Written 2026-08-18** — `session/SafSessionStore.kt` over `DocumentsContract` with no
  `DocumentFile` anywhere: each subdirectory's document URI is resolved once at open/create and
  cached, so writing a frame is `createDocument` + `openFileDescriptor` and enumerates nothing.
  Frames go through `ParcelFileDescriptor.AutoCloseOutputStream` rather than `openOutputStream`,
  because a provider may satisfy the latter with a **pipe** — a whole extra copy of every 24 MiB
  sub, paid at capture cadence.
  `session/SessionRoot.kt` holds the choice: it takes the grant persistably, **re-checks it on
  every resolve** (a grant can be revoked from settings and an SD card can be removed; finding out
  at frame 1 of an unattended session is finding out too late) and falls back to the app-private
  store when it is gone. The fallback stays deliberately — someone under a clear sky who has not
  picked a folder should still be able to press start.
  The landing screen now **states where frames will go and whether that survives uninstall**,
  because the app-private default is deleted on uninstall and a 2.4 GB session that vanished with
  a sideload is not a thing to learn afterwards.
  **Two honest gaps.** SAF has no atomic replace, so `writeAtomically` cannot be atomic the way
  the file store's `rename` is; it is arranged so one of the two documents is always complete and
  `readText` falls back to the temporary, which is the guarantee that actually matters. And
  `freeBytes` is an estimate — a tree URI is not a path, so `fstatvfs` on the root is tried and
  the primary volume is the fallback, which is wrong if the user picks an SD card. It feeds a
  warning, not a decision.
  **Remaining:** the measurement. `diag/StorageBenchmark.kt` is written and adb-driven
  (`--es diag storage --ei files 200 --ei sizeMb 25`); it has never run, so **OI-5 is still open**
  and the box stays `[~]` per §0. Also untested on hardware: whether this provider preserves a
  display name whose extension matches the MIME type — if it rewrites one, the name in
  `session.json` and the name on disk diverge and the log loses the frame.
  **Out of scope, and it will bite at Phase 3:** `DngReader` reads through `RandomAccessFile` and
  therefore cannot open a frame in a SAF tree at all. Readback needs a seekable source over a
  `ParcelFileDescriptor` before stacking can consume a SAF-rooted session (T-5.x).
- [~] **T-0.6** Diagnostics: rolling file log with crash handler, plus an in-app log viewer with
  share. Unattended 45-minute sessions fail at 2 a.m.; without this you get nothing back.
  *Accept:* force a crash mid-session, recover the log from the device.
  **Done 2026-08-18, acceptance demonstrated.** A session was crashed at **frame 21** on a worker
  thread; the process died and the log was recovered from the device carrying both the stack trace
  and the frames leading up to it, rotation measurements included.
  **`diag/FieldLog.kt` writes from three sources, because each misses what the others catch.** A
  `logcat` tee filtered to this process picks up every existing `Log.i/w/e` call with no call site
  touched — and the runtime's own `FATAL EXCEPTION`, which the framework writes and which passes
  through no handler of ours. `write()` covers deliberate entries. An uncaught-exception handler
  writes the trace *directly and flushed* before delegating, because the tee is a pipe between
  processes and a crash can outrun it. Reading one's own logs needs no permission; an app has been
  able to read exactly its own process since Android 4.1.
  Two files of 1 MiB. Measured ~6 KB/min with a session running, so a 45-minute run lands near
  270 KB and the rolled file survives a crash that restarts the app.
  **The Application class exists solely for this.** Starting the log from an Activity would leave
  the window between process start and `onCreate` uncovered — which is exactly where a startup
  crash happens, and a crash log blind to startup crashes is missing the case it can least
  reproduce afterwards.
  **Remaining:** the viewer and its share button compile and are wired to the landing screen, but
  no human has tapped them; only the file half is demonstrated. Worth knowing for the field: the
  `--es diag crash` trigger needs `--activity-single-top`, since `am start` on a task-root
  activity otherwise just brings the task forward without delivering the intent.
- [~] **T-0.7** `AppContainer`, dispatchers, and a `Clock`/`SensorSource`/`CameraSource` seam so
  logic is testable without hardware.
  **Done 2026-08-18, and smaller than the task implies — because most of it was already there.**
  The seams that carry the weight were built where they were needed: `SessionStore` hides SAF from
  the capture engine, `CaptureEngine.Environment` hides the sensors and the thermal API. Those are
  why 228 tests run on a laptop with no phone attached, and re-doing them as a framework would
  have bought nothing.
  What was genuinely missing was duller: **nothing owned the construction.** The Activity and the
  Service each built their own store, camera and environment from whatever `Context` they happened
  to be, so changing how any of them is made meant finding every place that made one.
  `core/AppContainer.kt` is that one place, hung off the Application. Camera and environment are
  **factories, not fields** — each owns hardware that must be closed, and a process-scoped instance
  would hold the camera open between sessions and sample the gyro all night.
  `core/Clock.kt` splits two things that were being used interchangeably: wall-clock for naming and
  timestamping, monotonic for every *duration*. A 15-minute darks prompt measured on wall-clock
  time resolves instantly the moment the network corrects the clock backwards. §1.14 makes the
  monotonic one load-bearing: it is the base `SensorEvent.timestamp` and `SENSOR_TIMESTAMP` share,
  which is the only reason a gyro window can be compared against an exposure at all.
  **Deliberately left.** `AppDispatchers` names the dispatchers but they are not yet threaded
  through every call site, and the ten `System.currentTimeMillis()` calls in `FieldDiagnostics` and
  `FramingController` are untouched — stopwatch measurements inside diagnostics, not logic under
  test, and converting them would be churn dressed as rigour. The clock now backs
  `DeviceEnvironment`, session naming and `SessionRecovery`.
  **This is the precondition for testability, not the tests.** It makes the Service and the
  controllers injectable; nothing yet injects a fake into them.
- [~] **T-0.8** Device profile store: JSON in app-private storage, versioned schema, export via
  share sheet (FR-3.2.1).
  **Done:** `ProfileJson` (hand-rolled writer, no serialization dependency, JVM-testable),
  `schemaVersion: 1`, export to `getExternalFilesDir` + FileProvider share.
  **Remaining:** persistence and reload across launches — the probe currently re-runs each start,
  which is fine while it *is* the app.
- [~] **T-0.9** Settings screen shell: session root, night-mode brightness note, calibration status
  entry point (stub until Phase 6), device profile export.
  **Done 2026-08-18, rendering on hardware.** Also the home for T-0.4's permissions and T-0.6's
  field log.
  **This was tidying with a deadline.** The session-root card arrived on the landing screen with
  T-0.5 and the field-log card with T-0.6, each because it needed somewhere to live and there was
  nowhere. Two more and the capability probe becomes a junk drawer, which is how a screen read in
  the dark stops being readable. The probe keeps a one-line notice of where sessions go — the
  app-private default is deleted on uninstall and that is worth stating where it is seen — but the
  control now lives here.
  **The night-mode entry is a note, not a setting**, and deliberately: every screen is already
  dark, which leaves system brightness, and that belongs to the system. A control that dimmed only
  this app's pixels while the notification shade stayed at full blast would be worse than saying so.
  **Found while building it:** `QuietButton` calls `fillMaxWidth()` unconditionally, so two of them
  in a `ButtonRow` leave the second rendering one character per line down the screen edge. The
  existing convention — wrapping each in `Box(Modifier.weight(1f))` — is undocumented and easy to
  miss; worth folding into the component itself if a third caller hits it.

**Checkpoint 0:** app installs, looks like the prototype, has a session root, survives a crash with
a readable log.

---

## 4. Phase 1A — First light

Goal: prove the device can do the one thing everything else rests on — a manual RAW frame written
as a DNG that desktop tools accept.

- [x] **T-1.1** Capability probe (FR-3.2) across every physical camera: hardware level,
  capabilities, stream config map, active/pixel array, **pixel pitch derived from the dimensions
  the RAW stream actually delivers** (quad-Bayer binning makes the naive
  `PIXEL_ARRAY_SIZE ÷ physical size` calculation wrong by 2× — see OI-17), focal lengths, apertures,
  ISO and exposure ranges, CFA, black/white levels, `SENSOR_NOISE_PROFILE`, timestamp source,
  max frame duration, focus-distance calibration + minimum focus + hyperfocal, AF-motor presence,
  OIS/EIS availability *and disableability*, `getConcurrentCameraIds()`.
  Also record, because later decisions read them: **`SCALER_MANDATORY_STREAM_COMBINATIONS`**
  (API 29+ — the device's own guaranteed-combination list, per D-9/OI-3),
  **`SENSOR_INFO_TIMESTAMP_SOURCE`** (OI-8), and which **temperature signals** the device
  actually exposes (D-16).
  *Accept:* profile JSON for the test device, human-readable dump on screen.
  **Done:** `device/CameraProbe.kt` enumerates exposed *and* logical-child physical cameras and
  converts to the Android-free `DeviceProfile` model; `device/ProfileJson.kt` exports it
  (FR-3.2.1); `ui/ProbeScreen.kt` renders it in the night palette.
  **Run on the device 2026-08-16 — see §1.5 for results.** Output at
  [probe-output/device-profile.json](probe-output/device-profile.json).

  **Correction to the requirements:** §3.2 lists `SENSOR_NOISE_PROFILE` among the probe outputs,
  but it is a **CaptureResult key, not a CameraCharacteristics key** — it only arrives with a real
  frame. The field is present and null in the profile, and gets populated in T-1.4.

  **Three things the first build got wrong, all found by running it:**
  1. *Enumeration was incomplete.* Walking published cameras plus their physical children finds
     only 2 of this device's 5 cameras, because the ultrawide and tele hang off a logical camera
     that is itself unpublished. Discovery now runs a third pass over unpublished IDs and labels
     each camera `LISTED` / `PHYSICAL_CHILD` / `HIDDEN`.
  2. *A null was read as a fact.* `hasAfMotor` was derived from
     `LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0`, so a null made the main camera look fixed-focus —
     which would have skipped the focus sweep and softened every session (FR-6.3). Focus type now
     comes from `CONTROL_AF_AVAILABLE_MODES` first, with an explicit `UNKNOWN` that warns rather
     than assumes.
  3. *Some characteristics are permission-gated.* On this device
     `LENS_INFO_MINIMUM_FOCUS_DISTANCE` and `LENS_INFO_HYPERFOCAL_DISTANCE` read **null until
     `CAMERA` is granted**, while everything else reads correctly. Enumeration works
     unpermissioned; lens data does not. The probe now re-runs after the grant.

  **Remaining:** temperature-signal probing (D-16).
- [x] **T-1.2** Tier classification + gate (FR-3.1): Full / Functional / Degraded / Unsupported.
  The unsupported screen must name **which specific requirement** failed. Warn if max exposure
  < 10 s (FR-3.2.2).
  *Accept:* forced-fail unit tests for each of the four disqualifiers. **Met — 14 JVM tests pass,**
  covering all four disqualifiers, the LIMITED→Degraded path, the short-exposure warning, the
  OI-17 pitch derivation, and device-level qualification on rear cameras only. Note the tier
  ceiling is `FUNCTIONAL` by construction until Phase 6 exists, which is exactly FR-3.1.1.
- [x] **T-1.3** Camera2 lifecycle wrapper: open/close, dedicated handler thread, capture session
  creation, robust error and disconnect handling, and a hard guarantee the camera is released
  when the session ends or the process dies.
  *Accept:* open/close 50× in a loop without leaking; another app can take the camera afterwards.
  **Done 2026-08-16:** `camera/CameraAccess.kt` — one handler thread, suspend wrappers over the
  callback API, `withDevice {}` closing the device on any path, and typed `CameraOpenException`.
  **OI-18 resolved, favourably: all five camera IDs open**, including the unpublished ultrawide,
  tele and logical camera (`camera/OpenabilityProbe.kt`).
  **Closed 2026-08-18 on hardware (§1.16).** 50 open/close cycles, 30 configured sessions, 25
  exception paths and 36 cancellations: descriptors flat at 173 and threads flat across every
  warm tail, and the camera service confirmed the camera free again after **all 141 opens**.
  Open costs 10/16/35 ms (min/median/max). The second clause is in the service's own client log —
  one second after the last close, with this process still alive, `com.nothing.camera` connected
  to a camera. Driven by `diag/CameraLifecycleCheck.kt`:
  `adb shell am start -n com.starstacker/.MainActivity --es diag lifecycle --ez handoff true`.
- [x] **T-1.4** Single manual `RAW_SENSOR` capture → `DngCreator` → session folder.
  Explicitly: NR off, edge/sharpening off, `CONTROL_MODE_OFF`, AE/AWB/AF off, OIS off,
  fixed WB, lens shading map reported not applied (FR-6.1).
  *Accept:* a `.dng` on disk from a tapped button. **Met** — `camera/RawCapture.kt`; 100 ms and
  10 s frames written, both with the full FR-6.1 profile, exposure honoured to 0.00%.
  **Two HAL behaviours found, both now encoded as decisions:**
  - **RAW-only sessions never stream** on this device (D-20). All four request profiles time out
    with a lone RAW target; all four succeed with a preview surface alongside. `RequestProfile`
    survives in the code as the bisection tool that found this.
  - **`TEMPLATE_STILL_CAPTURE` silently ignored the exposure** (D-21), returning 30 ms frames for
    a 10 s request. `TEMPLATE_MANUAL` fixed it on the first attempt.

  *Known inefficiency:* the settle-then-arm sequence spends two exposures per frame, so a 10 s
  capture takes ~40 s wall clock. Harmless here; **T-3.6 must not inherit it** — a repeating
  sequence settles once and then streams, rather than re-settling per frame.
- [x] **T-1.5** **Desktop validation checkpoint.** Open the DNG in Siril *and* RawTherapee/
  Lightroom. Confirm CFA pattern, black/white levels, no baked-in processing, correct EXIF
  exposure/ISO/focal length. **Also dump the TIFF tags** (`exiftool -a -G1`) and record
  `Compression`, `BitsPerSample`, `RowsPerStrip`, `StripOffsets` — this is the five-minute check
  that confirms D-13 on the actual device.
  *Accept:* written note in this doc's changelog recording what the tools said, including the
  compression tag value.
  **Assumed passing** on the owner's instruction (2026-08-16). The tag dump in §1.6 confirms
  `Compression = 1`, CFA `GRBG`, 16 bpp, black 64 / white 1023 and a 10.0 s exposure, and the
  round-trip in T-1.6 proves the pixel data is intact. **Still worth doing eventually:** opening
  a frame in Siril and RawTherapee, since FR-9.5 promises interoperability with them specifically
  and a structurally valid file can still trip a third-party decoder.
- [x] **T-1.6** **DNG reader** per **D-13**: header → IFD0 → SubIFD walk, strips into a
  `ShortArray` CFA plane plus the metadata the pipeline needs (dimensions, CFA pattern, black and
  white levels, ISO, exposure, timestamp).
  *Accept:* round-trip test — capture a frame, read it back, byte-compare against the in-memory
  buffer that was captured.
  **Done:** `dng/DngReader.kt`, ~330 lines, no Android dependencies. Walks IFD0 and falls back to
  SubIFDs (this device needs neither, but the DNG spec permits a thumbnail in IFD0), handles both
  byte orders, RATIONAL tags, and per-row strips. Refuses compressed, non-16-bit and demosaiced
  files by name rather than misreading them. **12 JVM tests** against synthetic files built to
  match §1.6 exactly.
  **On-device acceptance met (2026-08-16):** `RawCapture` snapshots the sensor buffer (honouring
  row stride) and compares it with the file it just wrote —
  `round trip: OK — 12582912 samples identical, CFA GRBG`, on both a 100 ms and a 10 s frame.
  Reading a 25 MB DNG back off disk costs **60–90 ms**.

**Checkpoint 1A:** one tap produces a DNG that Siril reads, and that the app can read back itself.

---

## 5. Phase 1B — Framing & focus

Goal: the part of "shooting mode" that happens *before* Start — in the dark, on a tripod, with a
sky you can't see on a normal preview.

- [~] **T-2.1** Stream configuration: preview + RAW still (+ analysis path per **D-9**). Do not
  hard-code the guaranteed-combination table — read the device's own
  `SCALER_MANDATORY_STREAM_COMBINATIONS` from the probe and confirm the chosen configuration with
  `CameraDevice.isSessionConfigurationSupported()` before opening the session. Handle the
  screen-off case: the preview surface goes away mid-session and the capture session must continue
  with only the `ImageReader` target.
  *Accept:* sequence keeps running with the screen off; resumes preview on wake without dropping a
  frame.
  **Written:** `camera/StreamPlan.kt` (pure size arithmetic, 8 JVM tests) picks RAW at maximum,
  the smallest even bin factor that meets the ~1 MP analysis budget, and an aspect-matched YUV
  second stream; `camera/StreamConfig.kt` reads the device's guaranteed list, looks for a
  combination of the same shape, and confirms the exact surfaces with
  `isSessionConfigurationSupported()` — treating "the HAL declined to answer" as *unknown*, not
  as *no*.
  **The screen-off half is now structural rather than handled (D-22):** the preview is drawn from
  the RAW frames, so no display surface is ever in the session. There is nothing to lose when the
  screen goes off, and "resumes without dropping a frame" is true by construction. That is a
  stronger property than the acceptance asked for, and it still needs demonstrating on hardware.
  **Verified on hardware 2026-08-17.** `isSessionConfigurationSupported()` returns **true** for
  RAW 4096×3072 + YUV 1440×1080, and the device names its own guarantee for it:
  *"In-app processing plus DNG capture"*. The planner's choice and the device's answer agree
  without any hard-coded table.
  **The screen-off half is re-filed, not met.** The loop keeps running with the screen off for
  about five seconds and is then frozen with the process still alive — D-22's surface argument is
  correct and does not cover process lifecycle (§1.7). This acceptance moves to T-3.6, where the
  foreground service that can actually satisfy it lives. Tracked as **OI-20**.
- [~] **T-2.2** **Night framing preview** — a repeating request at long exposure (~0.5–2 s) and high
  ISO, autostretched (MTF from median/MAD) for display only. This is the difference between
  framing being possible and impossible; a normal preview of a dark sky is a black rectangle.
  The UI must state the refresh rate so ~1 fps doesn't read as a freeze.
  **Spec:** default 1 s at high ISO; a "boost" control raising it to ~4 s for faint framing; the
  loop auto-stops after a period of no interaction, because framing heat is spent before the
  session even starts. Framing frames are never written to `lights/`. Default value is
  tuning-only — see **OI-4**.
  *Accept:* on a real night sky, stars are visible in the preview and framing is workable.
  **Written:** `camera/FramingSession.kt` — a repeating `TEMPLATE_MANUAL` request at 1 s / ISO 3200
  (boost 4 s), RAW frames copied into a **two-buffer pool** (25 MB each; allocating per second
  would spend more time in GC than in detection, FR-12.2), binned ×4, star-detected, autostretched
  and rotated to portrait. `imaging/Autostretch.kt` is the MTF stretch (7 tests) and
  `imaging/GrayImage.kt` the rotation (5 tests). The refresh rate, ISO and frame number are on
  screen; the loop stops itself after 2 minutes idle and says why.
  **Two things fell out of writing it:**
  - **Every frame is checked against its own metadata before it is measured** (D-21 applied per
    frame, not just per capture). Images and results arrive on separate paths, so they are paired
    by `SENSOR_TIMESTAMP`; a frame whose exposure or focus does not match the request is shown but
    marked *settling* and never fed to the focus sweep.
  - **The preview is rotated in pixels, not in layout.** A 90° `graphicsLayer` on a laid-out
    image leaves it letterboxed to the wrong axis; transposing the 786 KB grey raster costs a few
    milliseconds and makes the UI trivial.
  **Loop verified on hardware 2026-08-17.** Frames land at 1000 ± 40 ms with analysis at 68–100 ms
  warm (416 ms on the JIT-cold first frame) — comfortably inside the one-second budget, and a
  quarter of the 200 ms T-2.3 allowed for. The per-frame metadata check now works for the first
  time: `settled` was false on *every frame ever taken* before §1.7's tag fix, which would have
  made the focus sweep unusable and captioned the preview *settling* forever.
  **Remaining:** the acceptance itself — a real sky. Also **OI-4**: 1 s and 4 s are defaults, not
  measurements.
- [x] **T-2.3** Star detection module (Kotlin, pure, unit-tested): local background estimate,
  threshold, connected components, sub-pixel centroid via Gaussian/Moffat fit, **HFR**,
  **eccentricity**, star count. Operates on the binned ~1 MP plane.
  *Accept:* synthetic-frame unit tests (known star positions/FWHM) recover centroids to < 0.1 px
  and HFR to < 5%; runs in well under 200 ms on device.
  **Done:** `stars/StarDetector.kt` (tiled-median background with bilinear interpolation,
  MAD-derived noise, iterative flood fill, flux-weighted centroid, HFR as flux-weighted mean
  radius, eccentricity from second moments) and `stars/CfaBinner.kt` (green-channel binning per
  D-9, with conversions back to sensor coordinates). **12 JVM tests**, centroids recovered to
  < 0.1 px; HFR verified monotonic in defocus, which is the only property the focus sweep
  actually needs.

  Two things the tests forced:
  - **Intensity-weighted centroid, not a Gaussian fit.** It hits the < 0.1 px requirement on
    synthetic frames, and it does not assume a profile shape — a trailed or defocused star is not
    Gaussian, and those are exactly the frames being measured.
  - **The blob-size ceiling is generous (2000 px on the analysis plane), not tight.** A badly
    trailed star is long and thin; rejecting it as "too big" would make a trailed frame report
    *no stars*, which FR-7.5 diagnoses as cloud — telling the user to wait for clear sky when the
    real fix is a shorter sub.

  **On-device timing (2026-08-16), 4096×3072 → 1024×768:**

  | Stage | First call | Warm |
  |---|---|---|
  | DNG read from disk | 70 ms | 60 ms |
  | Green binning ×4 | 138 ms | **21 ms** |
  | Detection | 320 ms | **111 ms** |

  Detection meets the < 200 ms budget on the warm path; the first call is ~3× slower while the
  JIT warms up, which matters only for the very first sub of a session.

  **The binner was 546 ms before a one-line fix**: its inner loop held green-sample offsets in a
  `List<Pair<Int, Int>>`, boxing two Integers per sample across 6 million iterations. Swapping to
  two `IntArray`s made it 20× faster. This is FR-12.2's warning arriving early and in the exact
  shape it predicted — worth remembering before the Phase 3 accumulator is written.

  The disk read will not exist in the live path (T-3.10 bins straight from the in-memory sensor
  buffer), so the live per-frame cost is ≈ 130 ms against a 12 s sub. **Confirmed live
  2026-08-17:** 68–100 ms per frame in the framing loop, rising to ~340 ms with the screen off as
  the CPU clocks down.

  **Amended 2026-08-17 — the saturation trap (§1.7).** The detector reported 24–41 stars at a
  median HFR of 0.95 px on a *fully clipped* frame, because a saturated frame has zero measured
  noise and the threshold was a multiple of the noise with a `1e-6` floor. `saturatedFrame` is now
  a distinct answer from "no stars", and the floor is half an ADU. Two tests pin it. This was
  found only by pointing the phone at a lit room — the synthetic frames the original 12 tests use
  are never saturated, which is exactly the blind spot synthetic tests have.
- [~] **T-2.4** Focus sweep (§4.1.4): `LENS_FOCUS_DISTANCE` micro-steps around 0.0, HFR per
  position, always approach from the same direction (hysteresis), record the elevation it was
  calibrated at (gravity sag), store per camera. Fixed-focus cameras record "fixed focus"
  (FR-4.1.4.1).
  *Accept:* HFR-vs-position curve plotted in-app with a clear minimum; repeat sweeps agree.
  **Written:** `focus/FocusSweep.kt` (pure: positions, backlash park, curve analysis — 10 tests),
  `focus/FocusRunner.kt` (drives it on the live framing session), `focus/FocusStore.kt`
  (per-camera JSON, write-then-rename). The curve is drawn in-app as a bar per position with the
  minimum picked out.
  Three properties the tests pin down:
  - **Positions descend to 0.0 and the motor is parked past the first one**, so every setpoint is
    approached from the same side. Both are clamped to the lens's near limit — a park the HAL
    silently ignores is backlash left untaken.
  - **The minimum is interpolated** by a parabola through its two neighbours, recovering the
    vertex to well inside one step on a synthetic V-curve.
  - **A curve that never turns around is not reported as focus.** `MINIMUM_AT_EDGE`, `FLAT` and
    `TOO_FEW_STARS` are distinct verdicts, because "best HFR at the 0.0 hard stop", "the sweep was
    too narrow to see the curve" and "there are no stars tonight" call for three different
    actions, and merging them into one confident number is how a session ends up soft.
  **Mechanics verified on hardware 2026-08-17.** A nine-position sweep completes in 44 s with no
  timeouts, and every position lands on its nearest motor step in monotonic order —
  0.383, 0.346, 0.309, 0.271, 0.196, 0.159, 0.122, 0.047, 0.009 dioptres. Getting there needed
  three fixes from §1.7: the generation tag, `NEAR_INFINITY` (a request of exactly 0.0 returns
  hyperfocal, so the sweep never reached its own far end), and `awaitStableFrame` (two of the nine
  positions were reporting the position the lens was *leaving*, which files a real HFR under the
  wrong position and shifts the whole curve — a failure that looks like data, not like an error).
  Indoors the verdict is correctly `TOO_FEW_STARS`. That is worth stating plainly: before the
  saturation fix the same sweep would have returned a **confident bogus curve** built from ~30
  phantom stars per position.
  **A manual fallback was added 2026-08-17** (§1.11). The sweep needs measurable stars at several
  positions and correctly reports `TOO_FEW_STARS` when it cannot get them — an honest failure that
  was also a dead end, since there was no other way to set focus. The lens can now be stepped by
  hand, one measured motor step (0.0374 dioptres) at a time, against the live HFR readout, and the
  result stored with verdict `MANUAL` so the log records how it was arrived at.
  **Remaining:** the acceptance — a real sweep on stars, and repeat sweeps agreeing.
- [~] **T-2.5** Focus verification at session start + live HFR/star-count readout + mid-session
  drift alert (FR-6.3).
  *Accept:* deliberately defocus between sweeps → app detects and re-fixes.
  **Written:** `focus/FocusMonitor.kt` (7 tests) plus `FocusRunner.verify()`. Verification drives
  to the stored position, measures once, and re-sweeps *locally* only if HFR has actually
  degraded — a stored focus that still holds costs one frame to confirm. The live readout is
  always on, and the drift flag comes off a **rolling median**, so one hazy frame cannot trip it
  and a sustained rise cannot be missed. Frames with too few stars are ignored rather than
  counted as bad focus: that is a cloud diagnosis (FR-7.5), not a focus one. Degradation only —
  measuring *better* than the stored HFR means better seeing, not a reason to re-sweep. When the
  stored elevation and the current elevation differ, the message names gravity sag.
  **Remaining:** the acceptance — defocus deliberately and watch it recover.
- [~] **T-2.6** Pointing: accelerometer + magnetometer → altitude/azimuth (smoothed, with magnetic
  declination correction from GPS), latitude/longitude, derived field-centre declination and
  field-rotation rate `ρ ≈ 15.04·cos(lat)·cos(az)/cos(alt)`.
  *Accept:* alt/az agrees with a known star's position to a few degrees; the prototype's Pointing
  card is live.
  **Written:** `pointing/Astro.kt` (pure spherical astronomy — 11 tests) and
  `pointing/PointingSource.kt` (sensors, `GeomagneticField` declination correction, last-known
  location). The Pointing card is live with alt/az + compass point, declination, field-rotation
  rate, RA and position.
  - **Tested against positions whose answer is known by inspection**, not by running the same
    formula twice: due north at an altitude equal to your latitude must read +90°, the zenith must
    read your own latitude, and the requirements' own worked example (§7.1 — 40°N, due south, 45°
    altitude) must come out at 16.3″/s. It does.
  - **Smoothing is applied to the pointing vector, not to the azimuth angle.** Averaging angles
    across the 359°/0° wrap gives due south for a phone pointing due north; filtering the vector
    makes the wrap impossible rather than special-cased.
  - **The nulls are load-bearing.** No location means no latitude, which means no declination and
    no rotation rate — the card says "needs location" rather than showing a number derived from a
    guess. Near the zenith the rate is clamped and labelled as diverging, because that is the sky
    and not a bug.
  **Remaining:** the acceptance — check alt/az against a known star.
- [~] **T-2.7** Camera picker (prototype screen 02) with per-camera focal length and plain-language
  note, driven by the probe rather than hard-coded strings.
  **Written:** `device/CameraPicker.kt` (10 tests against the reference device's measured numbers
  from §1.5). Roles are *relative* — the best light-gathering rear camera (pitch²/N²) is "Main",
  and the others are named against it — and every note quotes the device's own measurements.
  Focal lengths are shown as 35 mm equivalents, since that is the number people compare.
  This is the task where hard-coding would have shipped a wrong belief: **FR-11.2 speculates the
  ultrawide may be the best astro camera, and on this device it is comfortably the worst**
  (1.12 µm at f/2.2 against 2.00 µm at f/1.88). The picker recommends the main camera because of
  the arithmetic, and the test asserts exactly that. Unpublished cameras are offered but flagged
  "capture is unproven" (OI-19), and a short exposure ceiling is called out even when the camera
  still qualifies.
  **Remaining:** seeing it render on the device.

**Checkpoint 1B:** on a tripod at night you can frame a target, watch focus lock, and read
alt/az, HFR and star count live. **Not yet demonstrated** — no device has been attached since
this phase was written.

---

## 6. Phase 1C — Unattended session ← **the primary deliverable**

Goal: FR-13/M3 — *press start, walk away, come back to a folder of good subs.*

### Exposure engine (§5)

- [~] **T-3.1** Sky measurement: capture a test frame, measure sky background level and per-ISO
  read noise (from the probe's `SENSOR_NOISE_PROFILE` at Functional tier; from the measured model
  once Phase 6 exists — one interface, two providers).
- [~] **T-3.2** Trailing limit: NPF-style using measured pixel pitch and focal length, corrected by
  field-centre declination, relaxed near the pole. User-visible tolerance defaulting to ~1.5 px
  star elongation (FR-5.1.1).
  *Accept:* unit tests against hand-computed values for several focal lengths and declinations —
  **plus one real-sky sanity check**, because a 2× pixel-pitch error (OI-17) passes every unit test
  and only shows up as trailed stars in frames the app declared safe. Shoot one sub at the computed
  limit and one at 2× it; the first must be round and the second visibly elongated.
- [~] **T-3.3** Sky-limited solver (FR-5.2): for each ISO ≥ dual-gain point, exposure to reach
  3–5× read noise in variance; clamp to trailing limit; pick the pair with the most clipping
  headroom. Emits a **derivation object** — every candidate and why it lost — not just the answer.
- [~] **T-3.4** Solve UI (prototype screen 02): the one-line answer, `Show work` expanding to the
  full derivation, and **pinning** any value with a re-solve around it (FR-5.3).
  *Accept:* pinning ISO re-solves exposure and vice versa; nothing downstream is disabled by a pin.
- [~] **T-3.5** Session planner (FR-5.4): input total time *or* target integration; output sub
  length, ISO, frame count, dark allocation, **storage budget** (warn before start if short),
  **battery budget**, estimated end time, and predicted common-area loss from field rotation.
  *Accept:* the prototype's Plan card is fully live; a deliberately under-provisioned storage
  scenario warns before capture starts.

### Capture engine (§6)

- [~] **T-3.6** Capture foreground service per **D-12**: `foregroundServiceType="camera"`,
  `FOREGROUND_SERVICE_CAMERA` + runtime `CAMERA`, started from the Start button while the app is
  visible (the `camera` type is while-in-use restricted, so it cannot be started from the
  background — starting it from the tap satisfies this and there is *no* 6 h limit on this type).
  Wake lock, persistent notification with progress, sequence state machine (`Idle → Focusing →
  Capturing → Paused → Darks → Finalising → Done/Failed`) exposed as one `StateFlow`. Fixed
  WB/focus/exposure/ISO across the whole sequence.
  *Accept:* 45-minute sequence completes with the screen off and the app backgrounded — **and
  repeats with battery optimisation left on**, since OEM battery managers are the residual risk
  the platform rules don't cover. If it fails, offer `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- [~] **T-3.7** Frame writer: DNG per frame with per-frame metadata capture, plus incremental
  `session.json` (FR-9.2 — timestamp, ISO, exposure, temperature, HFR, star count, background
  level, accept/reject + reason; transform added in Phase 2). Written incrementally, not at the
  end, so a killed process still leaves a usable log.
  *Accept:* kill the process mid-session → `session.json` describes every frame written.
- [x] **T-3.8** Session folder layout exactly per FR-9.1 (`lights/`, `darks/`, `flats/`, `master/`,
  `session.json`).
- [~] **T-3.9** Thermal pacing (FR-6.2): monitor `PowerManager.getThermalHeadroom()` + battery temp
  (+ sensor temp if the device exposes it — see **OI-7**), insert cooling gaps past a threshold,
  simple indicator with numbers on tap.
  *Accept:* thermal log across a full session, used to answer **OI-11**.
- [~] **T-3.10** Cheap live quality gating (the subset that needs no registration): eccentricity →
  trailing, star-count collapse → cloud, accelerometer spike → bump. Rejected frames kept on disk
  and flagged (D-10). Registration-residual gating and the common-area indicator arrive in Phase 2.
  **Bump check scoped to the exposure 2026-08-18** (§1.14): the peak is now queried over
  `[timestamp - exposure, timestamp]` rather than accumulated between reads, so motion during the
  readout or the DNG write no longer condemns a frame whose pixels are clean. Noise floor on a
  still phone over a 7.4 s sub is 0.013-0.021°, against a 0.5° threshold.
  **Verified on sky 2026-08-18** (§1.13): 42 of 49 accepted, zero false `TRAILED`, and the
  seven `BUMPED` are the phone being touched at the start and picked up at the end. The two
  detectors it shipped with were both wrong — see §1.10 for the accelerometer and §1.13 for the
  eccentricity — and neither was catchable from the JVM, since both were about the physical world.
- [~] **T-3.11** Live capture screen (prototype screen 03): the per-frame ring, metrics grid
  (HFR / stars / common area / sensor temp), recent-frame log with reject reasons, the
  non-blocking event note, `Pause` and `End & take darks`.
  *Accept:* readable from two metres away in the dark; matches the prototype's information density.
- [~] **T-3.12** Dark frames at end of session (FR-4.2.1): prompt to cover the lens, capture at
  matched ISO/exposure, log temperature per frame, write to `darks/`. Skippable, with the cost
  stated.
  **Written and demonstrated 2026-08-17.** `AWAITING_DARKS` is a real session state so a kill
  while waiting comes back knowing it owes darks; the prompt is on screen and in the
  notification; skipping is offered with the cost stated; and no answer within 15 minutes
  finishes the session cleanly and records why there are no darks. `SequenceSession` tags
  generations off `CaptureRequest.tag`, so frames still in flight when the lens went on cannot
  be filed as darks. Verified: 3 lights → prompt → confirm → 2 darks → `DONE`.
  **Allocation:** 15% of the light count, clamped to [10, 30], and charged *inside* the chosen
  session length rather than added to it. The 10 floor is thin for a short session — a tuning
  number to revisit once a real dark master has been stacked.
  **Remaining:** darks shot against a real session's warming curve.
- [~] **T-3.13** Interruption, pause/resume, and crash recovery: an interrupted session is
  resumable rather than lost (FR-6.4); on app restart, an incomplete session offers to resume.
  **Done and demonstrated by killing the process** — `am force-stop` at frame 12 of 30 left a log
  reading `CAPTURING` with exactly 12 frames recorded and 12 DNGs on disk; resuming continued the
  same folder to 30, contiguous, one directory, `DONE`. The engine needed no separate resume path
  because it starts from `lights.size + 1`. `SessionRecovery` scans the root rather than
  consulting an index (D-5, FR-10.6.4), declines to offer a session with nothing left to shoot,
  and "leave it" marks the log without deleting anything (D-10).
  **The offer sits on the landing screen**, not behind framing and setup: walking back through
  those would mean re-deriving an exposure and focus already decided correctly under a sky that
  has since moved.
  **Remaining:** the `Pause`/`Resume` controls have not been exercised mid-session on hardware.
- [~] **T-3.14** Live downsampled preview stack (FR-7.4) — translation-only running average until
  Phase 2 supplies real transforms. Depth per **OI-13**.
  **Done 2026-08-18, rendering on hardware.** `stars/PreviewStack.kt` is D-18's capped running
  mean over the ~1 MP analysis plane, downsampled again to 512x384 and autostretched;
  `stars/StarOffset.kt` supplies the translation.
  **Offsets by voting, not matching.** Pairing star A with star A is the hard problem asterism
  matching solves in Phase 2, and it can be skipped here: every correct pair yields the same
  offset and wrong pairs scatter, so binning all pairs and taking the fullest bin is a vote in
  which the signal agrees and the noise does not. The vote is also its own confidence — too few
  agreeing pairs returns null rather than a plausible number.
  **Aligned frame-to-frame and accumulated, not each frame against the first.** The field drifts
  by design: at 1.5 sensor px per sub, frame 1 and frame 40 are ~15 analysis px apart and their
  star lists stop overlapping enough for a vote to find them. First measured version aligned
  against the reference and the preview stuck at **depth 1 while the counter climbed to 21** —
  which reads as a broken app.
  **A failed vote does not drop the frame** (D-18: no rejection logic of its own). The gate has
  already passed it, so it goes in carried on the last known drift. Verified with the lens dark
  and zero stars detected: depth tracked the accepted count exactly, 22 of 22.
  **Two costs paid deliberately**, because D-18's thermal argument is the whole design and a
  preview that heats the sensor degrades the frames it is previewing: the stretch's median and MAD
  are taken on every 16th pixel rather than all of them, and the stats buffer is preallocated.
  **Remaining:** it has never run under a real sky, so nothing has yet confirmed that the vote
  finds a genuine star field or that the accumulated drift tracks it over a long session. Field
  rotation is uncorrected by construction and will smear stars away from the centre until Phase 2.
- [~] **T-3.15** Completion screen (FR-9.4): result summary, full session path, open/share.
- [~] **T-3.16** **Make the DNGs self-describing** (§1.12). A frame separated from its
  `session.json` currently cannot say which session it belongs to, whether it is a light or a
  dark, or what the sensor temperature was.
  1. **Dump a real frame first** — `exiftool` over a capture from `lights/`, recorded in §1.6 as
     a full tag list. Everything below is written against what is actually in the file, not
     against what `DngCreator` is assumed to write.
  2. **`setDescription`** with a compact `key=value` record: session id, frame index, frame kind,
     applied ISO and exposure, battery temperature, thermal headroom, focus dioptres, sky
     background ADU, HFR, star count, median eccentricity, and the gate verdict with its reason.
     One line, stable key order, so it diffs and greps.
  3. **`setOrientation`** — plumb the existing `writeDng` parameter through `CaptureEngine`
     instead of leaving it null.
  4. **`setLocation`** when a fix exists. The same fix the trailing limit wants (`PointingFix`),
     and the one a desktop plate-solve will ask for. Absent on the 2026-08-18 session, so this
     must stay optional and silent when there is no fix rather than blocking the write.
  5. **Do not write custom TIFF tags.** §1.12: growing IFD0 shifts all 3072 `StripOffsets`, and
     the reward is cosmetic.
  *Accept:* `exiftool` on any frame from a completed session prints the session id, the frame
  kind and the frame's own measured numbers; a `darks/` frame is distinguishable from a `lights/`
  frame **by its metadata alone**, with the file moved out of its directory. `DngReader` still
  parses every frame — the description must not disturb the strip layout — and the per-frame write
  budget is unchanged within noise.
  **Steps 1-3 done and verified on hardware 2026-08-18.** The tag dump is §1.13, and it changed
  the task: `ImageDescription` turned out to be **present and empty** rather than absent, and
  `Orientation` was **9**, which is not a value TIFF defines. `session/FrameDescription.kt` now
  builds the line and `CaptureEngine` passes it; a captured frame carries
  `StarStacker session=… frame=1 kind=LIGHT iso=800 exposure=0.2000s utc=… batteryTempC=35.0
  thermalHeadroom=0.739 battery=54%` in tag 270, and orientation is 1.
  **Step 2 is narrower than this task originally claimed, and the claim was wrong.** HFR, star
  count, eccentricity, background and the gate verdict **cannot** be in the DNG: the write
  ordering (§6) puts the bytes down *before* the pixels are analysed, so at write time those
  numbers do not exist. Getting them in would mean rewriting the file afterwards — the value sits
  in the IFD value area, and changing its length moves all 3072 `StripOffsets` — or analysing
  before writing, which trades a guarantee for a nicety. The DNG carries **identity and intent**;
  `session.json` keeps the measurements.
  **Step 4 done 2026-08-18** once the pointing record (T-3.17) put latitude and longitude on
  `CaptureEngine.Request`. A captured frame's GPS IFD went from **absent to 7 tags**, and IFD0
  from 54 to 55. `setLocation` is wrapped in `runCatching` — it throws on coordinates it dislikes,
  and a frame is worth more than its GPS tags.
  **Remaining:** the acceptance's dark half. `kind=DARK` is unit-tested but has not been shot on
  hardware — the darks path waits on a confirmation that only the UI can send, since the service
  is not exported and adb cannot reach it (correct security posture, not a defect).
  *Deferred:* `setThumbnail` would make sessions browsable in a file manager and in Lightroom,
  where they currently show as blank. It needs a downsampled image at write time, and the binned
  analysis plane does not exist yet at that point in the ordering (it is computed after the bytes
  are down, deliberately — §6). The secondary YUV stream is the candidate source. Worth doing,
  not worth reordering the write path for.

- [~] **T-3.17** **Record the pointing in the session log** (FR-9.2). `SessionInfo` has declared
  latitude, longitude, altitude, azimuth, declination and field rotation since the schema was
  written; `SessionLog` serialises and parses all six; and **nothing ever set them**, so every
  session ever written has a null pointing block.
  It is not exposure time that was lost — this camera's half-diagonal field is 42.6°, so unless
  the centre is above ~43° declination the fastest corner reaches the equator anyway and 7.399 s
  is the *correct* answer rather than a conservative one. What was lost is **auditability**: the
  declination is the only input to the sub length that leaves no trace in the pixels, so the log
  could not say whether the limit had been relaxed or worst-cased. Diagnosing the 2026-08-18
  session meant inferring "no fix" from the exposure matching cos δ = 1 exactly.
  **Done 2026-08-18** — `session/SessionPointing.kt` freezes the fix at Start and carries it
  through `CaptureEngine.Request` into `SessionInfo`. Frozen deliberately: the compass is not
  polled during capture, and the pointing that matters is the one the exposure was solved
  against, so re-reading it an hour later would describe a sky that has moved.
  **`compassAccuracy` is recorded alongside**, because a declination cannot be judged afterwards
  without it — a metal tripod head beside the magnetometer is an ordinary way to get a
  confident-looking, wrong azimuth, and that was the first hypothesis for the 2026-08-18 session.
  Verified end to end from adb (`--es lat 51.5 --es dec 22.3 --es compass HIGH`), which also
  exercises the intent transport that no JVM test can reach.
  **Remaining:** the producer. Nothing has yet confirmed that a fix taken from the *real* compass
  on the setup screen arrives non-null — every session so far predates this, and `PointingFix`
  yields a null declination unless magnetic declination, latitude and azimuth are all present.

> **The export half of Checkpoint 1C is met (owner-verified, 2026-08-18): the frames stack
> correctly in DSS.** That closes the largest open question under everything above — the DNGs this
> app writes are consumable by desktop tooling as they are, so nothing built on top of them is
> resting on an unverified format assumption. What the checkpoint still wants is the *session*:
> 45 minutes unattended, with darks, screen off.

**Checkpoint 1C — the one that matters:**
> A 45-minute unattended session on a tripod completes with the screen off, without thermal
> throttling, without being killed, without running out of storage unannounced, and leaves a
> session folder of DNG subs + darks + a complete `session.json` that Siril can stack on the
> desktop. (Success criterion §15.4, and §15.3's export half.)

At this point the app is already useful: it is a better capture tool than anything the phone ships
with, even with zero stacking.

---

---

## 6.5 Phase 1D — The interface it was designed to have

Nine changes from the owner's walkthrough (§1.15). They keep the `T-3.x` prefix because they are
the same app surface as Phase 1C, but they sit behind their own checkpoint: 1C is about a session
*working*, and none of this changes whether it does.

- [~] **T-3.18** **Main screen rebuilt to the prototype.** `Start a session` as the single
  full-intensity element, calibration banner below it, recent sessions, bottom strip of free space
  / device temperature / moon phase.
  The capability probe **moves to Settings** — it is a diagnostic and stops being the front door.
  *Depends on:* a session list, which was T-6.1's job in Phase 4 and is now T-3.27's (§1.17).
  Build the subset now: scan the
  root, read each `session.json` for target, frame counts and integration. Thumbnails need a
  stacked master (Phase 3), so `NO STACK` is the honest placeholder rather than a blank.
  Moon phase is pure arithmetic and belongs beside `Astro.kt`; free space is
  `SessionStore.freeBytes()`; device temperature is already on every frame record.
  *Accept:* the main screen answers "what do I do now" and "what did I shoot" without scrolling.
  **Built 2026-08-18.** `ui/MainScreen.kt`: `Start a session` as the only full-intensity element,
  warning banner *below* it, recent sessions, `All sessions · N`, and the free / device / moon
  strip. `Screen.MAIN` is now the back stack's root and **`PROBE` moved behind Settings** — §1.15's
  actual fix, since the capability probe had been the front door since Phase 1A.
  `session/SessionSummary.kt` reads the list. It parses a whole `session.json` per row and keeps
  five, which is honest at this size and will not be: **D-5's cached index is the real answer** and
  OI-5 still wants the scan measured. Loading happens off the launch path.
  Moon phase went into `Astro.kt` as mean-elongation arithmetic — a couple of percent of error,
  invisible at the precision shown, against an ephemeris this app has no other use for.
  Device temperature is read straight from the battery broadcast rather than through
  `DeviceEnvironment`, which would start a gyro listener to answer a question asked once.
  **Two honest placeholders.** The thumbnail says `NO STACK` because stacking is Phase 3, and the
  badge states where a session got to (`Captured`, `Unfinished`) rather than the prototype's
  `Stack now`, which is an *action* and cannot be one until T-5.x. A badge that does nothing would
  be the same mistake as the folder button in T-3.21.
  **Seen on device 2026-08-18, and it found three defects a build cannot.** The top bar drew
  *under* the system clock and battery — Android is edge-to-edge and nothing had ever applied an
  inset, so **every screen** was affected; the fix is one `systemBarsPadding()` at the
  `when (screen)` rather than per screen. The folder icon used `U+1F5C0`, which this font does not
  carry, and rendered as a sliver of vertical tofu — it is now **drawn from two strokes**, which
  cannot fail that way. And both session rows read `Session`, because the folder suffix is
  `_session` for every real one; rows are now named by start time (`01:23`, `00:50`), the only
  thing distinguishing two nights until targets are a feature.
  **Remaining:** `All sessions · N` opens the folder as a stopgap; the real list is **T-3.27**,
  which pulls it out of Phase 4 (§1.17).
  **The glyph lesson is now twice-learned** — the settings row's button widths in T-0.9, the folder
  icon here. Anything not laid out or drawn explicitly should be assumed wrong until photographed.

- [~] **T-3.19** **Settings icon top-right**, on the main screen's status bar, per the prototype.
  The capability probe lands behind it alongside the field log and the permission list.
  **Done 2026-08-18** — a gear in the main screen's top bar, and the only way in. A glyph in the
  app's own mono face rather than a vector asset: the icon set is not a dependency this app has.
  **Verified on device 2026-08-18** once the status-bar inset was applied — the gear was
  occluded by the battery icon before it.

- [~] **T-3.20** **Cut the explaining from Settings** (§1.15's rule). Delete the night-mode
  justification and the camera-permission rationale outright. Keep only consequence the user
  cannot deduce: the darks prompt lost with notifications, the equator fallback lost with location.
  Storage becomes a path plus a change control, with no essay about uninstall.
  **Done 2026-08-18.** The night-mode paragraph is deleted outright, `PermissionNeed.why` is empty
  for every permission, the calibration stub is one line, and storage is a path with `Open` and
  `Change`. What survives is consequence the user cannot deduce: the darks prompt lost with
  notifications, the equator fallback lost with location.
  **A test had to be inverted, which is the clearest sign the rule was needed.**
  `every permission has a reason and a consequence` asserted exactly what D-25 forbids; it is now
  `no permission justifies itself, and every refusable one names its consequence`.

- [~] **T-3.21** **Open the session folder from an icon**, on both the main screen and Settings,
  and from the all-sessions list.
  **The obstacle is real and shapes the design:** `Android/data/...` is unbrowsable on Android 11+,
  so an app-private default *cannot* be opened by any file manager (§ "Where your images are",
  measured 2026-08-18). A folder icon that does nothing is worse than none. So: when a SAF root
  exists, open it through `DocumentsUI`; when it does not, the icon offers to pick one — which is
  the moment the choice is actually motivated, and turns the constraint into the feature. Storage
  stays app-private by default and silent about it (T-3.20).
  **Mostly done 2026-08-18 as a consequence of T-3.18/T-3.20**: `openSessionFolder()` opens a SAF
  root through `ACTION_VIEW`, falls back to the picker rooted at the same folder when no documents
  app answers, and opens the picker outright when no root is chosen. Wired to the main screen's
  folder icon and to Settings' `Open`.
  **Remaining:** the all-sessions list does not exist yet, so `All sessions · N` opens the folder
  as a stopgap — **T-3.27** builds the pane, and the folder icon stays for the case where the
  answer really is "give me the files". None of it has been exercised on a device.

- [~] **T-3.22** **Inner ring: the exposure in flight.** The outer ring becomes the prototype's
  per-frame ticks (kept / rejected / remaining, leading-edge dot); a new inner ring sweeps 0→1 over
  the current sub.
  *Do not tick it from the capture thread.* Publish the exposure's start and duration on
  `Progress` and let Compose animate locally — the engine is busy and the screen is usually off.
  **The gap is not optional detail:** §1.14 measured ~3.4 s between a frame's exposure ending and
  its analysis finishing, so an inner ring that only knows about exposure needs a second state or
  it sits full and apparently stuck. It reads `exposing Ns left`, then `reading out`.
  **Done 2026-08-18, photographed mid-session.** The outer ring is one tick per frame with a
  leading-edge dot; the engine publishes the exposure's start on the monotonic clock and Compose
  animates it, so nothing ticks from the capture thread and nothing animates with the screen off.
  Verified on a 30-frame run at 4 s: ticks, dot, inner sweep and `exposing 3s left` all correct.

- [~] **T-3.23** **Drop the camera-openability tests from the UI.** They answered OI-18 in
  §1 — all five cameras open — and a resolved question does not need a permanent button. The code
  stays as an adb diagnostic; only the panel goes.
  **Done 2026-08-18.** The button and its results are gone from the probe screen; `RawCapture`'s
  two buttons stay, because "can this camera still produce a DNG" is a live question and "will
  these cameras open" is a settled one.
  **The task's own promise turned out to be false, which is why it is now true.** It said the
  probe survives on the `--ez autodiag true` path. It does not: autodiag guards the call with
  `profile?.let { }`, and measured on device it went straight to `RawCapture` and logged no
  openability line at all. Rather than ship the claim, the probe got its own trigger —
  `--es diag openability` — which logs `openability: 0: OPENED; 1: OPENED; 2: OPENED; 3: OPENED;
  4: OPENED`, OI-18's answer intact.
  Tidied alongside: `FieldDiagnostics` was logging `unknown diag mode` for every mode handled
  elsewhere (`capture`, `storage`, `crash`), which it now skips.
  **Still open, and not this task's:** why `profile` is null inside autodiag when `reprobe()` runs
  before it in `onCreate`. It affects only that debug path.

- [~] **T-3.24** **Focus, from the preview and unambiguous.** `Find focus` becomes an action on the
  preview itself rather than a separate card below it. Focus state gets three visibly distinct
  answers — **stored**, **not stored**, **stale** — because "no focus" currently looks much like
  "focus fine" and the fallback (hyperfocal at 0.0 dioptres, §1.11) is *soft but not ruined*, which
  is exactly the failure a user will not notice until morning.

- [~] **T-3.25** **Setup: solve on its own, and show what the frames will look like.**
  Remove the `Solve for an exposure` button — measuring the sky is the screen's purpose, so it
  should not need asking twice.
  Add a **predicted histogram** of the frames the current settings will produce. It is derivable
  from what the solver already holds: sky background in ADU, the per-ISO noise model, and the white
  level give the peak's position and width, and `clippingHeadroomStops` gives the distance to the
  right wall. This is the one picture that makes "sky-limited" mean something to a beginner.
  Add **exposure compensation**: a ± offset from the solved answer, with the histogram moving under
  it and the cost named (`skyToReadVariance` falling, or headroom shrinking). The solver keeps
  deciding; the user keeps the veto.
  **Done 2026-08-18, photographed.** The screen solves on arrival; the retry button survives only
  for the case where it failed, which is the only case a button helps. `PredictedHistogram` derives
  the frame from measurements already taken — sky electrons per second scaled by the exposure, read
  noise and gain from the noise model — so the hump's position is the signal and its width is the
  noise, with the clipping wall drawn. Live on device: *sky-limited · 5.1 stops of headroom*.
  Compensation is ±2 stops, and past that the solve is not being adjusted, it is being ignored; the
  trailing cost in pixels appears once the compensated sub passes the budget.

- [~] **T-3.26** **Session length as a continuous drag, not presets.**
  Remove the 15 / 30 minute buttons. One slider: **leftmost is a single frame**, rightmost about
  two hours, and the label tracks frames *and* wall-clock time as it moves.
  The quantum is the frame, not the minute, so the slider's value is a frame count and the time is
  derived — the reverse rounds to something that cannot be shot.
  **A correction to this entry, made while implementing it.** It originally said to use a measured
  cadence of exposure + ~3.4 s. That is wrong: §1.14's 3.4 s is the delay from *exposure end* to
  *analysis complete*, and the next exposure runs throughout it. Consecutive `SENSOR_TIMESTAMP`s in
  session `0123` sit **7.3993 s** apart for a 7.4 s sub, so the cadence *is* the exposure — the DNG
  write hides behind the next frame exactly as §1.9 claimed, and `MEASURED_OVERHEAD_SECONDS` stays
  at 0.01 s.
  Darks are charged inside the budget as they already are (15% clamped to [10, 30]).
  **Done 2026-08-18, photographed.** `SessionPlanner.Goal.Frames` is the slider's goal; the label
  reads `120 frames` and `17 min total`, spanning one frame to 2.5 hours of wall clock — a bound
  derived from the sub length, so the right-hand end is always the same amount of *night*.
  **The screenshot found an ambiguity worth fixing:** the headline says `15 min` (integration) and
  the slider said `17 min` (wall clock, darks included). Two times for one plan reads as a bug, so
  the slider now says `17 min total` and the caption states darks are inside it, not added to it.

**Checkpoint 1D:**
> Someone who has never seen the app can open it, understand what to press, and start a session
> without reading a paragraph. The three screens look like the prototype they were designed from.

## 6.6 Phase 1E — The second walkthrough

Ten changes from the owner's second pass (§1.17). Same `T-3.x` prefix and the same app surface as
1C and 1D, behind their own checkpoint: none of this changes whether a session works, and all of it
changes whether someone can run one without being surprised.

- [~] **T-3.27** **`All sessions` opens the sessions, not the folder.** The control currently calls
  `openSessionFolder()` — the same file-manager route as the folder icon beside it, so the app has
  two buttons doing one thing and no screen for the thing they are named after. It becomes a pane
  listing every session found by scanning the root (**D-5**, FR-10.6.4), with size on disk and
  state alongside what the main screen's rows already carry, and a tap opening that session's
  detail: frame log, derivation, pointing, path. The folder icon stays — a file manager is a
  different job, and T-3.21 put it there for the case where the answer really is "give me the
  files".
  This is **T-6.1 and T-6.3 arriving early**, out of Phase 4, because the button that needs them
  already exists and currently lies about what it does.
  *Accept:* `All sessions · N` lands on a list of all N, and tapping one shows its frame log. With
  no sessions it says so rather than presenting an empty box.
  *Watch:* this is the first screen that reads *every* `session.json` in the root, which is exactly
  the cost **OI-5** exists to measure. Time the scan on a root of ~12 sessions before building
  D-5's cached index — the index is only worth its second source of truth if the scan is slow.
  **Built 2026-08-19.** `SessionCatalogue.all()` scans the root and **times itself**, so OI-5's
  measurement is taken every time the pane opens rather than waiting to be instrumented;
  `SessionsController.scanNote()` states the figure on screen once it passes 250 ms. `SessionStore`
  grew `deleteSession` and `SessionFolder` grew `sizeBytes`, both implemented for SAF and for files.
  A folder whose log will not parse is **listed under `Could not be read`, not skipped** — the DNGs
  beside a damaged log are still worth having, and the screen built to find sessions is the worst
  place to hide one.
  **Remaining:** the scan has never run against a real root, so OI-5 is still open; and the pane
  has not been photographed.

- [x] **T-3.28** **Delete a session — and nothing else, ever.** Offered on a row and in the detail,
  with the frame count and the size on disk stated in the confirmation, deleting the folder and
  everything under it. Multi-select (T-3.29) makes it a batch.
  **D-26** amends **D-10**, which is otherwise contradicted by this task existing.
  *Accept:* delete a session and its folder is gone from the root and from the list; no deletion
  ever happens without a confirmation that names what is about to be lost.
  **Built 2026-08-19.** One route for the row, the detail screen and the batch, so the confirmation
  cannot be worded three ways or forgotten in one of them. The confirmation is drawn in the app's
  own palette rather than as a Material dialog, which would arrive at full brightness at 2 a.m.
  **`isPlainChildName` guards the call**, and `FileSessionStore` checks the *canonical* parent as
  well — a name that passes the guard could still resolve outside the root through a symlink, and
  this is the one call in the app that can destroy a night's work. A test deletes `../DCIM` and
  asserts the photos are still there.
  **Done 2026-08-19, on the phone.** A 24 MB probe session was deleted through the pane: the
  confirmation read `overexposure-probe · 1 light · 24 MB`, the row went, the pane went to
  `4 ON THIS PHONE`, and the folder was gone from disk — with the four real field sessions beside
  it untouched. The confirmation was also opened on one of those and cancelled, which is the other
  half of the acceptance.
  **Still untested:** SAF, where `deleteDocument` may refuse. The code reports a refusal per
  session rather than assuming success, but that path has never met a document provider.

- [~] **T-3.29** **Select sessions.** Multi-select in the pane, with the count stated and the
  selection surviving a scroll and a rotation.
  It serves **delete now and stack later**: `Stack selected` is Phase 3 (T-5.x) for one session and
  T-6.8 for several, so the action appears when there is something behind it. A visible button that
  silently does nothing is worse than no button — the same rule that keeps the prototype's
  `Stack now` badge off the main screen until it can act.
  *Accept:* several sessions can be selected, the count is shown, and the selection drives a batch
  delete. No stack control is drawn before it works.
  **Built 2026-08-19** as `SessionSelection`, plain data with a `Saver`, for the reason `BackStack`
  is: the rules are worth testing rather than clicking. Long press starts a selection and a tap then
  toggles, which is what every gallery on the phone already does. `retaining()` drops names that
  have gone — without it a batch delete leaves the vanished names selected, so the count claims
  three sessions and a second Delete acts on nothing while saying it acts on three. No stack
  control is drawn.
  **Remaining:** the rotation half of the acceptance rests on `rememberSaveable` rather than on a
  rotation anyone has performed — and the app is locked to portrait, so the case that will really
  exercise it is process death after a long background.

- [~] **T-3.30** **The session is named at the start.** Every session from the UI is currently
  labelled `"session"` — the literal string — so twelve nights of shooting produce twelve folders
  distinguished only by their timestamps. Start now prompts for a name.
  **Cancelled or left blank, the session is named for the day**, and the second that day is
  distinguished from the first (`2026-08-18`, `2026-08-18-2`, …). The number is decided by
  **scanning the root**, per D-5: a counter in preferences is wrong the moment a folder is copied
  in from a PC or deleted, which are both things FR-10.6.4 promises will work.
  The folder must not carry the date twice. `SessionLayout.folderName` already prefixes
  `yyyy-MM-dd_HHmm`, so a named session is `2026-08-18_2115_Orion` and a default-named one is
  `2026-08-18_2115` with no label suffix, while `session.json` keeps the full label either way.
  *Accept:* name a session `Orion` and the folder and log both say so; cancel the prompt and it is
  named for the day; start a second the same night and the two are told apart without reading the
  clock.
  *Note:* the name reaches the frames — T-3.16 writes session identity into every DNG's
  `ImageDescription` — so it is chosen before the first exposure and there is no rename to
  propagate afterwards.
  **Built 2026-08-19.** `SessionNaming` derives the default by counting the folders of that day,
  never from a stored counter, so deleting a session frees its number again — which is a test.
  `session.json` gained a **`label` field**, because the folder deliberately does not always carry
  the name and something had to; a log written before this decodes to a blank label and
  `SessionSummary` falls back to the folder suffix, then to the start time.
  **The prompt's field is pre-filled with the default** rather than hiding it behind a placeholder,
  so the user can see what the session will be called if they change nothing.
  **One deviation, stated:** `Not now` closes the prompt **without starting**, where the task says a
  cancelled prompt still starts under the day's name. A naming step with no way back makes a
  mistaken Start unrecoverable, which is the class of problem §1.17 is about; clearing the field and
  starting is the route to the default name, and it is what the screen says under the field. Say the
  word and it inverts in a line.
  **A third defect, found by a test rather than by reading:** the rule that stops a folder carrying
  its date twice matched `startsWith("$day-")`, which swallowed `2026-08-18-comet` — a name someone
  chose — and dropped it from the folder entirely, leaving the name only in `session.json` and a
  folder that looked unnamed. The suffix now has to be all digits.
  **Remaining:** no session has been started through the prompt.

- [~] **T-3.31** **Focus by hand becomes a disclosure under `Find focus`.** It is a permanent card
  today, several scrolls below the preview: open when it is not wanted, and out of sight at the one
  moment it is — a sweep that has just failed. It becomes a section under the `Find focus` control
  on the preview, opening **when the sweep fails** and when it is asked for. `Verify stored focus`
  moves into the same disclosure, for the same reason: it is a thing you do occasionally, not a
  thing you read every time.
  *Accept:* with focus unset the section is closed; a failed sweep leaves it open with the failure
  stated; tapping the disclosure opens it by hand. Both controls act against the live preview.
  *Unchanged:* the stepping itself — ±1 motor step of 0.0374 dioptres against the live HFR (§1.7).
  If the complaint is the control rather than its placement, that is a separate task and wants a
  number: which step, and judged against what.
  **Built 2026-08-19.** The disclosure opens on exactly two events, and the second is why the state
  lives on `FramingController` rather than in the composable: **a sweep that returns no record has
  not thrown** — it ran and found no minimum, which is the ordinary outcome under thin cloud — so
  the controller sets `sweepFailed` and opens the section from the camera layer. The old standing
  card is gone; what is left of it is `FocusCurveCard`, which exists only when there is a sweep to
  look at. The preview now reads image → rate → focus state → last focus message → disclosure.
  **Remaining:** the open-on-failure path needs a sweep that actually fails, which needs a sky.
  And the stepping itself is untouched — if "useless" meant the control and not the placement, that
  is still open.

- [~] **T-3.32** **Say what continuing without focus costs.** `Continue to session setup` is enabled
  on nothing but a camera being selected, so the app walks to setup with no stored focus and says
  nothing about it there. A line under the button when nothing is stored, in **the wording already
  used on the preview** — one sentence, authored once, per the trap T-3.24 records of a screen
  giving two answers to one question in two wordings.
  Deliberately **not a gate**: FR-3.1.1's Functional tier shoots without calibration, and a
  beginner stopped at 1 a.m. by a sweep that will not converge under thin cloud has nowhere to go.
  D-25: the consequence stays, the justification does not.
  *Accept:* with no focus stored the line appears and Continue still works; with focus stored there
  is no line.
  **Built 2026-08-19.** The sentence is a single `private const NO_FOCUS_CONSEQUENCE`, read by both
  the preview's focus state and the line under Continue — authored once, per the trap T-3.24
  records. Continue is not gated.

- [~] **T-3.33** **The sky is measured when asked.** Arriving at setup currently fires
  `measureAndSolve()` from a `LaunchedEffect` — the camera opens and frames are spent because a
  screen appeared. A `Measure the sky` button starts it instead, and the screen says what it will
  cost before it is pressed.
  **D-27** states the general rule, since the next screen with a measurement behind it will face
  the same temptation.
  *Accept:* arriving at setup takes no frames and leaves the camera closed; the solve appears after
  the button, and a failure offers the retry it already has.
  **Built 2026-08-19.** The `LaunchedEffect` is gone and `SetupController.measurementAsked`
  distinguishes *not measured yet* from *measured and failed*, which need different screens.
  `measurementCost()` states the price **before** the button — the ISO ladder's length and the test
  exposure, read off `SkyProbe` rather than written out, so the sentence cannot drift from what the
  probe does. Moving a surprise one tap later is not the same as removing it.
  **Remaining:** *"leaves the camera closed"* is an assertion about the HAL and needs the device to
  confirm. It is the one acceptance in 1E that a laptop cannot even partly answer.

- [~] **T-3.34** **Title the histogram.** T-3.25's prediction is the one picture that makes
  "sky-limited" checkable and it is drawn with no title and no labelled axis, so it reads as
  decoration. It gets a title that says it is *predicted*, a labelled clipping wall, and enough of
  an axis that the reader can say what the horizontal direction means without being told.
  *Accept:* someone who has not read §5 can say what the picture is of.
  **Built 2026-08-19.** Three additions, each answering a question a reader actually has: a title
  saying it is **predicted** (there are no frames yet, and a histogram normally describes something
  that exists), a **labelled** clipping wall (an unlabelled red line at one edge is a border), and
  the axis named at both ends — `black` / `brightness of one pixel →` / `full well` — which is what
  makes "the hump sits a little way in" a statement about the picture rather than a hint. A baseline
  under the bars, so it reads as a plot.
  **Remaining:** the acceptance is a person, and no person has seen it.

- [~] **T-3.35** **Exposure compensation as a photographer's control.** Today: an unlabelled slider
  over ±2 stops in thirds, titled `Exposure`, reading `as solved`. It becomes a **±4 stop** scale
  marked at whole stops (−4 −3 −2 −1 0 +1 +2 +3 +4), moving in **sixths of a stop**, under a title
  that says what it compensates.
  Two things fall out of the wider range and both are acceptance criteria, not notes:
  - ~~the compensated sub must **clamp to the sensor's maximum exposure** — 49.64 s here (§1.5) —
    rather than asking for one the HAL will silently refuse or truncate (D-21's whole family);~~
    **Reversed 2026-08-19 by measurement (§1.20, D-28).** The premise was wrong: the ceiling is
    advertised, not enforced, and the device returned 119.999987713 s for a 120 s request. The
    clamp was refusing exposures the hardware would take — and above dec 81.5° the sky asks for
    them. Replaced by a per-frame check that measures what the sensor did;
  - `SetupController.maxFrames` derives the session-length slider's upper bound from
    `solution.chosen.exposureSeconds`, the **uncompensated** sub, so the 2.5-hour bound is already
    wrong by up to 4× at ±2 stops and would be wrong by 16× at ±4.
  *Accept:* the scale reads −4 to +4 with the stops marked, moves in sixths, and the length
  slider's range follows the compensated sub. **Exposures past the sensor's stated ceiling are
  asked for and verified rather than refused** (D-28) — a frame that comes back at the wrong
  exposure fails the session by name.
  **Built 2026-08-19, and both defects are fixed and tested.** The arithmetic moved to
  `exposure/ExposureCompensation.kt` precisely so it could be: `SetupController` needs a `Context`
  and cannot be unit-tested, and these two are the kind of defect that looks like an ordinary
  number. `maxFrames` now takes the compensated sub — the test asserts 2244 frames at the solved
  4 s against 562 at +2 stops, which is the 4× that was wrong before — and `apply` clamps to the
  sensor's ceiling, with `isClampedAt` so the screen can say *"held at the sensor's longest
  exposure"* rather than letting the dial move while the number stops. A camera reporting **no**
  ceiling is not clamped to zero, which would have been a worse failure than not clamping.
  `compensate` also re-clamps the frame count, since a longer sub can put it past the new bound.
  **Dragged on glass 2026-08-19** (§1.19): `−3 5/6 stops`, the marked scale, and `1 frame to
  147 min` proving the length bound follows the compensated sub.
  **The clamp was then removed** (§1.20, **D-28**) when the device turned out to honour 120 s
  against a stated 49.64 s ceiling. `ExposureCompensation.apply` no longer clamps,
  `solverCeilingSeconds` lets the solver past the stated maximum up to a 240 s sanity bound, and
  `ExposureAttempts` fails a session whose frames come back at the wrong exposure.
  **Remaining:** whether sixths feel right under a thumb is a judgement about a physical gesture,
  and nobody has shot a circumpolar target — the case the reversal exists for.

- [x] **T-3.36** **Say what the exposure is, and what it becomes.** `as solved` is replaced by the
  solved sub as a time, and moving the control shows the change rather than the destination:
  `3.2 s → 4.5 s per frame`. The number is what the user is deciding about; "as solved" is the
  app's own bookkeeping.
  *Accept:* at zero compensation the solved sub is shown as a time; moved, both times are shown
  with the direction between them.
  **Done 2026-08-19, seen on the phone.** At zero it read `7.4 s per frame`; dragged to −3 5/6 it
  read `7.4 s → 519 ms per frame`. Both halves of the acceptance, photographed.
  `3.2 s → 4.5 s per frame`, and the title says `Exposure compensation` —
  `Exposure` named the wrong thing, since the screen has an exposure and this is what compensates
  it. Stops read as a photographer's fractions (`+1 1/3`), not as `+1.33`, because the scale is
  marked in stops and a decimal invites comparison against a number of seconds.

**Checkpoint 1E:**
> Sessions can be found, named, opened and deleted from inside the app; focus states its cost
> rather than hiding it; and nothing takes a frame that was not asked for.

---

---

## 7. Phase 2 — Registration & live gating

- [~] **T-4.0** **Synthetic sky generator** (test infrastructure, build this first): renders
  DNG-equivalent frames with known star fields, a known rotation/translation per frame, realistic
  noise, hot pixels, vignetting and a light-pollution gradient. Lets Phases 2–5 be developed and
  regression-tested indoors on cloudy nights, and gives registration and stacking a ground truth.
  **Built 2026-08-19** as `test/synth/SyntheticSky.kt`, 12 tests.
  **It renders a mosaic, not a plane.** `StarDetectorTest` already synthesised stars, but on the
  *binned mono plane* — the right level for the detector and the wrong one for everything
  downstream, since it skips the Bayer pattern, the black pedestal, the ADU quantisation and the
  clipping that the real pipeline meets first. This emits what the sensor emits: a GRBG mosaic of
  10-bit ADU with a black level, which `CfaBinner` bins and the rest reads unchanged.
  **Everything is accumulated in electrons and converted once**, because shot noise is √N in
  *electrons* and that statement becomes false the moment it is applied to ADU — which are
  electrons over a gain that moves with ISO. The defaults are §1.8's measured figures at ISO 3200.
  **The ground truth is checked before anything is built on it** (§1.22): a registration test
  asserting a 0.2 px residual is worth nothing if the frames do not carry the transform they claim,
  and a generator with a half-pixel placement bias would make a correct registrator look broken —
  or a broken one look correct.
  *Remaining:* nothing needs it yet. Its first real customers are T-4.2 and T-4.3, and the DNG
  *encoding* is not implemented — frames are in-memory mosaics, which is all Phases 2–3 consume.
  Writing actual DNG bytes is worth doing only when something wants to open one in Siril.
- [~] **T-4.1** Analytic transform seed from GPS + compass + accelerometer + timestamps + intrinsics
  (FR-7.2.1) — the robustness win when star-starved.
  **Built 2026-08-19** as `registration/SkyDrift.kt` (18 tests) and `pointing/CameraRoll.kt` (8),
  §1.23. The sky's motion is not unknown — it is the Earth turning at a rate known to nine figures —
  so the transform between two frames can be *computed* from where the phone is, where it points,
  which way up it is and how long has passed, leaving matching only to refine it.
  **The rotation half already existed**: `Astro.fieldRotationArcsecPerSec` is reused rather than
  reimplemented. What was missing is the *drift*: `d(alt)/dt = ω cos φ sin A` and
  `d(az)/dt = ω (sin φ − cos φ cos A tan a)`, the second reported as a great-circle rate so the
  cos(altitude) lives in one place instead of in every caller.
  **Roll had been thrown away.** `PointingFix` carried only the optical axis, though the rotation
  matrix holding roll was three lines away in `PointingSource` — and without it a seed knows the
  size of the drift and not its direction in the frame. `PointingFix.cameraRollDeg` now carries it.
  *Remaining:* nothing consumes the seed yet — T-4.2 is its first customer — and `cameraRollDeg` is
  **device** roll, so turning it into image roll needs each camera's `SENSOR_ORIENTATION` added.
  That belongs with T-4.4, where a real frame and a real camera meet.
- [~] **T-4.2** Asterism matching: triangle side ratios, invariant to translation/rotation/scale
  (astroalign as reference, MIT).
  **Built 2026-08-19** as `registration/AsterismMatcher.kt`, 15 tests, §1.24. Star *positions*
  change between frames — that is the problem — but a **triangle of three stars has a shape**, and
  shape survives moving, turning and scaling. Recognise the shape and each matched triangle
  proposes three correspondences; the ones proposed over and over are right.
  **Handedness is kept rather than discarded**: side ratios alone match a triangle to its mirror,
  and since the sky never reflects (FR-7.3) every such match is false. **Thin triangles are
  refused**, their ratios being noise wearing a number's clothes. **The seeded path is tried
  first** — T-4.1's prediction plus nearest-neighbour, which works on *four* stars where triangle
  statistics have nothing to say, and falls back rather than accepting a half-set.
  *Remaining:* nothing consumes the correspondences yet — T-4.3 fits them — and the matcher has
  never run on a real star field, only on rendered ones.
- [ ] **T-4.3** RANSAC outlier rejection + rigid (3-DoF) transform fit refining the seed (FR-7.3).
- [ ] **T-4.4** Live registration every frame on the binned plane; residual spike → bump detection.
- [ ] **T-4.5** Common-area tracking and the live `NN%` indicator (FR-7.5).
- [ ] **T-4.6** Transforms written into `session.json`; live preview stack upgraded to true aligned
  accumulation.

## 8. Phase 3 — Stacking

- [ ] **T-5.1** Add OpenCV Android SDK (D-7); warp/transform primitives only.
- [ ] **T-5.2** Calibration application on CFA data **before** debayer (FR-8.1 steps 1–2), with
  every master optional and pass-through when absent.
- [ ] **T-5.3** Debayer + tiled accumulator (FR-7.6): load tile T across all N frames, combine,
  write, advance. `FloatArray` only, buffers allocated once (FR-12.2).
- [ ] **T-5.4** Sigma-clipped mean as default; median / mean / kappa-sigma behind the expert
  affordance. **Build in Kotlin, profile, and only then consider the single sanctioned JNI
  exception** (§12.1).
- [ ] **T-5.5** Frame weighting by star count, HFR and background level.
- [ ] **T-5.6** Linear master out: 32-bit float TIFF, saved separately and treated as sacred
  (FR-8.2).
- [ ] **T-5.7** **Validation checkpoint:** stack the same subs in Siril/DSS on the desktop and
  compare SNR and star FWHM (success criterion §15.2). Record the numbers here.

## 9. Phase 4 — Session management

- [ ] **T-6.1** Session list from a root scan + cached index (D-5): thumbnail, target label, camera,
  accepted/rejected/total, integration time, status badge (FR-10.2).
  **Mostly pulled forward to T-3.27** (§1.17), which builds the list and the scan. What is left here
  is the two pieces that cannot come early: the **cached index**, which waits on OI-5 saying the
  scan is actually slow, and **thumbnails**, which want a stacked master from Phase 3.
- [ ] **T-6.2** Sort/filter by date, target, camera, status.
- [ ] **T-6.3** Session detail with the full frame log and manual include/exclude (FR-10.2.2).
  **The detail screen and its frame log are T-3.27.** What is left here is **manual
  include/exclude**, which is meaningless until something reads the flags — that is T-6.4's
  stacking queue.
- [ ] **T-6.4** Deferred stacking service — a **separate** FGS from capture, per **D-12**:
  `mediaProcessing` on API 35+, `dataSync` on API 34. Must implement `onTimeout()` → `stopSelf()`
  or the system throws `RemoteServiceException` at the 6 h budget. Progress, cancellable,
  resumable, queueable (FR-10.3).
  *Note the happy accident:* FR-10.3.3's "never stack unprompted" also satisfies the platform's
  rule that a service started from direct user interaction gets the full 6 h budget.
  *Accept:* checkpointed progress means a timeout-stop mid-queue resumes rather than restarts.
- [ ] **T-6.5** Restacking with versioned, non-destructive outputs and side-by-side comparison
  (FR-10.4).
- [ ] **T-6.6** `Stale` detection when calibration masters change (FR-10.4.2). Never auto-restack.
- [ ] **T-6.7** Storage management: per-session and total usage, "delete subs, keep masters"
  (FR-10.6.2), explicit deletion only.
  **Whole-session deletion is T-3.28** (**D-26**). What is left here is the *partial* case — keeping
  a master and dropping the subs behind it — which needs a master to keep, and so waits on Phase 3.
- [ ] **T-6.8** Multi-night stacking (FR-10.5): camera hard-reject, overlap check, per-session
  darks, background/scale normalisation, cold-start registration over a wide search range,
  composite session referencing its constituents, cumulative integration time.

## 10. Phase 5 — Auto-edit

- [ ] **T-7.1** Gradient removal — polynomial or RBF background model (FR-8.1.5).
- [ ] **T-7.2** Background neutralisation + rough colour balance.
- [ ] **T-7.3** MTF autostretch from median/MAD — the beginner payoff.
- [ ] **T-7.4** Mild saturation boost.
- [ ] **T-7.5** Auto-edit UI: one strength slider, before/after, re-run from the linear master,
  expert controls one tap deeper (FR-8.3).
- [ ] **T-7.6** MediaStore publish of the stretched JPEG (FR-9.3) + Siril/DSS-compatible export
  layout (FR-9.5).

## 11. Phase 6 — Calibration library

- [ ] **T-8.1** Noise characterisation: bias across the ISO range, read noise, gain, offset,
  dual-gain switch point, ISO invariance point (FR-4.1.1) → replaces the OEM-profile provider
  behind the T-3.1 interface.
- [ ] **T-8.2** Hot/warm pixel map (FR-4.1.2), quick and deep variants.
- [ ] **T-8.3** Flat field capture + validity checks (FR-4.1.3).
- [ ] **T-8.4** Lens intrinsics — measured, not trusted from `LENS_DISTORTION` (FR-4.1.5).
- [ ] **T-8.5** Timing calibration: gyro-to-timestamp offset, actual vs requested exposure, rolling
  shutter skew (FR-4.1.6).
- [ ] **T-8.6** Wizard UX per §4.0: independent resumable steps, visual guide per step, live
  validity check that fails fast with a specific reason, master preview after capture.
  **Never show a single large time figure** (FR-4.0.1.3); quick calibration is the sub-10-minute
  default offer.
- [ ] **T-8.7** Per-camera non-blocking banner naming what's missing and what it costs
  (FR-4.0.4.1–2).
- [ ] **T-8.8** Sky-dependent calibration folded into the first real session (FR-4.0.6) — not a
  separate chore night.
- [ ] **T-8.9** Calibration status screen: per camera × item, retake without a warning gate, master
  preview, delete, profile export/import (FR-4.0.7).
- [ ] **T-8.10** Sessions record the calibration versions they used; retake never rewrites history
  (FR-4.0.7.2).

## 12. Phase 7 — Wide-field correctness & second camera

- [ ] **T-9.1** De-project → rotate → re-project for fields > ~50° (FR-7.3).
- [ ] **T-9.2** Full per-camera isolation audit — nothing transfers between cameras (FR-11.1).
- [ ] **T-9.3** Camera recommendation with a stated reason (FR-11.3).

## 13. Phase 8 — Post-v1

- [-] **T-10.1** OIS dithering (FR-6.5) — investigate controllability during Phase 1A.
- [-] **T-10.2** Star trail mode — same capture, maximum instead of mean.
- [-] **T-10.3** Framing assistance: compass + accelerometer + small catalog → "point here" arrow
  (§14.7).
- [-] **T-10.4** Plate solving.

---

## 14. Open issues

**Needed-by** is the phase that cannot finish without a resolution.
**Status: 14 resolved · 8 open pending measurement · 2 deferred · 0 blocking.**
An issue is only "open" here if it can actually change the shape of the code. Questions with an
obvious default and a defined experiment are listed with that default already in force, so they
never block work.

### Blocking now

*Nothing is blocked, on a decision or on anything else.* Every issue below carries its default
already in force and an experiment that closes it, which is what makes them trackable rather than
blocking. Three of them — **OI-20**, **OI-21** and **OI-11** — are answered together by a single
45-minute session on a clear night, which is also Checkpoint 1C. **OI-5** is answerable indoors and
has been outstanding longest.

### Open — resolvable only by measurement

These are not design questions. Each has a decided default and a defined experiment; they close
when the number comes back.

| ID | Issue | Default until measured | Experiment | Needed by |
|---|---|---|---|---|
| **OI-19** | **Will the hidden cameras also *capture*, not just open?** All five IDs open, but only camera 0 has completed a real RAW capture. An ID that opens can still fail session configuration or never deliver a frame | Assume the tele and ultrawide work; verify before promising them to the user | Run the T-1.4 capture against IDs 2, 3 and 4 — cheap now the harness exists | 7 |
| **OI-20** | **Screen-off capture needs a foreground service, not just a surface-free session.** Measured 2026-08-17: the framing loop is frozen a few seconds after the screen goes off, process still alive. D-22 dissolved the *surface* problem but not the *lifecycle* one (§1.7) | Assume the `camera`-type FGS of D-12 is sufficient — it is what the type exists for | T-3.6's own acceptance: a 45-minute sequence with the screen off and the app backgrounded, then repeated with battery optimisation left on | 1C |
| **OI-22** | **A configured session occasionally delivers no frames at all.** Measured 2026-08-18 (§1.16): one session in 78 returned 0 of 2 frames inside a 12.4 s budget, immediately after a rapid open/close loop, while the other 77 configured in ~100 ms and delivered at once. It opens, configures and closes cleanly — only the frames never arrive, so nothing throws and nothing downstream is told anything is wrong | Accept and log. At 1 in 78 it costs a framing preview that stays black for a few seconds, not a session | Re-run `--es diag lifecycle --ei sessions 30` several times over and count. If it reproduces, the remedy is a deadline on the first frame and a re-configure, which is a shape change to `FramingSession` rather than a tuning constant | 1B |
| **OI-4** | Framing preview exposure length | 1 s, boost to 4 s, auto-stop after 2 min idle — **now implemented as the default** (T-2.2), so the experiment is a tuning pass rather than a build | Real-sky trial: shortest exposure at which framing is workable | 1B |
| **OI-5** | SAF write throughput and root-scan cost | **File baseline measured 2026-08-18: 200 × 24 MiB at 570 MiB/s (0.042 s/file), root scan 0.001 s.** SAF half still unmeasured — it needs a folder picked through the UI, which adb cannot do. The scan figure is from a 2-session root, not the ~12 the issue asks for, so it does not yet test D-5's premise | T-0.5: the same run against `SafSessionStore`, and a root with ~12 sessions. **T-3.27 both makes this bite and takes the measurement**: the session pane reads every `session.json` in the root and sums the bytes under every folder, where everything built so far reads five logs and no sizes — and `SessionCatalogue.all()` times itself on every open, surfacing the figure above 250 ms. So the experiment now runs whenever the pane is used; what is missing is a root with ~12 sessions to run it against | 0 |
| **OI-9** | Is the OEM `SENSOR_NOISE_PROFILE` good enough to pick a sane ISO at Functional tier? | Yes — use it. **Half-answered 2026-08-17: the profile is a real per-ISO measurement, not a stub** — nine distinct read-noise values across nine ISOs, falling smoothly from 5.64 e⁻ at ISO 50 to 2.07 e⁻ at ISO 3200 (§1.8). No dual-gain step is visible; the decline is the ordinary ADC-noise-over-gain trend. What remains is whether the *absolute* figures are right, which needs the Phase 6 bias series to compare against | **Trigger:** run the T-3.3 solver twice, once on OEM data and once on read noise measured from a quick bias pair. If the chosen ISO differs by more than one stop, promote the §4.1.1 noise model out of Phase 6 into 1C | 1C |
| **OI-21** | **Battery drain per hour of capture is unmeasured.** `SessionPlanner` warns against a placeholder of 18 %/h, chosen pessimistically so the warning fires early rather than late | 18 %/h | T-3.9's session log already records battery level per frame; a single 45-minute session yields the real figure | 1C |
| **OI-11** | Thermal pacing aggressiveness | No pacing; log only | T-3.9 logs temperature and dropped frames across a full 45-min session, then set the threshold from the curve. Tuning a pacing rule before seeing one real thermal curve is guesswork | 1C |

### Deferred

| ID | Issue | Needed by | Status |
|---|---|---|---|
| **OI-15** | **Framing assistance** (§14.7) — compass + accelerometer + catalog "point here" arrow. **Decided 2026-08-16: stays post-v1** (T-10.3), per the requirements' original placement. Phase 1B stays lean; framing is by eye and by the night preview. Note the enabling maths (alt/az ↔ RA/dec) still lands in T-2.6/T-3.2 for the trailing limit, so picking this up later remains cheap | 8 | **deferred** |
| **OI-16** | **OIS dithering** (§14.8) — depends on whether OIS is controllable at all; the T-1.1 probe answers that. Implement post-v1 regardless. | 8 | **deferred** |

### Resolved

**OI-24 — is the long-exposure frame cost per-session or per-frame? Closed 2026-08-19: per-frame,
above `SENSOR_INFO_MAX_FRAME_DURATION` and nowhere else.** Three real sessions at 0.951 s and
7.399 s and a probe at 40 s all run at exactly 1.00× cadence; a probe at 60 s runs at 2.0–2.9×, and
the limit is 49.6408 s (§1.21). The planner's per-frame cost is now a function of the sub rather
than a constant. §1.9's 2 ms overhead is confirmed for every sub below the limit, which is every
session shot so far.

**OI-23 — where the real exposure ceiling is. Closed 2026-08-19: there isn't one within reach.**
90, 120, 150, 240 and 320 s were all honoured to within 30 µs against a stated maximum of 49.6406 s
(§1.20). The app's own 240 s sanity bound sits below the hardware's capability, so nothing is
constrained by the sensor. Two register hypotheses died on the way (2²³ rows = 155.2 s, 2²⁴ rows =
310.4 s), and the applied values do not follow the 18.5 µs row quantum, so the extended range is
governed by something other than the arithmetic that explains the stated ceiling — a question about
the driver, and not one that blocks work.

| ID | Issue | Resolution | Closed |
|---|---|---|---|
| **OI-18** | Can the unpublished cameras be opened? | **Yes — all five IDs open**, including the ultrawide, tele and logical camera. Phase 7 stays reachable on this device (T-1.3) | 2026-08-16 |
| **OI-1** | DNG readback contradicts §12.1 — "RAW decoding not needed" is incompatible with FR-10.1's decoupled stacking, which must read frames back off disk the next morning | **D-13:** minimal Kotlin TIFF/DNG reader. `DngCreator` accepts only `RAW_SENSOR` at 16 bpp and writes uncompressed strips, and the requirements' own storage figures corroborate it (24 MB/frame ⇒ 3.6 GB per 150 frames ≈ the prototype's "3.8 GB"; compressed would be about half). Confirmed cheaply by an `exiftool` tag dump in T-1.5, with the lossless-JPEG fallback named and costed if `Compression ≠ 1` | 2026-08-16 |
| **OI-2** | Foreground service types for capture and stacking | **D-12:** capture = `camera` (while-in-use restricted, started from the Start tap, **no time limit** — so hours-long sessions are fine); stacking = `mediaProcessing` (API 35+) or `dataSync` (API 34), both 6 h / 24 h with a mandatory `onTimeout()` → `stopSelf()`. OEM battery-manager survival stays as a T-3.6 acceptance test rather than an open issue | 2026-08-16 |
| **OI-3** | Can preview + RAW + analysis be configured concurrently? | Not a risk, and the framing was wrong: don't reason from the published table at all — the device publishes its own guaranteed list as `SCALER_MANDATORY_STREAM_COMBINATIONS` (API 29+, and minSdk is 30), confirmable per-configuration with `isSessionConfigurationSupported()`. The RAW-capability table guarantees `PRIV(PREVIEW) + YUV(PREVIEW) + RAW(MAXIMUM)`, so **D-9**'s direct-RAW analysis and the YUV fallback are both available | 2026-08-16 |
| **OI-8** | `SENSOR_INFO_TIMESTAMP_SOURCE` = `UNKNOWN` would break gyro/frame alignment | Not a risk — the requirement was over-specified. Where timestamps feed the analytic seed, the quantity that matters is field rotation at ~16 ″/s; a millisecond of timing error is 0.016 ″, four orders of magnitude below a pixel. `UNKNOWN` only means an arbitrary monotonic base, correctable by one offset measurement at session start. Sub-millisecond alignment would matter for gyro-based deblur, which v1 does not do | 2026-08-16 |
| **OI-7** | Sensor temperature rarely exposed | **D-16:** log every available signal; use battery temperature as the dark-matching key. Darks are captured at the end of the same session along a monotonic warming curve, so proximity in time substitutes for an absolute reading | 2026-08-16 |
| **OI-10** | Are bias frames needed? | **D-14:** no. §4.2.2 makes bias conditional on implementing dark scaling; v1 doesn't, and per-session darks matched on ISO/exposure/temperature already contain the bias signal | 2026-08-16 |
| **OI-12** | Light-pollution input: Bortle picker vs GPS lookup | **D-17:** neither. The sky background is measured directly in T-3.1; a manual estimate is a worse input to the same calculation | 2026-08-16 |
| **OI-13** | Live preview stack depth | **D-18:** capped running mean of aligned binned frames, autostretched, no rejection logic | **closed 2026-08-18** — built as T-3.14 and rendering on device; the capped mean and the vote both behave as D-18 assumed |
| **OI-14** | Reference frame: first, or best quality? | **D-15:** both — first accepted frame for live registration, best-quality frame chosen at stack time. Decoupled stacking makes the selection pass free, since the frame log already holds the quality metrics | 2026-08-16 |
| **OI-6** | Does the Nothing Phone (3a) Pro clear the FR-3.1 envelope? | **Yes, comfortably.** LEVEL_3, RAW, full manual control, and a **49.6 s** maximum exposure on the main camera. Measured, not assumed — see §1.5 | 2026-08-16 |
| **OI-17** | Quad-Bayer RAW output form | **Binned, as hoped.** `SENSOR_INFO_BINNING_FACTOR = [2,2]`; 50 MP array delivered as a 12.6 MP `GRBG` Bayer frame at **2.00 µm** effective pitch. The platform reports the binned array directly, so the naive and effective pitch calculations agree here — but they are still reported separately, because that agreement is a property of this device, not of the maths | 2026-08-16 |

---

## 15. Verification strategy

| Level | What | Where |
|---|---|---|
| **Unit** | Star detection accuracy, trailing-limit maths, field-rotation maths, sky-limited solver, tier classification, session.json round-trip, DNG round-trip | JVM tests, no device |
| **Synthetic** | Registration and stacking against generated frames with known ground-truth transforms (T-4.0) | JVM / instrumented |
| **Device** | Camera lifecycle, stream configs, thermal behaviour, FGS survival, SAF throughput | Instrumented, real hardware |
| **Field** | The phase checkpoints — a tripod, a dark sky, and a completed session | Manual, logged in the changelog |
| **External** | DNGs open in Siril/RawTherapee (T-1.5); on-device master compared to Siril/DSS on identical subs (T-5.7) | Desktop |

**357 JVM tests as of 2026-08-19** — qualification 21, session naming 17, star detection 14,
session planner 14, frame gate 14, leak analysis 13, session pane store 12, session log 12, preview
stack 11, permissions 11, focus sweep 11, exposure solver 11, astro 11, DNG reader 10, camera picker
10, exposure compensation 10, trailing limit 9, stream planning 8, noise model 8, JSON 8, exposure
attempts 7, session selection 7, session recovery 7, navigation 7, focus monitor 7, autostretch 7,
predicted histogram 6, image rotation 5, frame description 5, session pointing 4, clock 3.

**The 46 added for Phase 1E are worth naming, because two of them describe defects that were
already live and one describes a defect a test found.** *The frame bound follows the compensated sub,
not the solved one* and *the compensated sub never exceeds the sensor's longest exposure* are
T-3.35's pair, both wrong at ±2 stops before the range widened. *A name that merely starts with a
date is not mistaken for the default* is the third, which nobody would have found by using the app.
Two more exist to stop a specific regression rather than to describe a feature: *delete cannot escape
the root*, which deletes `../DCIM` and asserts the photos survive, and *names that no longer exist
are dropped*, without which a batch delete leaves a count claiming sessions that are gone.

**The 7 in `ExposureAttempts` are the newest, and they guard a night rather than a feature** (§1.20).
The rule decides when a run of unusable frames stops being *settling* and starts being *refusal*,
which is what makes it safe to ask for exposures past the sensor's stated ceiling. The one worth
naming is *a frame skipped only for its generation is not evidence of refusal*: the generation guard
exists for darks, where frames from before the lens was covered are still in flight with the right
exposure and the wrong generation, and counting those would abandon every session that takes darks —
the exact opposite of the failure the rule guards against.

The 13 that closed T-1.3 are an odd entry in that list, because they test **a measurement rather
than the app** — whether a run of descriptor counts is a leak. They are here for the reason the
frame-gate tests are: the two shapes that fooled the check on the device are now fixtures taken
from that device, so *warm-up is not a leak* and *a phase too short to judge is inconclusive
rather than leaking* cannot regress silently (§1.16).

The 50 added on 2026-08-18 are worth naming, because three of them exist to stop a defect coming
back rather than to describe a feature: the frame gate's *undersampled stars are not judged on
their shape*, navigation's *entering capture leaves only the landing screen behind it*, and the
permission list's inverted *no permission justifies itself* — which used to assert the opposite
(D-25).

Phase 1C added 72 of those, and the split is worth noting: the exposure engine and the session
log are **entirely Android-free**, so the sky-limited solver, the trailing limit, the noise
model, the planner, the frame gate and the whole `session.json` round trip are testable on a
laptop. What needed a device was, again, exactly what should: opening cameras, configuring
streams, and whether a HAL honours what it was asked.

**A sixth level, added 2026-08-17: the `--es diag` harness.** Camera acceptances are driven from
`adb` rather than from the UI — `am start -n com.starstacker/.MainActivity --es diag <mode>`,
where the modes are now `framing`, `focus`, `lens` and `solve` (`diag/FieldDiagnostics.kt`),
`lifecycle` (`diag/CameraLifecycleCheck.kt`, T-1.3), `storage` (`diag/StorageBenchmark.kt`, T-0.5),
plus `capture`, `openability` and `crash` — writing a per-frame record to a file, because CamX
floods the log buffer and evicts our lines within seconds. This is
what made §1.7 findable: at roughly one frame per second, watching a preview cannot tell you that
the lens is reporting the position it is *leaving*, and none of the four HAL behaviours in §1.7
is visible from a screenshot.

The unit column is deliberately wide, and it is why the Android-free split is worth its cost.
Fifteen of the eighteen files under `device/`, `dng/`, `stars/`, `focus/`, `imaging/`,
`pointing/`, `json/` and `camera/StreamPlan.kt` import no `android.*` at all; the three that do
are exactly the three that must (`CameraProbe`, `FocusRunner`, `PointingSource` — the probe, the
lens driver and the sensors). So the maths is testable on a laptop on a cloudy night, and what
is left needing hardware is genuinely hardware: opening cameras, configuring streams, and
whether a HAL honours what it was asked.

The two external checks are the honest ones. §15.2 of the requirements sets the bar as
*measurably comparable* to a desktop stack — that number goes in the changelog when T-5.7 runs.

**Standing caveat:** JVM tests say the maths is right, not that the app works. Phase 1B's boxes
stayed `[~]` for exactly that reason, and the first hour on real hardware justified it — 109
passing tests coexisted with a `settled` flag that was false on every frame ever taken, a focus
sweep that could never have reached its own far end, and a star detector that read a blank white
frame as a well-focused sky. None of the three is a maths error, so no unit test was ever going
to catch them.

---

## 16. Changelog

| Date | Change |
|---|---|
| 2026-08-19 | **T-4.2 — asterism matching, and a threshold that had to be measured (§1.24).** Star positions change between frames; a **triangle of three stars has a shape**, and shape survives translation, rotation and scale. Each recognised triangle proposes three correspondences and the ones proposed repeatedly are right. Handedness is kept rather than discarded, since side ratios alone match a triangle to its mirror and the sky never reflects; thin triangles are refused as noise wearing a number's clothes; T-4.1's seed is tried first and works on **four stars**, where triangle statistics have nothing to say. **The first version was badly wrong and guessing would not have found it**: accepting any pair with two supporting triangles let fifteen correspondences through between two *unrelated* fields — a confident wrong answer, which is the one failure nothing downstream can catch. Measuring the vote distributions gave both the diagnosis and the fix: a true pair in a 24-star field collects **251–277 votes out of the 253 triangles its star belongs to**, against 14–35 for coincidences. So the rule is not a vote count but a **fraction of the chances the pair had** — scale-free, where a flat threshold tuned for 24 stars rejects true pairs at 8 and one tuned for 8 admits every coincidence at 24. **T-4.0 is what made any of this checkable**: correctness is "star 7 is star 12", which only a synthetic field knows. 357 JVM tests. |
| 2026-08-19 | **T-4.1 — the analytic drift seed, and a sign the tests caught (§1.23).** The sky's motion is not unknown, so the transform between two frames is *computed* from position, pointing, roll and elapsed time, leaving matching to refine rather than search. On the reference device the field moves about a pixel between consecutive 7.4 s subs and **hundreds of pixels across a 45-minute session**, which is the whole argument for seeding. The rotation half already existed in `Astro` and is reused; the drift half is `d(alt)/dt = ω cos φ sin A` and `d(az)/dt = ω (sin φ − cos φ cos A tan a)`. **Roll had been thrown away** — `PointingFix` kept only the optical axis though the rotation matrix holding roll was three lines away — and without it a seed knows the size of the drift but not its direction; `PointingFix.cameraRollDeg` now carries it. Both sign conventions are pinned against cases with known answers, because a seed pointing the wrong way is worse than none: matching converges confidently on the wrong star, and a flipped axis has exactly the right magnitude so nothing else would catch it. **The roll sign was wrong and the test caught it**: the natural cross product has the handedness of someone standing in front of the lens, where the useful convention is anticlockwise in the image. 342 JVM tests. |
| 2026-08-19 | **T-4.0 — a synthetic sky, and the trap inside it (§1.22).** Phase 2 begins with the fixture rather than the algorithm, because registration needs something a real sky cannot give: a frame whose **true** transform is known. `SyntheticSky` renders a GRBG mosaic in 10-bit ADU with a black pedestal — what the sensor emits, not the binned plane `StarDetectorTest` already had — accumulating everything in electrons and converting once, since shot noise is √N in electrons and that becomes false in ADU. Star fields follow a power law, with hot pixels, vignetting and a light-pollution gradient, all seeded so a failure can be replayed. **12 tests check the fixture itself**, because a placement bias would make a correct registrator look broken or a broken one look correct. **The trap worth recording**: the first version rendered 256×192 to be quick and found 5 of 20 stars, where 512×384 found 15 — `StarDetector` fits its background on 64 px tiles, and a plane one or two tiles across puts the gradient into the *noise* estimate (33 ADU against a true 17), doubling the threshold and silently losing every faint star. The economical choice produced a fixture that exercised a degenerate path. `MIN_USEFUL_WIDTH` is now the default. Also found: `StarDetector.saturationLevel` defaults to `Double.MAX_VALUE`, so a fully clipped frame is not flagged unless the white level is passed. 316 JVM tests. |
| 2026-08-19 | **`maxFrameDuration` is the number that *is* enforced (§1.21, OI-24 closed).** The long-exposure frame cost is **per-frame, not per-session** — but only past `SENSOR_INFO_MAX_FRAME_DURATION`, 49.6408 s here. Cadence measured from `capturedAt`: three real sessions at 0.951 s and 7.399 s and a probe at 40 s all run at exactly **1.00×**; a probe at 60 s runs at **2.89×, 2.01×, 2.87×**. So the two vendor numbers describe different things — the exposure range bounds a single frame and is not enforced (320 s works, §1.20), while the frame-duration limit bounds a sustained stream and is enforced as a cadence. This had to be fixed rather than noted, because **D-28** lets a user ask for subs past the ceiling and therefore past this limit too: a 60 s plan counted 60 s a frame and would have taken 156, putting the session length, end time, storage rate and battery estimate all out by 2.6×. `ExposureCompensation.frameCostSeconds` now returns `sub + 10 ms` below the limit and `sub × 2.6` above it, the factor being the measured mean rather than a round number. **§1.9's 2 ms overhead is confirmed, not overturned** — every session shot so far is below the limit and unaffected. 304 JVM tests. |
| 2026-08-19 | **The real exposure ceiling: there isn't one within reach (§1.20, OI-23 closed).** 90, 120, 150, 240 and **320 s** all honoured to within 30 µs against a stated maximum of 49.6406 s — 6.4× the advertised bound with no wall found. The 320 s frame was rejected `SATURATED` at 1023 ADU, which is 320 s at ISO 800 in a lit room working correctly; the *exposure* was honoured. Two register hypotheses died on the way (2²³ rows = 155.2 s, 2²⁴ rows = 310.4 s), and since the applied values do not sit on the 18.5 µs row quantum, the extended range is governed by something other than the arithmetic behind the stated ceiling. **The app's 240 s sanity bound is therefore below the hardware's capability**, so the operative limit is the one chosen for dark current and aeroplanes rather than one the sensor imposes. One methodological trap recorded: the 240 s probe was first called a failure after 7 minutes of no frame, which was premature — single-frame probes land at ~2× their own exposure, so it needed 13. **OI-24** opened for whether that 2× is per-session or per-frame, because if it is per-frame `SessionPlanner`'s 2 ms overhead is out by an exposure at long subs. Setup's `Solved from your sensor and this pointing` became **`Suggested settings based on measurements.`** — the old line described the app's working rather than the reader's position. |
| 2026-08-19 | **The sensor's exposure ceiling is advertised, not enforced — clamp removed (§1.20, D-28).** Asked "why is 50 s the limit?", the answer turned out to be "it is not". `SENSOR_INFO_EXPOSURE_TIME_RANGE` reports 49.6406 s; the device returned **119.999987713 s for a 120 s request**, and 89.999999662 s for a 90 s one. T-3.35's clamp rested on an assumption from the Camera2 contract rather than on a measurement, and it was refusing exposures the hardware would take — in **two** places, since `SetupController.resolve` capped the automatic solve as well. That is not academic: the trailing limit scales as 1/cos(dec), so **above dec 81.5° on this lens the sky permits longer subs than the ceiling allowed**, and every circumpolar target was capped by a number the sensor ignores. The clamp is gone; the solver's ceiling becomes `max(stated, 240 s)`, a sanity bound about dark current, aeroplanes and field rotation rather than about the sensor. **What replaces it is verification, not trust**: `nextVerifiedFrame` already checked every frame's metadata against the request (**D-21**), and past the stated ceiling it now fails with `ExposureRefused` instead of skipping — because skipping is right while the sensor settles and catastrophic if it never will, discarding two-minute frames until the session budget is gone and then reporting a *timeout*, which names the wrong problem. The rule lives in `ExposureAttempts`, pure Kotlin, 7 tests, and carries the subtlety that **a frame skipped for its generation is not evidence of refusal** — that path is the darks path, and counting it would abandon every session that takes darks. No warning on screen: a line about crossing a ceiling that is not enforced would warn about nothing. **OI-23** opened for where the real wall is. 300 JVM tests. **T-3.28 ticked** — a probe session was deleted through the pane (`overexposure-probe · 1 light · 24 MB`, gone from the list and from disk) with the four real field sessions untouched. |
| 2026-08-19 | **Phase 1E walked on the phone, and `KeyValue` fixed (§1.19).** The owner found the defect first: in session setup with the storage warning showing, `Storage` rendered **one letter per line**. The cause is in `KeyValue` and predates Phase 1E by three phases — the value was unweighted, so a `Row` measured it first against the full width and left the weighted label a few pixels. It only ever showed when it mattered, because the strings long enough to trigger it are the ones the storage and battery budgets emit *when the session will not fit*. Both sides are weighted now, 1 : 1.7, value end-aligned; the rule is that the row wraps the value rather than crushing the label. Reproduced and confirmed fixed on device at 17010 × 519 ms wanting 399.9 GB of 46.1 GB free. Two smaller ones of my own in the new pane: an unnamed session printed `started 20:39` under a row titled `20:39`, and the `Captured` badge floated mid-row against a wrapped description — the badge is top-aligned and the redundant line suppressed. **The walk confirmed T-3.33 outright** (arriving at setup takes no frames, `dumpsys media.camera` shows no open device, and the cost is stated before the button) **and T-3.35's substance** (sixths, a −4…+4 scale, and `1 frame to 147 min` proving the length bound follows the compensated sub). **T-3.36 is ticked** — `7.4 s per frame` at zero and `7.4 s → 519 ms per frame` when moved. Nothing was deleted: the confirmation was opened on a real 96 MB capture and cancelled. |
| 2026-08-19 | **Phase 1E built — all ten tasks, and three defects (§1.18).** `All sessions` opens the sessions rather than a file manager (**T-3.27**, pulling T-6.1/T-6.3 out of Phase 4), with a detail screen carrying the frame log, the derivation, the pointing and the path; sessions can be deleted singly or as a batch behind a confirmation that names the frames and the bytes (**T-3.28**, **T-3.29**, **D-26**); a session is named at Start and named for the day when it is not (**T-3.30**); focus by hand became a disclosure that opens itself when a sweep fails (**T-3.31**); the cost of no stored focus is stated under Continue from a single authored sentence (**T-3.32**); the sky is measured when asked, with the price stated first (**T-3.33**, **D-27**); the histogram has a title, a labelled clipping wall and a named axis (**T-3.34**); exposure compensation is ±4 stops in sixths under a title that says what it compensates, reading `3.2 s → 4.5 s per frame` (**T-3.35**, **T-3.36**). **T-3.35's two predicted defects were both real** and are fixed in a new pure `ExposureCompensation`, tested: the length slider's bound now follows the compensated sub (2244 frames vs 562 at +2 stops — the 4× that was wrong), and the sub is clamped to the sensor's 49.64 s ceiling with the clamp stated on screen. **A third defect was found by a test, not by reading**: the rule keeping the date out of a folder name twice also swallowed `2026-08-18-comet`, a chosen name, dropping it from the folder entirely. `session.json` gained a `label` field, since the folder deliberately does not always carry the name. 293 JVM tests, up 46. **Nothing has run on the phone** — the device was not attached, so all ten stay `[~]`, and four acceptances (T-3.33's closed camera, T-3.31's failing sweep, OI-5's scan cost, SAF deletion) are what `[~]` is carrying. One deviation, argued in §1.18: T-3.30's `Not now` returns without starting rather than starting under the day's name. |
| 2026-08-19 | **Audit pass, and two entries that had been wrong for three days.** §14's tally said 13 issues resolved where the table holds 12, and **Blocking now** still read "the one remaining gate is a measurement that takes minutes once T-1.1 exists — see OI-6", which stopped being true on 2026-08-16 when T-1.1 was built and OI-6 closed favourably; it now names the three issues a single 45-minute session closes together. The header had said `Last updated: 2026-08-16` through two whole phases. §15's test count caught up (234 → **247**), and the `--es diag` harness is listed by its modes rather than by the one file it started in. Phase 4's **T-6.1**, **T-6.3** and **T-6.7** now say which parts of them Phase 1E takes and which parts genuinely cannot come early — the cached index waits on OI-5, thumbnails and "delete subs, keep masters" wait on a Phase 3 master, and manual include/exclude waits on something that reads the flags. No task changed state. |
| 2026-08-18 | **Phase 1E planned — the second walkthrough (§1.17).** Ten tasks, and two of them reverse things this document argued for earlier. **The sky will be measured when asked** rather than on arrival (**D-27**): T-3.25's "solving is what this screen is for" was true of the screen and false of the cost, since the measurement opens the camera and spends frames the moment the screen appears. **Exposure compensation goes to ±4 stops** in sixths, marked like a camera's dial, because the predicted histogram sits directly above it and shows the consequence — the picture can do the arguing that `MAX_STOPS = 2.0` was doing by fiat. **D-26** amends **D-10** so a person can delete their own session, which D-10 never meant to forbid. The rest is placement: `All sessions` opened a file manager rather than the sessions (T-3.27, pulling T-6.1/T-6.3 forward), sessions were all labelled with the literal string `"session"` (T-3.30), focus by hand sat permanently open and scrolls away from the preview it must be judged against (T-3.31), nothing said what continuing without focus costs though the app allows it (T-3.32), and the histogram had no title (T-3.34). Two defects found while planning: the session-length slider's upper bound is computed from the **uncompensated** sub, already wrong by up to 4× and 16× at the new range, and the compensated sub is not clamped to the sensor's 49.64 s maximum (T-3.35). |
| 2026-08-18 | **T-1.3 closed — the camera lifecycle, run fifty times over (§1.16).** The last unticked box in Phase 1A, and the wrapper itself turned out to be fine: descriptors and threads flat across 50 open/close cycles, 30 configured sessions, 25 exception paths and 36 cancellations, with the camera service confirming the camera free again after **all 141 opens**. What needed fixing was the check. **Warm-up is shaped exactly like a leak** — a clean loop costs 132 → 173 descriptors over its first eight cycles and nothing after — so the first two versions of the rule convicted a clean run, twice, with confidence; it now needs twenty settled cycles before it will judge, and judges on the warm tail's rate and the median per-cycle step together. Two further traps: **re-opening the camera proves nothing**, since the framework hands a device between clients of one process without complaint, so a leak cannot be found by carrying on; and **`resolveActivity` answers with the chooser**, so the handoff spent 25 s waiting for a dialog to open a camera. The acceptance's second clause is in the camera service's own log — `DISCONNECT device 0 … com.starstacker` at 18:27:48, `CONNECT device 4 … com.nothing.camera` at 18:27:49, this process still alive. Two `resume` sites in `CameraAccess` that could drop a device on cancellation are now `resume(value) { release }`. 247 JVM tests. New **OI-22**: one configured session in 78 delivered no frames at all. |
| 2026-08-18 | **Phase 1D — the interface brought back to the prototype (§1.15).** The walkthrough found that **the main screen had never been built**: the capability probe sat there from Phase 1A, when the only question was whether the device worked, and was never replaced. Nine tasks. The main screen is now `Start a session` as the one bright element, warnings *below* it so they cannot read as a gate, recent sessions and a free/temperature/moon strip; the probe moved behind a settings gear. The capture ring became one tick per frame with an inner ring for the exposure in flight, which needs a second state because a frame is judged ~3.4 s after its exposure ends. Focus moved onto the preview and states the consequence — *"the session will shoot at hyperfocal — soft, not ruined"* — rather than a status word. Setup solves on arrival and draws a **predicted histogram** derived from the measured sky rate, read noise and gain, which is the one picture that makes "sky-limited" checkable. Session length became a drag in frames, 1 to 2.5 hours. **D-25** was written after the settings screen shipped with paragraphs justifying dark mode, the camera permission and the storage location — none of them a decision anyone makes. |
| 2026-08-18 | **The frame gate was rejecting everything, for two unrelated reasons, and both had passing unit tests (§1.13, §1.14).** A session accepted **0 of 105 frames**. The bump detector read the accelerometer, which cannot separate rotation from translation — and only rotation moves stars. On a tripod extension arm it flagged the *sharpest* frames in the session: one was called 7.85°, which at 74.2 arcsec/px would be a 382-pixel streak, while carrying 208 stars at HFR 0.925. Separately, eccentricity was measured on a 4× binned plane where a star is about one pixel across, so second moments are degenerate: the real 0.375 px trail predicts e ≈ 0.13 against a measured 0.855. The gyro replaced the accelerometer and the shape check now skips undersampled stars. Re-measured on sky: **42 of 49 accepted**, zero false `TRAILED`, and the seven `BUMPED` are the phone being touched at the start and picked up at the end. |
| 2026-08-18 | **Three device facts that each failed silently.** Scoping the bump check to the exposure needed them and every one was wrong in a way nothing surfaced. `SENSOR_DELAY_GAME` delivers at ~400 Hz, not the ~50 Hz its name implies, so the sample buffer spanned under ten seconds. **`SENSOR_TIMESTAMP` is the end of exposure on this device, not the start the documentation promises** — frames are analysed a stable 3.35–3.38 s after their own timestamp while the exposure is 7.4 s. With the sign backwards the window sat in the future, no query could be answered, and the check was **silently off**. And the zero-rate estimate was seeded from a single sample taken as the service starts — exactly when the phone is not still — which integrated **110°** of phantom rotation on the first frame. Still phone, 7.4 s subs, after all of it: 0.013–0.021°. |
| 2026-08-18 | **Phase 0 finished off.** SAF session storage over `DocumentsContract` with no `DocumentFile` anywhere (T-0.5); a **field log that survives the night** (T-0.6), demonstrated by crashing a session at frame 21 and recovering the trace *and* the frames leading to it; a permission flow that states what refusal costs — **`POST_NOTIFICATIONS` was never requested, and the darks prompt is delivered only as a notification**, so refusing it silently cost the session its darks (T-0.4); a settings screen (T-0.9); a **back stack** (T-0.3), whose absence meant the system back gesture left the app from any screen, mid-session included; and one place that owns construction, plus a clock seam (T-0.7). |
| 2026-08-18 | **The frames became self-describing, and the log stopped omitting the pointing.** `DngCreator` writes 54 tags and every one is the sensor's account of itself; nothing said which session a frame belonged to. `ImageDescription` turned out **present and empty** rather than absent, and `Orientation` read **9**, which TIFF does not define. Dumping a real frame also found three things §1.6 had wrong: `BlackLevel` is 64.25 not 64, `DefaultCropOrigin/Size` asks readers to trim 8 px, and `OpcodeList2` carries a 3908-byte lens-shading gain map — so a stacker honouring it has already flat-fielded the frame, which Phase 6's flats must not repeat. Separately, `SessionInfo` had declared six pointing fields since the schema was written and **nothing ever set them** (T-3.17). **The owner confirmed the DNGs stack correctly in DSS**, closing the export half of Checkpoint 1C. |
| 2026-08-17 | **Field guide written** (`docs/field-guide.html` → `StarStacker-Field-Guide.pdf`, 15 pages). Every screen, what each number means and its units, the order to press things in, and what to do when focus will not lock. Dark palette from the app's own tokens and a 100×190 mm page, so it reads at native size on a phone without zooming — which is where it will be read. It uses this device's measured figures rather than generic advice (black level 64, clipping at 1023 ADU, read noise 5.6 → 2.0 e⁻, the 0.0374-dioptre motor step, the hyperfocal quirk at 0.0, 25 MB per frame) and states plainly which four acceptances are still untested, so it cannot imply more confidence than the app has earned. |
| 2026-08-17 | **Three defects found by asking whether the app was ready to take out — §1.11.** None was a wrong answer; each was a missing question, which is why no test caught them. (1) **Nothing prompted the user to cover the lens** before darks, so an unattended session would have filled `darks/` with light frames — undetectable downstream and quietly fatal to every master built from it. (2) **Resume was unreachable from the UI**, wired only to the `adb` diagnostic, so a session dying at 03:40 was unrecoverable without a laptop — the one situation the feature exists for. (3) **The dark-sky branch chose the quietest ISO** instead of the ISO-invariance point: on this sensor read noise is flat above ISO 3200, so it picked ISO 6400 at 387 e⁻ of well, clipping every star to save 0.04 e⁻. Also added a **manual focus fallback** (§1.11), because the sweep correctly failing under thin cloud was an honest failure and a dead end. 184 JVM tests. |
| 2026-08-17 | **T-3.12's prompt — the first of §1.11's three.** The sequence rolled straight from lights into darks with nothing telling anyone to cover the lens, so an unattended session would have filled `darks/` with light frames — and nothing downstream can tell a light frame in a darks folder from a dark. Now a real state (`AWAITING_DARKS`) rather than a UI flag, because the session can be killed while waiting and has to come back knowing it owes darks and not lights. The sensor is stopped during the wait, the prompt is on screen and in the notification, darks are skippable with the cost stated (FR-4.2.1), and after 15 minutes with no answer the session finishes cleanly and records *why* there are no darks — waiting forever holds the camera open all night for someone who has gone to bed. `SequenceSession` now tags generations off `CaptureRequest.tag` like `FramingSession` does, so a frame exposed before the lens went on cannot be filed as a dark. |
| 2026-08-17 | **Phase 1C UI — the flow is walkable end to end on the device** (T-3.4, T-3.11, T-3.15). Session setup renders the solve as FR-5.3's single line, `Show work` expands the derivation object itself — every ISO considered with the reason it won or lost, rendered off the solver's own output rather than retold — and pinning re-solves around the pin with the plan, budgets and derivation all following it. Verified live: `ISO 50 · 3.4 s · 490 frames · 28 min`, pinned to 800 becomes `ISO 800 · 1.7 s · 1027 frames · 29 min`. The live capture screen is a pure function of the service's `StateFlow` (D-6), so it survives the Activity being destroyed and shows the same thing on return. `SkyProbe` extracts the on-device measurement so the setup screen and the `--es diag solve` diagnostic run *the same* solve — a diagnostic measuring something the app does not do is worse than none. **§1.10: the bump detector was measuring the wrong quantity** — the magnitude of the accelerometer vector, which is nearly invariant to rotation, so it was blind to tilt and tripping on noise; six of seven frames were rejected as BUMPED with the phone untouched. Now measured as the angle of the gravity vector, with the honest corollary written down: at 1.5 px the trailing tolerance is 0.031°, far below the sensor's noise, so the accelerometer can only catch a knocked tripod and the fine case belongs to Phase 2's registration residuals. 183 JVM tests. |
| 2026-08-17 | **T-3.13 — interruption and resume.** A 30-frame session killed with `am force-stop` mid-capture left a log reading `CAPTURING` with 12 frames recorded and exactly 12 DNGs on disk; resuming continued the same folder from frame 13 to 30, contiguous, one directory, state `DONE`. `SessionRecovery` scans the root for sessions whose own log says they were still going (D-5, FR-10.6.4 — no index, since an index is a second source of truth that is wrong the moment a folder is copied in from a PC), and declines to offer one with nothing left to shoot, which is a bookkeeping gap rather than lost sky. Abandoning marks the log and deletes nothing (D-10). 182 JVM tests. |
| 2026-08-17 | **The primary deliverable runs: a full unattended session, screen off, start to finish.** 53 frames (50 lights + 3 darks) at ISO 400 / 1 s written as valid DNGs into the FR-9.1 folder layout with a complete `session.json` — T-3.6, T-3.7, T-3.8, T-3.9, T-3.10 and T-3.12 all exercised on hardware (§1.9). **Per-frame overhead measured at 2 ms**: 53 frames in 52.0 s, so the 25 MB DNG write hides entirely behind the next exposure and capture runs at essentially 100% duty cycle. Battery temperature climbed 31 → 32 °C in the first minute, which is D-16's warming curve showing up per frame as designed. Capture needed **its own session class** rather than a flag on `FramingSession`: framing calls `acquireLatestImage()` and drops frames when busy, which is correct for a preview and would silently shorten the integration the planner promised, so `SequenceSession` uses `acquireNextImage()`, applies back pressure, and keeps the whole `TotalCaptureResult` that `DngCreator` requires. All 50 lights were correctly rejected as `SATURATED` — the phone was face-up under room light — which is §1.7's saturation guard proving itself in the capture path rather than only the framing one. 175 JVM tests. Still outstanding in 1C: the solve and live-capture UI (T-3.4, T-3.11, T-3.15), resume (T-3.13), the live preview stack (T-3.14), and SAF (T-0.5) — capture currently writes to `Android/data/.../files/sessions`, which is reachable from a PC over USB but is not yet FR-9.1's user-chosen root. |
| 2026-08-17 | **Phase 1C exposure engine written and validated against the sensor** (T-3.1, T-3.2, T-3.3, T-3.5). 152 JVM tests (112 before). §1.8 records what the device's own `SENSOR_NOISE_PROFILE` actually says: a real per-ISO curve, read noise 5.64 e⁻ at ISO 50 falling to 2.07 e⁻ by ISO 3200, **no dual conversion gain step** — which half-answers **OI-9**. Three findings came out of running it rather than testing it: **a clipped test frame produced a confident recommendation** while the advisory beside it said nothing could be measured (same family as the star detector's saturation trap — every number descends from a sky rate that a clipped frame cannot supply, so there is now no recommendation at all); **the headroom tiebreak was degenerate** under a bright sky, where all candidates sit at the same background fraction, so the answer was falling out of list order and the longest sub now wins explicitly; and **`RESULT_CACHE` was sized in entries**, holding only 160 ms of history at a 20 ms request, so short test frames never paired with their metadata and never settled. Two design points worth keeping: FR-5.2's sky-limited criterion is a **floor, not a target** — treating it as the answer recommends 20 ms subs — and FR-5.1's pole relaxation must be computed from the fastest-moving star *in the field*, since a phone's 85° diagonal makes the naive `cos(δ_centre)` form unbounded at Polaris while the corners still trail at two-thirds of the equatorial rate. New **OI-21**: battery drain per hour is a placeholder, not a measurement. |
| 2026-08-17 | **Phase 1B met on hardware, except what needs darkness.** A device was attached for the first time since the phase was written, and it found four HAL behaviours that were silently breaking it (**§1.7**): a **9–10 frame** request pipeline, focus quantised to ~0.0374 dioptre steps, a request of exactly 0.0 dioptres answered with *hyperfocal* rather than the far stop, and `LENS_STATE` reporting STATIONARY mid-move. Together these made `settled` false on **every frame ever taken**, which would have timed out every focus sweep and reported the sky as starless. Fixed by stamping requests with `CaptureRequest.setTag()` and reading the generation back off the result, so a frame is judged against the request that made it regardless of the HAL's queue; by `FocusSweep.NEAR_INFINITY`; and by `awaitStableFrame`, which waits for the lens to *arrive* rather than merely to be settled. Separately, the star detector was reading a **fully clipped frame as 24–41 stars at HFR 0.95 px** — saturation zeroes the noise estimate and the threshold was a multiple of it; `FrameStars.saturatedFrame` is now a third answer distinct from "no stars", since the two call for opposite remedies (FR-7.5). **T-2.1 confirmed** — the device names its own guarantee, *"In-app processing plus DNG capture"* — but its **screen-off half is re-filed to T-3.6 as OI-20**: the loop freezes seconds after the screen goes off with the process still alive, so D-22's surface argument, while correct, does not cover process lifecycle. 112 JVM tests pass. New `diag/FieldDiagnostics.kt` drives the camera acceptances from `adb`, which is what made any of this visible. |
| 2026-08-16 | **Phase 1B written — code-complete, field-unverified.** All seven tasks implemented: stream planning with the device's own guarantee check (T-2.1), the night framing preview (T-2.2), focus sweep and store (T-2.4), verification and drift monitoring (T-2.5), pointing (T-2.6) and the derived camera picker (T-2.7). **109 JVM tests pass** (43 before), `:app:assembleDebug` → 25.7 MB. **No device was attached, so nothing here has met its acceptance criterion** — every box is `[~]`. Three decisions came out of writing it: **D-22** (the preview is rendered from the RAW stream, which dissolves T-2.1's screen-off case instead of handling it), **D-23** (D-20's second surface must be a *drained* YUV reader — an unconsumed `SurfaceTexture` cannot be drained without a GL context and would stall a repeating request, which T-1.4 never noticed because it stopped after one frame) and **D-24** (own the JSON reader, since D-5 requires reading `session.json` back). Shared UI components extracted (T-0.2 part), and the FR-6.1 request profile de-duplicated into `ManualRequest` so there is one definition of "OEM processing off" rather than two that can drift. |
| 2026-08-16 | Plan created. Phases defined, capture prioritised ahead of calibration, 16 open issues registered. |
| 2026-08-16 | Issue triage. Closed OI-1, 2, 3, 7, 8, 10, 12, 13, 14 → decisions D-12…D-18. OI-4, 5, 9, 11 downgraded to measurements with defaults in force and defined experiments. OI-6 (test device) is the only blocker; OI-15 (framing assistance scope) awaits an owner decision. FGS types and stream-combination handling verified against Android docs; DNG compression corroborated by the requirements' own storage budget. |
| 2026-08-16 | **Phase 1A complete.** T-1.6 DNG reader and T-2.3 star detection written; 43 JVM tests pass. On-device round trip verified — 12,582,912 samples identical between sensor buffer and written DNG. Analysis chain measured: read 60 ms, bin 21 ms, detect 111 ms warm. Binning was 546 ms until a `List<Pair<Int, Int>>` in its inner loop was replaced with two `IntArray`s — FR-12.2's boxing warning, arriving early. T-1.5 marked passing on the owner's instruction. |
| 2026-08-16 | **First light (T-1.3, T-1.4).** All five camera IDs open — OI-18 closed favourably, the hidden ultrawide and tele are reachable. A 10 s ISO 800 RAW frame written as a 25.2 MB DNG with the exposure honoured exactly. Two HAL behaviours found and encoded as **D-20** (RAW-only sessions never stream — always configure a second surface) and **D-21** (`TEMPLATE_STILL_CAPTURE` silently ignored the exposure; use `TEMPLATE_MANUAL` and verify every frame's metadata). DNG structure measured (§1.6) — `Compression = 1`, 3072 one-row strips, CFA in IFD0 — closing **OI-1**. Camera 4 relabelled as the logical multi-camera it is, so the device reports 4 physical cameras + 1 logical, not 5. |
| 2026-08-16 | **Probed on real hardware. The device qualifies** — LEVEL_3, RAW, manual control, **49.6 s** max exposure, 2.00 µm binned pitch (§1.5). OI-6 and OI-17 closed; OI-3 and OI-8 confirmed on-device. Three probe bugs found by running it: incomplete camera enumeration (2 of 5 found), a null focus-distance misread as fixed focus, and permission-gated lens characteristics. All fixed; 21 JVM tests pass. New **OI-18**: the ultrawide and tele are unpublished — readable, openability unproven. |
| 2026-08-16 | **First code.** Project skeleton (T-0.1) + capability probe (T-1.1) + qualification gate (T-1.2, closed) + JSON export (T-0.8 partial) + night theme. `:app:assembleDebug` → 25 MB APK; 14 JVM tests pass. Requirements correction: `SENSOR_NOISE_PROFILE` is a CaptureResult key, not a CameraCharacteristics key, so it moves from the probe to T-1.4. Still no device attached — OI-6 and OI-17 remain unanswered until one is. |
| 2026-08-16 | Target device set to **Nothing Phone (3a) Pro** (§1.5 added, with a day-one qualification checklist). OI-6 reframed from a decision to a measurement; new **OI-17** raised on quad-Bayer RAW output and the 2× pixel-pitch trap it sets for the trailing limit. OI-15 decided: framing assistance stays post-v1. T-1.1 pulled ahead of the rest of Phase 0. |
