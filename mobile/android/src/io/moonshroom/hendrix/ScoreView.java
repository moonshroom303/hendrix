package io.moonshroom.hendrix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

final class ScoreView extends View {
    private static final int[] STEP = {0,0,1,1,2,3,3,4,4,5,5,6};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long[] notes;
    private final boolean tab;
    private final float scale;
    private final int staffTop;

    ScoreView(Context context, long[] notes, boolean tab) {
        super(context);
        this.notes = notes;
        this.tab = tab;
        long end = 0;
        int low = 0, high = 10;
        for (int i = 0; i < notes.length; i += 5) {
            end = Math.max(end, notes[i + 1]);
            int pitch = (int)notes[i + 2];
            int step = (pitch / 12 - 5) * 7 + STEP[pitch % 12];
            low = Math.min(low, step);
            high = Math.max(high, step);
        }
        staffTop = Math.max(60, high * 6 - 32);
        scale = Math.min(110, 14000 / Math.max(1f, end / 48000f));
        setMinimumWidth(dp(Math.max(320, end / 48000f * scale + 80)));
        setMinimumHeight(dp(tab ? 186 : Math.max(186, staffTop + 78 - low * 6)));
        setContentDescription(tab ? "Gitarrentabulatur der Aufnahme" : "Noten der Aufnahme");
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.rgb(30, 31, 35));
        int top = dp(tab ? 32 : staffTop), spacing = dp(tab ? 20 : 12);
        int rows = tab ? 6 : 5;
        paint.setStrokeWidth(dp(1));
        for (int row = 0; row < rows; row++) {
            float y = top + row * spacing;
            line(canvas, dp(25), y, getWidth() - dp(8), y);
            if (tab) text(canvas, InstrumentView.STRING_NAMES[row], dp(12), y + dp(4), 12);
        }
        if (!tab) text(canvas, "𝄞", dp(30), top + dp(41), 42);
        for (int i = 0; i < notes.length; i += 5) {
            float x = dp(56 + notes[i] / 48000f * scale);
            int pitch = (int)notes[i + 2];
            int string = (int)notes[i + 3];
            if (tab) {
                if (string == 0) continue;
                float y = top + (string - 1) * spacing;
                paint.setColor(Color.rgb(30, 31, 35));
                canvas.drawRect(x - dp(8), y - dp(8), x + dp(8), y + dp(8), paint);
                text(canvas, "" + notes[i + 4], x, y + dp(4), 12);
            } else {
                int step = (pitch / 12 - 5) * 7 + STEP[pitch % 12];
                float y = top + dp(60 - 6 * step);
                if (y < dp(7) || y > getHeight() - dp(7)) continue;
                for (int ledger = 0; ledger >= step; ledger -= 2)
                    line(canvas, x - dp(9), top + dp(60 - 6 * ledger), x + dp(9), top + dp(60 - 6 * ledger));
                for (int ledger = 12; ledger <= step; ledger += 2)
                    line(canvas, x - dp(9), top + dp(60 - 6 * ledger), x + dp(9), top + dp(60 - 6 * ledger));
                paint.setColor(InstrumentView.GOLD);
                canvas.drawOval(x - dp(5), y - dp(3), x + dp(5), y + dp(3), paint);
                line(canvas, x + dp(4), y, x + dp(4), y - dp(22));
                if (InstrumentView.PITCH[pitch % 12].contains("♯")) text(canvas, "♯", x - dp(10), y + dp(4), 12);
            }
        }
    }
    private void line(Canvas canvas, float x, float y, float x2, float y2) {
        paint.setColor(Color.GRAY);
        canvas.drawLine(x, y, x2, y2, paint);
    }
    private void text(Canvas canvas, String text, float x, float y, int size) {
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(dp(size));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);
        canvas.drawText(text, x, y, paint);
    }
    private int dp(float size) { return Math.round(size * getResources().getDisplayMetrics().density); }
}
