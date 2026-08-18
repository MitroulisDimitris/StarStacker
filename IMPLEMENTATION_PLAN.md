# StarStacker — Implementation Plan

**Companion to:** [astro-camera-app-requirements.md](astro-camera-app-requirements.md) (v0.1 draft) and
[astro-app-ui-prototype.html](astro-app-ui-prototype.html)
**Plan version:** 1.3 · **Created:** 2026-08-16 · **Last updated:** 2026-08-16
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
| **2** | Registration & live gating | M4 (reg), M5 | Live per-frame accept/reject with real transforms; common-area readout |
| **3** | Stacking | M4 | Linear master out, comparable to Siril on the same subs |
| **4** | Session management | M5.5 | Capture and stacking fully decoupled; restack, multi-night |
| **5** | Auto-edit | M6 | Shareable stretched JPEG without a desktop |
| **6** | Calibration library | M2 | Flats, noise model, hot pixels, intrinsics; Full tier reachable |
| **7** | Wide-field & second camera | M7 | De-project/re-project; per-camera calibration; recommendation |
| **8** | Post-v1 | §14 deferred | Dithering, star trails, framing assistance |

**Phases 0 → 1C are the priority.** Everything after 1C is sequenced but not yet scheduled.

### Progress

| Phase | Tasks | Done | Status |
|---|---|---|---|
| 0 | 9 | 1 | in progress — skeleton builds and installs; shared components extracted (T-0.2 part); SAF storage written but unmeasured (T-0.5, OI-5); field log and crash handler demonstrated (T-0.6) |
| 1A | 6 | 6 | **complete** — probe, qualification, camera lifecycle, first light, DNG reader |
| 1B | 7 | 2 | **hardware-verified except what needs darkness** — see §5 and §1.7 |
| 1C | 16 | 1 | **field-ready** — framing → setup → solve → start → live → darks → complete, with resume offered on launch and focus settable by hand. Outstanding: T-3.14 preview stack, T-3.16 DNG metadata, and T-0.5's benchmark (OI-5) |
| 1D | 9 | 0 | planned 2026-08-18 from the walkthrough (§1.15); T-3.18/19/20 built and T-3.21 mostly, none yet seen on a device |
| 2+ | outlined | 0 | not started |

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
| **D-10** | Rejected frames are written to `lights/` like any other frame and flagged in `session.json`. Nothing is ever deleted by the app | FR-7.5, FR-10.6.3 | — |
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
  **Two honesties.** `Session detail` in the task's list is Phase 4 (T-6.3) and does not exist; the
  five are probe, framing, setup, capture and settings. And only the probe-settings leg was walked
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
- [ ] **T-1.3** Camera2 lifecycle wrapper: open/close, dedicated handler thread, capture session
  creation, robust error and disconnect handling, and a hard guarantee the camera is released
  when the session ends or the process dies.
  *Accept:* open/close 50× in a loop without leaking; another app can take the camera afterwards.
  **Done 2026-08-16:** `camera/CameraAccess.kt` — one handler thread, suspend wrappers over the
  callback API, `withDevice {}` closing the device on any path, and typed `CameraOpenException`.
  **OI-18 resolved, favourably: all five camera IDs open**, including the unpublished ultrawide,
  tele and logical camera (`camera/OpenabilityProbe.kt`).
  **Remaining:** the 50× leak loop, and a check that another app can take the camera afterwards.
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
  *Depends on:* a session list, which is T-6.1's job in Phase 4. Build the subset now: scan the
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
  **Remaining:** never seen on a device — the phone was disconnected when it was finished, so this
  is compiled and unit-tested but unphotographed. `All sessions · N` currently opens the folder;
  the real list is T-6.1 in Phase 4.

- [~] **T-3.19** **Settings icon top-right**, on the main screen's status bar, per the prototype.
  The capability probe lands behind it alongside the field log and the permission list.
  **Done 2026-08-18** — a gear in the main screen's top bar, and the only way in. A glyph in the
  app's own mono face rather than a vector asset: the icon set is not a dependency this app has.
  **Remaining:** unphotographed, as T-3.18.

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
  **Remaining:** the all-sessions list does not exist (T-6.1), so `All sessions · N` opens the
  folder as a stopgap. And none of it has been exercised on a device.

