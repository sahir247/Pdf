package dev.sahir.pdfinspector;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

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
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity {
    private static final int PICK_PDF = 1001;
    private static final int SAVE_PDF_COPY = 1002;
    private static final int PICK_TRUSTED_CA = 1003;
    private static final long MAX_PDF_BYTES = 50L * 1024L * 1024L;

    private byte[] pendingSaveBytes;
    private String pendingSaveName = "pdf-copy.pdf";

    private LinearLayout contentContainer;
    private TrustedCertificateStore trustStore;
    private byte[] currentPdf;
    private String currentPdfName = "";
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
        trustStore = new TrustedCertificateStore(this);

        buildBaseLayout();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();
            if (uri != null) {
                loadPdfFromUri(uri);
            }
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                loadPdfFromUri(uri);
            }
        }
    }

    private void buildBaseLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF4F6F9);

        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(dp(16), dp(20), dp(16), dp(32));
        scroll.addView(contentContainer);

        setContentView(scroll);
        renderDocumentView();
    }

    private void renderDocumentView() {
        contentContainer.removeAllViews();

        // Header Card
        LinearLayout headerCard = createCard();
        headerCard.setBackground(roundBg(0xFF0D47A1, 0, dp(14)));
        headerCard.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView appTitle = text("PDF Validate", 24, true, 0xFFFFFFFF);
        headerCard.addView(appTitle);

        TextView tagLine = text("Native Android • Cryptographic Signature Verification & Rectification", 13, false, 0xFFB0BEC5);
        addSpaceIn(headerCard, 4);
        headerCard.addView(tagLine);

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(headerCard);

        // Document Selection / Actions
        LinearLayout pickerCard = createCard();
        pickerCard.setPadding(dp(16), dp(16), dp(16), dp(16));

        Button pickBtn = styledButton("📂 Choose PDF Document", 0xFF1976D2, 0xFFFFFFFF);
        pickBtn.setOnClickListener(v -> choosePdf());
        pickerCard.addView(pickBtn);

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(pickerCard);

        if (currentPdf == null) {
            // Empty State Card
            LinearLayout emptyCard = createCard();
            emptyCard.setGravity(Gravity.CENTER_HORIZONTAL);
            emptyCard.setPadding(dp(24), dp(32), dp(24), dp(32));

            TextView emptyIcon = text("📄", 42, false, 0xFF90A4AE);
            emptyIcon.setGravity(Gravity.CENTER);
            emptyCard.addView(emptyIcon);
            addSpaceIn(emptyCard, 8);

            TextView emptyTitle = text("No Document Opened", 18, true, 0xFF263238);
            emptyTitle.setGravity(Gravity.CENTER);
            emptyCard.addView(emptyTitle);
            addSpaceIn(emptyCard, 6);

            TextView emptyDesc = text("Select a digitally signed PDF or open/share one directly from WhatsApp, Gmail, or your file manager to verify signatures.", 14, false, 0xFF607D8B);
            emptyDesc.setGravity(Gravity.CENTER);
            emptyCard.addView(emptyDesc);

            addSpaceIn(contentContainer, 12);
            contentContainer.addView(emptyCard);
        } else {
            // Document Status Banner Card
            renderStatusBanner();

            // Quick Actions Card
            if (!records.isEmpty()) {
                renderQuickActions();
            }

            // Signatures Breakdown
            renderSignaturesBreakdown();
        }

        // Trust Store & Settings Section
        renderTrustSettingsCard();
    }

    private void renderStatusBanner() {
        LinearLayout statusCard = createCard();

        int overallStatusColor;
        int statusBgColor;
        String statusTitle;
        String statusSubtitle;

        if (records.isEmpty()) {
            overallStatusColor = 0xFF546E7A;
            statusBgColor = 0xFFECEFF1;
            statusTitle = "ℹ️ NO DIGITAL SIGNATURE DETECTED";
            statusSubtitle = "This document does not contain embedded cryptographic signature dictionaries. Visual stamps are not digital signatures.";
        } else {
            boolean allCryptoValid = true;
            boolean allTrusted = true;
            boolean anyTampered = false;

            for (SignatureRecord rec : records) {
                if (!rec.byteRangePlausible || rec.parseError != null) {
                    anyTampered = true;
                    break;
                }
                for (SignerRecord signer : rec.signers) {
                    if (!signer.cryptoValid) {
                        allCryptoValid = false;
                        anyTampered = true;
                    }
                    if (signer.pathResult == null ||
                            (signer.pathResult.status != CertificatePathValidator.Status.TRUSTED_SYSTEM_PATH
                                    && signer.pathResult.status != CertificatePathValidator.Status.TRUSTED_APP_PATH)) {
                        allTrusted = false;
                    }
                }
            }

            if (anyTampered || !allCryptoValid) {
                overallStatusColor = 0xFFC62828;
                statusBgColor = 0xFFFFEBEE;
                statusTitle = "❌ SIGNATURE TAMPERED / INVALID";
                statusSubtitle = "One or more signatures failed cryptographic mathematical verification or contains malformed byte ranges.";
            } else if (allTrusted) {
                overallStatusColor = 0xFF2E7D32;
                statusBgColor = 0xFFE8F5E9;
                statusTitle = "✓ SIGNATURE VERIFIED (TRUSTED)";
                statusSubtitle = "CMS signature mathematics verified and chains to a trusted root authority (CCA India 2022 / System CA).";
            } else {
                overallStatusColor = 0xFFE65100;
                statusBgColor = 0xFFFFF3E0;
                statusTitle = "⚠️ SIGNATURE VALID (UNTRUSTED ROOT)";
                statusSubtitle = "CMS signature mathematics is valid, but the certificate does not chain to a pre-trusted root. Review or trust the signer below.";
            }
        }

        statusCard.setBackground(roundBg(statusBgColor, overallStatusColor, dp(12)));
        statusCard.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView titleView = text(statusTitle, 16, true, overallStatusColor);
        statusCard.addView(titleView);
        addSpaceIn(statusCard, 6);

        TextView subtitleView = text(statusSubtitle, 13, false, 0xFF37474F);
        statusCard.addView(subtitleView);
        addSpaceIn(statusCard, 12);

        // Document Details
        View divider = new View(this);
        divider.setBackgroundColor(0x33000000);
        statusCard.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        addSpaceIn(statusCard, 10);

        TextView docName = text("📄 File: " + currentPdfName, 13, true, 0xFF263238);
        statusCard.addView(docName);
        addSpaceIn(statusCard, 4);

        TextView docMeta = text("Size: " + formatFileSize(currentPdf.length) + "  •  Signatures: " + records.size(), 12, false, 0xFF607D8B);
        statusCard.addView(docMeta);

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(statusCard);
    }

    private void renderQuickActions() {
        LinearLayout actionsCard = createCard();
        actionsCard.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView secTitle = text("SIGNATURE ACTIONS", 12, true, 0xFF78909C);
        actionsCard.addView(secTitle);
        addSpaceIn(actionsCard, 12);

        // Action 1: Save Original Signed PDF (Primary Integrity Choice)
        Button saveOrigBtn = styledButton("💾 Save Original Signed PDF (Preserve Integrity)", 0xFF2E7D32, 0xFFFFFFFF);
        saveOrigBtn.setOnClickListener(v -> saveOriginalPdfCopy());
        actionsCard.addView(saveOrigBtn);

        TextView saveOrigDesc = text("Preserves 100% byte-for-byte original signed document, intact digital signatures, and legal non-repudiation.", 12, false, 0xFF546E7A);
        saveOrigDesc.setPadding(dp(4), dp(4), dp(4), 0);
        actionsCard.addView(saveOrigDesc);
        addSpaceIn(actionsCard, 14);

        // Action 2: Fix Visual Appearance (Rendered Copy for Paper Printing)
        Button fixBtn = styledButton("✓ Fix Visual Signature (Print Replica)", 0xFF1565C0, 0xFFFFFFFF);
        fixBtn.setOnClickListener(v -> fixSignatureAppearance());
        actionsCard.addView(fixBtn);

        TextView fixDesc = text("Gated by cryptographic validation. Stamps Adobe green checkmark for paper printing and basic viewers. (Alters bytes).", 12, false, 0xFF546E7A);
        fixDesc.setPadding(dp(4), dp(4), dp(4), 0);
        actionsCard.addView(fixDesc);
        addSpaceIn(actionsCard, 14);

        // Action 3: Export Flattened Printable PDF
        Button flattenBtn = styledButton("🖨️ Export Flattened PDF (Legacy Printers)", 0xFF0277BD, 0xFFFFFFFF);
        flattenBtn.setOnClickListener(v -> exportFlattenedPrintablePdf());
        actionsCard.addView(flattenBtn);

        TextView flattenDesc = text("Gated by cryptographic validation. Permanently burns checkmark and strips AcroForm for physical printers.", 12, false, 0xFF546E7A);
        flattenDesc.setPadding(dp(4), dp(4), dp(4), 0);
        actionsCard.addView(flattenDesc);
        addSpaceIn(actionsCard, 14);

        // Action 4: Share Document
        Button shareBtn = styledButton("📤 Share Document", 0xFF37474F, 0xFFFFFFFF);
        shareBtn.setOnClickListener(v -> sharePdfBytes(currentPdf, currentPdfName));
        actionsCard.addView(shareBtn);

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(actionsCard);
    }

    private void renderSignaturesBreakdown() {
        if (records.isEmpty()) return;

        LinearLayout breakdownCard = createCard();
        breakdownCard.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView secTitle = text("SIGNATURE DETAILS (" + records.size() + ")", 12, true, 0xFF78909C);
        breakdownCard.addView(secTitle);
        addSpaceIn(breakdownCard, 12);

        int sigIndex = 0;
        for (SignatureRecord record : records) {
            sigIndex++;
            LinearLayout sigItem = new LinearLayout(this);
            sigItem.setOrientation(LinearLayout.VERTICAL);
            sigItem.setBackground(roundBg(0xFFF9FAFB, 0xFFE0E0E0, dp(8)));
            sigItem.setPadding(dp(14), dp(14), dp(14), dp(14));

            TextView sigHeader = text("Signature #" + sigIndex + ": " + emptyFallback(record.signature.getName(), "Digital Signature"), 14, true, 0xFF1565C0);
            sigItem.addView(sigHeader);
            addSpaceIn(sigItem, 6);

            String subFilter = emptyFallback(record.signature.getSubFilter(), "adbe.pkcs7.detached");
            TextView subFilterView = text("Format / SubFilter: " + subFilter, 12, false, 0xFF455A64);
            sigItem.addView(subFilterView);

            Date signDate = record.signature.getSignDate() != null ? record.signature.getSignDate().getTime() : null;
            String dateStr = signDate != null ? DateFormat.getDateTimeInstance().format(signDate) : "Not declared";
            TextView dateView = text("Claimed Signing Date: " + dateStr, 12, false, 0xFF455A64);
            sigItem.addView(dateView);

            String byteRangeStatus = record.byteRangePlausible ? "✓ Structurally Plausible" : "❌ Invalid / Suspicious";
            int byteRangeColor = record.byteRangePlausible ? 0xFF2E7D32 : 0xFFC62828;
            TextView byteRangeView = text("ByteRange Integrity: " + byteRangeStatus, 12, true, byteRangeColor);
            sigItem.addView(byteRangeView);

            if (record.coversCurrentFile) {
                TextView covView = text("Revision Coverage: Covers full document (to EOF)", 12, false, 0xFF2E7D32);
                sigItem.addView(covView);
            } else if (record.byteRangePlausible) {
                TextView covView = text("Revision Coverage: Signature covers up to byte " + record.signedRevisionEnd + " of " + currentPdf.length, 12, false, 0xFFE65100);
                sigItem.addView(covView);
            }

            // Signers
            int signerIndex = 0;
            for (SignerRecord signer : record.signers) {
                signerIndex++;
                addSpaceIn(sigItem, 10);
                View div = new View(this);
                div.setBackgroundColor(0xFFE0E0E0);
                sigItem.addView(div, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
                addSpaceIn(sigItem, 10);

                TextView signerTitle = text("Signer Certificate #" + signerIndex, 13, true, 0xFF263238);
                sigItem.addView(signerTitle);
                addSpaceIn(sigItem, 4);

                String subject = signer.certificate != null ? signer.certificate.getSubjectX500Principal().getName() : "Unknown Subject";
                TextView subjectView = text("Subject: " + subject, 12, false, 0xFF37474F);
                sigItem.addView(subjectView);

                String issuer = signer.certificate != null ? signer.certificate.getIssuerX500Principal().getName() : "Unknown Issuer";
                TextView issuerView = text("Issuer: " + issuer, 12, false, 0xFF546E7A);
                sigItem.addView(issuerView);

                int mathColor = signer.cryptoValid ? 0xFF2E7D32 : 0xFFC62828;
                String mathStatus = signer.cryptoValid ? "✓ Cryptographic Mathematics: VERIFIED" : "❌ Cryptographic Mathematics: INVALID";
                TextView mathView = text(mathStatus, 12, true, mathColor);
                sigItem.addView(mathView);

                String trustText = "Trust Path: " + (signer.pathResult != null ? signer.pathResult.status.name() : "UNKNOWN");
                int trustColor = (signer.pathResult != null && (signer.pathResult.status == CertificatePathValidator.Status.TRUSTED_SYSTEM_PATH || signer.pathResult.status == CertificatePathValidator.Status.TRUSTED_APP_PATH))
                        ? 0xFF2E7D32 : 0xFFE65100;
                TextView trustView = text(trustText, 12, true, trustColor);
                sigItem.addView(trustView);

                if (signer.fingerprint != null) {
                    TextView fpView = text("SHA-256: " + signer.fingerprint, 11, false, 0xFF78909C);
                    fpView.setPadding(0, dp(2), 0, dp(4));
                    fpView.setOnClickListener(v -> copyToClipboard("Certificate Fingerprint", signer.fingerprint));
                    sigItem.addView(fpView);
                }

                if (signer.certificate != null && signer.cryptoValid) {
                    final SignatureRecord curSig = record;
                    final SignerRecord curSigner = signer;
                    boolean isPinned = trustStore.isTrusted(signer.certificate);
                    Button trustBtn = styledButton(isPinned ? "✓ Manage Trusted Certificate" : "+ Trust This Signer Certificate",
                            isPinned ? 0xFF37474F : 0xFF1976D2, 0xFFFFFFFF);
                    trustBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    trustBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
                    trustBtn.setOnClickListener(v -> {
                        if (trustStore.isTrusted(curSigner.certificate)) {
                            showCertificateDetails(curSig, curSigner);
                        } else {
                            showTrustConfirmation(curSig, curSigner);
                        }
                    });
                    addSpaceIn(sigItem, 6);
                    sigItem.addView(trustBtn);
                }
            }

            breakdownCard.addView(sigItem);
            addSpaceIn(breakdownCard, 10);
        }

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(breakdownCard);
    }

    private void renderTrustSettingsCard() {
        LinearLayout trustCard = createCard();
        trustCard.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView secTitle = text("CERTIFICATE & TRUST MANAGEMENT", 12, true, 0xFF78909C);
        trustCard.addView(secTitle);
        addSpaceIn(trustCard, 10);

        Button manageTrustedBtn = styledButton("Manage Explicitly Trusted Signers", 0xFF455A64, 0xFFFFFFFF);
        manageTrustedBtn.setOnClickListener(v -> showTrustedCertificates());
        trustCard.addView(manageTrustedBtn);
        addSpaceIn(trustCard, 8);

        Button importCaBtn = styledButton("Import CA Trust Anchor (.cer / .crt)", 0xFF546E7A, 0xFFFFFFFF);
        importCaBtn.setOnClickListener(v -> chooseTrustedCa());
        trustCard.addView(importCaBtn);
        addSpaceIn(trustCard, 8);

        Button manageCaBtn = styledButton("Manage CA Trust Anchors", 0xFF607D8B, 0xFFFFFFFF);
        manageCaBtn.setOnClickListener(v -> showTrustedCaAnchors());
        trustCard.addView(manageCaBtn);
        addSpaceIn(trustCard, 12);

        TextView note = text("Security Note: The bundled CCA India 2022 root certificate is built directly into this app for Indian digital signatures. Imported CA anchors only modify trust within this app.", 11, false, 0xFF78909C);
        trustCard.addView(note);

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(trustCard);
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

    private InputStream openUriInputStream(Uri uri) throws Exception {
        if (uri == null) throw new IllegalArgumentException("URI is null");
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return new java.io.FileInputStream(new File(uri.getPath()));
        }
        return getContentResolver().openInputStream(uri);
    }

    private void loadPdfFromUri(Uri uri) {
        try (InputStream in = openUriInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Unable to open selected document.");
            currentPdf = readBounded(in, MAX_PDF_BYTES);
            currentPdfName = queryDisplayName(uri);
            currentPdfPassword = "";
            inspectPdf();
        } catch (Exception e) {
            currentPdf = null;
            currentPdfPassword = "";
            records.clear();
            renderDocumentView();
            showStatusDialog("Could Not Open PDF", "Failed to read or parse PDF: " + safeMessage(e));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
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
            try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (out == null) throw new IllegalStateException("Unable to create destination PDF.");
                out.write(pendingSaveBytes);
                out.flush();
                toast("PDF saved successfully.");
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

        if (requestCode == PICK_PDF && resultCode == RESULT_OK && data != null && data.getData() != null) {
            loadPdfFromUri(data.getData());
        }
    }

    private void inspectPdf() {
        records.clear();
        final PDDocument loadedDocument;
        try {
            loadedDocument = (currentPdfPassword != null && !currentPdfPassword.isEmpty())
                    ? PDDocument.load(new ByteArrayInputStream(currentPdf), currentPdfPassword)
                    : PDDocument.load(new ByteArrayInputStream(currentPdf));
        } catch (InvalidPasswordException passwordError) {
            showPasswordDialog(currentPdfPassword != null && !currentPdfPassword.isEmpty());
            return;
        } catch (Exception e) {
            showStatusDialog("Inspection Error", "Failed to inspect PDF: " + safeMessage(e));
            renderDocumentView();
            return;
        }

        try (PDDocument document = loadedDocument) {
            List<PDSignature> signatures = document.getSignatureDictionaries();
            if (signatures != null) {
                for (PDSignature signature : signatures) {
                    SignatureRecord record = new SignatureRecord();
                    record.signature = signature;
                    int[] range = signature.getByteRange();
                    record.byteRangePlausible = checkByteRange(range, currentPdf);
                    if (record.byteRangePlausible) {
                        record.signedRevisionEnd = (long) range[2] + (long) range[3];
                        record.coversCurrentFile = record.signedRevisionEnd == currentPdf.length;
                    }
                    record.revisionReport = PdfSignatureValidation.inspect(currentPdf, signature, range);

                    if (!record.byteRangePlausible) {
                        records.add(record);
                        continue;
                    }

                    try {
                        byte[] cmsBytes = signature.getContents(currentPdf);
                        byte[] signedContent = signature.getSignedContent(currentPdf);
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
                            } catch (Exception ignored) { }
                        }

                        for (SignerInformation cmsSigner : cmsSigners) {
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
                                try {
                                    appTrustAnchors.addAll(BundledTrustAnchors.loadTrustAnchors(this));
                                } catch (Exception ignored) { }
                                appTrustAnchors.addAll(trustStore.getTrustedCertificateAuthorities());

                                List<X509Certificate> intermediates = BundledTrustAnchors.loadBundledIntermediates(this);
                                signerRecord.pathResult = CertificatePathValidator.validate(
                                        signerRecord.certificate, embeddedCertificates, appTrustAnchors, intermediates);
                            } else {
                                signerRecord.pathResult = new CertificatePathValidator.Result(
                                        CertificatePathValidator.Status.UNKNOWN,
                                        "The CMS did not include a certificate matching this signer.");
                            }
                            record.signers.add(signerRecord);
                        }
                    } catch (Exception e) {
                        record.parseError = safeMessage(e);
                    }
                    records.add(record);
                }
            }
        } catch (Exception e) {
            showStatusDialog("Inspection Error", safeMessage(e));
        }

        renderDocumentView();
    }

    /**
     * Strictly verifies that all digital signatures in the document are mathematically
     * valid, have intact ByteRanges without tampering, and chain to a trusted CA.
     * Prevents generating visual 'verified' stamps on invalid or untrusted signatures.
     */
    private boolean isDocumentCryptographicallyValid() {
        if (records.isEmpty()) return false;
        for (SignatureRecord rec : records) {
            if (!rec.byteRangePlausible || rec.parseError != null) return false;
            if (rec.signers.isEmpty()) return false;
            for (SignerRecord signer : rec.signers) {
                if (!signer.cryptoValid) return false;
                boolean trusted = signer.pathResult != null &&
                        (signer.pathResult.status == CertificatePathValidator.Status.TRUSTED_SYSTEM_PATH
                                || signer.pathResult.status == CertificatePathValidator.Status.TRUSTED_APP_PATH
                                || (signer.certificate != null && trustStore.isTrusted(signer.certificate)));
                if (!trusted) return false;
            }
        }
        return true;
    }

    private void fixSignatureAppearance() {
        if (currentPdf == null) {
            toast("Please select a PDF first.");
            return;
        }
        if (records.isEmpty()) {
            toast("No digital signatures found in this document to fix.");
            return;
        }
        if (!isDocumentCryptographicallyValid()) {
            showStatusDialog("Visual Rectification Blocked",
                    "Cryptographic validation failed or certificate is untrusted.\n\n" +
                    "A visual 'Signature valid' green checkmark can ONLY be generated for documents whose digital signatures have been mathematically verified against trusted certificate authorities.\n\n" +
                    "Please ensure the certificate chains to a trusted root authority (e.g. CCA India / nCode / System CA) or explicitly trust the signer before rectifying.");
            return;
        }
        try {
            PdfStamperEngine.StampResult result = PdfStamperEngine.processPdf(currentPdf, currentPdfPassword);
            byte[] fixedPdf = result.rectifiedPdf;
            String baseName = getBaseName();
            String outputName = baseName + "-visual-print-copy.pdf";
            showExportOptionsDialog("Rendered Visual Print Copy Ready",
                    "The signature appearance has been updated with the Adobe-standard green checkmark and 'Signature valid' status for paper printing and basic viewers.\n\n" +
                    "⚠️ INTEGRITY NOTICE: Modifying visual appearance streams alters PDF bytes and invalidates digital signature cryptographic hashes. This file is intended as a visual print replica. To maintain digital legal non-repudiation, retain your Original Signed PDF.",
                    fixedPdf, outputName);
        } catch (Exception e) {
            toast("Failed to fix signature appearance: " + safeMessage(e));
        }
    }

    private void exportFlattenedPrintablePdf() {
        if (currentPdf == null) {
            toast("Please select a PDF first.");
            return;
        }
        if (records.isEmpty()) {
            toast("No digital signatures found in this document to export.");
            return;
        }
        if (!isDocumentCryptographicallyValid()) {
            showStatusDialog("Flattened Export Blocked",
                    "Cryptographic validation failed or certificate is untrusted.\n\n" +
                    "A flattened printable PDF can only be generated for documents whose digital signatures have been mathematically verified.\n\n" +
                    "Please ensure the certificate chains to a trusted root authority or trust the signer first.");
            return;
        }
        try {
            PdfStamperEngine.StampResult result = PdfStamperEngine.processPdf(currentPdf, currentPdfPassword);
            byte[] flattenedPdf = result.printablePdf;
            String baseName = getBaseName();
            String outputName = baseName + "-flattened-print.pdf";
            showExportOptionsDialog("Flattened Printable PDF Ready",
                    "The green checkmark is now permanently burned onto the page content, and AcroForm annotations were stripped so all printers and mobile viewers display it correctly.\n\n" +
                    "⚠️ INTEGRITY NOTICE: Destructive export for printing only. Signature widgets and AcroForm dictionaries have been removed.",
                    flattenedPdf, outputName);
        } catch (Exception e) {
            toast("Failed to export printable PDF: " + safeMessage(e));
        }
    }

    private void showExportOptionsDialog(String title, String message, byte[] pdfBytes, String defaultFileName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(message + "\n\nOutput: " + defaultFileName + " (" + formatFileSize(pdfBytes.length) + ")");
        builder.setPositiveButton("Save to Downloads", (dialog, which) -> {
            saveBytesToDownloads(pdfBytes, defaultFileName);
        });
        builder.setNeutralButton("Share", (dialog, which) -> {
            sharePdfBytes(pdfBytes, defaultFileName);
        });
        builder.setNegativeButton("Open", (dialog, which) -> {
            openPdfBytes(pdfBytes, defaultFileName);
        });
        builder.show();
    }

    private void saveBytesToDownloads(byte[] bytes, String defaultFileName) {
        try {
            Uri savedUri = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, defaultFileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                savedUri = getContentResolver().insert(collection, values);
                if (savedUri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(savedUri)) {
                        if (out != null) {
                            out.write(bytes);
                            out.flush();
                        }
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(savedUri, values, null, null);
                }
            } else {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) downloadsDir.mkdirs();
                File dest = new File(downloadsDir, defaultFileName);
                try (FileOutputStream fos = new FileOutputStream(dest)) {
                    fos.write(bytes);
                    fos.flush();
                }
                savedUri = Uri.fromFile(dest);
            }

            toast("Saved to Downloads: " + defaultFileName);
            final Uri finalUri = savedUri;
            new AlertDialog.Builder(this)
                    .setTitle("Saved to Downloads")
                    .setMessage("File saved successfully to Downloads:\n" + defaultFileName)
                    .setPositiveButton("Open", (d, w) -> {
                        if (finalUri != null) {
                            openUri(finalUri);
                        } else {
                            openPdfBytes(bytes, defaultFileName);
                        }
                    })
                    .setNeutralButton("Share", (d, w) -> sharePdfBytes(bytes, defaultFileName))
                    .setNegativeButton("Done", null)
                    .show();
        } catch (Exception e) {
            toast("Failed to save to Downloads: " + safeMessage(e));
        }
    }

    private void sharePdfBytes(byte[] bytes, String defaultFileName) {
        try {
            File cacheFile = new File(getCacheDir(), defaultFileName);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(bytes);
                fos.flush();
            }
            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cacheFile);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/pdf");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share PDF Document"));
        } catch (Exception e) {
            toast("Could not share PDF: " + safeMessage(e));
        }
    }

    private void openPdfBytes(byte[] bytes, String defaultFileName) {
        try {
            File cacheFile = new File(getCacheDir(), defaultFileName);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(bytes);
                fos.flush();
            }
            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cacheFile);
            openUri(contentUri);
        } catch (Exception e) {
            toast("Could not open PDF: " + safeMessage(e));
        }
    }

    private void openUri(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            toast("No PDF viewer app found on device.");
        }
    }

    private void saveOriginalPdfCopy() {
        if (currentPdf == null) {
            toast("Choose a PDF first.");
            return;
        }
        if (records.isEmpty()) {
            toast("No embedded signature detected.");
            return;
        }
        String baseName = getBaseName();
        String outputName = baseName + "-original-signed.pdf";
        showExportOptionsDialog("Original Signed PDF (Cryptographically Intact)",
                "This exports the exact, un-modified original signed PDF bytes.\n\n" +
                "✓ 100% byte-for-byte integrity preserved.\n" +
                "✓ Retains valid ByteRange and CMS digital signatures for Adobe Acrobat and government portals.\n" +
                "✓ Full legal non-repudiation.",
                currentPdf.clone(), outputName);
    }

    private void showPasswordDialog(boolean previousAttemptFailed) {
        if (currentPdf == null) return;

        EditText passwordInput = new EditText(this);
        passwordInput.setSingleLine(true);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setHint("PDF document password");

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(4));
        panel.addView(text(previousAttemptFailed
                ? "That password was not accepted. Please try again."
                : "This PDF is password-protected. Enter its document password to inspect signatures.",
                14, false, 0xFF263238));
        addSpaceIn(panel, 10);
        panel.addView(passwordInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(previousAttemptFailed ? "Incorrect Password" : "Password Protected PDF")
                .setView(panel)
                .setNegativeButton("Cancel", (d, which) -> {
                    currentPdfPassword = "";
                    renderDocumentView();
                })
                .setPositiveButton("Unlock", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                currentPdfPassword = passwordInput.getText().toString();
                dialog.dismiss();
                inspectPdf();
            });
        });
        dialog.show();
        passwordInput.requestFocus();
    }

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

        panel.addView(text("Trust only this exact signer certificate for PDF validation inside this app.", 14, false, 0xFF263238));
        addSpaceIn(panel, 12);
        panel.addView(text("SUBJECT", 11, true, 0xFF78909C));
        panel.addView(text(cert.getSubjectX500Principal().getName(), 13, false, 0xFF263238));
        addSpaceIn(panel, 8);
        panel.addView(text("ISSUER", 11, true, 0xFF78909C));
        panel.addView(text(cert.getIssuerX500Principal().getName(), 13, false, 0xFF263238));
        addSpaceIn(panel, 8);
        panel.addView(text("SHA-256 FINGERPRINT", 11, true, 0xFF78909C));
        TextView fp = text(expectedFingerprint, 12, true, 0xFF1565C0);
        fp.setTextIsSelectable(true);
        panel.addView(fp);

        new AlertDialog.Builder(this)
                .setTitle("Trust Signer Certificate")
                .setView(panel)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Trust Signer", (d, which) -> {
                    try {
                        trustStore.trust(cert, true, "User trusted via PDF Validate");
                        toast("Certificate trusted locally.");
                        inspectPdf();
                    } catch (Exception e) {
                        toast("Could not trust certificate: " + safeMessage(e));
                    }
                }).show();
    }

    private void showCertificateDetails(SignatureRecord signatureRecord, SignerRecord signerRecord) {
        X509Certificate cert = signerRecord.certificate;
        String message = "SHA-256: " + signerRecord.fingerprint + "\n\n"
                + "Subject: " + cert.getSubjectX500Principal().getName() + "\n\n"
                + "Issuer: " + cert.getIssuerX500Principal().getName() + "\n\n"
                + "Serial (hex): " + cert.getSerialNumber().toString(16).toUpperCase(Locale.ROOT) + "\n\n"
                + "Validity: " + DateFormat.getDateTimeInstance().format(cert.getNotBefore()) + " — "
                + DateFormat.getDateTimeInstance().format(cert.getNotAfter()) + "\n\n"
                + "Mathematics: " + (signerRecord.cryptoValid ? "Verified" : "Not verified") + "\n"
                + "Trust Path: " + (signerRecord.pathResult != null ? signerRecord.pathResult.status.name() : "UNKNOWN");

        new AlertDialog.Builder(this)
                .setTitle("Trusted Signer Certificate")
                .setMessage(message)
                .setNegativeButton("Close", null)
                .setNeutralButton("Remove Trust", (d, w) -> {
                    trustStore.remove(signerRecord.fingerprint);
                    toast("Trust removed.");
                    inspectPdf();
                }).show();
    }

    private void showTrustedCertificates() {
        List<String> fingerprints = trustStore.listFingerprints();
        if (fingerprints.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Trusted Signer Certificates")
                    .setMessage("No individual signer certificates have been explicitly trusted.")
                    .setPositiveButton("OK", null).show();
            return;
        }

        String[] labels = new String[fingerprints.size()];
        for (int i = 0; i < fingerprints.size(); i++) {
            X509Certificate cert = trustStore.getCertificate(fingerprints.get(i));
            String subject = cert == null ? "Invalid stored certificate" : cert.getSubjectX500Principal().getName();
            labels[i] = subject + "\n" + fingerprints.get(i);
        }

        new AlertDialog.Builder(this).setTitle("Trusted Signer Certificates")
                .setItems(labels, (dialog, which) -> {
                    String fp = fingerprints.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle("Remove Trusted Signer?")
                            .setMessage("Remove trust for SHA-256:\n" + fp)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Remove", (d, w) -> {
                                trustStore.remove(fp);
                                toast("Trust removed.");
                                if (currentPdf != null) inspectPdf();
                            }).show();
                }).setNegativeButton("Close", null).show();
    }

    private void showTrustedCaAnchors() {
        List<X509Certificate> certificates = trustStore.getTrustedCertificateAuthorities();
        if (certificates.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Trusted CA Anchors")
                    .setMessage("No user-imported CA anchors. The bundled CCA India 2022 root is built into the app.")
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
            labels[i] = certificate.getSubjectX500Principal().getName() + "\nSHA-256: " + fingerprints[i];
        }
        new AlertDialog.Builder(this).setTitle("User Trusted CA Anchors")
                .setItems(labels, (dialog, which) -> {
                    String fingerprint = fingerprints[which];
                    new AlertDialog.Builder(this)
                            .setTitle("Remove CA Anchor?")
                            .setMessage("Removing this CA anchor can make signatures that depend on it untrusted.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Remove", (d, w) -> {
                                trustStore.removeTrustedCertificateAuthority(fingerprint);
                                toast("CA anchor removed.");
                                if (currentPdf != null) inspectPdf();
                            }).show();
                }).setNegativeButton("Close", null).show();
    }

    private void reviewTrustedCa(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Unable to open selected certificate.");
            byte[] bytes = readBounded(in, 2L * 1024L * 1024L);
            java.security.cert.CertificateFactory factory =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(bytes));
            if (certificate.getBasicConstraints() < 0) {
                throw new SecurityException("This is not a CA certificate.");
            }
            String fingerprint = TrustedCertificateStore.fingerprint(certificate);
            String message = "Import this CA certificate as an app-specific trust anchor?\n\n"
                    + "Subject: " + certificate.getSubjectX500Principal().getName() + "\n\n"
                    + "Issuer: " + certificate.getIssuerX500Principal().getName() + "\n\n"
                    + "SHA-256: " + fingerprint;
            new AlertDialog.Builder(this)
                    .setTitle("Import CA Anchor")
                    .setMessage(message)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Import CA", (d, w) -> {
                        try {
                            trustStore.trustCertificateAuthority(certificate);
                            toast("CA anchor imported.");
                            if (currentPdf != null) inspectPdf();
                        } catch (Exception e) {
                            toast("Could not import CA: " + safeMessage(e));
                        }
                    }).show();
        } catch (Exception e) {
            toast("Certificate rejected: " + safeMessage(e));
        }
    }

    private byte[] readBounded(InputStream in, long limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > limit) throw new IllegalArgumentException("Selected file exceeds the 50MB limit.");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (Exception ignored) { }
        return "document.pdf";
    }

    private String getBaseName() {
        String baseName = currentPdfName == null ? "document" : currentPdfName.trim();
        if (baseName.isEmpty()) baseName = "document";
        if (baseName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        return baseName;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        toast("Copied to clipboard");
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundBg(0xFFFFFFFF, 0xFFE0E0E0, dp(12)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(2));
        }
        return card;
    }

    private Button styledButton(String label, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(textColor);
        b.setBackground(roundBg(bgColor, 0, dp(8)));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setElevation(dp(1));
        }
        return b;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private void addSpaceIn(LinearLayout parent, int value) {
        View space = new View(this);
        parent.addView(space, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(value)));
    }

    private GradientDrawable roundBg(int color, int strokeColor, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radius);
        if (strokeColor != 0) {
            bg.setStroke(dp(1), strokeColor);
        }
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

    private void showStatusDialog(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }
}
