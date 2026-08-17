# Astro Camera App — Requirements (v0.1 draft)

**Status:** working draft
**Scope decision:** astro-only v1
**Distribution:** side-loaded, own phone + a few friends' devices
**Implementation:** Kotlin + OpenCV Android SDK, no hand-written C++ (see §7)

---

## 1. Product vision

A guided deep-sky capture app for a tripod-mounted phone. The app knows the exact
hardware it is running on — measured, not assumed — and uses that knowledge to make
the decisions a beginner cannot make and an expert is tired of making by hand.

### 1.1 Design philosophy

**Beginner-friendly, expert-permeable.** Every automated decision must be:

1. **Sensible by default.** A first-time user taps "Start" and gets a stacked,
   stretched Milky Way image with zero configuration beyond framing.
2. **Visible.** The app always shows *what* it chose and *why* — "ISO 800, 12s
   (trailing limit 13.4s, sky-limited at 9s)". Not a black box.
3. **Overridable.** Any auto value can be pinned manually. Overriding never
   disables anything downstream.
4. **Reversible.** Nothing destructive. Linear masters and raw subs always survive.

**Corollary:** there is no "advanced mode" toggle. Advanced controls live one tap
deeper on the same screens, next to the auto value they replace. A beginner never
sees a wall of controls; an expert never has to hunt in a settings menu.

**The app is honest about failure.** If it's too cloudy, if the tripod got bumped,
if focus drifted — say so during the session, not after.

---

## 2. Scope

### 2.1 In scope (v1)

- Deep-sky / wide-field astrophotography from a **fixed (non-tracking) tripod**
- Guided multi-frame capture with automatic exposure and ISO selection
- Calibration frame management (darks, flats, bias, hot pixel maps)
- Per-device and per-camera calibration library
- Star-based registration with field-rotation handling
- Post-session stacking on-device
- Automated post-processing to a viewable image
- RAW (DNG) sequence export for external tools (Siril, PixInsight, DSS)

### 2.2 Out of scope (v1)

- Daytime / general photography (the earlier "general camera app" idea is deferred)
- Tracked mounts, star trackers, telescope control
- Planetary / lucky imaging (different problem: high frame rate, small ROI)
- Star trail mode (different output, easy to add later)
- Plate solving and target GOTO assistance — see §14
- Play Store distribution, in-app purchase, telemetry, accounts

### 2.3 Explicit non-goals

- Beating Google's Night Sight at handheld snapshot astro. Different product.
  We assume a tripod and minutes-to-hours of session time.
- Working around hardware that fundamentally can't do this (no RAW, no manual
  exposure, sub-2s exposure cap). The app detects and says so. See §3.1.

---

## 3. Target devices and platform

### FR-3.1 Device support model — runtime gating, not a whitelist

**No device needs to be known in advance.** Every fact required to decide whether a
device is supported is queryable from `CameraCharacteristics` at first launch, in
milliseconds, with no user involvement. Everything beyond that is calibration the
user supplies themselves.

So there is no curated device list and no per-device work by the developer. The app
probes, classifies itself into a capability tier, and adapts.

This is a deliberate reversal of the earlier whitelist plan.

#### Hard requirements (checked automatically; failure = unsupported)

| Requirement | Value |
|---|---|
| Min Android API | 30 (Android 11) — needed for `getConcurrentCameraIds()` and scoped storage behaviour |
| Target API | current |
| ABI | `arm64-v8a` only |
| Camera2 hardware level | `FULL` or `LEVEL_3`; `LIMITED` degraded; `LEGACY` unsupported |
| Required capability | `RAW` (`REQUEST_AVAILABLE_CAPABILITIES_RAW`) |
| Required | Manual exposure + manual ISO |
| Max exposure time | ≥ ~2s (below this the app cannot do useful deep-sky work) |
| Required sensors | Accelerometer, magnetometer; gyroscope and GPS strongly recommended |

**These four disqualifiers are real and unfixable by calibration:** no RAW, no manual
exposure, `LEGACY` level, or a max exposure under ~2s. "We support all devices" is
true only within this envelope — which is why the check is automatic and the failure
message is specific about *which* requirement the device missed.

Manual focus is **not** a hard requirement: a fixed-focus camera parked near
hyperfocal is perfectly usable (and immune to focus drift).

#### Capability tiers

