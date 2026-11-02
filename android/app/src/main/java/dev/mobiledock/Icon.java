package dev.mobiledock;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Vector icons drawn in code. Sharp at any size, no image files, and they work on Android 4.1.
 * Coordinates are fractions of the icon box, so 0.5 is the center.
 */
final class Icon extends Drawable {
    enum Kind {
        PLAY, PAUSE, PREV, NEXT, SHUFFLE, REPEAT, REPEAT_ONE, VOL_DOWN, VOL_UP, MUTE,
        APPS, MUSIC, TOUCHPAD, KEYBOARD, CAMERA, RECORD, STOP, POWER, BACK, PLUS, REFRESH,
        SHORTCUTS, CHART, SEND, FOLLOW,
        SLIDERS, SUN, MONITOR, LOCK, MOON, PEN, PICTURE, FILE, SCISSORS, MOVE, EXPAND, SHRINK, CLIPBOARD
    }

    private final Kind kind;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float s, ox, oy;

    Icon(Kind kind, int color) {
        this.kind = kind;
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        line.setColor(color);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    void setColor(int color) {
        fill.setColor(color);
        line.setColor(color);
        invalidateSelf();
    }

    private float x(float f) {
        return ox + f * s;
    }

    private float y(float f) {
        return oy + f * s;
    }

    private Path poly(float... pts) {
        Path p = new Path();
        p.moveTo(x(pts[0]), y(pts[1]));
        for (int i = 2; i < pts.length; i += 2) p.lineTo(x(pts[i]), y(pts[i + 1]));
        p.close();
        return p;
    }

    private void seg(Canvas c, float x1, float y1, float x2, float y2) {
        c.drawLine(x(x1), y(y1), x(x2), y(y2), line);
    }

    private RectF box(float l, float t, float r, float b) {
        return new RectF(x(l), y(t), x(r), y(b));
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        s = Math.min(b.width(), b.height());
        ox = b.left + (b.width() - s) / 2f;
        oy = b.top + (b.height() - s) / 2f;
        line.setStrokeWidth(Math.max(2f, s * 0.085f));
        float r = s * 0.06f;
        switch (kind) {
            case PLAY:
                c.drawPath(poly(0.32f, 0.2f, 0.32f, 0.8f, 0.8f, 0.5f), fill);
                break;
            case PAUSE:
                c.drawRoundRect(box(0.27f, 0.2f, 0.43f, 0.8f), r, r, fill);
                c.drawRoundRect(box(0.57f, 0.2f, 0.73f, 0.8f), r, r, fill);
                break;
            case PREV:
                c.drawRoundRect(box(0.2f, 0.24f, 0.3f, 0.76f), r, r, fill);
                c.drawPath(poly(0.8f, 0.24f, 0.8f, 0.76f, 0.33f, 0.5f), fill);
                break;
            case NEXT:
                c.drawRoundRect(box(0.7f, 0.24f, 0.8f, 0.76f), r, r, fill);
                c.drawPath(poly(0.2f, 0.24f, 0.2f, 0.76f, 0.67f, 0.5f), fill);
                break;
            case SHUFFLE:
                seg(c, 0.18f, 0.32f, 0.38f, 0.32f);
                seg(c, 0.38f, 0.32f, 0.62f, 0.68f);
                seg(c, 0.62f, 0.68f, 0.78f, 0.68f);
                seg(c, 0.18f, 0.68f, 0.38f, 0.68f);
                seg(c, 0.38f, 0.68f, 0.62f, 0.32f);
                seg(c, 0.62f, 0.32f, 0.78f, 0.32f);
                c.drawPath(poly(0.86f, 0.32f, 0.72f, 0.22f, 0.72f, 0.42f), fill);
                c.drawPath(poly(0.86f, 0.68f, 0.72f, 0.58f, 0.72f, 0.78f), fill);
                break;
            case REPEAT:
            case REPEAT_ONE:
                c.drawRoundRect(box(0.2f, 0.32f, 0.8f, 0.7f), s * 0.12f, s * 0.12f, line);
                c.drawPath(poly(0.62f, 0.2f, 0.62f, 0.44f, 0.78f, 0.32f), fill);
                if (kind == Kind.REPEAT_ONE) seg(c, 0.5f, 0.42f, 0.5f, 0.6f);
                break;
            case VOL_DOWN:
            case VOL_UP:
            case MUTE:
                c.drawPath(poly(0.14f, 0.4f, 0.28f, 0.4f, 0.48f, 0.22f, 0.48f, 0.78f, 0.28f, 0.6f, 0.14f, 0.6f), fill);
                if (kind == Kind.MUTE) {
                    seg(c, 0.62f, 0.38f, 0.84f, 0.62f);
                    seg(c, 0.84f, 0.38f, 0.62f, 0.62f);
                } else {
                    seg(c, 0.6f, 0.5f, 0.86f, 0.5f);
                    if (kind == Kind.VOL_UP) seg(c, 0.73f, 0.37f, 0.73f, 0.63f);
                }
                break;
            case APPS:
                for (int i = 0; i < 4; i++) {
                    float l = 0.2f + (i % 2) * 0.34f, t = 0.2f + (i / 2) * 0.34f;
                    c.drawRoundRect(box(l, t, l + 0.26f, t + 0.26f), r, r, fill);
                }
                break;
            case MUSIC:
                c.drawCircle(x(0.36f), y(0.7f), s * 0.12f, fill);
                c.drawCircle(x(0.72f), y(0.62f), s * 0.12f, fill);
                seg(c, 0.46f, 0.7f, 0.46f, 0.24f);
                seg(c, 0.82f, 0.62f, 0.82f, 0.18f);
                seg(c, 0.46f, 0.24f, 0.82f, 0.18f);
                break;
            case TOUCHPAD:
                c.drawRoundRect(box(0.16f, 0.2f, 0.84f, 0.8f), s * 0.1f, s * 0.1f, line);
                seg(c, 0.16f, 0.62f, 0.84f, 0.62f);
                seg(c, 0.5f, 0.62f, 0.5f, 0.8f);
                break;
            case KEYBOARD:
                c.drawRoundRect(box(0.12f, 0.28f, 0.88f, 0.74f), s * 0.08f, s * 0.08f, line);
                for (int i = 0; i < 4; i++) c.drawCircle(x(0.28f + i * 0.15f), y(0.44f), s * 0.035f, fill);
                seg(c, 0.32f, 0.6f, 0.68f, 0.6f);
                break;
            case CAMERA:
                c.drawRoundRect(box(0.14f, 0.3f, 0.86f, 0.78f), s * 0.1f, s * 0.1f, line);
                c.drawRoundRect(box(0.36f, 0.2f, 0.64f, 0.32f), r, r, fill);
                c.drawCircle(x(0.5f), y(0.54f), s * 0.13f, line);
                break;
            case RECORD:
                c.drawCircle(x(0.5f), y(0.5f), s * 0.3f, line);
                c.drawCircle(x(0.5f), y(0.5f), s * 0.17f, fill);
                break;
            case STOP:
                c.drawRoundRect(box(0.27f, 0.27f, 0.73f, 0.73f), r, r, fill);
                break;
            case POWER:
                c.drawArc(box(0.22f, 0.24f, 0.78f, 0.8f), -60, 300, false, line);
                seg(c, 0.5f, 0.16f, 0.5f, 0.48f);
                break;
            case BACK:
                seg(c, 0.62f, 0.2f, 0.32f, 0.5f);
                seg(c, 0.32f, 0.5f, 0.62f, 0.8f);
                break;
            case PLUS:
                seg(c, 0.5f, 0.2f, 0.5f, 0.8f);
                seg(c, 0.2f, 0.5f, 0.8f, 0.5f);
                break;
            case SHORTCUTS:
                for (int i = 0; i < 9; i++) {
                    float l = 0.16f + (i % 3) * 0.24f, t = 0.16f + (i / 3) * 0.24f;
                    c.drawRoundRect(box(l, t, l + 0.18f, t + 0.18f), r * 0.6f, r * 0.6f, fill);
                }
                break;
            case CHART:
                seg(c, 0.16f, 0.82f, 0.86f, 0.82f);
                Path p = new Path();
                p.moveTo(x(0.18f), y(0.66f));
                p.lineTo(x(0.38f), y(0.46f));
                p.lineTo(x(0.54f), y(0.58f));
                p.lineTo(x(0.82f), y(0.24f));
                c.drawPath(p, line);
                break;
            case SEND:
                seg(c, 0.5f, 0.66f, 0.5f, 0.18f);
                seg(c, 0.32f, 0.36f, 0.5f, 0.18f);
                seg(c, 0.68f, 0.36f, 0.5f, 0.18f);
                seg(c, 0.2f, 0.62f, 0.2f, 0.82f);
                seg(c, 0.2f, 0.82f, 0.8f, 0.82f);
                seg(c, 0.8f, 0.82f, 0.8f, 0.62f);
                break;
            case FOLLOW:
                c.drawCircle(x(0.5f), y(0.5f), s * 0.3f, line);
                c.drawCircle(x(0.5f), y(0.5f), s * 0.1f, fill);
                break;
            case SLIDERS:
                for (int i = 0; i < 3; i++) {
                    float xx = 0.26f + i * 0.24f, knob = new float[] {0.36f, 0.62f, 0.44f}[i];
                    seg(c, xx, 0.18f, xx, 0.82f);
                    seg(c, xx - 0.08f, knob, xx + 0.08f, knob);
                }
                break;
            case SUN:
                c.drawCircle(x(0.5f), y(0.5f), s * 0.14f, line);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    float cx = (float) Math.cos(a), cy = (float) Math.sin(a);
                    seg(c, 0.5f + cx * 0.26f, 0.5f + cy * 0.26f, 0.5f + cx * 0.36f, 0.5f + cy * 0.36f);
                }
                break;
            case MONITOR:
                c.drawRoundRect(box(0.14f, 0.2f, 0.86f, 0.66f), s * 0.07f, s * 0.07f, line);
                seg(c, 0.5f, 0.66f, 0.5f, 0.8f);
                seg(c, 0.34f, 0.82f, 0.66f, 0.82f);
                break;
            case LOCK:
                c.drawRoundRect(box(0.24f, 0.46f, 0.76f, 0.84f), s * 0.07f, s * 0.07f, line);
                c.drawArc(box(0.34f, 0.18f, 0.66f, 0.56f), 180, 180, false, line);
                break;
            case MOON:
                c.drawArc(box(0.2f, 0.18f, 0.8f, 0.82f), 60, 260, false, line);
                c.drawArc(box(0.42f, 0.16f, 0.86f, 0.62f), 120, 150, false, line);
                break;
            case PEN:
                seg(c, 0.16f, 0.74f, 0.32f, 0.58f);
                seg(c, 0.32f, 0.58f, 0.46f, 0.7f);
                seg(c, 0.46f, 0.7f, 0.62f, 0.5f);
                seg(c, 0.62f, 0.5f, 0.84f, 0.62f);
                seg(c, 0.2f, 0.84f, 0.84f, 0.84f);
                break;
            case PICTURE:
                c.drawRoundRect(box(0.14f, 0.22f, 0.86f, 0.78f), s * 0.07f, s * 0.07f, line);
                c.drawPath(poly(0.2f, 0.72f, 0.42f, 0.46f, 0.56f, 0.6f, 0.66f, 0.5f, 0.8f, 0.72f), fill);
                c.drawCircle(x(0.68f), y(0.36f), s * 0.05f, fill);
                break;
            case FILE:
                c.drawPath(poly(0.26f, 0.14f, 0.6f, 0.14f, 0.76f, 0.3f, 0.76f, 0.86f, 0.26f, 0.86f), line);
                seg(c, 0.36f, 0.5f, 0.66f, 0.5f);
                seg(c, 0.36f, 0.66f, 0.66f, 0.66f);
                break;
            case SCISSORS:
                c.drawCircle(x(0.3f), y(0.7f), s * 0.1f, line);
                c.drawCircle(x(0.7f), y(0.7f), s * 0.1f, line);
                seg(c, 0.36f, 0.62f, 0.72f, 0.16f);
                seg(c, 0.64f, 0.62f, 0.28f, 0.16f);
                break;
            case MOVE:
                seg(c, 0.5f, 0.14f, 0.5f, 0.86f);
                seg(c, 0.14f, 0.5f, 0.86f, 0.5f);
                c.drawPath(poly(0.5f, 0.1f, 0.4f, 0.24f, 0.6f, 0.24f), fill);
                c.drawPath(poly(0.5f, 0.9f, 0.4f, 0.76f, 0.6f, 0.76f), fill);
                c.drawPath(poly(0.1f, 0.5f, 0.24f, 0.4f, 0.24f, 0.6f), fill);
                c.drawPath(poly(0.9f, 0.5f, 0.76f, 0.4f, 0.76f, 0.6f), fill);
                break;
            case EXPAND:
                seg(c, 0.16f, 0.36f, 0.16f, 0.16f);
                seg(c, 0.16f, 0.16f, 0.36f, 0.16f);
                seg(c, 0.64f, 0.16f, 0.84f, 0.16f);
                seg(c, 0.84f, 0.16f, 0.84f, 0.36f);
                seg(c, 0.84f, 0.64f, 0.84f, 0.84f);
                seg(c, 0.84f, 0.84f, 0.64f, 0.84f);
                seg(c, 0.36f, 0.84f, 0.16f, 0.84f);
                seg(c, 0.16f, 0.84f, 0.16f, 0.64f);
                break;
            case SHRINK:
                seg(c, 0.16f, 0.36f, 0.36f, 0.36f);
                seg(c, 0.36f, 0.36f, 0.36f, 0.16f);
                seg(c, 0.64f, 0.16f, 0.64f, 0.36f);
                seg(c, 0.64f, 0.36f, 0.84f, 0.36f);
                seg(c, 0.84f, 0.64f, 0.64f, 0.64f);
                seg(c, 0.64f, 0.64f, 0.64f, 0.84f);
                seg(c, 0.36f, 0.84f, 0.36f, 0.64f);
                seg(c, 0.36f, 0.64f, 0.16f, 0.64f);
                break;
            case CLIPBOARD:
                c.drawRoundRect(box(0.22f, 0.18f, 0.78f, 0.86f), s * 0.06f, s * 0.06f, line);
                c.drawRoundRect(box(0.38f, 0.12f, 0.62f, 0.26f), s * 0.04f, s * 0.04f, fill);
                seg(c, 0.34f, 0.46f, 0.66f, 0.46f);
                seg(c, 0.34f, 0.62f, 0.58f, 0.62f);
                break;
            case REFRESH:
            default:
                c.drawArc(box(0.22f, 0.22f, 0.78f, 0.78f), 30, 290, false, line);
                c.drawPath(poly(0.86f, 0.3f, 0.66f, 0.3f, 0.8f, 0.48f), fill);
                break;
        }
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        line.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        line.setColorFilter(cf);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
