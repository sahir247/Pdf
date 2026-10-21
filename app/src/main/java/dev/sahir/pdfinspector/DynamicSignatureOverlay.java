package dev.sahir.pdfinspector;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Universal high-precision vector rendering engine for Adobe Acrobat DC signature appearances.
 *
 * In strict parity with Adobe Acrobat DC:
 * - NO artificial gray/blue card border or rounded rectangle.
 * - Fills background with clean white to seamlessly mask underlying unverified '?' and '(Signature Not Verified)'
 *   without altering or outlining the document canvas.
 * - Extracts stream geometry from PDF /FRM, /n1, /n2, /n4 when present, matching Adobe Acrobat DC
 *   to within 1 pixel on both compact stamps (e.g. Aadhaar 50x30 pt) and wide/tall banners (e.g. Income certificates 120x60 pt).
 * - Renders 'Signature valid' in black text matching Adobe Acrobat /n4 baseline and font size.
 * - Renders the Adobe Green checkmark (#00A128) with black drop shadow and outline,
 *   positioned and scaled exactly according to the /n1 transform matrix.
 * - Renders multi-line signer details in black regular text directly at their exact /n2 stream baselines.
 * - Graceful proportional fallback when appearance streams are not present.
 */
public final class DynamicSignatureOverlay {

    public static final int ADOBE_GREEN = Color.rgb(0, 161, 40); // #00A128 Adobe Acrobat green
    public static final int TEXT_BLACK = Color.BLACK;

    private DynamicSignatureOverlay() { }

    public static class AppearanceLayout {
        public float widgetWidth = -1f;
        public float widgetHeight = -1f;

        public float n1Scale = -1f;
        public float n1Tx = 0f;
        public float n1Ty = 0f;

        public float titleX = -1f;
        public float titleY = -1f; // baseline in PDF widget coordinates from bottom
        public float titleFontSize = -1f;

        public static class Line {
            public final String text;
            public final float x;
            public final float y; // baseline in PDF widget coordinates from bottom
            public final float fontSize;

            public Line(String text, float x, float y, float fontSize) {
                this.text = text;
                this.x = x;
                this.y = y;
                this.fontSize = fontSize;
            }
        }

        public final List<Line> lines = new ArrayList<>();

        public boolean hasExactPositions() {
            return !lines.isEmpty() && n1Scale > 0 && titleY >= 0;
        }

        public List<String> getPlainTextLines() {
            List<String> list = new ArrayList<>();
            for (Line l : lines) {
                list.add(l.text);
            }
            return list;
        }
    }

    public static void drawSignatureBadge(Canvas canvas, RectF bounds, String signerName, String signingDate, boolean isVerified) {
        drawSignatureBadge(canvas, bounds, signerName, signingDate, (AppearanceLayout) null, isVerified);
    }

    public static void drawSignatureBadge(Canvas canvas, RectF bounds, String signerName, String signingDate,
                                          List<String> detailLines, boolean isVerified) {
        drawSignatureBadgeFallback(canvas, bounds, signerName, signingDate, detailLines, isVerified);
    }

    public static void drawSignatureBadge(Canvas canvas, RectF bounds, String signerName, String signingDate,
                                          AppearanceLayout layout, boolean isVerified) {
        if (canvas == null || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return;

        if (!isVerified) {
            drawUnverifiedBadge(canvas, bounds);
            return;
        }

        if (layout != null && layout.hasExactPositions()) {
            drawExactStreamSignatureBadge(canvas, bounds, layout);
            return;
        }

        List<String> lines = layout != null ? layout.getPlainTextLines() : null;
        drawSignatureBadgeFallback(canvas, bounds, signerName, signingDate, lines, true);
    }

    private static void drawExactStreamSignatureBadge(Canvas canvas, RectF bounds, AppearanceLayout layout) {
        float widgetW = layout.widgetWidth > 0 ? layout.widgetWidth : bounds.width();
        float widgetH = layout.widgetHeight > 0 ? layout.widgetHeight : bounds.height();

        float scaleX = bounds.width() / widgetW;
        float scaleY = bounds.height() / widgetH;

        // 1. Clean white background covering the widget
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.WHITE);
        bgPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, bgPaint);

        // 2. Prepare "Signature valid" title paint and baseline coordinates
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(TEXT_BLACK);
        if (layout.titleFontSize >= 8.0f) {
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        } else {
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }
        float canvasTitleSize = (layout.titleFontSize > 0 ? layout.titleFontSize : 4.7f) * scaleY;
        titlePaint.setTextSize(canvasTitleSize);

        float titleX = bounds.left + (layout.titleX >= 0 ? layout.titleX : 2.0f) * scaleX;
        float titleY = bounds.bottom - (layout.titleY >= 0 ? layout.titleY : 23.3f) * scaleY;

        // 3. Draw Adobe green checkmark vector
        // In Adobe Acrobat DC, the checkmark top tip terminates precisely in the letter 'a' of "valid":
        float widthSigV = titlePaint.measureText("Signature v");
        float widthA = titlePaint.measureText("a");
        float tipX = titleX + widthSigV + 0.35f * widthA;
        float tipY = titleY - 0.45f * canvasTitleSize;

        float checkSize = (layout.n1Scale > 0 ? layout.n1Scale * (layout.titleFontSize >= 8.0f ? 72.0f : 44.0f) : canvasTitleSize * 2.55f) * scaleY;
        float checkScale = checkSize / 100.0f;

        float iconCx = tipX - 36.0f * checkScale;
        float iconCy = tipY + 50.0f * checkScale;

        drawAdobeCheckmark(canvas, iconCx, iconCy, checkSize);

        // 4. Draw "Signature valid" title on top
        canvas.drawText("Signature valid", titleX, titleY, titlePaint);

        // 5. Draw detail lines at exact /n2 baselines
        Paint detailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        detailPaint.setColor(TEXT_BLACK);
        detailPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

        for (AppearanceLayout.Line line : layout.lines) {
            float lineSize = line.fontSize * scaleY;
            detailPaint.setTextSize(lineSize);
            float lx = bounds.left + line.x * scaleX;
            float ly = bounds.bottom - line.y * scaleY;
            canvas.drawText(line.text, lx, ly, detailPaint);
        }
    }

    private static void drawSignatureBadgeFallback(Canvas canvas, RectF bounds, String signerName, String signingDate,
                                                  List<String> detailLines, boolean isVerified) {
        if (!isVerified) {
            drawUnverifiedBadge(canvas, bounds);
            return;
        }

        float width = bounds.width();
        float height = bounds.height();
        float aspectRatio = width / height;

        List<String> linesToDraw;
        if (detailLines != null && !detailLines.isEmpty()) {
            linesToDraw = detailLines;
        } else {
            linesToDraw = new ArrayList<>();
            String safeSigner = (signerName != null && !signerName.isEmpty()) ? signerName : "Signer";
            if (safeSigner.length() > 32) {
                int splitIdx = safeSigner.lastIndexOf(' ', 30);
                if (splitIdx > 10) {
                    linesToDraw.add("Digitally signed by " + safeSigner.substring(0, splitIdx));
                    linesToDraw.add(safeSigner.substring(splitIdx + 1));
                } else {
                    linesToDraw.add("Digitally signed by " + safeSigner.substring(0, 30));
                    linesToDraw.add(safeSigner.substring(30));
                }
            } else {
                linesToDraw.add("Digitally signed by " + safeSigner);
            }
            if (signingDate != null && !signingDate.isEmpty()) {
                linesToDraw.add("Date: " + signingDate);
            }
        }

        float titleSize = Math.max(2.5f, height * 0.155f);
        float detailSize = Math.max(2.0f, height * 0.112f);
        float lineSpacing = detailSize * 1.05f;

        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(TEXT_BLACK);
        titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        titlePaint.setTextSize(titleSize);

        Paint detailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        detailPaint.setColor(TEXT_BLACK);
        detailPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        detailPaint.setTextSize(detailSize);

        float textLeft = bounds.left + width * 0.04f;
        float maxTextWidth = titlePaint.measureText("Signature valid");
        for (String line : linesToDraw) {
            float lw = detailPaint.measureText(line);
            if (lw > maxTextWidth) maxTextWidth = lw;
        }

        // Clean white background covering the widget AND any text overflow
        float maxRight = Math.max(bounds.right, textLeft + maxTextWidth + width * 0.04f);
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.WHITE);
        bgPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(bounds.left, bounds.top, maxRight, bounds.bottom, bgPaint);

        // Proportional layout depending on aspect ratio
        if (aspectRatio < 1.2f) {
            // Square / compact signature: prominent checkmark centered
            float checkSize = Math.min(width, height) * 0.85f;
            drawAdobeCheckmark(canvas, bounds.centerX(), bounds.centerY(), checkSize);
        } else if (aspectRatio >= 2.5f) {
            // Wide banner signature: checkmark on left, text on right
            float checkSize = height * 0.82f;
            float iconCx = bounds.left + height * 0.5f;
            float iconCy = bounds.centerY();
            drawAdobeCheckmark(canvas, iconCx, iconCy, checkSize);

            float bannerTextLeft = bounds.left + height * 1.05f;
            drawSignatureTextInternal(canvas, bannerTextLeft, bounds.top, titlePaint, detailPaint, titleSize, lineSpacing, linesToDraw);
        } else {
            // Standard rectangular signature (Aadhaar, eSign)
            float widthSigV = titlePaint.measureText("Signature v");
            float widthA = titlePaint.measureText("a");
            float tipX = textLeft + widthSigV + 0.35f * widthA;
            float tipY = bounds.top + titleSize - 0.45f * titleSize;

            float checkSize = titleSize * 2.55f;
            float checkScale = checkSize / 100.0f;
            float iconCx = tipX - 36.0f * checkScale;
            float iconCy = tipY + 50.0f * checkScale;

            drawAdobeCheckmark(canvas, iconCx, iconCy, checkSize);
            drawSignatureTextInternal(canvas, textLeft, bounds.top, titlePaint, detailPaint, titleSize, lineSpacing, linesToDraw);
        }
    }

    /**
     * Draws the Adobe-standard verified checkmark vector with black drop shadow, outline, and green fill.
     */
    public static void drawAdobeCheckmark(Canvas canvas, float cx, float cy, float size) {
        if (size <= 0) size = 20f;
        float scale = size / 100.0f;

        float shadowOffsetX = 2.5f * scale;
        float shadowOffsetY = 3.8f * scale;
        float strokeWidth = Math.max(1.0f, 1.6f * scale);

        // Normalized Adobe 6-point checkmark path:
        // (0, 62), (8, 51), (38, 77), (86, 0), (100, 11), (36, 100)
        Path path = new Path();
        createCheckmarkPath(path, cx, cy, scale);

        // 1. Black Drop Shadow
        Path shadowPath = new Path();
        createCheckmarkPath(shadowPath, cx + shadowOffsetX, cy + shadowOffsetY, scale);
        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setColor(Color.BLACK);
        shadowPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(shadowPath, shadowPaint);

        // 2. Black Outer Border Outline
        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(Color.BLACK);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);
        strokePaint.setStrokeJoin(Paint.Join.MITER);
        strokePaint.setStrokeCap(Paint.Cap.SQUARE);
        canvas.drawPath(path, strokePaint);

        // 3. Vibrant Adobe Green Fill (#00A128)
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(ADOBE_GREEN);
        fillPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, fillPaint);
    }

    private static void createCheckmarkPath(Path path, float cx, float cy, float scale) {
        float[][] pts = {
                {0f, 62f},
                {8f, 51f},
                {38f, 77f},
                {86f, 0f},
                {100f, 11f},
                {36f, 100f}
        };

        path.reset();
        for (int i = 0; i < pts.length; i++) {
            float x = cx + (pts[i][0] - 50f) * scale;
            float y = cy + (pts[i][1] - 50f) * scale;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
    }

    private static void drawSignatureTextInternal(Canvas canvas, float left, float top,
                                                  Paint titlePaint, Paint detailPaint,
                                                  float titleSize, float lineSpacing,
                                                  List<String> linesToDraw) {
        // Line 0: "Signature valid" (Adobe Acrobat Title: Black, Bold)
        canvas.drawText("Signature valid", left, top + titleSize, titlePaint);

        // Details lines (Adobe Acrobat Text: Black, Regular, drawn cleanly over the checkmark)
        float currentY = top + titleSize + lineSpacing * 1.15f;
        for (String line : linesToDraw) {
            canvas.drawText(line, left, currentY, detailPaint);
            currentY += lineSpacing;
        }
    }

    private static void drawUnverifiedBadge(Canvas canvas, RectF bounds) {
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.WHITE);
        bgPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(bounds, bgPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.rgb(230, 81, 0)); // Amber/Orange
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(Math.max(10f, bounds.height() * 0.4f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("?", bounds.centerX(), bounds.centerY() + textPaint.getTextSize() * 0.35f, textPaint);
    }

    /**
     * Extracts existing multi-line signature appearance text from /n2 sub-form XObject if present.
     */
    public static List<String> extractAppearanceTextLines(PDAnnotationWidget widget) {
        AppearanceLayout layout = extractAppearanceLayout(widget);
        if (layout != null && !layout.lines.isEmpty()) {
            return layout.getPlainTextLines();
        }
        return null;
    }

    /**
     * Extracts the exact appearance stream layout from /FRM, /n1, /n2, /n4 XObjects.
     */
    public static AppearanceLayout extractAppearanceLayout(PDAnnotationWidget widget) {
        if (widget == null) return null;
        try {
            PDAppearanceDictionary ap = widget.getAppearance();
            if (ap == null) return null;
            PDAppearanceEntry norm = ap.getNormalAppearance();
            if (norm == null || !norm.isStream()) return null;
            PDAppearanceStream stream = norm.getAppearanceStream();
            if (stream == null || stream.getResources() == null) return null;

            PDResources res = stream.getResources();
            PDXObject frm = res.getXObject(COSName.getPDFName("FRM"));
            if (frm instanceof PDFormXObject) {
                PDFormXObject frmForm = (PDFormXObject) frm;
                AppearanceLayout layout = new AppearanceLayout();

                if (widget.getRectangle() != null) {
                    layout.widgetWidth = widget.getRectangle().getWidth();
                    layout.widgetHeight = widget.getRectangle().getHeight();
                } else if (frmForm.getBBox() != null) {
                    layout.widgetWidth = frmForm.getBBox().getWidth();
                    layout.widgetHeight = frmForm.getBBox().getHeight();
                }

                // 1. Read /FRM stream for /n1 matrix: e.g. "q 0.54 0 0 0.54 33 3 cm /n1 Do Q"
                try (InputStream is = frmForm.getContentStream().createInputStream()) {
                    byte[] bytes = new byte[is.available()];
                    is.read(bytes);
                    String frmStream = new String(bytes, StandardCharsets.ISO_8859_1);
                    Matcher m = Pattern.compile("([-\\d.]+)\\s+([-\\d.]+)\\s+([-\\d.]+)\\s+([-\\d.]+)\\s+([-\\d.]+)\\s+([-\\d.]+)\\s+cm\\s+/n1\\s+Do").matcher(frmStream);
                    if (m.find()) {
                        layout.n1Scale = Float.parseFloat(m.group(1));
                        layout.n1Tx = Float.parseFloat(m.group(5));
                        layout.n1Ty = Float.parseFloat(m.group(6));
                    }
                }

                PDResources frmRes = frmForm.getResources();
                if (frmRes != null) {
                    // 2. Read /n4 for title baseline and font size
                    PDXObject n4 = frmRes.getXObject(COSName.getPDFName("n4"));
                    if (n4 instanceof PDFormXObject) {
                        try (InputStream is = ((PDFormXObject) n4).getContentStream().createInputStream()) {
                            byte[] bytes = new byte[is.available()];
                            is.read(bytes);
                            String s = new String(bytes, StandardCharsets.ISO_8859_1);
                            Matcher mf = Pattern.compile("/F\\w+\\s+([\\d.]+)\\s+Tf").matcher(s);
                            if (mf.find()) {
                                layout.titleFontSize = Float.parseFloat(mf.group(1));
                            }
                            Matcher mTm = Pattern.compile("1\\s+0\\s+0\\s+1\\s+([-\\d.]+)\\s+([-\\d.]+)\\s+Tm").matcher(s);
                            if (mTm.find()) {
                                layout.titleX = Float.parseFloat(mTm.group(1));
                                layout.titleY = Float.parseFloat(mTm.group(2));
                            }
                        }
                    }

                    // 3. Read /n2 for detail lines, baselines, and font size
                    PDXObject n2 = frmRes.getXObject(COSName.getPDFName("n2"));
                    if (n2 instanceof PDFormXObject) {
                        try (InputStream is = ((PDFormXObject) n2).getContentStream().createInputStream()) {
                            byte[] bytes = new byte[is.available()];
                            is.read(bytes);
                            String s = new String(bytes, StandardCharsets.ISO_8859_1);
                            float defFontSize = 3.4f;
                            Matcher mf = Pattern.compile("/F\\w+\\s+([\\d.]+)\\s+Tf").matcher(s);
                            if (mf.find()) {
                                defFontSize = Float.parseFloat(mf.group(1));
                            }

                            Matcher mLine = Pattern.compile("1\\s+0\\s+0\\s+1\\s+([-\\d.]+)\\s+([-\\d.]+)\\s+Tm\\s*(?:/[^\\s]+\\s+([\\d.]+)\\s+Tf\\s*)?\\((.*?)\\)Tj").matcher(s);
                            while (mLine.find()) {
                                float lx = Float.parseFloat(mLine.group(1));
                                float ly = Float.parseFloat(mLine.group(2));
                                float lSize = mLine.group(3) != null ? Float.parseFloat(mLine.group(3)) : defFontSize;
                                String text = mLine.group(4).trim();
                                if (!text.isEmpty()) {
                                    layout.lines.add(new AppearanceLayout.Line(text, lx, ly, lSize));
                                }
                            }
                        }
                    }
                }

                if (layout.hasExactPositions()) {
                    return layout;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
