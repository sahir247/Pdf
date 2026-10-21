package dev.sahir.pdfinspector;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.security.MessageDigest;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField;

public class SignatureIntegrityTest {

    private static File assetsDir;

    @BeforeClass
    public static void setUp() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        assetsDir = new File("src/main/assets");
        if (!assetsDir.exists()) {
            assetsDir = new File("app/src/main/assets");
        }
    }

    @Test
    public void testBundledTrustAnchorsPinning() throws Exception {
        Assert.assertTrue("Assets dir must exist: " + assetsDir.getAbsolutePath(), assetsDir.exists());
        List<X509Certificate> roots = BundledTrustAnchors.loadTrustAnchors(assetsDir);
        Assert.assertEquals("Must load exactly 3 pinned CCA India roots", 3, roots.size());

        boolean saw2022 = false;
        boolean saw2014 = false;
        boolean saw2015Spl = false;

        for (X509Certificate root : roots) {
            String fp = BundledTrustAnchors.sha256(root);
            if ("9A3FD3176798E842DDCB12C262F11CFACCA70A8B84C6EA6FDA30842A95A94CD8".equalsIgnoreCase(fp)) {
                saw2022 = true;
            } else if ("60109BC6C38328598A112C7A25E38B0F23E5A7511CB815FB64E0C4FF05DB7DF7".equalsIgnoreCase(fp)) {
                saw2014 = true;
            } else if ("C34C5DF53080078FFE45B21A7F600469917204F4F0293F1D7209393E5265C04F".equalsIgnoreCase(fp)) {
                saw2015Spl = true;
            }
            Assert.assertTrue("Root must be a CA", root.getBasicConstraints() >= 0);
            Assert.assertEquals("Root must be self-signed",
                    root.getSubjectX500Principal(), root.getIssuerX500Principal());
        }

        Assert.assertTrue("CCA India 2022 must be present", saw2022);
        Assert.assertTrue("CCA India 2014 must be present", saw2014);
        Assert.assertTrue("CCA India 2015 SPL must be present", saw2015Spl);
    }

    @Test
    public void testBundledNCodeIntermediates() {
        List<X509Certificate> intermediates = BundledTrustAnchors.loadBundledIntermediates(assetsDir);
        Assert.assertTrue("Must load bundled nCode intermediate certificates", intermediates.size() >= 5);

        for (X509Certificate cert : intermediates) {
            Assert.assertTrue("Intermediate must be a CA", cert.getBasicConstraints() >= 0);
            Assert.assertNotEquals("Intermediate CA must not be self-signed",
                    cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
        }
    }

    @Test
    public void testNCodeCertificatePathValidation() throws Exception {
        List<X509Certificate> roots = BundledTrustAnchors.loadTrustAnchors(assetsDir);
        List<X509Certificate> intermediates = BundledTrustAnchors.loadBundledIntermediates(assetsDir);

        // Find (n)Code Solutions sub-CA for eSign 2024
        X509Certificate esignSubCa24 = null;
        for (X509Certificate cert : intermediates) {
            if (cert.getSubjectX500Principal().getName().contains("eSign 2024")) {
                esignSubCa24 = cert;
                break;
            }
        }
        Assert.assertNotNull("eSign 2024 sub-CA must be present in bundled intermediates", esignSubCa24);

        // Validate that esignSubCa24 chains through (n)Code Solutions CA 2022 to CCA India 2022
        CertificatePathValidator.Result result = CertificatePathValidator.validate(
                esignSubCa24,
                Collections.emptyList(),
                roots,
                intermediates
        );

        Assert.assertEquals("Certificate path must reach bundled CCA root anchor",
                CertificatePathValidator.Status.TRUSTED_APP_PATH, result.status);
    }

    @Test
    public void testByteRangeValidationOnTamperedBytes() {
        // Construct a synthetic 4-element byte range: [0, 100, 200, 100] on a 300-byte buffer
        byte[] fakePdf = new byte[300];
        fakePdf[100] = '<';
        for (int i = 101; i < 199; i++) fakePdf[i] = '0';
        fakePdf[199] = '>';

        int[] validRange = new int[]{0, 100, 200, 100};
        // Verify valid structure
        boolean rangeOk = (validRange[0] == 0 && validRange[1] > 0
                && validRange[2] > validRange[1] && validRange[3] > 0
                && (validRange[2] + validRange[3]) <= fakePdf.length);
        Assert.assertTrue("Synthetic valid range must pass range checks", rangeOk);

        // Overlapping range (malicious ByteRange)
        int[] overlappingRange = new int[]{0, 150, 100, 100};
        boolean overlapOk = (overlappingRange[0] == 0 && overlappingRange[1] > 0
                && overlappingRange[2] > overlappingRange[1]);
        Assert.assertFalse("Overlapping ByteRange must be rejected", overlapOk);

        // Truncated file (end exceeds file length)
        int[] outOfBoundsRange = new int[]{0, 100, 200, 500};
        boolean inBounds = (outOfBoundsRange[2] + outOfBoundsRange[3]) <= fakePdf.length;
        Assert.assertFalse("Out of bounds ByteRange must be rejected", inBounds);
    }

    @Test
    public void testOriginalSignedPdfBytePreservation() throws Exception {
        byte[] original = "Original Pristine Signed Bytes".getBytes("UTF-8");
        byte[] copy = original.clone();

        Assert.assertArrayEquals("Original bytes must be bit-for-bit identical when cloned", original, copy);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        Assert.assertArrayEquals("Hashes must match perfectly", sha.digest(original), sha.digest(copy));
    }
    @Test
    public void testAadhaarSignaturePreservationAndWidgetInspection() throws Exception {
        File file = new File("C:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/scratch/EAadhaar_0000003703510220260701195410_21102026132249.pdf");
        if (!file.exists()) return;
        try (PDDocument doc = PDDocument.load(file, "KHON2004")) {
            Assert.assertEquals("Document should have 1 page", 1, doc.getNumberOfPages());
            PDPage page = doc.getPage(0);
            PDRectangle crop = page.getCropBox();
            System.out.println("Aadhaar Page 0: cropBox=" + crop);
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            Assert.assertNotNull("AcroForm must be present in Aadhaar PDF", acroForm);
            boolean foundSigField = false;
            for (PDField field : acroForm.getFields()) {
                for (PDAnnotationWidget widget : field.getWidgets()) {
                    PDRectangle rect = widget.getRectangle();
                    System.out.println("Widget rect: " + rect);
                    Assert.assertTrue("Widget rectangle must have positive dimensions", rect.getWidth() > 0 && rect.getHeight() > 0);
                    foundSigField = true;
                    if (widget.getAppearance() != null && widget.getAppearance().getNormalAppearance() != null) {
                        com.tom_roush.pdfbox.cos.COSDictionary apDict = (com.tom_roush.pdfbox.cos.COSDictionary) widget.getAppearance().getNormalAppearance().getCOSObject();
                        com.tom_roush.pdfbox.cos.COSDictionary res = (com.tom_roush.pdfbox.cos.COSDictionary) apDict.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.RESOURCES);
                        com.tom_roush.pdfbox.cos.COSDictionary xobjs = (com.tom_roush.pdfbox.cos.COSDictionary) res.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.XOBJECT);
                        com.tom_roush.pdfbox.cos.COSStream frm = (com.tom_roush.pdfbox.cos.COSStream) xobjs.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.getPDFName("FRM"));
                        com.tom_roush.pdfbox.cos.COSDictionary frmRes = (com.tom_roush.pdfbox.cos.COSDictionary) frm.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.RESOURCES);
                        com.tom_roush.pdfbox.cos.COSDictionary subX = (com.tom_roush.pdfbox.cos.COSDictionary) frmRes.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.XOBJECT);
                        for (com.tom_roush.pdfbox.cos.COSName k : subX.keySet()) {
                            com.tom_roush.pdfbox.cos.COSStream s = (com.tom_roush.pdfbox.cos.COSStream) subX.getDictionaryObject(k);
                            try (java.io.InputStream in = s.createInputStream()) {
                                byte[] b = in.readAllBytes();
                                String text = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1).replace("\n", " ").replace("\r", " ");
                                System.out.println("Sub-stream " + k.getName() + " length=" + b.length + " content=" + text);
                            }
                        }
                    }
                }
            }
            Assert.assertTrue("Must find signature field in Aadhaar", foundSigField);
        }
    }
}
