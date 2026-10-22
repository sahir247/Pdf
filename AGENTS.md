# PDF Inspector & Stamper Engineering Instructions (`AGENTS.md`)

## 0. ABSOLUTE DIRECTIVE: ZERO FALSE PARITY & ZERO SYCOPHANCY (STRICT RULE)

> [!CAUTION]
> **CRITICAL INTEGRITY MANDATE — ZERO TOLERANCE FOR FALSEHOOD**
>
> 1. **Never Lie, Guess, or Bluff**: The agent must NEVER claim visual parity, completion, or correctness unless it has been empirically verified via high-resolution pixel measurements and 4x zoom visual crops.
> 2. **Never Say "Yes" to Placate**: When asked direct truthfulness questions (e.g. *"are you telling me the truth?"*, *"is this 100% identical to Adobe?"*), answering "yes" while any visual discrepancy, devouring of text, artificial stroke, or unmeasured parameter exists is a critical integrity violation. The agent MUST state the exact truth: what has been verified, what failed, and what remains unverified.
> 3. **Never Rationalize Defects**: If the user points out a defect (e.g., tick devouring a character, stamp compacted, stroke too bold), immediately acknowledge the exact defect without defensive spin, excuses, or sycophancy.
> 4. **Tests Do Not Equal Parity**: Passing Gradle tests only proves code syntax and execution. It proves ZERO about visual pixel parity with Adobe Acrobat DC.

---

## 1. Scope & Purpose

This repository (`dev.sahir.pdfinspector`) is the core engine for:
1. **Cryptographic Digital Signature Inspection**: Verifying PKCS#7 / CMS signatures, Indian CCA trust anchors (eSign, DSC, NIC, nCode), certificate revocation, and byte-range integrity.
2. **Universal Signature Stamping & Rectification**: Flattening and rectifying signature widgets into pristine PDF appearance streams with **bit-for-bit and visual parity with Adobe Acrobat DC**.
3. **Dynamic On-Screen UI Overlay**: Rendering pixel-perfect signature badges on Android canvas independent of device OEM or OS version.

---

## 2. Core Engineering Principles

### Rule 1: No Hardcoding by Document
- **STRICT PROHIBITION**: Never hardcode coordinates, bounding boxes, or offsets by document name, document type, or magic size thresholds (e.g. `if (isAadhaar)` or `if (parentWidth <= 65) { hardcode Aadhaar } else { hardcode Income }`).
- **Dynamic Derivation**: All geometry must be dynamically computed from the document's actual layout:
  - Title font size (S_title) and baseline (Y_baseline) extracted from the appearance stream (`/n4`) or widget bounds.
  - Checkmark size dynamically scaled to typography (2.90 * S_title) or widget bounds.
  - Checkmark position anchored to the typography metrics of the title string (e.g. `"Signature valid"`).

### Rule 2: Exact Glyph Overlap Rules (Visual Ground Truth)
When placing the green checkmark over `"Signature valid"` and the metadata lines:

1. **Right Arm / Right Wing Overlap**:
   - **Standard Banners (Income)**: The right arm cuts through `'v'` and terminates right at the left edge of `'a'` of `"valid"`.
     - **CRITICAL**: The checkmark must **NEVER touch or cover `'l'`, `'i'`, or `'d'`. The word `"valid"` must end cleanly and legibly.
   - **Compact Stamps (Aadhaar)**: The right wing terminates inside `'Signature'` (between `'u'` and `'r'`) **BEFORE the word `'valid'`**.
     - **CRITICAL**: The right wing must **NEVER cross into or touch `'valid'`**.

2. **Apex (Bottom Point) Placement**:
   - **Standard Banners (Income)**: The bottom apex must hover cleanly above the bottom metadata line (`Location: E-Dist 2.0`). It rests on Line 4 (`Reason: D-Sign`), leaving a clean gap of whitespace above the bottom line.
     - **CRITICAL**: It must **NEVER touch or stab into `"Dist"` or `"Location"`**.
   - **Compact Stamps (Aadhaar)**: The bottom apex rests delicately on the top curve of the `'2'` in `2026.10.21`.
     - **CRITICAL**: It must **NEVER devour the `'2'`**, **NEVER swallow the `'0'`**, and **NEVER plunge below the Date baseline**.

3. **Left Wing Placement**:
   - The left wing terminates over the second metadata line (`Name:` / signer identity).
   - **CRITICAL**: The top metadata line (`Digitally Signed.`) must remain **100% visible and untouched**.