- [ ] **T-3.22** **Inner ring: the exposure in flight.** The outer ring becomes the prototype's
  per-frame ticks (kept / rejected / remaining, leading-edge dot); a new inner ring sweeps 0→1 over
  the current sub.
  *Do not tick it from the capture thread.* Publish the exposure's start and duration on
  `Progress` and let Compose animate locally — the engine is busy and the screen is usually off.
  **The gap is not optional detail:** §1.14 measured ~3.4 s of readout and DNG write after a 7.4 s
  sub, so an inner ring that only knows about exposure sits full and apparently stuck for a third
  of every cycle. It needs a distinct second state (writing / analysing) or it will read as a hang.

- [ ] **T-3.23** **Drop the camera-openability tests from the UI.** They answered OI-18 in
  §1 — all five cameras open — and a resolved question does not need a permanent button. The code
  stays as an adb diagnostic; only the panel goes.

- [ ] **T-3.24** **Focus, from the preview and unambiguous.** `Find focus` becomes an action on the
  preview itself rather than a separate card below it. Focus state gets three visibly distinct
  answers — **stored**, **not stored**, **stale** — because "no focus" currently looks much like
  "focus fine" and the fallback (hyperfocal at 0.0 dioptres, §1.11) is *soft but not ruined*, which
  is exactly the failure a user will not notice until morning.

- [ ] **T-3.25** **Setup: solve on its own, and show what the frames will look like.**
  Remove the `Solve for an exposure` button — measuring the sky is the screen's purpose, so it
  should not need asking twice.
  Add a **predicted histogram** of the frames the current settings will produce. It is derivable
  from what the solver already holds: sky background in ADU, the per-ISO noise model, and the white
  level give the peak's position and width, and `clippingHeadroomStops` gives the distance to the
  right wall. This is the one picture that makes "sky-limited" mean something to a beginner.
  Add **exposure compensation**: a ± offset from the solved answer, with the histogram moving under
  it and the cost named (`skyToReadVariance` falling, or headroom shrinking). The solver keeps
  deciding; the user keeps the veto.

- [ ] **T-3.26** **Session length as a continuous drag, not presets.**
  Remove the 15 / 30 minute buttons. One slider: **leftmost is a single frame**, rightmost about
  two hours, and the label tracks frames *and* wall-clock time as it moves.
  The quantum is the frame, not the minute, so the slider's value is a frame count and the time is
  derived — the reverse rounds to something that cannot be shot. Use the **measured cadence**, not
  the exposure: §1.14 puts ~3.4 s of readout and write on top of every sub, so a 7.4 s sub costs
  ~10.8 s of wall clock and a "30 minute" session built from exposure alone would run 45.
  Darks are charged inside the budget as they already are (15% clamped to [10, 30]).

**Checkpoint 1D:**
> Someone who has never seen the app can open it, understand what to press, and start a session
> without reading a paragraph. The three screens look like the prototype they were designed from.

## 7. Phase 2 — Registration & live gating

- [ ] **T-4.0** **Synthetic sky generator** (test infrastructure, build this first): renders
  DNG-equivalent frames with known star fields, a known rotation/translation per frame, realistic
  noise, hot pixels, vignetting and a light-pollution gradient. Lets Phases 2–5 be developed and
  regression-tested indoors on cloudy nights, and gives registration and stacking a ground truth.
- [ ] **T-4.1** Analytic transform seed from GPS + compass + accelerometer + timestamps + intrinsics
  (FR-7.2.1) — the robustness win when star-starved.
- [ ] **T-4.2** Asterism matching: triangle side ratios, invariant to translation/rotation/scale
  (astroalign as reference, MIT).
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
- [ ] **T-6.2** Sort/filter by date, target, camera, status.
- [ ] **T-6.3** Session detail with the full frame log and manual include/exclude (FR-10.2.2).
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
**Status: 13 resolved · 7 open pending measurement · 2 deferred · 0 blocking.**
An issue is only "open" here if it can actually change the shape of the code. Questions with an
obvious default and a defined experiment are listed with that default already in force, so they
never block work.

### Blocking now

*Nothing is blocked on a decision. The one remaining gate is a measurement that takes minutes
once T-1.1 exists — see OI-6 below.*

### Open — resolvable only by measurement

These are not design questions. Each has a decided default and a defined experiment; they close
when the number comes back.

