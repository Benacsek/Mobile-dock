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
        seg.setBackground(Ui.rounded(c, Ui.SURFACE_HI, 21));  // an iOS segmented control
        int p = Ui.dp(c, 2);
        seg.setPadding(p, p, p, p);
        FrameLayout body = new FrameLayout(c);
        String[] names = {"Touchpad", "Keyboard", "Sign"};
        View[] views = {new PadPanel(c, client), new KeysPanel(c, client), new SignPanel(c, sender)};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            Button b = Ui.button(c, names[i]);
            b.setBackground(Ui.pressable(c, 0x00000000, Ui.PRESSED, 19));
            b.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    show(index);
                }
            });
            tabs.add(b);
            seg.addView(b, new LayoutParams(0, Ui.dp(c, 38), 1f));
            pages.add(views[i]);
            body.addView(views[i]);
        }
        // The page name on the left, the switch beside it, like the other pages.
        LinearLayout header = Ui.row(c);
        header.addView(Ui.title(c, "Touch"));
        LayoutParams slp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = Ui.dp(c, 16);
        header.addView(seg, slp);
        addView(header, Ui.fillWidth(c, 2));
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        show(0);
    }

    void show(int index) {
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).setVisibility(i == index ? VISIBLE : GONE);
            Button b = tabs.get(i);
            b.setBackground(i == index ? Ui.rounded(getContext(), Ui.DARK ? 0xFF636366 : 0xFFFFFFFF, 19)
                    : Ui.pressable(getContext(), 0x00000000, Ui.PRESSED, 19));
            b.setTextColor(Ui.TEXT);
        }
    }
}