| Tier | Condition | What the user gets |
|---|---|---|
| **Full** | Hard requirements met + all calibration complete | Everything in this document |
| **Functional** | Hard requirements met, no calibration yet | Capture, registration, stacking, auto-edit all work. Exposure engine falls back to `SENSOR_NOISE_PROFILE` and conservative ISO; no dark/flat/hot-pixel correction; focus found by live HFR sweep each session |
| **Degraded** | `LIMITED` hardware level, or RAW available but constrained stream configs | Reduced frame rate or resolution; warn explicitly |
| **Unsupported** | Any hard requirement failed | Clear message naming the specific missing capability |

**FR-3.1.1** The app must be genuinely useful at **Functional** tier on first night.
Calibration improves results; it is never a gate on getting an image.

### FR-3.2 Capability probe (first-run, per device)

On first launch the app enumerates and persists, for **every physical camera**:

- Hardware level, available capabilities, stream configuration map
- `SENSOR_INFO_ACTIVE_ARRAY_SIZE`, `SENSOR_INFO_PIXEL_ARRAY_SIZE`, physical size
  → derived pixel pitch
- `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`, `LENS_INFO_AVAILABLE_APERTURES`
- `SENSOR_INFO_SENSITIVITY_RANGE`, `SENSOR_INFO_EXPOSURE_TIME_RANGE`
- `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`, white level, black levels
- `SENSOR_NOISE_PROFILE` (OEM-provided; we measure our own anyway)
- `SENSOR_INFO_TIMESTAMP_SOURCE`, `SENSOR_INFO_MAX_FRAME_DURATION`
- `LENS_INFO_FOCUS_DISTANCE_CALIBRATION`, `LENS_INFO_MINIMUM_FOCUS_DISTANCE`
- `LENS_INFO_HYPERFOCAL_DISTANCE`
- Whether the camera has an AF motor at all (some ultrawides are fixed-focus)
- OIS/EIS availability and whether they can be disabled
- `getConcurrentCameraIds()` results and the concurrent stream configs

**FR-3.2.1** This probe is exportable as JSON so a friend can send their device's
profile without installing a calibration workflow.

**FR-3.2.2** The app must warn if the reported max exposure time is short
(< 10s), since that materially changes the achievable session design.

---

## 4. Calibration

### 4.0 Onboarding and prompting flow

Calibration is **offered, never enforced**. The app is fully functional without it
(§3.1, Functional tier); calibration is the upgrade path.

#### FR-4.0.1 Calibration is split into short, independent steps

**The earlier "~40 minutes" figure was wrong and must not appear in the UI.** It
overcounted by assuming a persistent dark library — which this design does not need,
because darks are captured per-session at working temperature (§4.2.1). Persistent
calibration needs long darks *only* for the hot pixel map.

Calibration is therefore a set of **independent, individually short, resumable
steps** — never one long blocking chore:

| Step | Time | Needs | Buys |
|---|---|---|---|
| Flats | ~2 min | White screen or twilight sky | Vignetting correction |
| Noise model (bias/short darks across ISO) | ~3 min | Lens covered, dark room | Correct ISO choice, dual-gain point |
| Hot pixel map (quick) | ~3 min | Lens covered, dark room | Removes most hot pixels |
| Hot pixel map (deep, optional) | ~15 min | Lens covered, dark room | Marginal gain; expert-only |
| Infinity focus + intrinsics | ~2 min | **Clear night sky** | Sharp stars, robust registration |

**FR-4.0.1.1 Quick calibration** = flats + noise model + quick hot pixel map.
**Under 10 minutes, indoors.** This is the default offer and captures nearly all the
available benefit.

**FR-4.0.1.2** Each step is separately runnable, separately skippable, and
individually stored. Progress survives leaving the wizard — a user can do flats
tonight and the noise model next week.

**FR-4.0.1.3** The UI must never present a single large time figure. It shows the
per-step cost, and quick calibration as one sub-10-minute option.

#### FR-4.0.2 First launch

After the automatic capability probe, offer quick calibration:

1. **Main (wide) camera first**, pre-selected. This is the one that matters.
2. Then optionally each additional physical camera, listed with a plain-language
   note on what it's good for ("ultrawide — widest field, longest subs before
   trailing"; "tele — Moon and small bright targets").
3. **"Skip for now" is always present and never punished.**

**FR-4.0.2.1** Only calibrate cameras the user actually intends to use. Do not walk
them through all four.

#### FR-4.0.3 In-wizard guidance

Each step must include a **short visual guide**, shown before capture starts, not
buried in a help page:

