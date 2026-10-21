package dev.sahir.pdfinspector;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TrustedCertificateStore {
    private static final String PREFS = "trusted_signer_certificates_v1";
    private final SharedPreferences preferences;

    public TrustedCertificateStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder out = new StringBuilder(digest.length * 3 - 1);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) out.append(':');
            out.append(String.format(java.util.Locale.ROOT, "%02X", digest[i] & 0xff));
        }
        return out.toString();
    }

    public boolean isTrusted(X509Certificate certificate) {
        try {
            String fp = fingerprint(certificate);
            String encoded = preferences.getString(fp, null);
            if (encoded == null) return false;
            byte[] stored = Base64.decode(encoded, Base64.NO_WRAP);
            return MessageDigest.isEqual(stored, certificate.getEncoded());
        } catch (Exception e) {
            return false;
        }
    }

    public void trust(X509Certificate certificate) throws Exception {
        String fp = fingerprint(certificate);
        String encoded = Base64.encodeToString(certificate.getEncoded(), Base64.NO_WRAP);
        boolean saved = preferences.edit().putString(fp, encoded).commit();
        if (!saved || !isTrusted(certificate)) {
            throw new IllegalStateException("Could not persist or verify the trust record.");
        }
    }

    public void remove(String fingerprint) {
        preferences.edit().remove(fingerprint).commit();
    }

    public List<String> listFingerprints() {
        List<String> items = new ArrayList<>(preferences.getAll().keySet());
        Collections.sort(items);
        return items;
    }

    public X509Certificate getCertificate(String fingerprint) {
        try {
            String encoded = preferences.getString(fingerprint, null);
            if (encoded == null) return null;
            byte[] der = Base64.decode(encoded, Base64.NO_WRAP);
            java.security.cert.CertificateFactory factory =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                    new java.io.ByteArrayInputStream(der));
            if (!fingerprint.equals(fingerprint(certificate))) return null;
            return certificate;
        } catch (Exception e) {
            return null;
        }
    }
}
