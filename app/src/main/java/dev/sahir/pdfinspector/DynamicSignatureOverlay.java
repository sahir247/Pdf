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
 * - Renders 'Signature valid' in black bold text (matching Adobe Acrobat /n4 replacement).
 * - Renders the Adobe Green checkmark (#00A128) with black drop shadow and outline,
 *   centered gracefully behind the text lines.
 * - Renders multi-line signer details in black regular text directly on top of the checkmark
 *   with full readability.
 */
public final class DynamicSignatureOverlay {

    public static final int ADOBE_GREEN = Color.rgb(0, 161, 40); // #00A128 Adobe Acrobat green
    public static final int TEXT_BLACK = Color.BLACK;

    private DynamicSignatureOverlay() { }

    public static void drawSignatureBadge(Canvas canvas, RectF bounds, String signerName, String signingDate, boolean isVerified) {
        drawSignatureBadge(canvas, bounds, signerName, signingDate, null, isVerified);
    }

    public static void drawSignatureBadge(Canvas canvas, RectF bounds, String signerName, String signingDate,
                                          List<String> detailLines, boolean isVerified) {
        if (canvas == null || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return;

        float width = bounds.width();
        float height = bounds.height();
        float aspectRatio = width / height;

        if (!isVerified) {
            drawUnverifiedBadge(canvas, bounds);
            return;
        }

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

        // Adobe Acrobat DC typography proportions:
        // /n4 title: 4.7 pt on a 30 pt widget = 0.1566
        // /n2 details: 3.4 pt on a 30 pt widget = 0.1133
        // Line spacing: 3.4 pt on a 30 pt widget = 0.1133
        // Removed high Math.max(8f/6f) floors which distorted 72 DPI print adapter canvas
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

        // 1. Clean white background covering the widget AND any text overflow
        float maxRight = Math.max(bounds.right, textLeft + maxTextWidth + width * 0.04f);
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.WHITE);
        bgPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(bounds.left, bounds.top, maxRight, bounds.bottom, bgPaint);

        // 2. Proportional layout depending on aspect ratio
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
            // Standard rectangular signature (Aadhaar 50x30 pt, eSign, etc.):
            // In Adobe Acrobat DC parity:
            // Checkmark center X is placed at 51.4% of the text block width!
            // Checkmark center Y is at 50.5% of the text block height!
            // Checkmark height is 77% of the text block height!
            float checkSize = height * 0.77f;
            float iconCx = textLeft + maxTextWidth * 0.514f;
            float iconCy = bounds.top + height * 0.505f;

            // Draw checkmark FIRST
            drawAdobeCheckmark(canvas, iconCx, iconCy, checkSize);

            // Draw text ON TOP of checkmark
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
                PDResources frmRes = ((PDFormXObject) frm).getResources();
                if (frmRes != null) {
                    PDXObject n2 = frmRes.getXObject(COSName.getPDFName("n2"));
                    if (n2 instanceof PDFormXObject) {
                        try (InputStream is = ((PDFormXObject) n2).getContentStream().createInputStream()) {
                            byte[] bytes = new byte[is.available()];
                            is.read(bytes);
                            String s = new String(bytes, StandardCharsets.ISO_8859_1);
                            Matcher m = Pattern.compile("\\((.*?)\\)Tj").matcher(s);
                            List<String> lines = new ArrayList<>();
                            while (m.find()) {
                                String line = m.group(1).trim();
                                if (!line.isEmpty()) {
                                    lines.add(line);
                                }
                            }
                            if (!lines.isEmpty()) {
                                return lines;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