- **What to do**, in one or two sentences, plainly worded ("Cover the lens
  completely — a thick cloth or your palm in a dark room. No light must reach it.")
- **A reference illustration or example frame** of a correct setup
- **A live validity check** before committing: for flats, verify even illumination
  and no clipping; for darks, verify no light leak. Fail fast with a specific reason
  ("too bright — light is reaching the sensor") rather than silently storing a bad
  master.
- **Why it matters**, one line, expandable — beginners follow instructions better
  when they know the purpose.

**FR-4.0.3.1** After capture, show a preview of the resulting master (the vignetting
pattern, the hot pixel map) so the user sees a tangible result rather than a spinner
followed by nothing.

#### FR-4.0.4 Deferred prompting

If skipped, the session screen shows a **non-blocking banner**, not a modal:

> *Uncalibrated — results will improve with a one-time setup. [Calibrate]*

Tapping it opens the wizard at the right step for that camera. The banner must
never block starting a session, and must be dismissible for the current session.

**FR-4.0.4.1** The banner is **per camera**. Calibrating the wide must not produce
a nagging prompt when the user later picks the ultrawide, and vice versa — it shows
only for the camera currently selected.

**FR-4.0.4.2** The banner names what's missing and what it costs, specifically:
"No flats — vignetting won't be corrected (2 min, indoors)". Not a generic warning.

#### FR-4.0.5 The indoor/outdoor split

The first-run wizard **cannot** cover everything, and the doc must not pretend
otherwise. Calibration divides by what it physically requires:

| Group | Needs | When |
|---|---|---|
| Noise model, hot pixels, flats (§4.1.1–4.1.3) | Dark room + white screen | First-run wizard, any time, indoors |
| Infinity focus, lens intrinsics, timing (§4.1.4–4.1.6) | **Clear night sky** | Cannot be done at first launch |

**FR-4.0.6** Sky-dependent calibration must be **folded into the first real session,
not presented as a separate chore.** On the first uncalibrated session the app runs
the focus sweep and intrinsics fit as part of normal startup (~2 extra minutes),
tells the user what it's doing, and stores the result. The user never schedules a
"calibration night".

#### FR-4.0.7 Calibration status and retake (Settings)

A dedicated screen under Settings, listing **each camera × each calibration item**:

- Present/absent, capture date, and a quality indicator
- **Retake** action on every item, always available, no warning gate
- Preview of the stored master (flat pattern, hot pixel count, noise curve)
- Delete action for an individual item
- Export/import of the whole device profile as JSON

**FR-4.0.7.1** Retaking is a normal, expected operation, not a recovery path.
Reasons the user will legitimately want it: cleaned or scratched lens, suspected bad
flat, a phone case shadow in the original, or simply wanting to redo it properly
after a rushed first attempt.

**FR-4.0.7.2** Retaking replaces the master but **must not silently invalidate past
stacks.** Sessions record which calibration version they used (§9.2); a restack with
different calibration is a new output, not an overwrite (§10.4).

**FR-4.0.8** Staleness: flats and hot pixel maps should prompt for re-capture after
a long interval or if a validity check fails (§4.1.3.1). Noise model and intrinsics
do not expire.

### 4.1 Persistent (per-device, per-camera) calibration

Measured **once**, stored in a library, reused indefinitely. This is the core of
the "device-specific" premise and what removes the hardest steps from the
beginner flow.

#### FR-4.1.1 Sensor noise characterisation

Guided workflow (lens covered, dark room):

- Bias frames at minimum exposure across the full ISO range
- Measure per-ISO **read noise** (ADU and e⁻), **gain (e⁻/ADU)**, offset
- Detect the **dual conversion gain switch point** — the ISO where read noise
  drops sharply (present on most modern Sony/Samsung phone sensors)
- Derive the **ISO invariance point** and store it

Output: a per-ISO noise model used by the exposure engine (§5).

#### FR-4.1.2 Hot/warm pixel map

From stacked long-exposure darks: a permanent map of pixels that are
consistently anomalous. Stored per camera. Applied before stacking.

#### FR-4.1.3 Flat field

Phone lenses are sealed and fixed-aperture, so vignetting is a **stable per-device
property** — unlike a DSLR, flats do not need reshooting per session.

- Guided capture against an even light source (white screen + diffuser, or twilight sky)
- Stored as a normalised master flat per camera
- App must detect and warn if a flat looks wrong (clipped, gradient, dust change)

**FR-4.1.3.1** Provide an obvious "reshoot flats" action, since a scratched or
dirty lens invalidates the stored flat.

#### FR-4.1.4 Infinity focus calibration

- Sweep `LENS_FOCUS_DISTANCE` in micro-steps around nominal 0.0
- Compute **HFR** (half-flux radius) of detected stars at each position
- Store the position with minimum HFR

Must account for VCM behaviour:

- **Gravity sag** — calibrate at the elevation actually used (near zenith is worst case)
- **Hysteresis** — always approach the setpoint from the same direction
- **Thermal drift** — re-verify at session start (see FR-6.3)

**FR-4.1.4.1** For cameras with no AF motor, skip and record "fixed focus".

#### FR-4.1.5 Lens intrinsics

Measured, not trusted from `LENS_DISTORTION`:

- Focal length in pixels, principal point
- Radial/tangential distortion coefficients
- Lateral chromatic aberration

Used for the analytic transform seeding in §7.2 and for wide-field de-projection.

#### FR-4.1.6 Timing calibration

- Measured gyro-to-frame-timestamp offset
- Actual vs requested exposure time
- Rolling shutter readout time (`SENSOR_ROLLING_SHUTTER_SKEW`)

### 4.2 Per-session calibration

#### FR-4.2.1 Dark frames

Darks **must** be per-session and matched to the lights' ISO, exposure and
**temperature**. The sensor is materially hotter after 40 minutes of capture than
at session start.

- Default: capture darks **at the end of the session** (or interleaved), not before
- Temperature logged per frame (sensor temp if exposed, else battery temp as proxy)
- Matching temperature matters more than matching frame count
- Prompt: "Cover the lens — capturing darks. ~3 minutes."

#### FR-4.2.2 Bias frames

Needed only if dark scaling/optimisation is implemented. With darks captured at
matching exposure/ISO/temperature, the bias signal is already included.
**Open question — see §12.**

---

## 5. Exposure and ISO engine

### FR-5.1 Trailing limit

Do **not** use the 500 rule. Compute properly:

- Use measured pixel pitch and focal length (§3.2) — NPF-style
- Use compass + accelerometer to determine pointing altitude/azimuth, then
  derive field-centre declination
- Relax the limit when pointing near the celestial pole

**FR-5.1.1** The trailing budget is expressed as a user-visible tolerance
("max star elongation"), defaulting to ~1.5 px, adjustable.

### FR-5.2 Sky-limited exposure

The criterion for sub length is **not** "as long as possible":

> Expose until sky background shot noise dominates read noise
> (target ≈ 3–5× read noise in variance).

Procedure:

1. Capture a test frame
2. Measure sky background level
3. For each candidate ISO ≥ the dual-gain point, compute the exposure needed to
   reach the sky-limited threshold
4. Clamp to the trailing limit from FR-5.1
5. Pick the ISO/exposure pair that reaches sky-limited within the trailing budget
   with the most headroom against clipping

**Behaviour by sky:** under dark skies the result is typically read-noise limited
→ recommend more, shorter subs. Under light-polluted skies the sky limit is
reached quickly → shorter subs are sufficient and highlight headroom matters more.

### FR-5.3 UI presentation

- **Beginner:** one line. "ISO 800 · 12s · 150 frames · 30 min"
- **Tap to expand:** the full derivation — trailing limit, sky-limited exposure,
  read noise at each ISO, clipping headroom, why this ISO was chosen
- **Any value pinnable.** Pinning ISO re-solves exposure around it, and vice versa.

### FR-5.4 Session planner

User inputs: total session time **or** target integration time.

App outputs and displays:

- Sub length, ISO, frame count
- Time allocated to darks
- **Storage budget** — ~5–6 GB per hour of 12MP DNG subs; must warn before starting
  if free space is insufficient
- **Battery budget** — estimated drain; warn if insufficient
- Estimated end time
- Predicted field rotation over the session and resulting common-area loss (§6.2)

---

## 6. Capture

### FR-6.1 Capture engine

- Full `RAW_SENSOR` capture via Camera2, written as DNG via `DngCreator`
- No OEM ISP processing: NR off, sharpening off, lens shading map reported not applied
- OIS/EIS **disabled** by default for tripod use (see FR-6.5 for the exception)
- Fixed white balance, fixed focus, fixed exposure across the whole sequence
- Runs in a **foreground service** — a 40-minute session with the screen off must not be killed
- Wake lock held; screen may dim/off

### FR-6.2 Thermal pacing

- Model the device's throttling behaviour; monitor temperature continuously
- Insert cooling gaps if temperature climbs past a threshold (dark current rises,
  and the ISP starts dropping frames)
- Surface temperature in the live UI as a simple indicator, with numbers on tap
- **Open question:** default aggressiveness of pacing — see §14

### FR-6.3 Focus verification

Focus is the single biggest beginner failure mode — an entire session soft-focused
is unrecoverable.

- At session start: drive to stored infinity value, capture one frame, compute HFR
- If HFR is above threshold, run a short local sweep and re-fix
- **Live HFR + star count readout during the whole session** so the user can see
  that it's working
- Alert if HFR degrades mid-session (thermal drift, bump)

### FR-6.4 Interruption handling

- Bump detection via accelerometer + registration residual spike
- Cloud detection via star-count collapse
- On interruption: pause, notify, offer to resume (re-registering against the
  existing reference frame) rather than discarding the session

### FR-6.5 Dithering (stretch goal)

If OIS is controllable, apply deliberate sub-pixel lens offsets between subs to
enable drizzle-style resolution recovery, mirroring a standard desktop workflow.
**Deferred to post-v1** — see §12.

---

## 7. Registration and stacking

### 7.1 Why offsets alone are insufficient

An alt-az (tripod) mount cannot track the sky's rotation about the celestial pole,
so the field rotates relative to the sensor. Rate:

```
ρ ≈ 15.04 × cos(latitude) × cos(azimuth) / cos(altitude)   arcsec/sec
```

At ~40°N pointing south at 45° altitude this is roughly 16 arcsec/sec — comparable
to the sidereal rate — and it diverges near zenith. Over 30 minutes: several degrees.

**Key property:** rotational displacement at pixel radius `r` is `r × θ`, which is
**independent of focal length**. Wide and tele lose the same *fraction* of frame.
Only trailing scales with focal length.

### FR-7.2 Per-frame registration pipeline

1. **Analytic seed** — from GPS, compass, accelerometer, frame timestamps and known
   intrinsics, compute the *expected* transform relative to the reference frame.
   This is the robustness win: it works even when star-starved (thin cloud, tele
   lens, bright sky).
2. **Star detection** — threshold above local background on a downsampled (~1MP)
   frame; sub-pixel centroid via Gaussian/Moffat fit
3. **Asterism matching** — triangle side ratios (invariant to translation, rotation,
   scale)
4. **RANSAC** outlier rejection
5. **Transform fit** — refines the analytic seed

### FR-7.3 Transform model

- **Rigid (3 DoF)** is the correct physical model for narrow fields on a tripod
  (no scale change)
- **Wide fields (> ~50°)**: sky rotation is not a pure image-plane rotation.
  Gnomonic projection means stars follow curved paths and a single global rotation
  leaves corner residuals.
  → **De-project to spherical coordinates, apply the true rotation, re-project.**
- Homography is an acceptable intermediate approximation

### FR-7.4 Live vs deferred

| Stage | When | Why |
|---|---|---|
| Star detection + registration | **Live, every frame** | Cheap (tens of ms on a downsampled frame, once per ~15s) |
| Downsampled preview stack | **Live** | User feedback and confidence |
| Full-resolution stack | **After session** | Warping/accumulating 12MP frames heats the phone → raises dark current → degrades the frames still being captured. Vicious loop. |

**FR-7.4.1** Full stacking should ideally run with the screen off while the phone
cools, and must survive being backgrounded.

### FR-7.5 Live quality gating

This is the highest-value beginner feature. Per frame, live:

- **Star eccentricity above threshold** → trailing; reject the frame and offer to
  shorten the sub
- **Star count collapse** → cloud; pause
- **Transform residual spike** → tripod bumped
- **Common area remaining: NN%** → running indicator so the user knows when framing
  is about to die from rotation

Rejected frames are **kept on disk**, flagged in the log, and excludable/includable
by the user afterwards. Never silently deleted.

### FR-7.6 Stacking

- Tiled accumulator (memory-bound, not compute-bound): load tile T across all N
  frames, combine, write, advance
- **Sigma-clipped mean** as default combination — removes satellites and aircraft
  for free
- Alternatives available to experts: median, average, kappa-sigma, entropy-weighted
- Frame weighting by measured quality (star count, HFR, background level)

---

## 8. Post-processing

### FR-8.1 Pipeline order

Calibration happens on the **CFA data before debayering**:

1. `(light − dark) / normalised_flat`
2. Hot pixel correction from the stored map
3. Debayer
4. Register + sigma-clipped stack → **linear master**
5. **Gradient removal** — polynomial or RBF background model.
   Non-negotiable for phone astro; light-pollution gradients otherwise dominate
   everything downstream.
6. Background neutralisation + rough colour balance
7. **Autostretch** — MTF (midtone transfer function) with midtone and shadow points
   derived from image median and MAD. This is the step that turns a black rectangle
   into a visible Milky Way; it is the entire beginner payoff.
8. Mild saturation boost

### FR-8.2 Linear master is sacred

**The linear stack is saved separately from the stretched output.** Stretching is
destructive, and the linear master is what goes into Siril/PixInsight.

### FR-8.3 Auto-edit UI

- One **strength** slider
- **Before/after** toggle
- Re-run from the linear master at any time — non-destructive
- Expert affordance: expose gradient-removal degree, MTF shadow/midtone points,
  and saturation individually, one tap deeper

---

## 9. Storage and output

### FR-9.1 Session folder

User selects a session root via SAF (`ACTION_OPEN_DOCUMENT_TREE`). App-private
storage is hostile when the user is moving ~6 GB to a PC regularly.

```
<session-root>/<yyyy-MM-dd_HHmm>_<target-or-camera>/
├── lights/       *.dng
├── darks/        *.dng
├── flats/        (symlink/copy from calibration library, or reference)
├── master/
│   ├── stack_linear.tif      (32-bit float, linear)
│   ├── stack_stretched.jpg
│   └── stack_stretched.tif
└── session.json
```

### FR-9.2 Session log (`session.json`)

Per frame: timestamp, ISO, exposure, temperature, fitted transform, HFR, star count,
background level, accept/reject flag and reason.

Plus session-level: device profile ID, camera ID, calibration library versions used,
location, pointing, planner inputs, exposure derivation.

This is both the debugging tool and the expert's audit trail.

### FR-9.3 Gallery integration

The stretched JPEG is published via MediaStore so it appears in the system gallery.

### FR-9.4 Result visibility

On completion, show: the image, the full session path, and open/share actions.
The user must never have to hunt for where the output went.

### FR-9.5 External tool compatibility

Export the raw sequence in a layout directly consumable by Siril and DSS, so the
user can bail out to a desktop workflow whenever the on-device stack isn't good enough.

---

## 10. Session management

### FR-10.1 Capture and stacking are separate operations

**This is an architectural commitment, not just a UI convenience.** Capture and
stacking are decoupled: a session is a durable object that exists independently of
whether it has ever been stacked.

Rationale — stacking immediately after capture is often the *wrong* time. The phone
is hot (§6.2), the user is packing up in the dark, and the battery is low. Deferring
lets the stack run on the drive home, the next morning, or never.

**FR-10.1.1** A session's captured frames and its stack outputs have independent
lifecycles. Deleting a stack never touches the subs. Restacking never re-captures.

### FR-10.2 Session Management screen

Reachable from a top-level button on the main screen. Lists all past sessions,
newest first. Each row shows:

- Date, time, duration
- Thumbnail — the stretched result if stacked, otherwise a preview from the live
  preview stack (§7.4) so an unstacked session is still visually identifiable
- Target/label (user-editable, defaults to pointing coordinates)
- Camera used
- Frame count: accepted / rejected / total
- Integration time
- **Status badge:** `Captured` · `Stacking` · `Stacked` · `Stale` · `Failed`

**FR-10.2.1** Sortable and filterable by date, target, camera, and status.

**FR-10.2.2** Per-session detail view exposes the full frame log (§9.2): every
frame with its HFR, star count, transform, temperature, and accept/reject reason —
with the ability to manually include or exclude individual frames before restacking.

### FR-10.3 Deferred stacking

**FR-10.3.1** A session in `Captured` state has a prominent **Stack now** action.

**FR-10.3.2** Stacking runs in a foreground service, survives backgrounding and
screen-off, shows progress, and is cancellable and resumable.

**FR-10.3.3** The app should *suggest* a good moment — when charging, or when the
device is cool — rather than starting automatically. Never stack unprompted.

**FR-10.3.4** Queueing: multiple sessions can be queued to stack sequentially.

### FR-10.4 Restacking

Any session can be restacked at any time with different settings:

- Different combination method (sigma-clipped / median / mean / kappa-sigma)
- Different rejection thresholds
- Manual frame inclusion/exclusion
- Updated calibration masters (e.g. after retaking flats, FR-4.0.7.2)
- Different reference frame

**FR-10.4.1** Restacking is **non-destructive**. Previous outputs are retained as
versioned results within the session, each labelled with the settings that produced
it, and comparable side by side.

**FR-10.4.2** A session is marked `Stale` when its calibration masters have been
retaken since it was last stacked, with a one-tap restack offer. Never auto-restack.

### FR-10.5 Multi-night stacking

*(Promoted into v1 from the earlier deferred list.)*

The user selects **multiple sessions** from the management screen and stacks them
into a single result.

**FR-10.5.1 Compatibility check.** Before offering to combine, verify and report:

| Check | Rule |
|---|---|
| Camera | **Hard reject** — different physical cameras must never combine (§11.1) |
| Field overlap | Compute from pointing/registration; warn if common area is small |
| Sub exposure / ISO | May differ; frames must be weighted accordingly, not naively averaged |
| Sky background | Will differ between nights; requires per-session background normalisation |
| Orientation | May differ by a large rotation; registration must handle it |

**FR-10.5.2 Each session is calibrated with its own darks first.** Sensor
temperature differs between nights, so darks are never shared across sessions.
Calibration is per-session; combination happens on the calibrated, registered result.

**FR-10.5.3 Registration across sessions** cannot assume small offsets. Framing may
differ substantially and orientation may differ by tens of degrees. The analytic seed
(§7.2) is unavailable across nights without pointing data, so triangle matching must
work from a cold start over a wide search range.

**FR-10.5.4 Normalisation before combination.** Per-session background level and
scale must be normalised, and frames weighted by measured quality and exposure, or
a good night will be dragged down by a poor one.

**FR-10.5.5** The multi-night result is stored as its own composite session,
referencing its constituent sessions rather than duplicating their frames.

**FR-10.5.6** Show cumulative integration time across the selection — the number the
user actually cares about.

### FR-10.6 Storage management

**FR-10.6.1** Show per-session and total disk usage. Sessions are large (~5–6 GB/hr).

**FR-10.6.2** Offer **"delete subs, keep masters"** per session — reclaims almost all
the space while retaining the linear and stretched results. Must warn clearly that
restacking becomes impossible.

**FR-10.6.3** Deletion is always explicit and never automatic.

**FR-10.6.4** Sessions are discovered by scanning the session root, so a session
folder copied back from a PC (or from another device) is picked up and manageable.

---

### FR-11.1 Treat each physical camera as a separate instrument

Each requires its own darks, flats, hot pixel map, intrinsics and infinity focus
value. **Nothing transfers between cameras.** Frames from different cameras must
never land in the same stack.

### FR-11.2 Realistic expectations

The tele is usually the weaker astro camera: smaller sensor, slower aperture
(often f/2.8 vs f/1.8), and a narrow field yields fewer stars for registration.
It earns its place on the Moon and on small bright targets (Orion core, Andromeda).
The analytic transform seeding (FR-7.2 step 1) matters most here.

The ultrawide may be fixed-focus — mechanically incapable of focus drift — and its
wider field permits longer subs before trailing. It may simply be the best astro
camera on the device.

### FR-11.3 Camera recommendation

The session planner should recommend a camera for the chosen target and explain why.

---

## 12. Technical architecture

### FR-12.1 Language and libraries

| Concern | Choice | Rationale |
|---|---|---|
| App / UI / logic | Kotlin | — |
| Geometric ops (warp, transform) | **OpenCV Android SDK** (Apache 2.0) | Java/Kotlin API over prebuilt NEON-optimised native libs. Native speed without writing C++. |
| Star detection / matching / RANSAC | Kotlin | Runs once per ~15s on ~1MP. Performance irrelevant. |
| Accumulator | Kotlin, tiled | Memory-bandwidth-bound, not ALU-bound. NEON doesn't make an mmap faster. |
| RAW decoding | **Not needed** | Camera2 gives sensor data directly; `DngCreator` writes DNG. No LibRaw. |

**Deliberate non-choice:** no hand-written NDK/C++ in v1. Stacking runs *after* the
session; a 4-minute stack vs a 90-second stack is invisible on the drive home. Not
worth the complexity tax.

**Single sanctioned exception:** sigma-clipped combination has no OpenCV primitive
and is arithmetic-dense per pixel across N frames. If profiling shows it dominating,
*that one function* becomes a single `.cpp` file (~80 lines, clean JNI boundary,
`FloatArray` in / `FloatArray` out). **Build in Kotlin first, measure, port only if
the numbers say so.**

**Ruled out for v1:** GPU compute. RenderScript is deprecated, Vulkan compute is a
large lift, and it heats the phone — which is the enemy here.

### FR-12.2 Kotlin performance rules

- `FloatArray` / `ShortArray` only in hot paths. One `List<Float>` boxes 12 million objects.
- Allocate buffers once and reuse. GC pauses mid-stack are avoidable.
- Foreground service for both capture and stacking.
- `arm64-v8a` only — cuts the OpenCV payload to ~30 MB.

### FR-12.3 Third-party and licensing

| Component | Licence | Use |
|---|---|---|
| OpenCV | Apache 2.0 | Direct dependency |
| DeepSkyStacker | BSD 3-Clause | **Algorithm reference only.** Permissive, so lifting code with attribution is legal, but it's a Visual Studio + Qt 6.8 Windows desktop codebase with 2006-era MFC roots; the Linux port (oaDSS) is still explicitly an attempt. Reimplement, don't port. |
| Siril | GPL | **Do not vendor code.** Would force the whole app to GPL. Interoperate at the file-format level only. |
| astroalign | MIT | Useful reference for triangle-matching registration |

---

## 13. Milestones

**M1 — Instrumentation**
Capability probe, device profile export, manual RAW capture, DNG write, SAF folder.
*Deliverable: a dump of what each target phone can actually do.*

**M2 — Calibration library**
Noise characterisation, hot pixel map, flats, infinity focus sweep.
*Deliverable: reusable per-device calibration for your phone.*

**M3 — Capture engine**
Session planner, exposure/ISO engine, unattended sequence capture, thermal pacing,
foreground service, dark capture.
*Deliverable: press start, walk away, come back to a folder of good subs.*

**M4 — Registration + stacking**
Star detection, analytic seed, triangle matching, rigid transform, tiled sigma-clipped
stack. Linear master out.
*Deliverable: a stacked linear master, verifiable against DSS/Siril on the same subs.*

**M5 — Live feedback**
Live registration, quality gating, common-area indicator, preview stack, HFR readout.
*Deliverable: the beginner experience.*

**M5.5 — Session management**
Session list, deferred stacking, restack, storage management, multi-night combination.
*Deliverable: capture and stacking fully decoupled.*

**M6 — Auto-edit**
Gradient removal, autostretch, colour balance, before/after UI, gallery publish.
*Deliverable: a shareable image without touching a desktop.*

**M7 — Wide-field correctness + second camera**
De-project/re-project transform, per-camera calibration, camera recommendation.

---

## 14. Open questions

**Blocking:**

*(None. The former blocker — "which specific phone models?" — is resolved: the app
determines device capability at runtime (§3.1) and the user supplies calibration
(§4.0), so no model needs to be known at design time. Having one real device to
test on is still needed for M1, but it no longer constrains the design.)*

**Design:**

2. **Bias frames** — are they needed at all, given darks matched on exposure/ISO/
   temperature already contain the bias signal? Only required if dark scaling/
   optimisation is implemented. Decide before M2.
3. **Thermal pacing aggressiveness** — how much session time are we willing to
   spend cooling? Needs empirical data from M1/M3 on a real device.
4. **Live preview stack depth** — how far to go? A cheap running average is enough
   for framing confidence; anything more competes with capture for thermal budget.
5. **Light pollution input** — manual Bortle entry, or lookup from GPS against a
   light-pollution dataset? The exposure engine measures sky brightness directly
   anyway, so this may be redundant UI.
6. **Reference frame selection** — first frame, or best-quality frame? Best-quality
   requires either a second pass or accepting a mid-session switch.
7. **Framing assistance** — without plate solving, how does a beginner find a target?
   Compass + accelerometer + a small catalog could give a "point here" arrow.
   Cheap and high value; plate solving is expensive and probably post-v1.

**Deferred:**

8. **OIS dithering** (FR-6.5) — depends on whether OIS is controllable at all on the
   target devices. Investigate during M1, implement post-v1.
9. ~~Multi-night stacking~~ — **promoted into v1**, see §10.5.
10. **Star trail mode** — trivially adjacent (same capture, different combination:
    maximum instead of mean). Worth adding once M3 exists.

---

## 15. Success criteria for v1

1. A complete beginner can produce a recognisable, stacked, stretched Milky Way
   image with no configuration beyond framing and pressing start.
2. The on-device stacked linear master is measurably comparable (SNR, star FWHM)
   to the same subs stacked in DSS or Siril on a desktop.
3. An experienced user can inspect every automated decision, override any of them,
   and export a sequence that drops cleanly into their existing desktop workflow.
4. A 45-minute unattended session completes without thermal throttling, without
   being killed by the OS, and without running out of storage unannounced.
