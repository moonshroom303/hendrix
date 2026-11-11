package io.moonshroom.hendrix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

final class InstrumentView extends View {
    static final int SOLO = 0, CHORD = 1, KEYS = 2;
    static final int GOLD = Color.rgb(235, 184, 100);
    static final int[] STRINGS = {64, 59, 55, 50, 45, 40};
    static final String[] STRING_NAMES = {"e", "B", "G", "D", "A", "E"};
    static final String[] CHORD_NAMES = {"C", "G", "D", "Am", "Em", "E", "A", "Dm"};
    static final String[] PITCH = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};
    private static final int[][] CHORDS = {{0,1,0,2,3,-1},{3,0,0,0,2,3},{2,3,2,0,-1,-1},{0,1,2,2,0,-1},
            {0,0,0,2,2,0},{0,0,1,2,2,0},{0,2,2,2,0,-1},{1,3,2,0,-1,-1}};
    private static final int[] WHITE = {0,2,4,5,7,9,11,12};
    private static final int[] BLACK_INDEX = {0,1,3,4,5}, BLACK_NOTE = {1,3,6,8,10};

    interface Play { void note(int pitch, int velocity); void pluck(int pitch); void release(); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseIntArray active = new SparseIntArray();
    private final int[] held = new int[128];
    private final Play play;
    private int mode, value;
    private boolean playing;

    InstrumentView(Context context, Play play, int mode, int value) {
        super(context);
        this.play = play;
        this.mode = mode;
        this.value = value;
        setContentDescription(mode == KEYS ? "Klaviatur" : "Gitarrengriffbrett");
        setFocusable(true);
    }

    void update(int mode, int value) {
        release();
        this.mode = mode;
        this.value = value;
        invalidate();
    }

    void release() {
        active.clear();
        java.util.Arrays.fill(held, 0);
        play.release();
        finishGesture();
        invalidate();
    }

    private void finishGesture() {
        if (!playing) return;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        playing = false;
    }

    @Override protected void onDetachedFromWindow() { release(); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mode == KEYS) drawKeys(canvas);
        else drawStrings(canvas);
    }

    private void drawKeys(Canvas canvas) {
        canvas.drawColor(Color.rgb(27, 28, 31));
        float width = getWidth() / 8f;
        int base = (value + 1) * 12;
        for (int index = 0; index < WHITE.length; index++) {
            paint.setColor(held[base + WHITE[index]] > 0 ? GOLD : Color.rgb(232, 232, 225));
            canvas.drawRect(index * width, 0, (index + 1) * width - 2, getHeight(), paint);
        }
        for (int i = 0; i < BLACK_INDEX.length; i++) {
            float x = (BLACK_INDEX[i] + 0.69f) * width;
            paint.setColor(held[base + BLACK_NOTE[i]] > 0 ? GOLD : Color.rgb(26, 24, 25));
            canvas.drawRoundRect(x, 0, x + width * .62f, getHeight() * .58f, 5, 5, paint);
        }
        text(canvas, "C" + value, width / 2, getHeight() - dp(15), Color.DKGRAY);
    }

    private void drawStrings(Canvas canvas) {
        canvas.drawColor(Color.rgb(52, 36, 30));
        float row = getHeight() / 6f;
        for (int i = 0; i < 6; i++) {
            float y = (i + .5f) * row;
            paint.setColor(Color.rgb(170, 136, 92));
            paint.setStrokeWidth(dp(.7f + i * .2f));
            canvas.drawLine(0, y, getWidth(), y, paint);
            text(canvas, STRING_NAMES[i], dp(12), y - dp(5), Color.WHITE);
            if (mode == CHORD) {
                int fret = CHORDS[value][i];
                text(canvas, fret < 0 ? "×" : "" + fret, getWidth() / 2f, y - dp(5), GOLD);
            }
        }
        if (mode == SOLO) {
            float column = getWidth() / 5f;
            for (int i = 0; i < 5; i++) {
                paint.setColor(Color.rgb(119, 98, 82));
                paint.setStrokeWidth(dp(1));
                canvas.drawLine(column * i, 0, column * i, getHeight(), paint);
                float center = column * (i + .5f);
                text(canvas, "" + (value + i), center, dp(16), Color.WHITE);
                for (int string = 0; string < 6; string++) {
                    if (held[STRINGS[string] + value + i] > 0) {
                        paint.setColor(GOLD);
                        canvas.drawCircle(center, row * (string + .5f), dp(7), paint);
                    }
                }
            }
        }
    }

    private float dp(float size) { return size * getResources().getDisplayMetrics().density; }

    private void text(Canvas canvas, String text, float x, float y, int color) {
        paint.setColor(color);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(12));
        canvas.drawText(text, x, y, paint);
    }

    private int hit(float x, float y) {
        if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) return -1;
        if (mode == KEYS) {
            float column = x / getWidth() * 8;
            int base = (value + 1) * 12;
            if (y < getHeight() * .58f) {
                for (int i = 0; i < BLACK_INDEX.length; i++)
                    if (Math.abs(column - BLACK_INDEX[i] - 1) < .31f) return base + BLACK_NOTE[i];
            }
            return base + WHITE[(int)column];
        }
        int string = (int)(y / getHeight() * 6);
        return mode == CHORD ? string : STRINGS[string] + value + (int)(x / getWidth() * 5);
    }

    private void move(int id, int next) {
        int previous = active.get(id, -1);
        if (previous == next) return;
        if (next < 0) active.delete(id);
        else active.put(id, next);
        if (mode == CHORD) {
            if (next < 0) return;
            int row = previous < 0 ? next : previous + Integer.signum(next - previous);
            int step = next >= row ? 1 : -1;
            for (;;) {
                int fret = CHORDS[value][row];
                if (fret >= 0) play.pluck(STRINGS[row] + fret);
                if (row == next) break;
                row += step;
            }
        } else {
            if (previous >= 0 && --held[previous] == 0) play.note(previous, 0);
            if (next >= 0 && held[next]++ == 0) play.note(next, 96);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked(), index = event.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                playing = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                move(event.getPointerId(index), hit(event.getX(index), event.getY(index)));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++)
                    move(event.getPointerId(i), hit(event.getX(i), event.getY(i)));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                move(event.getPointerId(index), -1);
                if (action == MotionEvent.ACTION_UP) finishGesture();
                break;
            case MotionEvent.ACTION_CANCEL:
                release();
                break;
            default: return false;
        }
        invalidate();
        return true;
    }
}
