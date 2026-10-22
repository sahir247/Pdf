package dev.sahir.pdfinspector;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

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
    private static final long MAX_PDF_BYTES = 50L * 1024L * 1024L;

    private LinearLayout contentContainer;
    private TrustedCertificateStore trustStore;
    private byte[] currentPdf;
    private String currentPdfName = "";
    private String currentPdfPassword = "";
    private Bitmap renderedPagePreview;
    private final List<SignatureRecord> records = new ArrayList<>();
    private boolean promptedForCurrentDoc = false;

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
        DynamicSignatureOverlay.init(this);
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

    @Override
    protected void onResume() {
        super.onResume();
        if (currentPdf != null) {
            // Re-inspect to reflect any changes made in TrustManagementActivity
            inspectPdf();
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();
            if (uri != null) {
                String pass = intent.getStringExtra("password");
                if (pass != null) currentPdfPassword = pass;
                promptedForCurrentDoc = false;
                loadPdfFromUri(uri);
            }
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                promptedForCurrentDoc = false;
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

        // 1. Header Card with Title and Upper-Right Certificate Icon
        LinearLayout headerCard = createCard();
        headerCard.setBackground(roundBg(0xFF0D47A1, 0, dp(14)));
        headerCard.setPadding(dp(18), dp(16), dp(18), dp(16));

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        titleCol.setLayoutParams(titleParams);

        TextView appTitle = text("PDF Validate", 22, true, 0xFFFFFFFF);
        titleCol.addView(appTitle);

        TextView tagLine = text("Native Android • Adobe Acrobat Parity Engine", 12, false, 0xFFB0BEC5);
        addSpaceIn(titleCol, 2);
        titleCol.addView(tagLine);

        headerRow.addView(titleCol);

        // Certificate & Trust Store management icon button on upper right
        ImageButton certBtn = new ImageButton(this);
        certBtn.setImageResource(R.drawable.ic_certificate);
        certBtn.setBackground(roundBg(0x22FFFFFF, 0x44FFFFFF, dp(10)));
        certBtn.setColorFilter(Color.WHITE);
        certBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        certBtn.setContentDescription("Certificate & Trust Management");
        certBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, TrustManagementActivity.class);
            startActivity(intent);
        });
        headerRow.addView(certBtn);

        headerCard.addView(headerRow);
        addSpaceIn(contentContainer, 12);
        contentContainer.addView(headerCard);

        // 2. Document Selection / Action
        LinearLayout pickerCard = createCard();
        pickerCard.setPadding(dp(16), dp(16), dp(16), dp(16));

        Button pickBtn = styledButton("Choose PDF Document", R.drawable.ic_folder_open, 0xFF1976D2, 0xFFFFFFFF);
        pickBtn.setOnClickListener(v -> choosePdf());
        pickerCard.addView(pickBtn);

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(pickerCard);

        if (currentPdf == null) {
            // Empty State Card
            LinearLayout emptyCard = createCard();
            emptyCard.setGravity(Gravity.CENTER_HORIZONTAL);
            emptyCard.setPadding(dp(24), dp(32), dp(24), dp(32));

                        ImageView emptyIcon = new ImageView(this);
            emptyIcon.setImageResource(R.drawable.ic_pdf_file);
            emptyIcon.setColorFilter(0xFF90A4AE, PorterDuff.Mode.SRC_IN);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(56), dp(56));
            iconLp.gravity = Gravity.CENTER_HORIZONTAL;
            emptyIcon.setLayoutParams(iconLp);
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

            // 2-Option Export Actions (Save and Print)
            if (!records.isEmpty()) {
                renderQuickActions();
            }

            // Document Preview Card (Adobe Dynamic Viewport)
            if (renderedPagePreview != null) {
                renderPreviewCard();
            }

            // Signatures Breakdown
            renderSignaturesBreakdown();
        }
    }

    private void renderStatusBanner() {
        LinearLayout statusCard = createCard();

        int overallStatusColor;
        int statusBgColor;
        String statusTitle;
        String statusSubtitle;

                int statusIconRes;
        if (records.isEmpty()) {
            overallStatusColor = 0xFF546E7A;
            statusBgColor = 0xFFECEFF1;
            statusIconRes = R.drawable.ic_info;
            statusTitle = "NO DIGITAL SIGNATURE DETECTED";
            statusSubtitle = "This document does not contain embedded cryptographic signature dictionaries. Visual stamps are not digital signatures.";
        } else if (isDocumentTampered()) {
            overallStatusColor = 0xFFC62828;
            statusBgColor = 0xFFFFEBEE;
            statusIconRes = R.drawable.ic_error_circle;
            statusTitle = "SIGNATURE TAMPERED / INVALID";
            statusSubtitle = "Document failed cryptographic mathematical verification or byte ranges were modified after signing. Stamping or exporting tampered documents is strictly blocked.";
        } else if (isDocumentCryptographicallyValid()) {
            overallStatusColor = 0xFF2E7D32;
            statusBgColor = 0xFFE8F5E9;
            statusIconRes = R.drawable.ic_check_circle;
            statusTitle = "SIGNATURE VERIFIED (TRUSTED)";
            statusSubtitle = "CMS digital signature verified and chains to a trusted root authority (CCA India PKI / System CA / Pinned Signer).";
        } else {
            overallStatusColor = 0xFFE65100;
            statusBgColor = 0xFFFFF3E0;
            statusIconRes = R.drawable.ic_warning;
            statusTitle = "SIGNATURE VALID (UNTRUSTED ROOT)";
            statusSubtitle = "Signature mathematics is valid, but the certificate does not chain to a pre-trusted root authority. Tap the certificate icon in the top right or add to trusted.";
        }

        statusCard.setBackground(roundBg(statusBgColor, overallStatusColor, dp(12)));
        statusCard.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView iconView = new ImageView(this);
        Drawable statusDrawable = ContextCompat.getDrawable(this, statusIconRes);
        if (statusDrawable != null) {
            statusDrawable = statusDrawable.mutate();
            statusDrawable.setColorFilter(overallStatusColor, PorterDuff.Mode.SRC_IN);
            iconView.setImageDrawable(statusDrawable);
        }
        int iconSize = dp(22);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMargins(0, 0, dp(10), 0);
        titleRow.addView(iconView, iconLp);

        TextView titleView = text(statusTitle, 16, true, overallStatusColor);
        titleRow.addView(titleView);

        statusCard.addView(titleRow);
        addSpaceIn(statusCard, 6);

        TextView subView = text(statusSubtitle, 13, false, 0xFF37474F);
        statusCard.addView(subView);

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(statusCard);
    }

    private void renderPreviewCard() {
        LinearLayout previewCard = createCard();
        previewCard.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView secTitle = text("DOCUMENT PREVIEW (ADOBE DYNAMIC VIEWPORT)", 12, true, 0xFF78909C);
        previewCard.addView(secTitle);
        addSpaceIn(previewCard, 8);

                if (isDocumentCryptographicallyValid()) {
            LinearLayout badgeRow = new LinearLayout(this);
            badgeRow.setOrientation(LinearLayout.HORIZONTAL);
            badgeRow.setGravity(Gravity.CENTER_VERTICAL);
            badgeRow.setBackground(roundBg(0xFFE8F5E9, 0xFF81C784, dp(6)));
            badgeRow.setPadding(dp(10), dp(6), dp(10), dp(6));

            ImageView checkIcon = new ImageView(this);
            Drawable d = ContextCompat.getDrawable(this, R.drawable.ic_check_circle);
            if (d != null) {
                d = d.mutate();
                d.setColorFilter(0xFF2E7D32, PorterDuff.Mode.SRC_IN);
                checkIcon.setImageDrawable(d);
            }
            int bSize = dp(16);
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(bSize, bSize);
            bLp.setMargins(0, 0, dp(6), 0);
            badgeRow.addView(checkIcon, bLp);

            TextView badge = text("Dynamic Verification Active \u2022 Original Signed Bytes 100% Intact", 12, true, 0xFF2E7D32);
            badgeRow.addView(badge);

            previewCard.addView(badgeRow);
            addSpaceIn(previewCard, 10);
        }

        ImageView previewView = new ImageView(this);
        previewView.setAdjustViewBounds(true);
        previewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewView.setImageBitmap(renderedPagePreview);
        previewView.setBackground(roundBg(0xFFFAFAFA, 0xFFCFD8DC, dp(8)));
        previewView.setPadding(dp(4), dp(4), dp(4), dp(4));
        previewView.setOnClickListener(v -> showFullPreviewDialog(renderedPagePreview));

        previewCard.addView(previewView);

        TextView hint = text("Tap preview to inspect full-screen with high-resolution Adobe checkmark", 11, false, 0xFF78909C);
        hint.setPadding(0, dp(6), 0, 0);
        previewCard.addView(hint);

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(previewCard);
    }

    private void showFullPreviewDialog(Bitmap bitmap) {
        if (bitmap == null) return;
        ImageView fullView = new ImageView(this);
        fullView.setAdjustViewBounds(true);
        fullView.setImageBitmap(bitmap);

        ScrollView sv = new ScrollView(this);
        sv.addView(fullView);

        new AlertDialog.Builder(this)
                .setTitle("Document Viewport Preview")
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
    }

    /**
     * Streamlined 2-Option Actions: Save PDF and Print.
     * Both options strip signature dictionaries and AcroForms, preserving pristine visual output
     * so that downstream viewers (such as Adobe Acrobat DC) never show integrity errors.
     * Exporting or signing tampered documents is strictly prohibited.
     */
    private void renderQuickActions() {
        LinearLayout actionsCard = createCard();
        actionsCard.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView secTitle = text("EXPORT & PRINT ACTIONS", 12, true, 0xFF78909C);
        actionsCard.addView(secTitle);
        addSpaceIn(actionsCard, 12);

                if (isDocumentTampered()) {
            LinearLayout warnRow = new LinearLayout(this);
            warnRow.setOrientation(LinearLayout.HORIZONTAL);
            warnRow.setGravity(Gravity.CENTER_VERTICAL);

            ImageView warnIcon = new ImageView(this);
            Drawable d = ContextCompat.getDrawable(this, R.drawable.ic_warning);
            if (d != null) {
                d = d.mutate();
                d.setColorFilter(0xFFC62828, PorterDuff.Mode.SRC_IN);
                warnIcon.setImageDrawable(d);
            }
            int wSize = dp(20);
            LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(wSize, wSize);
            wLp.setMargins(0, 0, dp(8), 0);
            warnRow.addView(warnIcon, wLp);

            TextView warn = text("Document has been modified or tampered with after signing. Export and printing are blocked to protect document integrity.", 13, true, 0xFFC62828);
            warnRow.addView(warn);

            actionsCard.addView(warnRow);
            addSpaceIn(contentContainer, 12);
            contentContainer.addView(actionsCard);
            return;
        }

        // Option 1: Save Verified PDF (Visual Only)
        Button saveBtn = styledButton("Save PDF (Visual Only)", R.drawable.ic_save, 0xFF2E7D32, 0xFFFFFFFF);
        saveBtn.setOnClickListener(v -> exportVisualSavePdf());
        actionsCard.addView(saveBtn);

        TextView saveDesc = text("Preserves visual layout & verified checkmark. Strips signature data so Adobe Acrobat and all viewers open without integrity errors.", 12, false, 0xFF546E7A);
        saveDesc.setPadding(dp(4), dp(4), dp(4), 0);
        actionsCard.addView(saveDesc);
        addSpaceIn(actionsCard, 14);

        // Option 2: Print Document
        Button printBtn = styledButton("Print Document", R.drawable.ic_print, 0xFF1565C0, 0xFFFFFFFF);
        printBtn.setOnClickListener(v -> printVisualDocument());
        actionsCard.addView(printBtn);

        TextView printDesc = text("Spools high-resolution visual pages with verified checkmark directly to Android Print / Save as PDF.", 12, false, 0xFF546E7A);
        printDesc.setPadding(dp(4), dp(4), dp(4), 0);
        actionsCard.addView(printDesc);

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(actionsCard);
    }

    private void exportVisualSavePdf() {
        if (currentPdf == null) {
            toast("Please select a PDF first.");
            return;
        }
        if (isDocumentTampered()) {
            showStatusDialog("Export Blocked", "This document has been modified, tampered with, or corrupted after signing. Stamping or exporting tampered documents is strictly prohibited.");
            return;
        }
        if (!isDocumentCryptographicallyValid()) {
            showStatusDialog("Export Blocked", "Cryptographic validation failed or certificate is untrusted.\n\nPlease verify or trust the certificate before exporting.");
            return;
        }

        try {
            PdfStamperEngine.StampResult result = PdfStamperEngine.processPdf(currentPdf, currentPdfPassword);
            byte[] visualPdf = result.printablePdf;
            String baseName = getBaseName();
            String outputName = baseName + "-verified.pdf";
            saveBytesToDownloads(visualPdf, outputName);
        } catch (Exception e) {
            toast("Failed to export verified PDF: " + safeMessage(e));
        }
    }

    private void printVisualDocument() {
        if (currentPdf == null) {
            toast("Please select a PDF first.");
            return;
        }
        if (isDocumentTampered()) {
            showStatusDialog("Print Blocked", "This document has been modified, tampered with, or corrupted after signing. Printing tampered documents with verified status is strictly prohibited.");
            return;
        }
        if (!isDocumentCryptographicallyValid()) {
            showStatusDialog("Print Blocked", "Cryptographic validation failed or certificate is untrusted.\n\nPlease verify or trust the certificate before printing.");
            return;
        }

        PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (printManager == null) {
            toast("Print service is not available on this device.");
            return;
        }

        try {
            // Strip data and preserve visuals
            PdfStamperEngine.StampResult result = PdfStamperEngine.processPdf(currentPdf, currentPdfPassword);
            byte[] visualPdf = result.printablePdf;
            String jobName = getBaseName() + " (Verified)";
            String signerName = getPrimarySignerName();
            String signingDate = getPrimarySigningDate();

            // visualPdf already has the checkmark and text baked in!
            // Passing isVerified = false to PdfPrintDocumentAdapter renders visualPdf cleanly without drawing a second overlay.
            PdfPrintDocumentAdapter adapter = new PdfPrintDocumentAdapter(
                    this, visualPdf, null, currentPdfName, false, signerName, signingDate);

            printManager.print(jobName, adapter, null);
        } catch (Exception e) {
            toast("Failed to prepare document for printing: " + safeMessage(e));
        }
    }

    private String getPrimarySignerName() {
        if (records.isEmpty()) return "Signer";
        SignatureRecord rec = records.get(0);
        if (!rec.signers.isEmpty() && rec.signers.get(0).certificate != null) {
            String dn = rec.signers.get(0).certificate.getSubjectX500Principal().getName();
            return extractCommonName(dn);
        }
        if (rec.signature != null && rec.signature.getName() != null) {
            return rec.signature.getName();
        }
        return "Signer";
    }

    private String getPrimarySigningDate() {
        if (records.isEmpty()) return "";
        SignatureRecord rec = records.get(0);
        if (rec.signature != null && rec.signature.getSignDate() != null) {
            return DateFormat.getDateTimeInstance().format(rec.signature.getSignDate().getTime());
        }
        return "";
    }

    private String extractCommonName(String dn) {
        if (dn == null) return "Signer";
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("CN=")) {
                return trimmed.substring(3).trim();
            }
        }
        return dn;
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

            String byteRangeStatus = record.byteRangePlausible ? "Structurally Plausible" : "Invalid / Suspicious";
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
                String mathStatus = signer.cryptoValid ? "Cryptographic Mathematics: VERIFIED" : "Cryptographic Mathematics: INVALID";
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
            }

            breakdownCard.addView(sigItem);
            addSpaceIn(breakdownCard, 10);
        }

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(breakdownCard);
    }

    private void choosePdf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        startActivityForResult(intent, PICK_PDF);
    }

    private InputStream openUriInputStream(Uri uri) throws Exception {
        if (uri == null) throw new IllegalArgumentException("URI is null");
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            if (f.exists()) return new java.io.FileInputStream(f);
        }
        InputStream in = getContentResolver().openInputStream(uri);
        if (in != null) return in;
        throw new IllegalStateException("Cannot open input stream for URI: " + uri);
    }

    private void loadPdfFromUri(Uri uri) {
        try (InputStream in = openUriInputStream(uri)) {
            currentPdf = readBounded(in, MAX_PDF_BYTES);
            currentPdfName = queryDisplayName(uri);
            promptedForCurrentDoc = false;
            inspectPdf();
        } catch (Exception e) {
            showStatusDialog("Open Error", "Could not read selected PDF: " + safeMessage(e));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PDF && resultCode == RESULT_OK && data != null && data.getData() != null) {
            promptedForCurrentDoc = false;
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

                        SignerInformationStore signers = cms.getSignerInfos();
                        Collection<SignerInformation> cmsSigners = signers.getSigners();
                        JcaX509CertificateConverter converter =
                                new JcaX509CertificateConverter().setProvider("BC");

                        List<X509Certificate> embeddedCertificates = new ArrayList<>();
                        for (X509CertificateHolder holder : cms.getCertificates().getMatches(null)) {
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
        generateDynamicPreview();

        // Automatic verification vs. Prompt for Untrusted Signature
        checkAndPromptUntrustedSignature();
    }

    private void checkAndPromptUntrustedSignature() {
        if (records.isEmpty() || isDocumentTampered() || promptedForCurrentDoc) {
            return;
        }

        // If the document is already cryptographically valid & trusted: verify automatically with NO popup!
        if (isDocumentCryptographicallyValid()) {
            return;
        }

        // Find the first untrusted signer that is mathematically valid
        SignerRecord untrustedSigner = null;
        for (SignatureRecord rec : records) {
            for (SignerRecord signer : rec.signers) {
                if (signer.cryptoValid && signer.certificate != null) {
                    boolean isPinned = trustStore.isTrusted(signer.certificate);
                    boolean isPathTrusted = signer.pathResult != null &&
                            (signer.pathResult.status == CertificatePathValidator.Status.TRUSTED_SYSTEM_PATH
                                    || signer.pathResult.status == CertificatePathValidator.Status.TRUSTED_APP_PATH);
                    if (!isPathTrusted && !isPinned) {
                        untrustedSigner = signer;
                        break;
                    }
                }
            }
            if (untrustedSigner != null) break;
        }

        if (untrustedSigner != null) {
            promptedForCurrentDoc = true;
            final SignerRecord signerToTrust = untrustedSigner;
            String signerName = extractCommonName(signerToTrust.certificate.getSubjectX500Principal().getName());
            String issuerName = extractCommonName(signerToTrust.certificate.getIssuerX500Principal().getName());
            String fp = signerToTrust.fingerprint != null ? signerToTrust.fingerprint : "";
            if (fp.length() > 32) fp = fp.substring(0, 32) + "...";

            new AlertDialog.Builder(this)
                    .setTitle("Signature Found")
                    .setMessage("A valid digital signature was found in this document:\n\n" +
                            "• Signer: " + signerName + "\n" +
                            "• Issuer: " + issuerName + "\n" +
                            "• SHA-256: " + fp + "\n\n" +
                            "This signature is mathematically valid, but the certificate is not yet in your trusted store.\n\n" +
                            "Add this certificate to your trusted store to verify the signature?")
                    .setPositiveButton("Add to Trusted", (d, w) -> {
                        try {
                            trustStore.trust(signerToTrust.certificate);
                            toast("Added to trusted certificates");
                            inspectPdf();
                        } catch (Exception e) {
                            toast("Could not trust certificate: " + safeMessage(e));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private void generateDynamicPreview() {
        if (currentPdf == null) {
            renderedPagePreview = null;
            return;
        }

        new Thread(() -> {
            try (PDDocument doc = (currentPdfPassword != null && !currentPdfPassword.isEmpty())
                    ? PDDocument.load(new ByteArrayInputStream(currentPdf), currentPdfPassword)
                    : PDDocument.load(new ByteArrayInputStream(currentPdf))) {

                if (doc.getNumberOfPages() == 0) return;
                PDFRenderer renderer = new PDFRenderer(doc);
                int previewPageIndex = 0;
                PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
                if (acroForm != null) {
                    for (PDField field : acroForm.getFields()) {
                        if (field instanceof PDSignatureField) {
                            for (PDAnnotationWidget widget : field.getWidgets()) {
                                if (widget.getPage() != null) {
                                    int pIdx = doc.getPages().indexOf(widget.getPage());
                                    if (pIdx >= 0) {
                                        previewPageIndex = pIdx;
                                        break;
                                    }
                                }
                            }
                        }
                        if (previewPageIndex != 0) break;
                    }
                }

                Bitmap bmp = renderer.renderImage(previewPageIndex, 1.5f);
                Bitmap mutableBmp = bmp.copy(Bitmap.Config.ARGB_8888, true);
                bmp.recycle();

                if (isDocumentCryptographicallyValid()) {
                    Canvas canvas = new Canvas(mutableBmp);
                    if (acroForm != null && previewPageIndex < doc.getNumberOfPages()) {
                        PDPage page = doc.getPage(previewPageIndex);
                        PDRectangle crop = page.getCropBox();
                        float scaleX = (float) mutableBmp.getWidth() / crop.getWidth();
                        float scaleY = (float) mutableBmp.getHeight() / crop.getHeight();

                        for (PDField field : acroForm.getFields()) {
                            if (!(field instanceof PDSignatureField)) continue;
                            PDSignatureField sigField = (PDSignatureField) field;
                            PDSignature pdSig = sigField.getSignature();

                            String widgetSignerName = null;
                            String widgetSigningDate = null;
                            if (pdSig != null) {
                                for (SignatureRecord r : records) {
                                    if (r.signature == pdSig && !r.signers.isEmpty()) {
                                        SignerRecord sr = r.signers.get(0);
                                        if (sr.certificate != null) {
                                            widgetSignerName = extractCommonName(sr.certificate.getSubjectX500Principal().getName());
                                        }
                                        if (pdSig.getSignDate() != null) {
                                            widgetSigningDate = DateFormat.getDateTimeInstance().format(pdSig.getSignDate().getTime());
                                        }
                                        break;
                                    }
                                }
                            }
                            if (widgetSignerName == null) widgetSignerName = getPrimarySignerName();
                            if (widgetSigningDate == null) widgetSigningDate = getPrimarySigningDate();

                            for (PDAnnotationWidget widget : sigField.getWidgets()) {
                                boolean isOnThisPage = false;
                                if (widget.getPage() != null) {
                                    isOnThisPage = doc.getPages().indexOf(widget.getPage()) == previewPageIndex;
                                } else {
                                    try {
                                        List<PDAnnotation> pageAnnots = page.getAnnotations();
                                        if (pageAnnots != null) {
                                            for (PDAnnotation annot : pageAnnots) {
                                                if (annot.getCOSObject().equals(widget.getCOSObject())) {
                                                    isOnThisPage = true;
                                                    break;
                                                }
                                            }
                                        }
                                    } catch (Exception ignored) { }
                                }
                                if (!isOnThisPage) continue;

                                PDRectangle rect = widget.getRectangle();
                                if (rect == null) continue;

                                float x = (rect.getLowerLeftX() - crop.getLowerLeftX()) * scaleX;
                                float y = (crop.getUpperRightY() - rect.getUpperRightY()) * scaleY;
                                float w = rect.getWidth() * scaleX;
                                float h = rect.getHeight() * scaleY;

                                RectF badgeRect = new RectF(x, y, x + w, y + h);
                                DynamicSignatureOverlay.AppearanceLayout layout = DynamicSignatureOverlay.extractAppearanceLayout(widget);
                                DynamicSignatureOverlay.drawSignatureBadge(canvas, badgeRect, widgetSignerName, widgetSigningDate, layout, true);
                            }
                        }
                    }
                }

                runOnUiThread(() -> {
                    renderedPagePreview = mutableBmp;
                    renderDocumentView();
                });
            } catch (Exception e) {
                // Keep existing preview
            }
        }).start();
    }

    /**
     * Checks if the document was modified or tampered with after signing.
     */
    private boolean isDocumentTampered() {
        if (records.isEmpty()) return false;
        for (SignatureRecord rec : records) {
            if (!rec.byteRangePlausible || rec.parseError != null) return true;
            if (rec.revisionReport != null && rec.revisionReport.hasFailedChecks()) return true;
            if (rec.signers.isEmpty()) return true;
            for (SignerRecord signer : rec.signers) {
                if (!signer.cryptoValid) return true;
            }
        }
        return false;
    }

    /**
     * Returns true if every signature has valid CMS mathematics AND chains to a trusted root authority.
     */
    private boolean isDocumentCryptographicallyValid() {
        if (records.isEmpty()) return false;
        if (isDocumentTampered()) return false;
        for (SignatureRecord rec : records) {
            for (SignerRecord signer : rec.signers) {
                boolean trusted = signer.pathResult != null &&
                        (signer.pathResult.status == CertificatePathValidator.Status.TRUSTED_SYSTEM_PATH
                                || signer.pathResult.status == CertificatePathValidator.Status.TRUSTED_APP_PATH
                                || (signer.certificate != null && trustStore.isTrusted(signer.certificate)));
                if (!trusted) return false;
            }
        }
        return true;
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
                    .setMessage("File saved successfully to Downloads:\n" + defaultFileName + "\n\n(Signature metadata stripped for universal viewing)")
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

        dialog.setOnShowListener(d -> {
            Button unlockBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            unlockBtn.setOnClickListener(v -> {
                String input = passwordInput.getText().toString();
                if (TextUtils.isEmpty(input)) {
                    passwordInput.setError("Password required");
                    return;
                }
                currentPdfPassword = input;
                dialog.dismiss();
                inspectPdf();
            });
        });
        dialog.show();
    }

    private boolean checkByteRange(int[] range, byte[] fileBytes) {
        if (range == null || range.length != 4) return false;
        int r0 = range[0];
        int r1 = range[1];
        int r2 = range[2];
        int r3 = range[3];
        if (r0 != 0 || r1 <= 0 || r2 <= 0 || r3 <= 0) return false;
        long sigEnd = (long) r0 + (long) r1;
        long fileEnd = (long) r2 + (long) r3;
        return sigEnd <= r2 && fileEnd <= fileBytes.length;
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

    private Button styledButton(String label, int iconResId, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(textColor);
        b.setBackground(roundBg(bgColor, 0, dp(8)));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (iconResId != 0) {
            Drawable icon = ContextCompat.getDrawable(this, iconResId);
            if (icon != null) {
                icon = icon.mutate();
                icon.setColorFilter(textColor, PorterDuff.Mode.SRC_IN);
                int size = dp(20);
                icon.setBounds(0, 0, size, size);
                b.setCompoundDrawables(icon, null, null, null);
                b.setCompoundDrawablePadding(dp(10));
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setElevation(dp(1));
        }
        return b;
    }

    private Button styledButton(String label, int bgColor, int textColor) {
        return styledButton(label, 0, bgColor, textColor);
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
