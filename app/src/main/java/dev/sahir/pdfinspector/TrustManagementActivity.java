package dev.sahir.pdfinspector;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Dedicated Activity for Certificate & Trust Store Management.
 * Manages user-pinned signer certificates, imported root authorities,
 * and displays pre-bundled Controller of Certifying Authorities (CCA India) trust anchors.
 */
public final class TrustManagementActivity extends AppCompatActivity {

    private static final int PICK_CA_FILE = 2001;

    private TrustedCertificateStore trustStore;
    private LinearLayout contentContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        trustStore = new TrustedCertificateStore(this);
        buildLayout();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF4F6F9);

        // 1. Top App Bar with Back Button
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(0xFF0D47A1);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(16), dp(16), dp(16));

        ImageButton backBtn = new ImageButton(this);
        backBtn.setImageResource(R.drawable.ic_arrow_back);
        backBtn.setBackground(null);
        backBtn.setColorFilter(Color.WHITE);
        backBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        backBtn.setOnClickListener(v -> finish());
        topBar.addView(backBtn);

        TextView title = new TextView(this);
        title.setText("Certificate & Trust Store");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setPadding(dp(12), 0, 0, 0);
        topBar.addView(title);

        root.addView(topBar);

        // 2. Scrollable Content Container
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(contentContainer);

        root.addView(scroll);
        setContentView(root);

        renderTrustList();
    }

    private void renderTrustList() {
        contentContainer.removeAllViews();

        // Section A: Header Summary Card
        LinearLayout infoCard = createCard();
        infoCard.setBackground(roundBg(0xFFE8EAF6, 0, dp(12)));
        infoCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView infoTitle = text("Digital Trust Management", 14, true, 0xFF1A237E);
        infoCard.addView(infoTitle);
        addSpaceIn(infoCard, 4);

        TextView infoDesc = text("Signatures from trusted certificates display a verified green checkmark and can be exported. Untrusted certificates prompt for confirmation.", 12, false, 0xFF3949AB);
        infoCard.addView(infoDesc);

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(infoCard);

        // Section B: User Trusted Signers
        renderUserTrustedSigners();

        // Section C: User Imported CA Roots
        renderImportedRoots();

        // Section D: Built-in CCA Trust Anchors
        renderBuiltInAnchors();
    }

    private void renderUserTrustedSigners() {
        LinearLayout card = createCard();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView secTitle = text("USER-TRUSTED SIGNERS", 12, true, 0xFF78909C);
        card.addView(secTitle);
        addSpaceIn(card, 10);

        List<String> fingerprints = trustStore.listFingerprints();
        if (fingerprints.isEmpty()) {
            TextView emptyText = text("No custom trusted signers pinned yet.\n\nWhen you open a document with an unrecognised certificate, selecting \"Add to Trusted\" will add the signer here.", 13, false, 0xFF546E7A);
            card.addView(emptyText);
        } else {
            for (String fp : fingerprints) {
                X509Certificate cert = trustStore.getCertificate(fp);
                TrustedCertificateStore.TrustMetadata meta = trustStore.getTrustMetadata(fp);

                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setBackground(roundBg(0xFFF1F5F9, 0, dp(8)));
                item.setPadding(dp(12), dp(10), dp(12), dp(10));

                String name = cert != null ? extractCommonName(cert.getSubjectX500Principal().getName()) : "Unknown Signer";
                TextView nameView = text(name, 14, true, 0xFF0F172A);
                item.addView(nameView);

                String dateStr = meta != null && meta.trustedAtEpochMillis > 0
                        ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(meta.trustedAtEpochMillis))
                        : "Unknown date";
                TextView dateView = text("Trusted: " + dateStr, 11, false, 0xFF64748B);
                item.addView(dateView);

                TextView fpView = text("SHA-256: " + fp, 10, false, 0xFF94A3B8);
                item.addView(fpView);

                Button removeBtn = styledButton("Remove Trust", 0xFFD32F2F, 0xFFFFFFFF);
                removeBtn.setTextSize(12);
                removeBtn.setOnClickListener(v -> confirmRemoveTrust(fp, name));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
                lp.topMargin = dp(8);
                removeBtn.setLayoutParams(lp);
                item.addView(removeBtn);

                addSpaceIn(card, 8);
                card.addView(item);
            }
        }

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(card);
    }

    private void renderImportedRoots() {
        LinearLayout card = createCard();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView secTitle = text("CUSTOM CERTIFICATE AUTHORITIES (CAs)", 12, true, 0xFF78909C);
        card.addView(secTitle);
        addSpaceIn(card, 10);

        List<X509Certificate> customCas = trustStore.getTrustedCertificateAuthorities();
        if (customCas.isEmpty()) {
            TextView emptyText = text("No custom Root CAs imported.", 13, false, 0xFF546E7A);
            card.addView(emptyText);
        } else {
            for (X509Certificate ca : customCas) {
                String caName = extractCommonName(ca.getSubjectX500Principal().getName());
                String rawFp;
                try {
                    rawFp = TrustedCertificateStore.fingerprint(ca);
                } catch (Exception e) {
                    rawFp = "";
                }
                final String fp = rawFp;

                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setBackground(roundBg(0xFFF1F5F9, 0, dp(8)));
                item.setPadding(dp(12), dp(10), dp(12), dp(10));

                TextView nameView = text("CA: " + caName, 13, true, 0xFF0F172A);
                item.addView(nameView);

                Button removeBtn = styledButton("Remove CA", 0xFFD32F2F, 0xFFFFFFFF);
                removeBtn.setTextSize(12);
                removeBtn.setOnClickListener(v -> {
                    trustStore.removeTrustedCertificateAuthority(fp);
                    Toast.makeText(this, "Root CA removed", Toast.LENGTH_SHORT).show();
                    renderTrustList();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
                lp.topMargin = dp(6);
                removeBtn.setLayoutParams(lp);
                item.addView(removeBtn);

                addSpaceIn(card, 8);
                card.addView(item);
            }
        }

        addSpaceIn(card, 12);
        Button importBtn = styledButton("+ Import CA Certificate (.cer, .crt, .pem)", 0xFF1565C0, 0xFFFFFFFF);
        importBtn.setOnClickListener(v -> chooseCaFile());
        card.addView(importBtn);

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(card);
    }

    private void renderBuiltInAnchors() {
        LinearLayout card = createCard();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView secTitle = text("BUILT-IN TRUST ANCHORS (CCA INDIA PKI)", 12, true, 0xFF78909C);
        card.addView(secTitle);
        addSpaceIn(card, 6);

        TextView desc = text("Pre-bundled trust anchors authenticate Indian Digital Signatures out of the box without manual configuration:", 12, false, 0xFF546E7A);
        card.addView(desc);
        addSpaceIn(card, 10);

        String[] anchors = {
                "\u2022 Controller of Certifying Authorities (CCA India 2014 Root)",
                "\u2022 Controller of Certifying Authorities (CCA India 2015-SPL Root)",
                "\u2022 Controller of Certifying Authorities (CCA India 2022 Root - 4096-bit)",
                "\u2022 nCode Solutions CA (2014, 2022, SPL)",
                "\u2022 eSign Sub-CA (2019, 2022, 2024)",
                "\u2022 National Informatics Centre (NIC) Sub-CA",
                "\u2022 e-Mudhra & IDRBT Banking Sub-CAs"
        };

        for (String a : anchors) {
            TextView anchorItem = text(a, 12, false, 0xFF1E293B);
            anchorItem.setPadding(dp(4), dp(3), dp(4), dp(3));
            card.addView(anchorItem);
        }

        addSpaceIn(contentContainer, 12);
        contentContainer.addView(card);
    }

    private void chooseCaFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_CA_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_CA_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importCaFromUri(data.getData());
        }
    }

    private void importCaFromUri(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("Cannot open certificate stream");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends java.security.cert.Certificate> certs = cf.generateCertificates(in);
            int count = 0;
            for (java.security.cert.Certificate c : certs) {
                if (c instanceof X509Certificate) {
                    X509Certificate xc = (X509Certificate) c;
                    trustStore.trustCertificateAuthority(xc);
                    count++;
                }
            }
            if (count > 0) {
                Toast.makeText(this, "Imported " + count + " CA certificate(s)", Toast.LENGTH_SHORT).show();
                renderTrustList();
            } else {
                Toast.makeText(this, "No valid X.509 certificate found in file", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to import CA: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRemoveTrust(String fingerprint, String name) {
        new AlertDialog.Builder(this)
                .setTitle("Remove Trust")
                .setMessage("Are you sure you want to remove \"" + name + "\" from trusted certificates?\n\nDocuments signed by this entity will show as unverified.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    trustStore.remove(fingerprint);
                    Toast.makeText(this, "Trust removed for " + name, Toast.LENGTH_SHORT).show();
                    renderTrustList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String extractCommonName(String dn) {
        if (dn == null) return "Unknown";
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=") || trimmed.startsWith("cn=")) {
                return trimmed.substring(3).trim();
            }
        }
        return dn;
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundBg(0xFFFFFFFF, 0xFFE2E8F0, dp(14)));
        return card;
    }

    private Button styledButton(String label, int bgColor, int textColor) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(textColor);
        btn.setTextSize(13);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setBackground(roundBg(bgColor, 0, dp(8)));
        btn.setPadding(dp(14), dp(10), dp(14), dp(10));
        return btn;
    }

    private TextView text(String content, float sp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize(sp);
        tv.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setTextColor(color);
        return tv;
    }

    private void addSpaceIn(LinearLayout container, int dpVal) {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(dpVal));
        container.addView(v, lp);
    }

    private GradientDrawable roundBg(int fill, int stroke, int cornerRadius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(cornerRadius);
        if (stroke != 0) {
            gd.setStroke(dp(1), stroke);
        }
        return gd;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
