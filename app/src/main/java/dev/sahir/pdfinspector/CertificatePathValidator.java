package dev.sahir.pdfinspector;

import java.security.KeyStore;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Checks whether a signer certificate currently chains to the Android system CA store.
 *
 * Exact user pins are intentionally not added as CA trust anchors here. A signer pin is a
 * separate, leaf-certificate-only trust decision and must never make a sibling certificate
 * or issuer trusted. Revocation is disabled here on purpose; this class does not claim that
 * a certificate is not revoked.
 */
public final class CertificatePathValidator {
    public enum Status {
        TRUSTED_SYSTEM_PATH,
        UNTRUSTED,
        UNKNOWN
    }

    public static final class Result {
        public final Status status;
        public final String summary;

        public Result(Status status, String summary) {
            this.status = status;
            this.summary = summary;
        }
    }

    private CertificatePathValidator() { }

    public static Result validate(X509Certificate signer,
                                  Collection<X509Certificate> embeddedCertificates) {
        if (signer == null) {
            return new Result(Status.UNKNOWN, "Signer certificate is unavailable.");
        }

        try {
            Set<TrustAnchor> anchors = loadSystemTrustAnchors();
            if (anchors.isEmpty()) {
                return new Result(Status.UNKNOWN, "Android system trust anchors could not be loaded.");
            }

            X509CertSelector target = new X509CertSelector();
            target.setCertificate(signer);

            PKIXBuilderParameters parameters = new PKIXBuilderParameters(anchors, target);
            parameters.setDate(new Date());
            // Revocation is a separate status. Never turn an unavailable CRL/OCSP response into
            // a successful revocation result by letting the path builder make that decision.
            parameters.setRevocationEnabled(false);

            Collection<X509Certificate> candidates = new ArrayList<>();
            candidates.add(signer);
            if (embeddedCertificates != null) {
                candidates.addAll(embeddedCertificates);
            }
            parameters.addCertStore(CertStore.getInstance(
                    "Collection", new CollectionCertStoreParameters(candidates)));

            PKIXCertPathBuilderResult result = (PKIXCertPathBuilderResult)
                    CertPathBuilder.getInstance("PKIX").build(parameters);
            if (result.getTrustAnchor() == null) {
                return new Result(Status.UNTRUSTED, "No system trust anchor was established.");
            }
            return new Result(Status.TRUSTED_SYSTEM_PATH,
                    "Certificate path reaches an Android system trust anchor at the current device time.");
        } catch (CertPathBuilderException e) {
            return new Result(Status.UNTRUSTED,
                    "No valid path to an Android system trust anchor was found at the current device time.");
        } catch (Exception e) {
            return new Result(Status.UNKNOWN,
                    "System certificate path validation could not be completed (" +
                            e.getClass().getSimpleName() + ").");
        }
    }

    private static Set<TrustAnchor> loadSystemTrustAnchors() throws Exception {
        Set<TrustAnchor> anchors = new HashSet<>();
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);

        for (TrustManager manager : factory.getTrustManagers()) {
            if (!(manager instanceof X509TrustManager)) continue;
            X509Certificate[] accepted = ((X509TrustManager) manager).getAcceptedIssuers();
            if (accepted == null) continue;
            for (X509Certificate certificate : accepted) {
                if (certificate != null && certificate.getBasicConstraints() >= 0) {
                    anchors.add(new TrustAnchor(certificate, null));
                }
            }
        }
        return anchors;
    }
}
