package dev.mobiledock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Draw a signature or a quick sketch with your finger, then paste it on the PC with Ctrl+V. */
final class SignPanel extends LinearLayout {
    interface Sender {
        void sendPng(byte[] png);
    }

    /** The drawing surface. Ink is dark on a light card, like paper. */
    private static final class Pad extends View {
        final List<Path> strokes = new ArrayList<Path>();
        final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint paper = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path current;

        Pad(Context c) {
            super(c);
            ink.setColor(0xFF111827);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(Ui.dp(c, 3.5f));
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            paper.setColor(0xFFF8FAFC);
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = Ui.dp(getContext(), 18);
            c.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), r, r, paper);
            for (Path p : strokes) c.drawPath(p, ink);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    current = new Path();
                    current.moveTo(x, y);
                    current.lineTo(x + 0.1f, y);
                    strokes.add(current);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (current != null) {
                        for (int h = 0; h < e.getHistorySize(); h++) current.lineTo(e.getHistoricalX(h), e.getHistoricalY(h));
                        current.lineTo(x, y);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    current = null;
                    performClick();
                    break;
                default:
                    current = null;
                    break;
            }
            invalidate();
            return true;
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        /** PNG with a transparent background, cropped to the ink. Null when nothing is drawn. */
        byte[] export() {
            if (strokes.isEmpty()) return null;
            RectF bounds = new RectF();
            RectF one = new RectF();
            boolean first = true;
            for (Path p : strokes) {
                p.computeBounds(one, true);
                if (first) bounds.set(one);
                else bounds.union(one);
                first = false;
            }
            float pad = ink.getStrokeWidth() * 2;
            bounds.inset(-pad, -pad);
            int w = Math.max(1, (int) bounds.width()), h = Math.max(1, (int) bounds.height());
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.translate(-bounds.left, -bounds.top);
            for (Path p : strokes) c.drawPath(p, ink);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            bmp.recycle();
            return out.toByteArray();
        }
    }

    SignPanel(Context c, final Sender sender) {
        super(c);
        setOrientation(VERTICAL);
        final Pad pad = new Pad(c);
        addView(pad, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout row = Ui.row(c);
        TextView hint = Ui.text(c, "Sign with your finger. It lands on the PC clipboard with a clear background.", 12,
                Ui.MUTED);
        row.addView(hint, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        Button undo = Ui.button(c, "Undo");
        undo.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!pad.strokes.isEmpty()) pad.strokes.remove(pad.strokes.size() - 1);
                pad.invalidate();
            }
        });
        row.addView(undo, Ui.wrap(c, 4));
        Button clear = Ui.button(c, "Clear");
        clear.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pad.strokes.clear();
                pad.invalidate();
            }
        });
        row.addView(clear, Ui.wrap(c, 4));
        Button send = Ui.accentButton(c, "Send to PC");
        send.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                byte[] png = pad.export();
                if (png != null) sender.sendPng(png);
            }
        });
        row.addView(send, Ui.wrap(c, 4));
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 6);
        addView(row, lp);
    }
}
