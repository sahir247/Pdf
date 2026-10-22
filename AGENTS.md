# PDF Inspector & Stamper Engineering Instructions (`AGENTS.md`)

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
  - Title font size ($S_{title}$) and baseline ($Y_{baseline}$) extracted from the appearance stream (`/n4`) or widget bounds.
  - Checkmark size dynamically scaled to typography ($2.90 \times S_{title}$) or widget bounds.
  - Checkmark position anchored to the typography metrics of the title string (e.g. `"Signature valid"`).

### Rule 2: Exact Glyph Overlap Rules (Visual Ground Truth)
When placing the green checkmark over `"Signature valid"` and the metadata lines:
1. **Right Arm Overlap**:
   - The right arm must cut through the letters `'v'`, `'a'`, and the left boundary of `'l'`.
   - The right top tip must terminate inside the letter `'a'` (between 30% and 50% along `'a'`).
   - **CRITICAL**: The checkmark must **NEVER cross or touch the letters `'i'` or `'d'`**. The word `"valid"` must end cleanly and legibly.
2. **Apex (Bottom Point) Placement**:
   - The bottom apex must hover cleanly above the bottom metadata line (`Location` / `"Dist"`).
   - The apex must rest on the line directly above it (e.g. `Reason: D-Sign`), leaving a clean gap of whitespace above the bottom line.
   - **CRITICAL**: The apex must **NEVER touch or stab into `"Dist"` or `"Location"`**.
3. **Left Wing Placement**:
   - The left wing must terminate over the second metadata line (`Name:` / signer identity).
   - **CRITICAL**: The top metadata line (`Digitally Signed.`) must remain **100% visible and untouched**.

### Rule 3: Multi-Layer Adobe Checkmark Structure (Never "Green Only")
A real Adobe Acrobat DC checkmark is NOT a single flat green path. It must always render as three distinct vector layers:
1. **Layer 1: Black 3D Drop Shadow**:
   - Deep black fill (`0 G ... h f`) extruded down and to the right by $(+1.5\text{ pt}, -2.5\text{ pt})$.
2. **Layer 2: Vibrant Adobe Green Fill**:
   - Official Adobe green (`0 0.7 0.23 rg ... h f` / `#00B33B`).
3. **Layer 3: Crisp Black Outer Outline Border**:
   - Black stroke (`0 G 1.0 w ... h S`) with clean miter joins.
   - Prevents the checkmark from appearing as a flat sticker ("green only") and ensures clear separation against background text.

### Rule 4: Zero OS Typography Dependency (Adobe CoolType Engine)
- Android system typefaces vary across OEMs (Samsung, Xiaomi, Pixel, OnePlus). Standard fallback to `sans-serif` or `Roboto` results in cramped, compact tracking.
- All typography must go through `AdobeCoolTypeEngine`:
  - Use bundled `assets/fonts/Helvetica-Bold.ttf` and `Helvetica-Regular.ttf`.
  - Use standard Adobe 1000-unit Linotype advance width tables for deterministic string measurements.
  - Enforce authentic CoolType fractional tracking (`+0.018 em` in UI, `0.15 Tc` in PDF streams).

---

## 3. Mandatory Verification Workflow

1. **Clean Code & Unit Tests**:
   - Run: `.\gradlew.bat testDebugUnitTest`
   - Run clean verification: `.\gradlew.bat clean testDebugUnitTest`
   - All tests must pass with zero failures.
2. **Pure Vision High-Resolution Inspection**:
   - Render the stamped PDF at high resolution ($\ge 300\text{ DPI}$).
   - Crop the signature block.
   - Compare directly against Adobe Acrobat DC reference screenshots (`income_adobe.png`, `user_aadhaar_ref.png`).
   - Create and inspect:
     - Side-by-side comparison card.
     - 50/50 differential overlay.
   - **Verify visually**:
     - Does the right arm cross `'a'` and stop before `'d'`?
     - Does the apex stay above `"Dist"`?
     - Is the top line `Digitally Signed.` untouched?
     - Does the checkmark have outline and shadow (not green only)?
3. **Pre-Commit Gate**:
   - `git diff` review to ensure no unrelated changes or document-specific hardcoding.
   - Clean working tree after commit and push.

---

## 4. Definition of Done

A task in this repository is complete ONLY when:
1. The mathematical geometry is dynamic and document-agnostic.
2. Pure vision inspection proves exact visual parity with Adobe Acrobat DC.
3. Both side-by-side and overlay comparison images are generated and verified.
4. Clean unit tests pass (`.\gradlew.bat clean testDebugUnitTest`).
5. Changes are committed and pushed to `main` with a clean working tree.
