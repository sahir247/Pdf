package dev.sahir.pdfinspector;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * High-precision vector rendering engine for Adobe Acrobat DC signature appearances.
 *
 * In accordance with ISO 32000-1, ETSI EN 319 142 (PAdES), and Adobe DigSig architecture:
 * - Adobe Acrobat NEVER modifies the signed PDF byte stream on disk to show a green checkmark.
 * - Digital signatures lock the /ByteRange of the document; altering file bytes breaks SHA-256 integrity.
 * - When Acrobat validates a signature in-memory against trusted roots (AATL/CCA India), it
 *   suppresses the unverified question-mark XObject (/n1) and renders this dynamic green
 *   checkmark overlay directly into the display framebuffer and printer spooler canvas.
 * - This engine renders the verified checkmark with Adobe-standard geometry: bold black stroke,
 *   3D drop shadow, and vibrant Adobe Green fill (#00B43C).
 */
public final class DynamicSignatureOverlay {

    public static final int ADOBE_GREEN = Color.rgb(0, 180, 60); // #00B43C
    public static final int ADOBE_GREEN_TEXT = Color.rgb(0, 130, 40);
    public static final int TEXT_DARK = Color.rgb(38, 50, 56);
    public static final int TEXT_MUTED = Color.rgb(84, 110, 122);
    public static final int BORDER_COLOR = Color.rgb(207, 216, 220);

    private DynamicSignatureOverlay() { }

    /**
     * Dynamically paints the verified signature badge over the specified widget coordinates.
     *
     * @param canvas Target Android Canvas (display Bitmap or PrintDocument canvas)
     * @param bounds Bounding box of the signature widget in canvas pixels
     * @param signerName Common Name or Subject of the signer
     * @param signingDate Claimed or timestamped signing date
     * @param isVerified Whether cryptographic verification and CA path validation succeeded
     */
    public static void drawSignatureBadge(Canvas canvas, RectF bounds, String signerName, String signingDate, boolean isVerified) {
        if (canvas == null || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return;

        float width = bounds.width();
        float height = bounds.height();
        float aspectRatio = width / height;

        if (!isVerified) {
            // Draw unverified / question mark state if unverified
            drawUnverifiedBadge(canvas, bounds);
            return;
        }

        // Draw opaque background to cleanly cover underlying unverified /n1 question mark and /n4
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.WHITE);
        bgPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(bounds, Math.min(width, height) * 0.05f, Math.min(width, height) * 0.05f, bgPaint);

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(BORDER_COLOR);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1f, height * 0.02f));
        canvas.drawRoundRect(bounds, Math.min(width, height) * 0.05f, Math.min(width, height) * 0.05f, borderPaint);

        if (aspectRatio <= 1.35f) {
            // Square or icon-only widget: fill with prominent checkmark
            RectF iconBounds = new RectF(
                    bounds.left + width * 0.06f,
                    bounds.top + height * 0.06f,
                    bounds.right - width * 0.06f,
                    bounds.bottom - height * 0.06f
            );
            drawAdobeCheckmark(canvas, iconBounds);
        } else {
            // Horizontal compound widget: icon on left, status + signer details on right
            float iconBoxWidth = Math.min(width * 0.32f, height);
            RectF iconBounds = new RectF(
                    bounds.left + width * 0.02f,
                    bounds.top + height * 0.05f,
                    bounds.left + iconBoxWidth - width * 0.02f,
                    bounds.bottom - height * 0.05f
            );
            drawAdobeCheckmark(canvas, iconBounds);

            float textLeft = bounds.left + iconBoxWidth + width * 0.015f;
            float textRight = bounds.right - width * 0.02f;
            float textTop = bounds.top + height * 0.12f;
            float textHeight = bounds.height() * 0.76f;

            drawSignatureDetailsText(canvas, textLeft, textTop, textRight - textLeft, textHeight, signerName, signingDate);
        }
    }

    /**
     * Draws the Adobe-standard verified checkmark vector with black outline, drop shadow, and green fill.
     */
    public static void drawAdobeCheckmark(Canvas canvas, RectF iconRect) {
        float width = iconRect.width();
        float height = iconRect.height();
        // Path box is 182x180. Scale to fit comfortably within the iconRect.
        float scale = Math.min(width * 0.88f / 182f, height * 0.88f / 180f);
        if (scale <= 0) scale = 0.2f;

        float cx = iconRect.centerX();
        float cy = iconRect.centerY();

        float strokeWidth = Math.max(1.8f, 18f * scale);
        float shadowOffsetX = 3.5f * scale;
        float shadowOffsetY = 5.5f * scale;

        Path path = new Path();
        createCheckmarkPath(path, cx, cy, scale);

        // 1. Black Drop Shadow
        Path shadowPath = new Path();
        createCheckmarkPath(shadowPath, cx + shadowOffsetX, cy + shadowOffsetY, scale);
        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setColor(Color.BLACK);
        shadowPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        shadowPaint.setStrokeWidth(strokeWidth);
        shadowPaint.setStrokeJoin(Paint.Join.ROUND);
        shadowPaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPath(shadowPath, shadowPaint);

        // 2. Black Outer Border Stroke
        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(Color.BLACK);
        strokePaint.setStyle(Paint.Style.FILL_AND_STROKE);
        strokePaint.setStrokeWidth(strokeWidth);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPath(path, strokePaint);

        // 3. Vibrant Adobe Green Fill
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(ADOBE_GREEN);
        fillPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, fillPaint);
    }

    private static void createCheckmarkPath(Path path, float cx, float cy, float scale) {
        // SVG Vector: M 12 122 L 30 102 L 80 158 L 170 12 L 194 30 L 78 192 Z (Center: 103, 102)
        float p1x = cx + (12f - 103f) * scale;
        float p1y = cy + (122f - 102f) * scale;

        float p2x = cx + (30f - 103f) * scale;
        float p2y = cy + (102f - 102f) * scale;

        float p3x = cx + (80f - 103f) * scale;
        float p3y = cy + (158f - 102f) * scale;

        float p4x = cx + (170f - 103f) * scale;
        float p4y = cy + (12f - 102f) * scale;

        float p5x = cx + (194f - 103f) * scale;
        float p5y = cy + (30f - 102f) * scale;

        float p6x = cx + (78f - 103f) * scale;
        float p6y = cy + (192f - 102f) * scale;

        path.reset();
        path.moveTo(p1x, p1y);
        path.lineTo(p2x, p2y);
        path.lineTo(p3x, p3y);
        path.lineTo(p4x, p4y);
        path.lineTo(p5x, p5y);
        path.lineTo(p6x, p6y);
        path.close();
    }

    private static void drawSignatureDetailsText(Canvas canvas, float left, float top, float maxWidth, float maxHeight,
                                                 String signerName, String signingDate) {
        // Line 1: 'Signature valid'
        float titleSize = Math.max(9f, maxHeight * 0.28f);
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(ADOBE_GREEN_TEXT);
        titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        titlePaint.setTextSize(titleSize);
        canvas.drawText("Signature valid", left, top + titleSize, titlePaint);

        // Details lines
        float detailSize = Math.max(7f, maxHeight * 0.18f);
        Paint detailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        detailPaint.setColor(TEXT_DARK);
        detailPaint.setTextSize(detailSize);

        float currentY = top + titleSize + detailSize * 1.35f;

        String safeSigner = (signerName != null && !signerName.isEmpty()) ? signerName : "Signer";
        if (safeSigner.length() > 36) {
            safeSigner = safeSigner.substring(0, 34) + "...";
        }
        canvas.drawText("Digitally signed by " + safeSigner, left, currentY, detailPaint);

        if (signingDate != null && !signingDate.isEmpty()) {
            currentY += detailSize * 1.35f;
            Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            datePaint.setColor(TEXT_MUTED);
            datePaint.setTextSize(detailSize);
            canvas.drawText("Date: " + signingDate, left, currentY, datePaint);
        }
    }

    private static void drawUnverifiedBadge(Canvas canvas, RectF bounds) {
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.rgb(255, 248, 225)); // Light amber
        bgPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(bounds, 4, 4, bgPaint);

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(Color.rgb(255, 179, 0)); // Amber
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f);
        canvas.drawRoundRect(bounds, 4, 4, borderPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.rgb(230, 81, 0));
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(Math.max(10f, bounds.height() * 0.4f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("?", bounds.centerX(), bounds.centerY() + textPaint.getTextSize() * 0.35f, textPaint);
    }
}
