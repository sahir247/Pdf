package dev.sahir.pdfinspector;

import android.content.Context;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only trust anchors shipped with the application.
 *
 * The CCA India 2022 root is the Indian PKI document-signing root. Its SHA-256
 * fingerprint is pinned here so a damaged or replaced asset cannot silently
 * become a trust anchor. The CCA India 2022 SPL root is intentionally excluded:
 * it is the special-purpose SSL/code-signing root, not the document-signing root.
 */
public final class BundledTrustAnchors {
    private static final String CCA_INDIA_2022_SHA256 =
            "9A3FD3176798E842DDCB12C262F11CFACCA70A8B84C6EA6FDA30842A95A94CD8";

    private BundledTrustAnchors() { }

    public static List<X509Certificate> load(Context context) throws Exception {
        try (InputStream input = context.getAssets().open("trust/cca-india-2022.pem")) {
            X509Certificate certificate = (X509Certificate) CertificateFactory
                    .getInstance("X.509").generateCertificate(input);

            if (certificate.getBasicConstraints() < 0) {
                throw new SecurityException("Bundled CCA certificate is not a CA.");
            }
            boolean[] keyUsage = certificate.getKeyUsage();
            if (keyUsage != null && (keyUsage.length <= 5 || !keyUsage[5])) {
                throw new SecurityException("Bundled CCA certificate cannot sign certificates.");
            }
            String actualFingerprint = sha256(certificate);
            if (!CCA_INDIA_2022_SHA256.equals(actualFingerprint)) {
                throw new SecurityException("Bundled CCA root fingerprint mismatch.");
            }
            certificate.verify(certificate.getPublicKey());
            certificate.checkValidity();

            return Collections.unmodifiableList(
                    new ArrayList<>(Collections.singletonList(certificate)));
        }
    }

    private static String sha256(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02X", value & 0xff));
        return hex.toString();
    }
}
