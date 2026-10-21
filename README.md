# PDF Signature Inspector

Native Android prototype for inspecting embedded PDF digital signatures and managing exact signer-certificate trust decisions stored locally in the app.

## Features

- Open a PDF through Android's system document picker.
- Discover embedded PDF signature dictionaries.
- Attempt CMS cryptographic verification using Bouncy Castle.
- Display signer certificate subject, issuer and SHA-256 fingerprint.
- Require explicit confirmation that the full fingerprint was compared with an independent trusted source.
- Persist exact certificate DER and trust decision in app-private SharedPreferences.
- List and remove trusted certificate pins.
- Keep certificate trust separate from cryptographic signature validity.

## Build

Open this project in Android Studio or build the debug variant with Gradle 8.11.1+ and JDK 17. The GitHub Actions workflow builds an installable debug APK.

## Important limitations

This is a focused prototype, not an Acrobat-equivalent validation engine. It does not currently perform CRL/OCSP revocation checks, complete PKIX path validation, full PDF incremental-revision analysis, DocMDP/FieldMDP permission analysis, PAdES long-term validation, or trusted timestamp validation. The UI explicitly reports revocation as unknown and does not claim a full green-check validation result.

Trusting a certificate is app-specific and does not install it into Android's system store or trust its issuer. The app disables Android backup so trust decisions are not silently copied to another device.

The signer name embedded in a PDF is untrusted display data. Compare the full SHA-256 certificate fingerprint with an independent trusted source before trusting it. Never trust a certificate merely because it is embedded in the PDF.

## Libraries and licensing

- PDFBox-Android 2.0.27.0 (Apache License 2.0)
- Bouncy Castle 1.85 (MIT-style license)

Review upstream security advisories and dependency licenses before distributing a production build.
