# PDF Signature Inspector

Native Android prototype for inspecting embedded PDF digital signatures and managing exact signer-certificate trust decisions locally. This repository is the foundation for the VeilFrame trusted-certificate workflow.

## Included in this build

- Open a PDF through Android's system document picker; no broad file-storage permission is required.
- Save an existing signed PDF as a separate `-signed.pdf` copy without rewriting its bytes. This preserves the signature already embedded in the source PDF; it does not create a new signature.
- No PKCS#12 file or private key is requested when saving an already-signed PDF. Creating a *new* digital signature is a separate operation and requires access to an authorized private-key signing identity.
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

## Saving an already-signed PDF

Open the PDF that already contains the signature (for example, the digitally signed e-Aadhaar), then tap **Save signed PDF (preserve signature)** and choose a destination. The app copies the original bytes directly into a separate `-signed.pdf` file rather than opening and resaving the document through PDFBox, so it does not invalidate the existing signature by rewriting the signed content.

This action preserves the existing signature; it does not create a new one. No private key or PKCS#12 identity is required to save an already-signed document. Full signature validation remains incomplete as described below.

## Validation coverage and critical limitations

The app performs layered checks: CMS signer signature mathematics; certificate validity at current device time; PKIX path building to Android system anchors, the bundled CCA India 2022 root, or a user-imported CA; ByteRange bounds and excluded Contents delimiters; parsing the PDF prefix through the signed revision; and basic inspection of declared DocMDP/FieldMDP transform references.

**This is not yet full PDF signature validation or Acrobat-equivalent validation.** The app does not yet validate CRL/OCSP revocation, authenticate RFC 3161 timestamps and use them for historical certificate validation, retrieve and validate missing issuer certificates, validate PAdES DSS/VRI evidence, compare every incremental revision's object/field changes against DocMDP/FieldMDP permissions, or comprehensively detect shadow attacks and other semantic PDF ambiguities. The transform-reference inspection detects declarations and malformed parameters; it does not enforce the permitted-change rules against the full revision history.

To avoid overstating results, the app is now **fail-closed**: it does not display a fully passed green-check result while those required validation areas remain incomplete. A valid CMS signature or trusted certificate path alone does not prove that the entire PDF is safe or that the certificate was not revoked.

Older pins created by the previous version remain readable. Their original fingerprint-confirmation checkbox was required before saving, but that version did not record a trust timestamp, so the UI displays their trust date as not recorded.

## Build

Open this project in Android Studio or build the debug variant with Gradle 8.11.1+ and JDK 17. GitHub Actions builds an installable debug APK for commits to `main`.

## Test / release status

A successful APK build verifies compilation and packaging only. Before production use, exercise the app with real signed PDFs including malformed/overlapping byte ranges, padded `/Contents`, tampered signed bytes, incremental updates, multiple CMS signers, expired and revoked certificates, incomplete chains, valid timestamps, and DocMDP/FieldMDP restrictions. Confirm behavior against Acrobat/Reader or another independent validator. The current CI workflow does not itself prove those runtime scenarios.

## Libraries and licensing

- PDFBox-Android 2.0.27.0 (Apache License 2.0)
- Bouncy Castle 1.85 (MIT-style license)

Review upstream security advisories and dependency licenses before distributing a production build.
