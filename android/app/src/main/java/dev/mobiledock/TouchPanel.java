package dev.mobiledock;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/** Touch & sign: touchpad, keyboard and signature pad behind one segmented switch. */
final class TouchPanel extends LinearLayout {
    private final List<Button> tabs = new ArrayList<Button>();
    private final List<View> pages = new ArrayList<View>();

    TouchPanel(Context c, DockClient client, SignPanel.Sender sender) {
        super(c);
        setOrientation(VERTICAL);
        LinearLayout seg = Ui.row(c);
        seg.setBackground(Ui.rounded(c, Ui.SURFACE, 14));
        int p = Ui.dp(c, 4);
        seg.setPadding(p, p, p, p);
        FrameLayout body = new FrameLayout(c);
        String[] names = {"Touchpad", "Keyboard", "Sign"};
        View[] views = {new PadPanel(c, client), new KeysPanel(c, client), new SignPanel(c, sender)};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            Button b = Ui.button(c, names[i]);
            b.setBackground(Ui.pressable(c, 0x00000000, Ui.SURFACE_HI, 12));
            b.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    show(index);
                }
            });
            tabs.add(b);
            seg.addView(b, new LayoutParams(0, Ui.dp(c, 42), 1f));
            pages.add(views[i]);
            body.addView(views[i]);
        }
        addView(seg, Ui.fillWidth(c, 2));
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        show(0);
    }

    void show(int index) {
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).setVisibility(i == index ? VISIBLE : GONE);
            Button b = tabs.get(i);
            b.setSelected(i == index);
            b.setTextColor(i == index ? Ui.onAccent() : Ui.TEXT);
        }
    }
}
