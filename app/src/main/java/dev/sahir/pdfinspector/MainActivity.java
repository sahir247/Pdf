package dev.sahir.pdfinspector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaSimpleSignerInfoVerifierBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int PICK_PDF = 1001;
    private static final long MAX_PDF_BYTES = 50L * 1024L * 1024L;

    private LinearLayout content;
    private LinearLayout actions;
    private TextView status;
    private TrustedCertificateStore trustStore;
    private byte[] currentPdf;
    private String currentPdfName = "No PDF selected";
    private final List<SignatureRecord> records = new ArrayList<>();

    private static final class SignatureRecord {
        PDSignature signature;
        X509Certificate certificate;
        boolean cryptoValid;
        boolean byteRangePlausible;
        String detail;
        String fingerprint;
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
        addSpace(12);
        content.addView(status);

        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        content.addView(actions);

        Button manage = button("Manage trusted certificates");
        manage.setOnClickListener(v -> showTrustedCertificates());
        addSpace(12);
        content.addView(manage);

        TextView caveat = text("Security scope: app-private trust only. This prototype verifies PDF CMS signatures and stores exact certificate pins. It does not yet perform CRL/OCSP revocation checks or complete PDF revision / DocMDP analysis. Unknown revocation status will never be presented as a full green-check validation.", 12, false, 0xFF6A4B12);
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

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PDF || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Unable to open the selected document.");
            currentPdf = readBounded(in, MAX_PDF_BYTES);
            currentPdfName = queryDisplayName(uri);
            inspectPdf();
        } catch (Exception e) {
            currentPdf = null;
            records.clear();
            actions.removeAllViews();
            showStatus("Could not inspect PDF\n" + safeMessage(e) +
                    "\n\nThe file may be encrypted, malformed, too large, or unsupported.");
        }
    }

    private byte[] readBounded(InputStream in, long limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > limit) throw new IllegalArgumentException("PDF exceeds the 50 MiB prototype limit.");
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
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(currentPdf))) {
            List<PDSignature> signatures = document.getSignatureDictionaries();
            if (signatures == null || signatures.isEmpty()) {
                showStatus(report.append("No embedded PDF digital signatures were found.\nA visible image of a signature is not a cryptographic signature.").toString());
                return;
            }
            report.append("Found ").append(signatures.size()).append(" signature(s).\n");
            int index = 0;
            for (PDSignature signature : signatures) {
                index++;
                SignatureRecord record = new SignatureRecord();
                record.signature = signature;
                record.byteRangePlausible = checkByteRange(signature.getByteRange(), currentPdf.length);
                report.append("\n────────────────────────\n");
                report.append("Signature ").append(index).append("\n");
                report.append("Field / signer name: ").append(emptyFallback(signature.getName(), "Not provided")).append("\n");
                report.append("SubFilter: ").append(emptyFallback(signature.getSubFilter(), "Not provided")).append("\n");
                report.append("Signing time claimed in PDF: ").append(signature.getSignDate() == null ? "Not provided" : signature.getSignDate().getTime().toString()).append("\n");
                report.append("ByteRange bounds: ").append(record.byteRangePlausible ? "Plausible" : "Invalid or suspicious").append("\n");

                try {
                    byte[] cmsBytes = signature.getContents(currentPdf);
                    byte[] signedContent = signature.getSignedContent(currentPdf);
                    CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(signedContent), cmsBytes);
                    SignerInformationStore signerStore = cms.getSignerInfos();
                    Collection<SignerInformation> signers = signerStore.getSigners();
                    if (signers.isEmpty()) throw new SecurityException("CMS contains no signers.");

                    JcaX509CertificateConverter converter = new JcaX509CertificateConverter().setProvider("BC");
                    boolean anyValid = false;
                    for (SignerInformation signer : signers) {
                        Collection<X509CertificateHolder> matches = cms.getCertificates().getMatches(signer.getSID());
                        for (X509CertificateHolder holder : matches) {
                            X509Certificate cert = converter.getCertificate(holder);
                            boolean verified = signer.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider("BC").build(cert));
                            if (verified) {
                                anyValid = true;
                                record.certificate = cert;
                                break;
                            }
                            if (record.certificate == null) record.certificate = cert;
                        }
                    }
                    record.cryptoValid = anyValid && record.byteRangePlausible;
                    if (record.certificate != null) record.fingerprint = TrustedCertificateStore.fingerprint(record.certificate);
                    report.append("Signer certificate: ").append(record.certificate == null ? "Not found in CMS" : record.certificate.getSubjectX500Principal().getName()).append("\n");
                    report.append("SHA-256: ").append(record.fingerprint == null ? "Unavailable" : record.fingerprint).append("\n");
                    report.append("Cryptographic signature: ").append(record.cryptoValid ? "VALID" : "NOT VERIFIED").append("\n");
                    report.append("Certificate pin: ").append(record.certificate != null && trustStore.isTrusted(record.certificate) ? "Trusted by you in this app" : "Not trusted by this app").append("\n");
                    report.append("Certificate dates: ").append(certificateDateStatus(record.certificate)).append("\n");
                    report.append("Revocation: UNKNOWN (not checked)\n");
                    report.append("PDF revision / permitted changes: NOT FULLY ANALYZED\n");

                    if (record.certificate != null) {
                        final SignatureRecord selected = record;
                        Button add = button(trustStore.isTrusted(record.certificate) ? "Certificate already trusted · Manage" : "Add signer certificate to trusted list");
                        add.setOnClickListener(v -> {
                            if (trustStore.isTrusted(selected.certificate)) showCertificateDetails(selected);
                            else showTrustConfirmation(selected);
                        });
                        addSpaceIn(actions, 10);
                        actions.addView(add);
                    }
                } catch (Exception e) {
                    record.cryptoValid = false;
                    report.append("Cryptographic signature: NOT VERIFIED\nReason: ").append(safeMessage(e)).append("\n");
                }
                records.add(record);
            }
            report.append("\nImportant: a cryptographically valid signature is not, by itself, proof that the signer is trusted or that all later PDF changes are safe.");
            showStatus(report.toString());
        }
    }

    private boolean checkByteRange(int[] range, int fileLength) {
        if (range == null || range.length != 4) return false;
        long a = range[0], b = range[1], c = range[2], d = range[3];
        if (a != 0 || b < 0 || c < b || d < 0) return false;
        if (b > fileLength || c > fileLength || d > fileLength) return false;
        return c + d <= fileLength;
    }

    private String certificateDateStatus(X509Certificate certificate) {
        if (certificate == null) return "UNKNOWN";
        try {
            certificate.checkValidity(new Date());
            return "Valid at current device time";
        } catch (java.security.cert.CertificateExpiredException e) {
            return "EXPIRED now (historical validity not established)";
        } catch (java.security.cert.CertificateNotYetValidException e) {
            return "NOT YET VALID at current device time";
        }
    }

    private void showTrustConfirmation(SignatureRecord record) {
        if (record.certificate == null || record.fingerprint == null) {
            toast("No signer certificate is available to trust.");
            return;
        }
        X509Certificate cert = record.certificate;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(4));

        panel.addView(text("Trust only this exact signer certificate for PDF signature validation inside PDF Signature Inspector. This does not trust its issuer or any other certificate.", 14, false, 0xFF263746));
        addDialogSpace(panel, 12);
        panel.addView(text("SUBJECT", 11, true, 0xFF687785));
        panel.addView(text(cert.getSubjectX500Principal().getName(), 13, false, 0xFF17212B));
        addDialogSpace(panel, 8);
        panel.addView(text("ISSUER", 11, true, 0xFF687785));
        panel.addView(text(cert.getIssuerX500Principal().getName(), 13, false, 0xFF17212B));
        addDialogSpace(panel, 8);
        panel.addView(text("SHA-256 FINGERPRINT", 11, true, 0xFF687785));
        TextView fingerprint = text(record.fingerprint, 12, true, 0xFF17212B);
        fingerprint.setTextIsSelectable(true);
        panel.addView(fingerprint);
        Button copy = button("Copy full fingerprint");
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Certificate SHA-256", record.fingerprint));
            toast("Fingerprint copied");
        });
        addDialogSpace(panel, 6);
        panel.addView(copy);

        CheckBox verified = new CheckBox(this);
        verified.setText("I compared the full fingerprint with an independent, trusted source.");
        verified.setTextColor(0xFF263746);
        verified.setTextSize(13);
        addDialogSpace(panel, 8);
        panel.addView(verified);

        TextView caveat = text("Revocation is not checked in this build. Certificate expiry, revocation, document revision changes and certification permissions are not overridden by trusting this certificate. A full green verification status is not available until those checks are implemented.", 12, false, 0xFF744B0B);
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
                    if (!recalculated.equals(record.fingerprint)) {
                        toast("Certificate changed. Trust was not saved.");
                        dialog.dismiss();
                        return;
                    }
                    trustStore.trust(cert);
                    dialog.dismiss();
                    toast("Certificate trusted in this app only.");
                    inspectPdf();
                } catch (Exception e) {
                    toast("Could not save trust: " + safeMessage(e));
                }
            });
        });
        dialog.show();
    }

    private void showCertificateDetails(SignatureRecord record) {
        X509Certificate cert = record.certificate;
        String message = "This exact certificate is trusted by you in this app.\n\n" +
                "SHA-256: " + record.fingerprint + "\n\n" +
                "Subject: " + cert.getSubjectX500Principal().getName() + "\n\n" +
                "Issuer: " + cert.getIssuerX500Principal().getName() + "\n\n" +
                "Revocation: unknown; not checked.\n" +
                "Revision / DocMDP analysis: incomplete.";
        new AlertDialog.Builder(this).setTitle("Trusted signer certificate")
                .setMessage(message)
                .setNegativeButton("Close", null)
                .setNeutralButton("Remove trust", (d, w) -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Remove trusted certificate?")
                            .setMessage("Future validations will no longer use this local trust decision.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Remove", (dd, ww) -> {
                                trustStore.remove(record.fingerprint);
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
        String[] labels = fingerprints.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Trusted certificates · app only")
                .setItems(labels, (dialog, which) -> {
                    String fp = fingerprints.get(which);
                    X509Certificate cert = trustStore.getCertificate(fp);
                    if (cert == null) {
                        trustStore.remove(fp);
                        toast("Invalid trust record removed.");
                        return;
                    }
                    String message = "SHA-256: " + fp + "\n\nSubject: " +
                            cert.getSubjectX500Principal().getName() + "\n\nIssuer: " +
                            cert.getIssuerX500Principal().getName() +
                            "\n\nRevocation is not checked by this build.";
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
