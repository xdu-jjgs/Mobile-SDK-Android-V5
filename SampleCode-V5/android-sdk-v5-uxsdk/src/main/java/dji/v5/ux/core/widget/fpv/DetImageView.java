package dji.v5.ux.core.widget.fpv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ImageView;

import java.util.ArrayList;

public class DetImageView extends ImageView {
    private class DetBox {
        int x;
        int y;
        int w;
        int h;
        int p;
        String s;

        public DetBox(int x, int y, int w, int h, int p, String s) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.p = p;
            this.s = s;
        }
    }

    public DetImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private Canvas canvas = new Canvas();
    private Paint paint = new Paint();
    {
        paint.setAntiAlias(true);
        paint.setColor(Color.YELLOW);
        paint.setStyle(Style.STROKE);
        paint.setStrokeWidth(4.5f);
        paint.setTextSize(40);
        paint.setAlpha(100);
    };
    private ArrayList<DetBox> boxes = new ArrayList<>();

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int i = 0; i < boxes.size(); i++) {
            DetBox box = boxes.get(i);
            canvas.drawRect(new Rect(box.x, box.y, box.x + box.w, box.y + box.h), paint);
            canvas.drawText(String.format("%s: %d%%", box.s, box.p), box.x, box.y - 10, paint);
        }
    }
    public void clearBoxes() {
        boxes.clear();
    }
    public void addBox(int x, int y, int w, int h, int p, String s) {
        boxes.add(new DetBox(x, y, w, h, p, s));
    }
    public void draw() {
        this.onDraw(canvas);
    }
}