| ID | Issue | Default until measured | Experiment | Needed by |
|---|---|---|---|---|
| **OI-19** | **Will the hidden cameras also *capture*, not just open?** All five IDs open, but only camera 0 has completed a real RAW capture. An ID that opens can still fail session configuration or never deliver a frame | Assume the tele and ultrawide work; verify before promising them to the user | Run the T-1.4 capture against IDs 2, 3 and 4 — cheap now the harness exists | 7 |
| **OI-20** | **Screen-off capture needs a foreground service, not just a surface-free session.** Measured 2026-08-17: the framing loop is frozen a few seconds after the screen goes off, process still alive. D-22 dissolved the *surface* problem but not the *lifecycle* one (§1.7) | Assume the `camera`-type FGS of D-12 is sufficient — it is what the type exists for | T-3.6's own acceptance: a 45-minute sequence with the screen off and the app backgrounded, then repeated with battery optimisation left on | 1C |
| **OI-4** | Framing preview exposure length | 1 s, boost to 4 s, auto-stop after 2 min idle — **now implemented as the default** (T-2.2), so the experiment is a tuning pass rather than a build | Real-sky trial: shortest exposure at which framing is workable | 1B |
| **OI-5** | SAF write throughput and root-scan cost | **File baseline measured 2026-08-18: 200 × 24 MiB at 570 MiB/s (0.042 s/file), root scan 0.001 s.** SAF half still unmeasured — it needs a folder picked through the UI, which adb cannot do. The scan figure is from a 2-session root, not the ~12 the issue asks for, so it does not yet test D-5's premise | T-0.5: the same run against `SafSessionStore`, and a root with ~12 sessions | 0 |
| **OI-9** | Is the OEM `SENSOR_NOISE_PROFILE` good enough to pick a sane ISO at Functional tier? | Yes — use it. **Half-answered 2026-08-17: the profile is a real per-ISO measurement, not a stub** — nine distinct read-noise values across nine ISOs, falling smoothly from 5.64 e⁻ at ISO 50 to 2.07 e⁻ at ISO 3200 (§1.8). No dual-gain step is visible; the decline is the ordinary ADC-noise-over-gain trend. What remains is whether the *absolute* figures are right, which needs the Phase 6 bias series to compare against | **Trigger:** run the T-3.3 solver twice, once on OEM data and once on read noise measured from a quick bias pair. If the chosen ISO differs by more than one stop, promote the §4.1.1 noise model out of Phase 6 into 1C | 1C |
| **OI-21** | **Battery drain per hour of capture is unmeasured.** `SessionPlanner` warns against a placeholder of 18 %/h, chosen pessimistically so the warning fires early rather than late | 18 %/h | T-3.9's session log already records battery level per frame; a single 45-minute session yields the real figure | 1C |
| **OI-11** | Thermal pacing aggressiveness | No pacing; log only | T-3.9 logs temperature and dropped frames across a full 45-min session, then set the threshold from the curve. Tuning a pacing rule before seeing one real thermal curve is guesswork | 1C |

### Deferred

| ID | Issue | Needed by | Status |
|---|---|---|---|
| **OI-15** | **Framing assistance** (§14.7) — compass + accelerometer + catalog "point here" arrow. **Decided 2026-08-16: stays post-v1** (T-10.3), per the requirements' original placement. Phase 1B stays lean; framing is by eye and by the night preview. Note the enabling maths (alt/az ↔ RA/dec) still lands in T-2.6/T-3.2 for the trailing limit, so picking this up later remains cheap | 8 | **deferred** |
| **OI-16** | **OIS dithering** (§14.8) — depends on whether OIS is controllable at all; the T-1.1 probe answers that. Implement post-v1 regardless. | 8 | **deferred** |

### Resolved

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
| **OI-13** | Live preview stack depth | **D-18:** capped running mean of aligned binned frames, autostretched, no rejection logic | 2026-08-16 |
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

**184 JVM tests as of Phase 1C** — qualification 21, star detection 14, session planner 14,
session log 12, pointing 11, focus sweep 11, exposure solver 11, frame gate 11, DNG reader 10,
camera picker 10, trailing limit 9, JSON 8, noise model 8, stream planning 8, session recovery 7,
autostretch 7, focus monitor 7, image rotation 5.

Phase 1C added 72 of those, and the split is worth noting: the exposure engine and the session
log are **entirely Android-free**, so the sky-limited solver, the trailing limit, the noise
model, the planner, the frame gate and the whole `session.json` round trip are testable on a
laptop. What needed a device was, again, exactly what should: opening cameras, configuring
streams, and whether a HAL honours what it was asked.

**A sixth level, added 2026-08-17: `diag/FieldDiagnostics.kt`.** Phase 1B's camera acceptances are
driven from `adb` rather than from the UI —
`am start -n com.starstacker/.MainActivity --es diag framing|focus|lens` — writing a per-frame
record to a file, because CamX floods the log buffer and evicts our lines within seconds. This is
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
