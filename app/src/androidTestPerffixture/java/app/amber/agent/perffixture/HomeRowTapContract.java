package app.amber.agent.perffixture;

import java.util.List;

/** Test-only selection and real touch sequence, shared by the Android adapter and local regression. */
final class HomeRowTapContract {
    static final String PACKAGE = "app.amber.agent.perffixture";
    static final int DOWN = 0, UP = 1, CANCEL = 3;

    static final class Bounds {
        final int left, top, right, bottom;
        Bounds(int left, int top, int right, int bottom) {
            this.left=left; this.top=top; this.right=right; this.bottom=bottom;
        }
        boolean positive() { return right > left && bottom > top; }
        Bounds clip(Bounds other) {
            return new Bounds(Math.max(left,other.left),Math.max(top,other.top),Math.min(right,other.right),Math.min(bottom,other.bottom));
        }
    }
    static final class Node {
        final String packageName, stableDescription;
        final boolean visible, enabled, clickable;
        final int windowId;
        final int[] actions;
        final Bounds bounds;
        Node(String packageName, String stableDescription, boolean visible, boolean enabled, boolean clickable,
             int windowId, int[] actions, Bounds bounds) {
            this.packageName=packageName; this.stableDescription=stableDescription; this.visible=visible;
            this.enabled=enabled; this.clickable=clickable; this.windowId=windowId; this.actions=actions; this.bounds=bounds;
        }
    }
    static final class Owner {
        final String packageName, activityId;
        final int pid, windowId, displayId;
        final boolean homeWithRow, focused, shown;
        final Bounds window, screen;
        Owner(String packageName, String activityId, int pid, int windowId, int displayId,
              boolean homeWithRow, boolean focused, boolean shown, Bounds window, Bounds screen) {
            this.packageName=packageName; this.activityId=activityId; this.pid=pid; this.windowId=windowId;
            this.displayId=displayId; this.homeWithRow=homeWithRow; this.focused=focused; this.shown=shown;
            this.window=window; this.screen=screen;
        }
    }
    static final class Point {
        final float x,y;
        final Bounds visibleBounds;
        Point(float x,float y,Bounds visibleBounds) { this.x=x; this.y=y; this.visibleBounds=visibleBounds; }
    }
    interface Injector { boolean inject(int action,float x,float y); }

    static void requireOwner(Owner owner,int expectedPid,String expectedActivity) {
        require(PACKAGE.equals(owner.packageName),"wrong owner package");
        require(owner.pid==expectedPid && expectedActivity.equals(owner.activityId),"owner PID/Activity changed");
        require(owner.homeWithRow && owner.focused && owner.shown,"owner is not focused/visible target Home");
        // Public MotionEvent injection here targets the default display only; never silently hit a mirror.
        require(owner.displayId==0,"non-default owner display unsupported by test touch adapter");
        require(owner.windowId>=0 && owner.window.positive() && owner.screen.positive(),"unknown/empty owner window or screen");
    }

    static Point select(String expectedDescription,List<Node> matches,List<Node> ancestors,Node root,
                        boolean windowActive,boolean windowFocused,int windowDisplayId,
                        Owner owner,int expectedPid,String expectedActivity) {
        require(expectedDescription.matches("perf_home_row_10000000-0000-4000-8000-00000000000[123]"),"not a fixed fixture row ID");
        requireOwner(owner,expectedPid,expectedActivity);
        require(matches.size()==1,"expected exactly one stable target row; count="+matches.size());
        require(windowActive && windowFocused && windowDisplayId==owner.displayId,"active accessibility window/display mismatch or unknown");
        require(PACKAGE.equals(root.packageName) && root.windowId==owner.windowId && root.visible && root.bounds.positive(),"wrong/stale foreground root");
        Node row=matches.get(0);
        require(expectedDescription.equals(row.stableDescription),"wrong stable row ID");
        requireNode(row,owner.windowId);
        Bounds visible=row.bounds.clip(owner.window).clip(owner.screen).clip(root.bounds);
        for (Node ancestor:ancestors) {
            requireNode(ancestor,owner.windowId);
            visible=visible.clip(ancestor.bounds);
        }
        require(visible.positive(),"row is offscreen/fully clipped");
        float x=visible.left+(visible.right-visible.left)/2f;
        float y=visible.top+(visible.bottom-visible.top)/2f;
        require(x>=owner.screen.left && x<owner.screen.right && y>=owner.screen.top && y<owner.screen.bottom,"illegal tap coordinate");
        // ACTION_CLICK/clickable are audited, not prerequisites for physical user input.
        return new Point(x,y,visible);
    }

    private static void requireNode(Node node,int windowId) {
        require(PACKAGE.equals(node.packageName) && node.windowId==windowId,"row/ancestor package or window mismatch");
        require(node.visible && node.enabled && node.bounds.positive(),"row/ancestor invisible, disabled or empty");
    }

    static void tap(Point point,Injector injector) {
        require(injector.inject(DOWN,point.x,point.y),"DOWN input injection rejected");
        boolean up=false;
        Throwable primary=null;
        try {
            up=injector.inject(UP,point.x,point.y);
            require(up,"UP input injection rejected");
        } catch (RuntimeException | Error error) {
            primary=error;
            throw error;
        } finally {
            if (!up) {
                try { require(injector.inject(CANCEL,point.x,point.y),"CANCEL cleanup injection rejected"); }
                catch (RuntimeException | Error cleanup) { if(primary!=null) primary.addSuppressed(cleanup); else throw cleanup; }
            }
        }
    }
    private static void require(boolean value,String reason) { if(!value) throw new AssertionError("Home row tap: "+reason); }
}
