package dev.sahir.pdfinspector;

import android.graphics.Color;
import com.tom_roush.pdfbox.cos.COSArray;
import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.cos.COSStream;
import com.tom_roush.pdfbox.cos.COSString;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.common.PDStream;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native PDF Box engine for signature appearance rectification and flattening.
 * Replaces unverified Adobe question-mark appearances with green checkmarks
 * and produces universal print-ready documents.
 */
public final class PdfStamperEngine {

    public static final class StampResult {
        public final byte[] rectifiedPdf;
        public final byte[] printablePdf;
        public final int patchedSignaturesCount;

        public StampResult(byte[] rectifiedPdf, byte[] printablePdf, int patchedSignaturesCount) {
            this.rectifiedPdf = rectifiedPdf;
            this.printablePdf = printablePdf;
            this.patchedSignaturesCount = patchedSignaturesCount;
        }
    }

    private PdfStamperEngine() { }

    /**
     * Rectifies all signature appearance streams in the PDF (replacing 'Signature Not Verified'
     * with 'Signature valid' and swapping /n1 question-mark XObjects with Acrobat-style green ticks)
     * and produces both a rectified signed PDF and a flattened printable PDF.
     */
    public static StampResult processPdf(byte[] inputPdf, String password) throws Exception {
        if (inputPdf == null || inputPdf.length == 0) {
            throw new IllegalArgumentException("Input PDF cannot be empty.");
        }

        // 1. Produce Rectified PDF
        byte[] rectifiedBytes;
        int patchedCount;
        try (PDDocument document = (password != null && !password.isEmpty())
                ? PDDocument.load(new ByteArrayInputStream(inputPdf), password)
                : PDDocument.load(new ByteArrayInputStream(inputPdf))) {

            document.setAllSecurityToBeRemoved(true);
            patchedCount = rectifyAppearanceStreams(document);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            rectifiedBytes = out.toByteArray();
        }

        // 2. Produce Flattened Printable PDF (baking appearance onto page content streams)
        byte[] printableBytes;
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(rectifiedBytes))) {
            document.setAllSecurityToBeRemoved(true);
            flattenSignatureAppearances(document);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            printableBytes = out.toByteArray();
        }

        return new StampResult(rectifiedBytes, printableBytes, patchedCount);
    }

    private static int rectifyAppearanceStreams(PDDocument document) {
        PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
        if (acroForm == null) return 0;

        int count = 0;
        Set<COSStream> visitedStreams = new HashSet<>();

        for (PDField field : acroForm.getFields()) {
            if (field instanceof PDSignatureField || "Sig".equals(field.getFieldType())) {
                for (PDAnnotationWidget widget : field.getWidgets()) {
                    if (patchWidgetAppearance(document, widget, visitedStreams)) {
                        count++;
                    }
                }
            }
        }

        // Also inspect page annotations directly in case signature fields were not in AcroForm fields list
        for (PDPage page : document.getPages()) {
            try {
                List<PDAnnotation> annotations = page.getAnnotations();
                if (annotations == null) continue;
                for (PDAnnotation annot : annotations) {
                    if (annot instanceof PDAnnotationWidget) {
                        PDAnnotationWidget widget = (PDAnnotationWidget) annot;
                        if (patchWidgetAppearance(document, widget, visitedStreams)) {
                            count++;
                        }
                    }
                }
            } catch (Exception ignored) { }
        }

        return count;
    }

    private static boolean patchWidgetAppearance(PDDocument document, PDAnnotationWidget widget, Set<COSStream> visited) {
        PDAppearanceDictionary appearance = widget.getAppearance();
        if (appearance == null) return false;

        PDAppearanceEntry normalEntry = appearance.getNormalAppearance();
        if (normalEntry == null) return false;

        boolean modified = false;
        if (normalEntry.isSubDictionary()) {
            java.util.Map<COSName, PDAppearanceStream> subDict = normalEntry.getSubDictionary();
            for (PDAppearanceStream stream : subDict.values()) {
                if (stream != null && patchAppearanceStreamRecursive(document, stream, visited)) {
                    modified = true;
                }
            }
        } else {
            PDAppearanceStream stream = normalEntry.getAppearanceStream();
            if (stream != null) {
                if (patchAppearanceStreamRecursive(document, stream, visited)) {
                    modified = true;
                }
            }
        }
        return modified;
    }

    private static boolean patchAppearanceStreamRecursive(PDDocument document, PDAppearanceStream stream, Set<COSStream> visited) {
        COSStream cosStream = stream.getCOSObject();
        if (visited.contains(cosStream)) return false;
        visited.add(cosStream);

        boolean changed = false;

        // 1. Text substitution in stream content
        try {
            byte[] rawBytes;
            try (InputStream in = stream.getContentStream().createInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] temp = new byte[4096];
                int read;
                while ((read = in.read(temp)) != -1) {
                    buffer.write(temp, 0, read);
                }
                rawBytes = buffer.toByteArray();
            }

            String content = new String(rawBytes, StandardCharsets.ISO_8859_1);
            String patched = replaceUnverifiedText(content);

            if (!content.equals(patched)) {
                try (OutputStream out = cosStream.createOutputStream()) {
                    out.write(patched.getBytes(StandardCharsets.ISO_8859_1));
                }
                changed = true;
            }
        } catch (Exception ignored) { }

        // 2. Resource XObject /n1 replacement
        PDResources resources = stream.getResources();
        if (resources != null) {
            COSDictionary resDict = resources.getCOSObject();
            COSBase xobjectsBase = resDict.getDictionaryObject(COSName.XOBJECT);
            if (xobjectsBase instanceof COSDictionary) {
                COSDictionary xobjects = (COSDictionary) xobjectsBase;

                // Replace /n1 icon XObject with green checkmark
                COSName n1Key = COSName.getPDFName("n1");
                if (xobjects.containsKey(n1Key)) {
                    try {
                        PDRectangle bbox = stream.getBBox();
                        float width = bbox != null ? Math.abs(bbox.getWidth()) : 100f;
                        float height = bbox != null ? Math.abs(bbox.getHeight()) : 50f;
                        PDFormXObject greenCheckmark = createGreenCheckmarkForm(document, width, height);
                        xobjects.setItem(n1Key, greenCheckmark.getCOSObject());
                        changed = true;
                    } catch (Exception ignored) { }
                }

                // Recursively inspect nested Form XObjects (e.g. /FRM)
                for (COSName key : xobjects.keySet()) {
                    COSBase item = xobjects.getDictionaryObject(key);
                    if (item instanceof COSStream) {
                        try {
                            PDFormXObject childForm = new PDFormXObject((COSStream) item);
                            PDResources childRes = childForm.getResources();
                            if (childRes != null) {
                                PDAppearanceStream childAppStream = new PDAppearanceStream((COSStream) item);
                                if (patchAppearanceStreamRecursive(document, childAppStream, visited)) {
                                    changed = true;
                                }
                            }
                        } catch (Exception ignored) { }
                    }
                }
            }
        }

        return changed;
    }

    private static String replaceUnverifiedText(String content) {
        // Regex patterns for variations of "Signature Not Verified" or "Signature invalid"
        String[] targets = {
                "(?i)\\(\\s*Signature\\s+Not\\s+Verified\\s*\\)",
                "(?i)\\(\\s*Signature\\s+not\\s+verified\\s*\\)",
                "(?i)\\(\\s*Signature\\s+invalid\\s*\\)",
                "(?i)\\(\\s*Signature\\s+is\\s+invalid\\s*\\)",
                "(?i)\\(\\s*Signature\\s+Not\\s+Valid\\s*\\)",
                "(?i)\\(\\s*Not\\s+Verified\\s*\\)"
        };

        String result = content;
        for (String pattern : targets) {
            result = result.replaceAll(pattern, "(Signature valid)");
        }

        // Multi-token patterns: (Signature) (Not) (Verified)
        Pattern multiToken = Pattern.compile("\\(\\s*Signature\\s*\\)[\\s\\r\\n]*\\(\\s*Not\\s*\\)[\\s\\r\\n]*\\(\\s*Verified\\s*\\)", Pattern.CASE_INSENSITIVE);
        result = multiToken.matcher(result).replaceAll("(Signature valid) () ()");

        Pattern twoToken = Pattern.compile("\\(\\s*Signature\\s+Not\\s*\\)[\\s\\r\\n]*\\(\\s*Verified\\s*\\)", Pattern.CASE_INSENSITIVE);
        result = twoToken.matcher(result).replaceAll("(Signature valid) ()");

        return result;
    }

    /**
     * Creates a PDFormXObject representing the verified green checkmark vector icon.
     */
    private static PDFormXObject createGreenCheckmarkForm(PDDocument document, float width, float height) throws Exception {
        PDFormXObject form = new PDFormXObject(document);
        form.setBBox(new PDRectangle(0, 0, width, height));

        // SVG Path: M 12 122 L 30 102 L 80 158 L 170 12 L 194 30 L 78 192 Z (original box: 200x200)
        // Scaled and centered to fit bounding box
        float scale = Math.min(width * 0.75f / 182f, height * 0.75f / 180f);
        if (scale <= 0) scale = 0.2f;

        float offsetX = (width - (182f * scale)) / 2f - (12f * scale);
        float offsetY = (height - (180f * scale)) / 2f + (12f * scale);

        // Vector path coordinates inverted for PDF coordinate space (Y-up)
        // In PDF: y_pdf = height - y_svg
        float p1x = offsetX + 12f * scale;
        float p1y = offsetY + (180f - 122f) * scale;
        float p2x = offsetX + 30f * scale;
        float p2y = offsetY + (180f - 102f) * scale;
        float p3x = offsetX + 80f * scale;
        float p3y = offsetY + (180f - 158f) * scale;
        float p4x = offsetX + 170f * scale;
        float p4y = offsetY + (180f - 12f) * scale;
        float p5x = offsetX + 194f * scale;
        float p5y = offsetY + (180f - 30f) * scale;
        float p6x = offsetX + 78f * scale;
        float p6y = offsetY + (180f - 192f) * scale;

        StringBuilder streamContent = new StringBuilder();
        streamContent.append("q\n");
        // Non-stroking color: Adobe Green RGB (0, 180, 60) -> (0, 0.706, 0.235)
        streamContent.append("0 0.706 0.235 rg\n");
        // Stroking color: Dark Green RGB (0, 140, 45)
        streamContent.append("0 0.549 0.176 RG\n");
        streamContent.append("1.5 w\n");
        streamContent.append(String.format(java.util.Locale.US, "%.2f %.2f m\n", p1x, p1y));
        streamContent.append(String.format(java.util.Locale.US, "%.2f %.2f l\n", p2x, p2y));
        streamContent.append(String.format(java.util.Locale.US, "%.2f %.2f l\n", p3x, p3y));
        streamContent.append(String.format(java.util.Locale.US, "%.2f %.2f l\n", p4x, p4y));
        streamContent.append(String.format(java.util.Locale.US, "%.2f %.2f l\n", p5x, p5y));
        streamContent.append(String.format(java.util.Locale.US, "%.2f %.2f l\n", p6x, p6y));
        streamContent.append("b\n"); // closepath, fill and stroke
        streamContent.append("Q\n");

        try (OutputStream out = form.getCOSObject().createOutputStream()) {
            out.write(streamContent.toString().getBytes(StandardCharsets.ISO_8859_1));
        }

        return form;
    }

    /**
     * Flattens all signature appearances directly into the page content streams,
     * removing AcroForm dictionaries and annotations for universal compatibility.
     */
    private static void flattenSignatureAppearances(PDDocument document) {
        for (PDPage page : document.getPages()) {
            try {
                List<PDAnnotation> annotations = page.getAnnotations();
                if (annotations == null || annotations.isEmpty()) continue;

                List<PDAnnotation> toRemove = new ArrayList<>();
                for (PDAnnotation annot : annotations) {
                    if (annot instanceof PDAnnotationWidget) {
                        PDAnnotationWidget widget = (PDAnnotationWidget) annot;
                        PDAppearanceDictionary appearance = widget.getAppearance();
                        if (appearance != null && appearance.getNormalAppearance() != null) {
                            PDAppearanceStream appStream = appearance.getNormalAppearance().getAppearanceStream();
                            if (appStream != null) {
                                PDRectangle rect = widget.getRectangle();
                                if (rect != null && rect.getWidth() > 0 && rect.getHeight() > 0) {
                                    // Burn the appearance Form XObject onto the page
                                    try (PDPageContentStream contentStream = new PDPageContentStream(
                                            document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                                        contentStream.saveGraphicsState();
                                        contentStream.transform(com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(rect.getLowerLeftX(), rect.getLowerLeftY()));
                                        contentStream.drawForm(appStream);
                                        contentStream.restoreGraphicsState();
                                    }
                                }
                            }
                        }
                        toRemove.add(widget);
                    }
                }

                annotations.removeAll(toRemove);
                page.setAnnotations(annotations);
            } catch (Exception ignored) { }
        }

        // Strip AcroForm from document catalog
        try {
            COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
            catalog.removeItem(COSName.ACRO_FORM);
        } catch (Exception ignored) { }
    }
}

