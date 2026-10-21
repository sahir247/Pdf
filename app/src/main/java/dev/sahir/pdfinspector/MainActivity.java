package dev.sahir.pdfinspector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.Security;
import java.text.DateFormat;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int PICK_PDF = 1001;
    private static final int SAVE_PDF_COPY = 1002;
    private static final int PICK_TRUSTED_CA = 1003;
    private static final long MAX_PDF_BYTES = 50L * 1024L * 1024L;
    private byte[] pendingSaveBytes;
    private String pendingSaveName = "pdf-copy.pdf";

    private LinearLayout content;
    private LinearLayout actions;
    private TextView status;
    private Button saveOriginalButton;
    private TrustedCertificateStore trustStore;
    private byte[] currentPdf;
    private String currentPdfName = "No PDF selected";
    // Kept only in memory so trust changes can revalidate the currently opened encrypted PDF.
    // Never persisted or logged.
    private String currentPdfPassword = "";
    private final List<SignatureRecord> records = new ArrayList<>();

    private static final class SignatureRecord {
        PDSignature signature;
        boolean byteRangePlausible;
        boolean coversCurrentFile;
        long signedRevisionEnd;
        String parseError;
        PdfSignatureValidation.Report revisionReport;
        final List<SignerRecord> signers = new ArrayList<>();
    }

    private static final class SignerRecord {
        X509Certificate certificate;
        boolean cryptoValid;
        String fingerprint;
        CertificatePathValidator.Result pathResult;
        PdfSignatureValidation.Report revisionReport;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        PDFBoxResourceLoader.init(getApplicationContext());
        // Use the bundled provider rather than relying on the Android platform's older BC version.
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
        trustStore = new TrustedCertificateStore(this);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(28));
        content.setBackgroundColor(0xFFF7F8FA);
        scroll.addView(content);
        setContentView(scroll);

        TextView title = text("PDF Signature Inspector", 26, true, 0xFF17212B);
        content.addView(title);
        TextView subtitle = text("Inspect PDF signatures and manage certificates trusted only by this app.", 14, false, 0xFF526171);
        addSpace(8);
        content.addView(subtitle);

        Button pick = button("Choose a PDF");
        pick.setOnClickListener(v -> choosePdf());
        addSpace(18);
        content.addView(pick);


        status = text("No document selected. Choose a digitally signed PDF to inspect its signatures.", 14, false, 0xFF263746);
        status.setPadding(dp(14), dp(14), dp(14), dp(14));
        status.setBackground(roundBg(0xFFFFFFFF, 0xFFD9E0E7));
        addSpace(8);
        content.addView(status);

        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        content.addView(actions);

        saveOriginalButton = button("Save signed PDF (preserve signature)");
        saveOriginalButton.setEnabled(false);
        saveOriginalButton.setOnClickListener(v -> saveOriginalPdfCopy());
        addSpace(8);
        content.addView(saveOriginalButton);

        Button manage = button("Manage trusted certificates");
        manage.setOnClickListener(v -> showTrustedCertificates());
        addSpace(12);
        content.addView(manage);

        Button importCa = button("Import trusted CA certificate");
        importCa.setOnClickListener(v -> chooseTrustedCa());
        addSpace(8);
        content.addView(importCa);

        Button manageCa = button("Manage trusted CA anchors");
        manageCa.setOnClickListener(v -> showTrustedCaAnchors());
        addSpace(8);
        content.addView(manageCa);

        TextView caveat = text("This app performs layered PDF signature checks, but does not yet claim full validation. It checks CMS signature mathematics, certificate paths, ByteRange structure, parsing of the signed revision, and declared DocMDP/FieldMDP transform references. CRL/OCSP revocation, authenticated timestamp validation, complete incremental-revision comparison, DocMDP/FieldMDP enforcement, and comprehensive shadow-attack analysis remain incomplete; these gaps can affect trust decisions.", 12, false, 0xFF6A4B12);
        caveat.setPadding(dp(12), dp(12), dp(12), dp(12));
        caveat.setBackground(roundBg(0xFFFFF4D6, 0xFFFFE0A3));
        addSpace(16);
        content.addView(caveat);
    }

    private void choosePdf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        startActivityForResult(intent, PICK_PDF);
    }

    private void chooseTrustedCa() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_TRUSTED_CA);
    }

    private void showTrustedCaAnchors() {
        List<X509Certificate> certificates = trustStore.getTrustedCertificateAuthorities();
        if (certificates.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Trusted CA anchors")
                    .setMessage("No user-imported CA anchors. The bundled CCA India 2022 document-signing root is built into the app and cannot be removed here.")
                    .setPositiveButton("Close", null).show();
            return;
        }
        String[] labels = new String[certificates.size()];
        String[] fingerprints = new String[certificates.size()];
        for (int i = 0; i < certificates.size(); i++) {
            X509Certificate certificate = certificates.get(i);
            try {
                fingerprints[i] = TrustedCertificateStore.fingerprint(certificate);
            } catch (Exception e) {
                fingerprints[i] = "";
            }
            labels[i] = certificate.getSubjectX500Principal().getName()
                    + "\nSHA-256: " + fingerprints[i];
        }
        new AlertDialog.Builder(this).setTitle("App trusted CA anchors")
                .setItems(labels, (dialog, which) -> {
                    String fingerprint = fingerprints[which];
                    new AlertDialog.Builder(this)
                            .setTitle("Remove CA trust anchor?")
                            .setMessage("Removing this anchor can make signatures that depend on it untrusted in this app. This does not change Android or Adobe trust settings.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Remove", (d, w) -> {
                                trustStore.removeTrustedCertificateAuthority(fingerprint);
                                toast("CA trust anchor removed.");
                                if (currentPdf != null) {
                                    try { inspectPdf(); } catch (Exception e) { showStatus(safeMessage(e)); }
                                }
                            }).show();
                }).setNegativeButton("Close", null).show();
    }

    private void reviewTrustedCa(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Unable to open the selected certificate.");
            byte[] bytes = readBounded(in, 2L * 1024L * 1024L);
            java.security.cert.CertificateFactory factory =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(bytes));
            if (certificate.getBasicConstraints() < 0) {
                throw new SecurityException("This is not a CA certificate. Import a CA certificate, not a signer/leaf certificate.");
            }
            boolean[] usage = certificate.getKeyUsage();
            if (usage != null && (usage.length <= 5 || !usage[5])) {
                throw new SecurityException("The certificate's key usage does not permit certificate signing.");
            }
            certificate.checkValidity(new Date());
            String fingerprint = TrustedCertificateStore.fingerprint(certificate);
            String message = "Import this certificate as an app-specific trust anchor?\n\n"
                    + "Subject: " + certificate.getSubjectX500Principal().getName() + "\n\n"
                    + "Issuer: " + certificate.getIssuerX500Principal().getName() + "\n\n"
                    + "SHA-256: " + fingerprint + "\n\n"
                    + "Only import a CA certificate obtained from an authoritative source and independently verify its full fingerprint. "
                    + "Importing it changes trust decisions inside this app only; it does not modify Android or Adobe Acrobat's trust store.";
            new AlertDialog.Builder(this)
                    .setTitle("Review CA trust anchor")
                    .setMessage(message)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Import CA", (d, w) -> {
                        try {
                            trustStore.trustCertificateAuthority(certificate);
                            toast("CA trust anchor imported into this app.");
                            if (currentPdf != null) inspectPdf();
                        } catch (Exception e) {
                            toast("Could not import CA: " + safeMessage(e));
                        }
                    }).show();
        } catch (Exception e) {
            toast("Certificate rejected: " + safeMessage(e));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SAVE_PDF_COPY) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                pendingSaveBytes = null;
                return;
            }
            if (pendingSaveBytes == null) {
                toast("The PDF output is no longer available. Please repeat the operation.");
                return;
            }
            try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (out == null) throw new IllegalStateException("Unable to create the destination PDF.");
                out.write(pendingSaveBytes);
                out.flush();
                toast("PDF saved unchanged. Its existing embedded signature bytes were preserved.");
            } catch (Exception e) {
                toast("Could not save PDF: " + safeMessage(e));
            } finally {
                pendingSaveBytes = null;
            }
            return;
        }
        if (requestCode == PICK_TRUSTED_CA) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                reviewTrustedCa(data.getData());
            }
            return;
        }
        if (requestCode != PICK_PDF || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Unable to open the selected document.");
            currentPdf = readBounded(in, MAX_PDF_BYTES);
            currentPdfName = queryDisplayName(uri);
            currentPdfPassword = "";
            saveOriginalButton.setEnabled(true);
            inspectPdf();
        } catch (Exception e) {
            currentPdf = null;
            currentPdfPassword = "";
            saveOriginalButton.setEnabled(false);
            records.clear();
            actions.removeAllViews();
            showStatus("Could not inspect PDF\n" + safeMessage(e) +
                    "\n\nThe file may be malformed, too large, or unsupported.");
        }
    }

    private byte[] readBounded(InputStream in, long limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > limit) throw new IllegalArgumentException("Selected file exceeds the size limit.");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (Exception ignored) { }
        return "Selected PDF";
    }

    private void inspectPdf() throws Exception {
        records.clear();
        actions.removeAllViews();
        StringBuilder report = new StringBuilder();
        report.append(currentPdfName).append("\n\n");

        final PDDocument loadedDocument;
        try {
            loadedDocument = PDDocument.load(new ByteArrayInputStream(currentPdf), currentPdfPassword);
        } catch (InvalidPasswordException passwordError) {
            showPasswordDialog(currentPdfPassword.length() > 0);
            return;
        }
        try (PDDocument document = loadedDocument) {
            List<PDSignature> signatures = document.getSignatureDictionaries();
            if (signatures == null || signatures.isEmpty()) {
                showStatus(report.append("No embedded PDF digital signatures were found.\n")
                        .append("A visible image of a signature is not a cryptographic signature.").toString());
                return;
            }

            report.append("Found ").append(signatures.size()).append(" PDF signature dictionary/dictionaries.\n");
            int index = 0;
            for (PDSignature signature : signatures) {
                index++;
                SignatureRecord record = new SignatureRecord();
                record.signature = signature;
                int[] range = signature.getByteRange();
                record.byteRangePlausible = checkByteRange(range, currentPdf);
                if (record.byteRangePlausible) {
                    record.signedRevisionEnd = (long) range[2] + (long) range[3];
                    record.coversCurrentFile = record.signedRevisionEnd == currentPdf.length;
                }
                record.revisionReport = PdfSignatureValidation.inspect(currentPdf, signature, range);

                report.append("\n────────────────────────\n");
                report.append("PDF signature ").append(index).append("\n");
                report.append("PDF signer label (untrusted metadata): ")
                        .append(emptyFallback(signature.getName(), "Not provided")).append("\n");
                report.append("SubFilter: ").append(emptyFallback(signature.getSubFilter(), "Not provided")).append("\n");
                report.append("Signing time claimed by PDF (not independently authenticated): ")
                        .append(signature.getSignDate() == null ? "Not provided" : signature.getSignDate().getTime().toString())
                        .append("\n");
                report.append("ByteRange / excluded Contents syntax: ")
                        .append(record.byteRangePlausible ? "STRUCTURALLY PLAUSIBLE" : "INVALID / SUSPICIOUS")
                        .append("\n");
                report.append("Revision and permission checks:\n");
                if (record.revisionReport != null) {
                    report.append(record.revisionReport.toString());
                } else {
                    report.append("Revision validation unavailable.\n");
                }

                if (record.byteRangePlausible) {
                    if (record.coversCurrentFile) {
                        report.append("Revision coverage: ByteRange reaches current end-of-file. This does not detect hidden content ")
                                .append("already present in the signed revision; full PDF revision / DocMDP analysis is not implemented.\n");
                    } else {
                        report.append("Revision coverage: signature ends at byte ").append(record.signedRevisionEnd)
                                .append(" of ").append(currentPdf.length)
                                .append(" bytes. Later bytes exist; incremental changes and their permissions are NOT evaluated.\n");
                    }
                } else {
                    report.append("Revision coverage: UNKNOWN because ByteRange validation failed.\n");
                    report.append("CMS cryptographic check: NOT RUN for this signature because the ByteRange is unsafe.\n");
                    records.add(record);
                    continue;
                }

                try {
                    byte[] cmsBytes = signature.getContents(currentPdf);
                    byte[] signedContent = signature.getSignedContent(currentPdf);
                    // /Contents normally reserves more bytes than the CMS object uses. Parse through
                    // a stream so trailing reservation padding does not break CMS parsing.
                    CMSSignedData cms = new CMSSignedData(
                            new CMSProcessableByteArray(signedContent),
                            new ByteArrayInputStream(cmsBytes));
                    SignerInformationStore signerStore = cms.getSignerInfos();
                    Collection<SignerInformation> cmsSigners = signerStore.getSigners();
                    if (cmsSigners.isEmpty()) {
                        throw new SecurityException("CMS contains no signers.");
                    }

                    JcaX509CertificateConverter converter =
                            new JcaX509CertificateConverter().setProvider("BC");
                    List<X509Certificate> embeddedCertificates = new ArrayList<>();
                    Collection<X509CertificateHolder> allHolders = cms.getCertificates().getMatches(null);
                    for (X509CertificateHolder holder : allHolders) {
                        try {
                            embeddedCertificates.add(converter.getCertificate(holder));
                        } catch (Exception ignored) {
                            // An unusable embedded certificate must not crash inspection.
                        }
                    }

                    int signerIndex = 0;
                    for (SignerInformation cmsSigner : cmsSigners) {
                        signerIndex++;
                        SignerRecord signerRecord = new SignerRecord();
                        Collection<X509CertificateHolder> matchingHolders =
                                cms.getCertificates().getMatches(cmsSigner.getSID());

                        for (X509CertificateHolder holder : matchingHolders) {
                            X509Certificate candidate = converter.getCertificate(holder);
                            if (signerRecord.certificate == null) signerRecord.certificate = candidate;
                            boolean verified = cmsSigner.verify(
                                    new JcaSimpleSignerInfoVerifierBuilder().setProvider("BC").build(candidate));
                            if (verified) {
                                signerRecord.certificate = candidate;
                                signerRecord.cryptoValid = true;
                                break;
                            }
                        }

                        if (signerRecord.certificate != null) {
                            signerRecord.fingerprint = TrustedCertificateStore.fingerprint(signerRecord.certificate);
                            List<X509Certificate> appTrustAnchors = new ArrayList<>();
                            // Built-in roots are read-only and fingerprint-pinned. Fail closed if the
                            // packaged root cannot be loaded; user-imported anchors remain separate.
                            try {
                                appTrustAnchors.addAll(BundledTrustAnchors.load(this));
                            } catch (Exception bundledRootError) {
                                // No bundled trust is added if the asset fails integrity/validity checks.
                            }
                            appTrustAnchors.addAll(trustStore.getTrustedCertificateAuthorities());
                            signerRecord.pathResult = CertificatePathValidator.validate(
                                    signerRecord.certificate, embeddedCertificates, appTrustAnchors);
                        } else {
                            signerRecord.pathResult = new CertificatePathValidator.Result(
                                    CertificatePathValidator.Status.UNKNOWN,
                                    "The CMS did not include a certificate matching this signer.");
                        }
                        record.signers.add(signerRecord);

                        report.append("\n  CMS signer ").append(signerIndex).append("\n");
                        report.append("  Signer certificate: ").append(signerRecord.certificate == null
                                ? "Not found in CMS" : signerRecord.certificate.getSubjectX500Principal().getName()).append("\n");
                        report.append("  Certificate SHA-256: ").append(signerRecord.fingerprint == null
                                ? "Unavailable" : signerRecord.fingerprint).append("\n");
                        report.append("  CMS signature mathematics: ")
                                .append(signerRecord.cryptoValid ? "VERIFIED" : "NOT VERIFIED").append("\n");
                        report.append("  Exact certificate pin: ")
                                .append(signerRecord.certificate != null && trustStore.isTrusted(signerRecord.certificate)
                                        ? "TRUSTED LOCALLY IN THIS APP" : "NOT TRUSTED BY PIN").append("\n");
                        report.append("  Certificate trust path: ")
                                .append(signerRecord.pathResult.status.name()).append(" — ")
                                .append(signerRecord.pathResult.summary).append("\n");
                        report.append("  Certificate validity at current device time: ")
                                .append(certificateDateStatus(signerRecord.certificate)).append("\n");
                        report.append("  Revocation: UNKNOWN (CRL/OCSP not implemented)\n");
                        report.append("  Trusted timestamp / historical signing time: NOT VALIDATED\n");

                        if (signerRecord.certificate != null && signerRecord.cryptoValid) {
                            final SignatureRecord selectedSignature = record;
                            final SignerRecord selectedSigner = signerRecord;
                            boolean pinned = trustStore.isTrusted(signerRecord.certificate);
                            Button add = button(pinned
                                    ? "View / manage this trusted signer certificate"
                                    : "Review / add this exact signer certificate");
                            add.setContentDescription("Certificate actions for CMS signer " + signerIndex
                                    + " of PDF signature " + index);
                            add.setOnClickListener(v -> {
                                if (trustStore.isTrusted(selectedSigner.certificate)) {
                                    showCertificateDetails(selectedSignature, selectedSigner);
                                } else {
                                    showTrustConfirmation(selectedSignature, selectedSigner);
                                }
                            });
                            addSpaceIn(actions, 10);
                            actions.addView(add);
                        }
                    }

                    if (record.signers.isEmpty()) {
                        report.append("No CMS signer records were available.\n");
                    }
                } catch (Exception e) {
                    record.parseError = safeMessage(e);
                    report.append("CMS parsing / verification could not be completed: ")
                            .append(record.parseError).append("\n");
                }
                records.add(record);
            }

            // Fail closed: a partial validator must never advertise a fully validated signature.
            // Full green status stays disabled until revocation, authenticated timestamp, and
            // semantic revision / DocMDP / FieldMDP enforcement are implemented and tested.
            boolean greenTick = false;
            if (greenTick) {
                report.append("\n✓ SIGNATURE CHECKS PASSED — CMS signature mathematics verified; certificate chains to a trusted local root; ")
                        .append("certificate valid at the current device time; ByteRange covers the current file.\n");
                report.append("LIMITATION: revocation, trusted timestamp, hidden-content/shadow-attack and complete PDF revision / DocMDP / FieldMDP ")
                        .append("checks are not implemented. This green tick is limited to the checks listed above.\n");
                status.setTextColor(0xFF263746);
                status.setBackground(roundBg(0xFFFFFFFF, 0xFFD9E0E7));
            } else {
                report.append("\nOVERALL RESULT: REVIEW REQUIRED.\n");
                report.append("A green tick requires valid CMS signature mathematics and a certificate path to a trusted local root, ")
                        .append("current certificate validity, structurally plausible ByteRange and coverage of the current file for every signature/signer.\n");
                report.append("FULL VALIDATION NOT ESTABLISHED: CRL/OCSP revocation, authenticated timestamp and historical-time validation, ")
                        .append("complete incremental-revision change analysis, DocMDP/FieldMDP enforcement, and comprehensive shadow-attack checks ")
                        .append("are incomplete. A valid CMS signature or trusted certificate path alone is not proof that the complete PDF is safe.");
                status.setTextColor(0xFF263746);
                status.setBackground(roundBg(0xFFFFFFFF, 0xFFD9E0E7));
            }
            showStatus(report.toString());
        }
    }

    private boolean isEligibleForGreenTick() {
        if (currentPdf == null || records.isEmpty()) return false;
        for (SignatureRecord record : records) {
            if (!record.byteRangePlausible || !record.coversCurrentFile || record.parseError != null
                    || record.signers.isEmpty()) return false;
            for (SignerRecord signer : record.signers) {
                if (!signer.cryptoValid || signer.certificate == null || signer.fingerprint == null
                        || signer.pathResult == null
                        || (signer.pathResult.status != CertificatePathValidator.Status.TRUSTED_SYSTEM_PATH
                            && signer.pathResult.status != CertificatePathValidator.Status.TRUSTED_APP_PATH)) return false;
                try {
                    signer.certificate.checkValidity(new Date());
                } catch (Exception invalidDate) {
                    return false;
                }
            }
        }
        return true;
    }

    private void saveOriginalPdfCopy() {
        if (currentPdf == null) {
            toast("Choose a PDF first.");
            return;
        }
        if (records.isEmpty()) {
            toast("No embedded signature was detected. Choose the already-signed PDF you want to preserve.");
            return;
        }
        // Do not rewrite/resave with PDFBox: copy the original bytes so the existing
        // signature's ByteRange and CMS Contents remain byte-for-byte intact.
        pendingSaveBytes = currentPdf.clone();
        String baseName = currentPdfName == null ? "document.pdf" : currentPdfName.trim();
        if (baseName.isEmpty()) baseName = "document.pdf";
        if (!baseName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) baseName += ".pdf";
        pendingSaveName = baseName.substring(0, baseName.length() - 4) + "-signed.pdf";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, pendingSaveName);
        startActivityForResult(intent, SAVE_PDF_COPY);
    }

    private void showPasswordDialog(boolean previousAttemptFailed) {
        if (currentPdf == null) {
            showStatus("Choose the PDF again before entering its password.");
            return;
        }

        EditText passwordInput = new EditText(this);
        passwordInput.setSingleLine(true);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setHint("PDF document password");
        passwordInput.setText("");
        passwordInput.setSelection(0);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(4));
        panel.addView(text(previousAttemptFailed
                ? "That password was not accepted. Check it and try again."
                : "This PDF is password-protected. Enter its document-open password to inspect its signatures.",
                14, false, 0xFF263746));
        addDialogSpace(panel, 10);
        panel.addView(passwordInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(previousAttemptFailed ? "Incorrect PDF password" : "Password-protected PDF")
                .setView(panel)
                .setNegativeButton("Cancel", (d, which) -> {
                    currentPdfPassword = "";
                    showStatus(currentPdfName + "\\n\\nInspection cancelled. This PDF requires a password; no signature checks were performed.");
                })
                .setPositiveButton("Unlock and inspect", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String candidate = passwordInput.getText().toString();
                currentPdfPassword = candidate;
                dialog.dismiss();
                try {
                    inspectPdf();
                } catch (Exception e) {
                    if (isPasswordFailure(e)) {
                        currentPdfPassword = "";
                        showPasswordDialog(true);
                    } else {
                        showStatus("Could not inspect PDF\\n" + safeMessage(e)
                                + "\\n\\nThe password may be correct, but the file may be malformed, too large, or unsupported.");
                    }
                }
            });
        });
        dialog.show();
        passwordInput.requestFocus();
    }

    private boolean isPasswordFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InvalidPasswordException) return true;
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("cannot decrypt pdf")
                        || normalized.contains("password is incorrect")
                        || normalized.contains("incorrect password")
                        || normalized.contains("invalid password")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Validate the four ByteRange numbers and require the excluded byte span to be a PDF hex
     * string (normally the signature dictionary's /Contents value). This is a structural check,
     * not a complete PDF parser-based revision or shadow-attack analysis.
     */
    private boolean checkByteRange(int[] range, byte[] pdfBytes) {
        if (range == null || range.length != 4 || pdfBytes == null) return false;

        long start1 = range[0];
        long length1 = range[1];
        long start2 = range[2];
        long length2 = range[3];
        long fileLength = pdfBytes.length;

        if (start1 != 0 || length1 <= 0 || start2 <= length1 || length2 <= 0) return false;
        if (length1 > fileLength || start2 > fileLength || length2 > fileLength) return false;
        long end2 = start2 + length2;
        if (end2 < start2 || end2 > fileLength) return false;

        int gapStart = (int) length1;
        int gapEnd = (int) start2;
        if (gapEnd - gapStart < 2) return false;

        while (gapStart < gapEnd && isPdfWhitespace(pdfBytes[gapStart])) gapStart++;
        while (gapEnd > gapStart && isPdfWhitespace(pdfBytes[gapEnd - 1])) gapEnd--;
        return gapEnd - gapStart >= 2
                && pdfBytes[gapStart] == '<'
                && pdfBytes[gapEnd - 1] == '>';
    }

    private boolean isPdfWhitespace(byte value) {
        return value == 0 || value == 9 || value == 10 || value == 12 || value == 13 || value == 32;
    }

    private String certificateDateStatus(X509Certificate certificate) {
        if (certificate == null) return "UNKNOWN (certificate unavailable)";
        try {
            certificate.checkValidity(new Date());
            return "Valid at current device time";
        } catch (java.security.cert.CertificateExpiredException e) {
            return "EXPIRED now (historical validity not established)";
        } catch (java.security.cert.CertificateNotYetValidException e) {
            return "NOT YET VALID at current device time";
        }
    }

    private void showTrustConfirmation(SignatureRecord signatureRecord, SignerRecord signerRecord) {
        if (signerRecord.certificate == null || signerRecord.fingerprint == null) {
            toast("No signer certificate is available to trust.");
            return;
        }
        X509Certificate cert = signerRecord.certificate;
        String expectedFingerprint = signerRecord.fingerprint;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(4));

        panel.addView(text("Trust only this exact signer certificate for PDF signature validation inside this app. "
                + "This does not trust its issuer, another certificate from the same issuer, or other signers.", 14, false, 0xFF263746));
        addDialogSpace(panel, 12);
        panel.addView(text("SUBJECT", 11, true, 0xFF687785));
        panel.addView(text(cert.getSubjectX500Principal().getName(), 13, false, 0xFF17212B));
        addDialogSpace(panel, 8);
        panel.addView(text("ISSUER (NOT AUTOMATICALLY TRUSTED)", 11, true, 0xFF687785));
        panel.addView(text(cert.getIssuerX500Principal().getName(), 13, false, 0xFF17212B));
        addDialogSpace(panel, 8);
        panel.addView(text("SERIAL NUMBER (HEX)", 11, true, 0xFF687785));
        panel.addView(text(cert.getSerialNumber().toString(16).toUpperCase(java.util.Locale.ROOT), 13, false, 0xFF17212B));
        addDialogSpace(panel, 8);
        panel.addView(text("CERTIFICATE VALIDITY WINDOW", 11, true, 0xFF687785));
        panel.addView(text(DateFormat.getDateTimeInstance().format(cert.getNotBefore()) + " — "
                + DateFormat.getDateTimeInstance().format(cert.getNotAfter()), 13, false, 0xFF17212B));
        addDialogSpace(panel, 8);
        panel.addView(text("SHA-256 OF CERTIFICATE DER", 11, true, 0xFF687785));
        TextView fingerprint = text(expectedFingerprint, 12, true, 0xFF17212B);
        fingerprint.setTextIsSelectable(true);
        panel.addView(fingerprint);

        Button copy = button("Copy full fingerprint");
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Certificate SHA-256", expectedFingerprint));
            toast("Fingerprint copied");
        });
        addDialogSpace(panel, 6);
        panel.addView(copy);

        CheckBox verified = new CheckBox(this);
        verified.setText("I compared the entire SHA-256 fingerprint with an independent, trusted source.");
        verified.setTextColor(0xFF263746);
        verified.setTextSize(13);
        verified.setContentDescription("Confirm independent verification of the full certificate SHA-256 fingerprint");
        addDialogSpace(panel, 8);
        panel.addView(verified);

        TextView caveat = text("This pin is a local trust decision, not proof that the certificate is unexpired, unrevoked, or chained to a public CA. "
                + "Revocation and timestamp checks are not implemented. Document revision and certification-permission checks are incomplete. "
                + "Trusting this certificate will not make an invalid signature pass.", 12, false, 0xFF744B0B);
        caveat.setPadding(dp(10), dp(10), dp(10), dp(10));
        caveat.setBackground(roundBg(0xFFFFF4D6, 0xFFFFD88A));
        addDialogSpace(panel, 8);
        panel.addView(caveat);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add to Trusted Certificates?")
                .setView(scroller)
                .setNegativeButton("Cancel", (d, w) -> { })
                .setPositiveButton("Trust certificate", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setEnabled(false);
            verified.setOnCheckedChangeListener((buttonView, isChecked) -> positive.setEnabled(isChecked));
            positive.setOnClickListener(v -> {
                try {
                    String recalculated = TrustedCertificateStore.fingerprint(cert);
                    if (!recalculated.equals(expectedFingerprint)) {
                        toast("Certificate changed. Trust was not saved.");
                        dialog.dismiss();
                        return;
                    }
                    trustStore.trust(cert, verified.isChecked(), null);
                    dialog.dismiss();
                    toast("Exact certificate trusted in this app only.");
                    inspectPdf();
                } catch (Exception e) {
                    toast("Could not save trust: " + safeMessage(e));
                }
            });
        });
        dialog.show();
    }

    private void showCertificateDetails(SignatureRecord signatureRecord, SignerRecord signerRecord) {
        X509Certificate cert = signerRecord.certificate;
        TrustedCertificateStore.TrustMetadata metadata =
                trustStore.getTrustMetadata(signerRecord.fingerprint);
        String addedAt = metadata.trustedAtEpochMillis > 0
                ? DateFormat.getDateTimeInstance().format(new Date(metadata.trustedAtEpochMillis))
                : "Not recorded (legacy pin)";
        String message = "This exact certificate is trusted by you in this app.\n\n"
                + "SHA-256: " + signerRecord.fingerprint + "\n\n"
                + "Subject: " + cert.getSubjectX500Principal().getName() + "\n\n"
                + "Issuer: " + cert.getIssuerX500Principal().getName() + "\n\n"
                + "Serial (hex): " + cert.getSerialNumber().toString(16).toUpperCase(java.util.Locale.ROOT) + "\n\n"
                + "Certificate dates: " + DateFormat.getDateTimeInstance().format(cert.getNotBefore()) + " — "
                + DateFormat.getDateTimeInstance().format(cert.getNotAfter()) + "\n\n"
                + "Trust decision recorded: " + addedAt + "\n"
                + "Fingerprint confirmation recorded: " + (metadata.fingerprintVerified ? "Yes" : "No/unknown") + "\n"
                + "Policy version: " + metadata.policyVersion + "\n"
                + "Purpose: " + metadata.purpose + "\n\n"
                + "CMS signature mathematics: " + (signerRecord.cryptoValid ? "Verified" : "Not verified") + "\n"
                + "Android system PKIX path: " + signerRecord.pathResult.status.name() + "\n"
                + "Current certificate date status: " + certificateDateStatus(cert) + "\n"
                + "Revocation: unknown; not checked.\n"
                + "Timestamp: not validated.\n"
                + "PDF revision / DocMDP / FieldMDP analysis: incomplete.";
        new AlertDialog.Builder(this).setTitle("Trusted signer certificate")
                .setMessage(message)
                .setNegativeButton("Close", null)
                .setNeutralButton("Remove trust", (d, w) -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Remove trusted certificate?")
                            .setMessage("This removes only the exact local certificate pin. A later validation will be recomputed.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Remove", (dd, ww) -> {
                                trustStore.remove(signerRecord.fingerprint);
                                toast("Trust removed.");
                                try { inspectPdf(); } catch (Exception e) { showStatus(safeMessage(e)); }
                            }).show();
                }).show();
    }

    private void showTrustedCertificates() {
        List<String> fingerprints = trustStore.listFingerprints();
        if (fingerprints.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Trusted certificates")
                    .setMessage("No certificates have been explicitly trusted in this app.")
                    .setPositiveButton("OK", null).show();
            return;
        }

        String[] labels = new String[fingerprints.size()];
        for (int i = 0; i < fingerprints.size(); i++) {
            X509Certificate cert = trustStore.getCertificate(fingerprints.get(i));
            String subject = cert == null ? "Invalid stored certificate"
                    : cert.getSubjectX500Principal().getName();
            labels[i] = subject + "\n" + fingerprints.get(i);
        }

        new AlertDialog.Builder(this).setTitle("Trusted certificates · app only")
                .setItems(labels, (dialog, which) -> {
                    String fp = fingerprints.get(which);
                    X509Certificate cert = trustStore.getCertificate(fp);
                    if (cert == null) {
                        trustStore.remove(fp);
                        toast("Invalid trust record removed.");
                        return;
                    }

                    TrustedCertificateStore.TrustMetadata metadata = trustStore.getTrustMetadata(fp);
                    String addedAt = metadata.trustedAtEpochMillis > 0
                            ? DateFormat.getDateTimeInstance().format(new Date(metadata.trustedAtEpochMillis))
                            : "Not recorded (legacy pin)";
                    String message = "SHA-256: " + fp + "\n\n"
                            + "Subject: " + cert.getSubjectX500Principal().getName() + "\n\n"
                            + "Issuer: " + cert.getIssuerX500Principal().getName() + "\n\n"
                            + "Serial (hex): " + cert.getSerialNumber().toString(16).toUpperCase(java.util.Locale.ROOT) + "\n\n"
                            + "Certificate dates: " + DateFormat.getDateTimeInstance().format(cert.getNotBefore()) + " — "
                            + DateFormat.getDateTimeInstance().format(cert.getNotAfter()) + "\n\n"
                            + "Trust decision recorded: " + addedAt + "\n"
                            + "Independent fingerprint confirmation: " + (metadata.fingerprintVerified ? "Recorded" : "Unknown")
                            + "\nPolicy version: " + metadata.policyVersion
                            + "\nPurpose: " + metadata.purpose
                            + "\n\nRevocation status remains unknown; trust does not override certificate expiry or invalid signatures.";
                    new AlertDialog.Builder(this).setTitle("Trusted certificate")
                            .setMessage(message)
                            .setNegativeButton("Close", null)
                            .setPositiveButton("Remove trust", (d, w) -> {
                                trustStore.remove(fp);
                                toast("Trust removed.");
                                if (currentPdf != null) {
                                    try { inspectPdf(); } catch (Exception e) { showStatus(safeMessage(e)); }
                                }
                            }).show();
                }).setNegativeButton("Close", null).show();
    }

    private void showStatus(String message) {
        status.setText(message);
        status.setTextIsSelectable(true);
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        return b;
    }

    private void addSpace(int value) {
        View space = new View(this);
        content.addView(space, new LinearLayout.LayoutParams(1, dp(value)));
    }

    private void addSpaceIn(LinearLayout parent, int value) {
        View space = new View(this);
        parent.addView(space, new LinearLayout.LayoutParams(1, dp(value)));
    }

    private void addDialogSpace(LinearLayout parent, int value) {
        View space = new View(this);
        parent.addView(space, new LinearLayout.LayoutParams(1, dp(value)));
    }

    private android.graphics.drawable.GradientDrawable roundBg(int color, int stroke) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), stroke);
        return bg;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String emptyFallback(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (TextUtils.isEmpty(message)) return error == null ? "Unknown error" : error.getClass().getSimpleName();
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
