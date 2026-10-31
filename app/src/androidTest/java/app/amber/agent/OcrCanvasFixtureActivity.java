package app.amber.agent;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;

/**
 * Canvas-only fixture: no accessibility text, so only on-device OCR can read the label.
 *
 * Written in Java on purpose: an Activity declared in the test APK runs in its own process, which
 * does not carry the Kotlin runtime used by the app under test, so a Kotlin View subclass fails at
 * class load with ClassNotFoundException: kotlin.jvm.internal.Intrinsics.
 */
public class OcrCanvasFixtureActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View canvasView = new View(this) {
            private final Paint paint = new Paint();

            {
                paint.setColor(Color.BLACK);
                paint.setTextSize(96f);
                paint.setAntiAlias(true);
                paint.setFakeBoldText(true);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                canvas.drawColor(Color.WHITE);
                canvas.drawText("AMBER OCR 2026", 60f, 320f, paint);
            }
        };
        setContentView(canvasView);
    }
}
