package app.amber.core.automation.display;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

interface IAmberDisplayService {
    int uid() = 1;
    Bundle capabilities() = 2;
    int createDisplay(String runId, long generation, int width, int height, int dpi, in Surface surface) = 3;
    int launchOnDisplay(long generation, int displayId, String packageName) = 4;
    boolean injectMotion(long generation, int displayId, in MotionEvent event) = 5;
    boolean injectKey(long generation, int displayId, in KeyEvent event) = 6;
    void release(long generation, String reason) = 7;
    void destroy() = 16777114;
}
