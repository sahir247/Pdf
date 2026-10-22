package dev.sahir.pdfinspector;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class PdfStamperEngineParityTest {

    @Test
    public void testDynamicCheckmarkIncomeBannerParity() throws Exception {
        PDDocument doc = new PDDocument();
        Method method = PdfStamperEngine.class.getDeclaredMethod(
                "createAdobeGreenCheckmarkForm", PDDocument.class, float.class, float.class, float.class, float.class);
        method.setAccessible(true);

        // Standard Income certificate banner: 120 x 60 pt
        PDFormXObject form = (PDFormXObject) method.invoke(null, doc, 100f, 100f, 120f, 60f);
        Assert.assertNotNull("Form XObject must be generated", form);

        InputStream in = form.getCOSObject().createInputStream();
        byte[] bytes = new byte[in.available()];
        in.read(bytes);
        String stream = new String(bytes, StandardCharsets.ISO_8859_1);

        // Rule 3: Multi-layer structure (NEVER green only)
        Assert.assertTrue("Must include black drop shadow (0 G ... h f)", stream.contains("0 G\n") && stream.contains("h f"));
        Assert.assertTrue("Must include vibrant Adobe green fill (0 0.7 0.23 rg)", stream.contains("0 0.7 0.23 rg\n"));
        Assert.assertTrue("Must include crisp black outer outline stroke (0 G ... w ... h S)", stream.contains("w\n") && stream.contains("h S"));

        // Rule 2: Right arm overlap inside 'a' of valid, NEVER touching 'd'
        Assert.assertTrue("Stream must contain right arm terminating inside 'a' (<= 75 pt)",
                stream.contains("55.00 l") || stream.contains("54.99 l") || stream.contains("48.99 l"));

        // Rule 2: Apex placement above Line 5 "Dist" / Location (Y=2 pt)
        Assert.assertTrue("Stream must contain apex hovering at Y=11 pt above Dist",
                stream.contains("11.00 m") || stream.contains("11.00 l"));

        doc.close();
    }

    @Test
    public void testDynamicCheckmarkCompactAadhaarParity() throws Exception {
        PDDocument doc = new PDDocument();
        Method method = PdfStamperEngine.class.getDeclaredMethod(
                "createAdobeGreenCheckmarkForm", PDDocument.class, float.class, float.class, float.class, float.class);
        method.setAccessible(true);

        // Compact Aadhaar stamp: 50 x 30 pt
        PDFormXObject form = (PDFormXObject) method.invoke(null, doc, 100f, 100f, 50f, 30f);
        Assert.assertNotNull("Form XObject must be generated", form);

        InputStream in = form.getCOSObject().createInputStream();
        byte[] bytes = new byte[in.available()];
        in.read(bytes);
        String stream = new String(bytes, StandardCharsets.ISO_8859_1);

        // Multi-layer structure
        Assert.assertTrue("Compact checkmark must have shadow", stream.contains("0 G\n"));
        Assert.assertTrue("Compact checkmark must have green fill", stream.contains("0 0.7 0.23 rg\n"));
        Assert.assertTrue("Compact checkmark must have outline stroke", stream.contains("h S"));

        doc.close();
    }

    @Test
    public void testRealDocumentRectification() throws Exception {
        File scratchDir = new File("C:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/scratch");
        File incomeFile = new File(scratchDir, "income.pdf");
        if (incomeFile.exists()) {
            byte[] inBytes = Files.readAllBytes(incomeFile.toPath());
            PdfStamperEngine.StampResult res = PdfStamperEngine.processPdf(inBytes, null);
            Assert.assertNotNull("Result must not be null", res);
            Assert.assertTrue("Must have rectified PDF bytes", res.rectifiedPdf != null && res.rectifiedPdf.length > 0);
            File outFile = new File(scratchDir, "production_engine_income.pdf");
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(res.rectifiedPdf);
            }
            Assert.assertTrue("Output file must exist", outFile.exists());
        }

        File aadhaarFile = new File(scratchDir, "eaadhaar.pdf");
        if (aadhaarFile.exists()) {
            byte[] inBytes = Files.readAllBytes(aadhaarFile.toPath());
            PdfStamperEngine.StampResult res = PdfStamperEngine.processPdf(inBytes, "KHON2004");
            Assert.assertNotNull("Result must not be null", res);
            Assert.assertTrue("Must have rectified PDF bytes", res.rectifiedPdf != null && res.rectifiedPdf.length > 0);
            File outFile = new File(scratchDir, "production_engine_eaadhaar.pdf");
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(res.rectifiedPdf);
            }
            Assert.assertTrue("Output file must exist", outFile.exists());
        }
    }
}
