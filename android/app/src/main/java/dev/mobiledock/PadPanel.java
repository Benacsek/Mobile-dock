package dev.mobiledock;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Touchpad. One finger moves, tap clicks, two finger tap right clicks,
 * two finger drag scrolls. Hold a button below to drag.
 */
final class PadPanel extends LinearLayout {
    private static final long TAP_MS = 220;

    private final DockClient client;
    private float lastX, lastY, startX, startY, scrollAcc, hScrollAcc;
    private long downTime;
    private int maxPointers;
    private boolean moved;
    private final float slop;
    private float speed = 1.6f;

    @SuppressLint("ClickableViewAccessibility")
    PadPanel(Context c, final DockClient client) {
        super(c);
        this.client = client;
        setOrientation(VERTICAL);
        slop = Ui.dp(c, 8);

        TextView pad = Ui.text(c, "Touchpad\n\nTap to click. Two fingers to scroll or right click.", 14, Ui.MUTED);
        pad.setGravity(Gravity.CENTER);
        pad.setBackground(Ui.rounded(c, Ui.SURFACE, 16));
        pad.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return touch(e);
            }
        });
        addView(pad, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = Ui.row(c);
        buttons.addView(holdButton(c, "Left", "left"), new LayoutParams(0, Ui.dp(c, 56), 2f));
        buttons.addView(holdButton(c, "Middle", "middle"), new LayoutParams(0, Ui.dp(c, 56), 1f));
        buttons.addView(holdButton(c, "Right", "right"), new LayoutParams(0, Ui.dp(c, 56), 2f));
        final Button speedBtn = Ui.button(c, "Speed 2");
        speedBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                speed = speed > 2.5f ? 0.8f : speed + 0.8f;
                speedBtn.setText("Speed " + Math.round(speed / 0.8f));
            }
        });
        buttons.addView(speedBtn, new LayoutParams(0, Ui.dp(c, 56), 1f));
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 8);
        addView(buttons, lp);
    }

    @SuppressLint("ClickableViewAccessibility")
    private Button holdButton(Context c, String label, final String button) {
        Button b = Ui.button(c, label);
        b.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    v.setPressed(true);
                    client.fire("mouse.button", DockClient.args("button", button, "action", "down"));
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                    client.fire("mouse.button", DockClient.args("button", button, "action", "up"));
                }
                return true;
            }
        });
        return b;
    }

    private float avgX(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i);
        return s / e.getPointerCount();
    }

    private float avgY(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
        return s / e.getPointerCount();
    }

    private boolean touch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downTime = e.getEventTime();
                maxPointers = 1;
                moved = false;
                startX = lastX = e.getX();
                startY = lastY = e.getY();
                scrollAcc = hScrollAcc = 0;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                maxPointers = Math.max(maxPointers, e.getPointerCount());
                lastX = avgX(e);
                lastY = avgY(e);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = avgX(e), y = avgY(e);
                float dx = x - lastX, dy = y - lastY;
                lastX = x;
                lastY = y;
                if (Math.abs(e.getX() - startX) > slop || Math.abs(e.getY() - startY) > slop) moved = true;
                if (e.getPointerCount() >= 2) {
                    // Natural scrolling: content follows the fingers. One unit is a third of a wheel notch.
                    float step = Ui.dp(getContext(), 12);
                    scrollAcc += dy / step;
                    hScrollAcc -= dx / step;
                    int sy = (int) scrollAcc, sx = (int) hScrollAcc;
                    if (sy != 0 || sx != 0) {
                        scrollAcc -= sy;
                        hScrollAcc -= sx;
                        client.fire("mouse.scroll", DockClient.args("dx", sx, "dy", sy));
                    }
                } else if (maxPointers == 1) {
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    float accel = speed * (1f + Math.min(dist / Ui.dp(getContext(), 10), 2f));
                    client.moveBy(dx * accel, dy * accel);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!moved && e.getEventTime() - downTime < TAP_MS * (maxPointers > 1 ? 2 : 1)) {
                    String button = maxPointers >= 3 ? "middle" : maxPointers == 2 ? "right" : "left";
                    client.fire("mouse.button", DockClient.args("button", button, "action", "click"));
                }
                return true;
            default:
                return true;
        }
    }
}
