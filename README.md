# PDF Signature Inspector

Native Android prototype for inspecting embedded PDF digital signatures and managing exact signer-certificate trust decisions locally. This repository is the foundation for the VeilFrame trusted-certificate workflow.

## Included in this build

- Open a PDF through Android's system document picker; no broad file-storage permission is required.
- Discover PDF signature dictionaries and evaluate CMS signer information with Bouncy Castle.
- Report each CMS signer independently, including signature mathematics, its SHA-256 certificate fingerprint, current certificate dates, and local pin status.
- Perform a structural `/ByteRange` check, including range bounds and the excluded `/Contents` hex-string shape.
- Distinguish a signature whose byte range reaches the current end-of-file from a signature that covers an earlier revision with later bytes appended.
- Attempt PKIX path construction from the signer certificate and embedded certificates to Android system trust anchors, the bundled CCA India 2022 document-signing root, and optional user-imported CA anchors, at the current device time.
- Add an exact certificate pin only after requiring explicit confirmation that the complete fingerprint was checked against an independent, trusted source.
- Store the certificate DER and separate audit metadata (confirmation flag, timestamp, purpose and policy version) in app-private preferences.
- List and remove local pins; removing a pin triggers reinspection of the currently open PDF.
- Keep local signer pins separate from the Android CA store and from PKIX path status. A pinned leaf does not become a trusted issuer or trust sibling certificates.
- Bundle the CCA India 2022 root for document-signing validation. The packaged PEM is fingerprint-pinned to SHA-256 `9A:3F:D3:17:67:98:E8:42:DD:CB:12:C2:62:F1:1C:FA:CC:A7:0A:8B:84:C6:EA:6F:DA:30:84:2A:95:A9:4C:D8`, checked for CA usage, self-signature, and current validity at runtime. The special-purpose CCA India 2022 SPL root is intentionally not trusted for document signatures.
- Disable Android automatic backup so local trust decisions are not silently copied to another device.

## Critical validation limitations

This is **not** an Acrobat-equivalent signature validation engine. The build does **not** perform CRL or OCSP revocation checking, trusted timestamp validation, historical certificate validation at an authenticated signing time, complete PDF incremental-revision comparison, shadow-attack analysis, or DocMDP/FieldMDP permitted-change analysis. Embedded revocation evidence and PAdES long-term validation (LTV) are also not implemented.

A structurally plausible byte range and mathematically valid CMS signature do not prove the current document is safe. A valid PKIX path does not prove that the certificate is not revoked. A local pin records a user's explicit trust decision for one exact DER certificate; it does not override invalid cryptography, expiry, revocation, or post-signing changes.

For those reasons, the UI reports evidence categories separately. A green tick is limited to the implemented checks: valid CMS signature mathematics, current certificate validity, full current-file ByteRange coverage, an exact locally trusted signer certificate, and a trusted PKIX path. It is not an Acrobat-equivalent or comprehensive validation result. When these checks do not all pass, the overall result remains **REVIEW REQUIRED**. PKIX uses current device time; revocation is deliberately disabled in path building and remains **UNKNOWN**. The bundled root does not by itself establish revocation status, a trusted signing time, or integrity of prior PDF revisions.

Older pins created by the previous version remain readable. Their original fingerprint-confirmation checkbox was required before saving, but that version did not record a trust timestamp, so the UI displays their trust date as not recorded.

## Build

Open this project in Android Studio or build the debug variant with Gradle 8.11.1+ and JDK 17. GitHub Actions builds an installable debug APK for commits to `main`.

## Test / release status

A successful APK build verifies compilation and packaging only. Before production use, exercise the app with real signed PDFs including malformed/overlapping byte ranges, padded `/Contents`, tampered signed bytes, incremental updates, multiple CMS signers, expired and revoked certificates, incomplete chains, valid timestamps, and DocMDP/FieldMDP restrictions. Confirm behavior against Acrobat/Reader or another independent validator. The current CI workflow does not itself prove those runtime scenarios.

## Libraries and licensing

- PDFBox-Android 2.0.27.0 (Apache License 2.0)
- Bouncy Castle 1.85 (MIT-style license)

Review upstream security advisories and dependency licenses before distributing a production build.