### Rule 3: Multi-Layer Adobe Checkmark Structure (Never Heavy Outlines & Never "Green Only")
A real Adobe Acrobat DC checkmark is NOT a single flat green path, NOR is it enclosed in a thick cartoonish black border:
1. **Layer 1: Black 3D Drop Shadow**:
   - Deep black fill (`0 G ... h f`) extruded down and to the right by (+1.5 pt, -2.5 pt) or soft shadow.
2. **Layer 2: Vibrant Adobe Green Fill**:
   - Official Adobe green (`0 0.7 0.23 rg ... h f` / `#00B33B`).
3. **Layer 3: Subtle Outline / Stroke Weight**:
   - Must match Adobe DC stroke weight precisely (delicate <= 0.35 w or native DigSig stroke).
   - **STRICT PROHIBITION**: **NEVER add a heavy 1.0 w or 0.85 w black outline** enclosing the entire checkmark. A thick border makes the checkmark look like a cartoon sticker and devours adjacent text.

### Rule 4: Stamp Geometry & Typography Proportions (No Artificial Compacting)
- **STRICT PROHIBITION**: Never make a stamp more compact or squeezed than Adobe Acrobat DC.
- The aspect ratio, line spacing, and font tracking must match Adobe CoolType output.
- All typography must go through `AdobeCoolTypeEngine`:
  - Bundled authentic Helvetica fonts (`assets/fonts/Helvetica-Bold.ttf`, `Helvetica-Regular.ttf`).
  - Standard Adobe 1000-unit Linotype advance width tables.
  - Authentic CoolType fractional tracking (`+0.018 em` in UI, `0.15 Tc` in PDF streams).

### Rule 5: Strict Truthfulness & Empirical Verification Protocol
- **MANDATORY MEASURED METRICS**: Before declaring parity on any document, the agent MUST run high-resolution pixel measurement scripts and report a Verification Table containing:
  1. **Ground Truth Dimensions**: Checkmark width and height in Adobe DC at 300 DPI.
  2. **App Render Dimensions**: Checkmark width and height in Our App at 300 DPI.
  3. **Apex Coordinates & Margin**: Exact (X, Y) coordinates of the apex vs the baseline of the text below it.
  4. **Right Tip Coordinates & Overlap**: Exact (X, Y) coordinates of the right tip and the exact letter it terminates on.
  5. **Delta**: Absolute difference |delta X|, |delta Y| in pixels. Must be <= 2 px.
- **VISUAL INSPECTION MANDATE**:
  - Must generate and inspect 4x zoom crops of:
    1. Apex crop showing relationship to `'2'` or `"Dist"`.
    2. Right tip crop showing relationship to `'valid'` or `'Signature'`.
  - Must visually confirm:
    - Does apex devour any character? -> If YES: **FAIL**.
    - Does right arm overshoot? -> If YES: **FAIL**.
    - Is stroke width too heavy? -> If YES: **FAIL**.
    - Is stamp overly compact? -> If YES: **FAIL**.

---

## 3. Mandatory Verification Workflow

1. **Clean Code & Unit Tests**:
   - Run: `.\gradlew.bat testDebugUnitTest`
   - Run clean verification: `.\gradlew.bat clean testDebugUnitTest`
   - All tests must pass with zero failures.
2. **Pure Vision High-Resolution Inspection**:
   - Render the stamped PDF at high resolution (>= 300 DPI).
   - Crop the signature block and generate high-zoom crops (4x) of:
     - The right tip crossing the title.
     - The apex resting on the bottom line.
   - Measure pixel bounding boxes and deltas against Adobe DC reference images.
   - Programmatically verify that glyphs are not occluded or devoured.
3. **Pre-Commit Gate**:
   - `git diff` review to ensure no unrelated changes or document-specific hardcoding.
   - Commit with clear, descriptive commit message.
   - Clean working tree after commit and push.

---

## 4. Definition of Done

A task in this repository is complete ONLY when:
1. The mathematical geometry is dynamic and document-agnostic.
2. High-zoom pixel crops prove that glyph overlaps, apex placement, and stroke widths match Adobe DC ground truth.
3. Apex rests delicately above `'2'` without devouring it (in Aadhaar) and hovers cleanly above `"Dist"` (in Income).
4. Right wing terminates over `'Signature'` before `"valid"` (in Aadhaar) and terminates between `'v'` and `'a'` without touching `'l'` or `'d'` (in Income).
5. Stroke width is delicate (<= 0.35 w), not a heavy cartoon outline.
6. Stamp proportions and font tracking match Adobe DC without artificial compacting.
7. Clean unit tests pass (`.\gradlew.bat clean testDebugUnitTest`).
8. All claims are 100% truthful, backed by measured pixel evidence and high-zoom visual crops.
