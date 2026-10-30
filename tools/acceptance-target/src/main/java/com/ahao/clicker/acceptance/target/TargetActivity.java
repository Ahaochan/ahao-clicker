package com.ahao.clicker.acceptance.target;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class TargetActivity extends Activity {
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) { ReceiverJournal.touch(event); Receipts.touch(event); return super.dispatchTouchEvent(event); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); Receipts.focused(focused); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = getResources().getDimensionPixelSize(R.dimen.padding);
        root.setPadding(padding, getResources().getDimensionPixelSize(R.dimen.top_padding), padding, padding);
        TextView count = new TextView(this);
        count.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_size));
        count.setText(getString(R.string.receipts, Receipts.size()));
        root.addView(count);
        for (String name : new String[]{"A", "B", "C"}) {
            Button button = new Button(this);
            button.setText(getString(R.string.target, name));
            button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_size));
            root.addView(button, new LinearLayout.LayoutParams(getResources().getDimensionPixelSize(R.dimen.target_width), getResources().getDimensionPixelSize(R.dimen.target_height)));
            button.setOnClickListener(view -> {
                ReceiverJournal.click(name);
                Receipts.click(name);
                count.setText(getString(R.string.receipts, Receipts.size()));
            });
            button.post(() -> {
                int[] location = new int[2];
                button.getLocationOnScreen(location);
                Receipts.point(name, location[0] + button.getWidth() / 2, location[1] + button.getHeight() / 2);
            });
        }
        setContentView(root);
    }
}
