package dev.sahir.pdfinspector;

import android.content.Context;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.util.Store;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Creates a real detached CMS signature embedded in a new incremental PDF revision.
 * The caller must keep the PKCS#12 password transient and must not persist/log key material.
 */
public final class PdfSigningService {
    private PdfSigningService() { }

    public static byte[] sign(Context context, byte[] originalPdf, String pdfPassword,
                              byte[] pkcs12Bytes, char[] pkcs12Password) throws Exception {
        if (originalPdf == null || originalPdf.length == 0) throw new IllegalArgumentException("Choose a PDF first.");
        if (pkcs12Bytes == null || pkcs12Bytes.length == 0) throw new IllegalArgumentException("Choose a .p12/.pfx signing identity.");
        if (pkcs12Password == null) pkcs12Password = new char[0];

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(pkcs12Bytes)) {
            keyStore.load(in, pkcs12Password);
        }

        String alias = null;
        PrivateKey privateKey = null;
        X509Certificate signerCertificate = null;
        Certificate[] chain = null;
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String candidate = aliases.nextElement();
            if (!keyStore.isKeyEntry(candidate)) continue;
            Key key = keyStore.getKey(candidate, pkcs12Password);
            if (!(key instanceof PrivateKey)) continue;
            Certificate cert = keyStore.getCertificate(candidate);
            if (!(cert instanceof X509Certificate)) continue;
            alias = candidate;
            privateKey = (PrivateKey) key;
            signerCertificate = (X509Certificate) cert;
            chain = keyStore.getCertificateChain(candidate);
            break;
        }
        if (alias == null || privateKey == null || signerCertificate == null) {
            throw new SecurityException("The PKCS#12 file contains no usable private-key signing identity.");
        }
        signerCertificate.checkValidity();
        if (chain == null || chain.length == 0) chain = new Certificate[]{signerCertificate};

        List<X509Certificate> certificateChain = new ArrayList<>();
        for (Certificate certificate : chain) {
            if (certificate instanceof X509Certificate) certificateChain.add((X509Certificate) certificate);
        }
        if (certificateChain.isEmpty()) certificateChain.add(signerCertificate);

        File inputFile = File.createTempFile("pdf-sign-input-", ".pdf", context.getCacheDir());
        File outputFile = File.createTempFile("pdf-sign-output-", ".pdf", context.getCacheDir());
        try {
            Files.write(inputFile.toPath(), originalPdf);
            try (PDDocument document = PDDocument.load(inputFile, pdfPassword == null ? "" : pdfPassword);
                 SignatureOptions options = new SignatureOptions()) {
                PDSignature signature = new PDSignature();
                signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
                signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
                signature.setName(signerCertificate.getSubjectX500Principal().getName());
                signature.setReason("Digitally signed on this device");
                signature.setSignDate(Calendar.getInstance());
                options.setPreferredSignatureSize(32768);
                document.addSignature(signature, options);

                try (FileOutputStream output = new FileOutputStream(outputFile)) {
                    ExternalSigningSupport external = document.saveIncrementalForExternalSigning(output);
                    byte[] contentBytes;
                    try (InputStream content = external.getContent()) {
                        contentBytes = readAll(content);
                    }
                    byte[] cms = createDetachedCms(contentBytes, privateKey, certificateChain);
                    external.setSignature(cms);
                }
            }
            return Files.readAllBytes(outputFile.toPath());
        } finally {
            // Private key and password are not persisted. Best-effort cleanup of temporary PDF files.
            try { Files.deleteIfExists(inputFile.toPath()); } catch (Exception ignored) { }
            try { Files.deleteIfExists(outputFile.toPath()); } catch (Exception ignored) { }
            java.util.Arrays.fill(pkcs12Password, '\0');
        }
    }

    private static byte[] createDetachedCms(byte[] signedBytes, PrivateKey privateKey,
                                            List<X509Certificate> chain) throws Exception {
        String keyAlgorithm = privateKey.getAlgorithm().toUpperCase(Locale.ROOT);
        String signatureAlgorithm;
        if (keyAlgorithm.contains("RSA")) signatureAlgorithm = "SHA256withRSA";
        else if (keyAlgorithm.contains("EC")) signatureAlgorithm = "SHA256withECDSA";
        else if (keyAlgorithm.contains("DSA")) signatureAlgorithm = "SHA256withDSA";
        else throw new SecurityException("Unsupported signing key algorithm: " + privateKey.getAlgorithm());

        ContentSigner contentSigner = new JcaContentSignerBuilder(signatureAlgorithm).setProvider("BC").build(privateKey);
        DigestCalculatorProvider digestProvider = new JcaDigestCalculatorProviderBuilder().setProvider("BC").build();
        CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
        generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(digestProvider).build(contentSigner, chain.get(0)));
        generator.addCertificates(new JcaCertStore(chain));
        CMSSignedData cms = generator.generate(new CMSProcessableByteArray(signedBytes), false);
        return cms.getEncoded();
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
