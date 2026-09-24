package rpg.engine.android.controls;

import android.content.*;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** Renders and edits controls. It emits logical actions, never movement vectors. */
public final class ControlOverlay extends View {
    public interface Listener { void action(ControlAction action, boolean pressed); }
    public interface ZoomListener { void zoom(float factor); }
    /** A plain tap that hit no control (screen fractions 0..1) — forwarded to the HUD widget layer. */
    public interface TapListener { void tap(float fx, float fy); }
    private final ControlLayout layout; private final Listener listener; private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean editMode; private ControlBinding selected; private float lastX,lastY; private final HashSet<ControlAction> held=new HashSet<>();
    private ZoomListener zoomListener; private float pinchStart = -1;
    private TapListener tapListener; private float downX, downY; private boolean downMiss;
    public ControlOverlay(Context c,ControlLayout l,Listener listener){super(c);this.layout=l;this.listener=listener;setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
    public void setZoomListener(ZoomListener l){zoomListener=l;}
    public void setTapListener(TapListener l){tapListener=l;}
    public void setEditMode(boolean v){editMode=v;invalidate();}
    public boolean isEditMode(){return editMode;}
    public ControlBinding selected(){return selected;}
    public void select(ControlBinding binding){selected=binding;invalidate();}
    private float px(float v){return v*getWidth();} private float py(float v){return v*getHeight();}
    @Override protected void onDraw(Canvas c){super.onDraw(c);for(ControlBinding b:layout.bindings()){
        float cx=px(b.x),cy=py(b.y),r=b.size*Math.min(getWidth(),getHeight())*.5f;
        p.setStyle(Paint.Style.FILL);p.setColor(editMode?Color.argb(90,40,160,255):Color.argb(70,255,255,255));c.drawCircle(cx,cy,r,p); if(b.type==ControlType.JOYSTICK){p.setColor(Color.argb(150,255,255,255));c.drawCircle(cx,cy,r*.38f,p);}
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(editMode&&b==selected?5:2);p.setColor(b==selected?Color.WHITE:Color.argb(160,255,255,255));c.drawCircle(cx,cy,r,p);
        p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(Math.max(12,Math.min(22,r*.34f)));p.setColor(Color.WHITE);String text=(b.label==null||b.label.isBlank())?b.action.title:b.label; c.drawText(text,cx,cy-p.ascent()/3,p); if(editMode){p.setTextSize(Math.max(10,r*.22f));p.setColor(Color.argb(230,255,255,180));c.drawText(b.action.title,cx,cy+r*.72f,p);}
    }}
    private ControlBinding hit(float x,float y){ControlBinding best=null;float bd=Float.MAX_VALUE;for(ControlBinding b:layout.bindings()){
        float dx=x-px(b.x),dy=y-py(b.y),r=b.size*Math.min(getWidth(),getHeight())*.5f,d=dx*dx+dy*dy;if(d<=r*r&&d<bd){best=b;bd=d;}}
        return best;
    }
    public void cycleType(){ if(selected!=null){ selected.type=selected.type==ControlType.BUTTON?ControlType.JOYSTICK:ControlType.BUTTON; invalidate(); } }
    @Override public boolean onTouchEvent(MotionEvent e){
        // Two or more pointers = pinch-to-zoom; release button presses so they never stick.
        if (e.getPointerCount() >= 2) return pinch(e);
        float x=e.getX(),y=e.getY();
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){selected=hit(x,y);lastX=x;lastY=y;downX=x;downY=y;downMiss=selected==null;if(selected!=null){if(editMode){invalidate();}else if(held.add(selected.action))listener.action(selected.action,true);}return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&selected!=null&&editMode){float dx=(x-lastX)/getWidth(),dy=(y-lastY)/getHeight();selected.x=Math.max(0,Math.min(1,selected.x+dx));selected.y=Math.max(0,Math.min(1,selected.y+dy));lastX=x;lastY=y;invalidate();return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP&&selected!=null&&!editMode){if(held.remove(selected.action))listener.action(selected.action,false);}
        else if(e.getActionMasked()==MotionEvent.ACTION_UP&&tapListener!=null&&!editMode&&downMiss){
            // Tap that hit no control and did not drift — the HUD widget layer (custom screens)
            // decides what it means (fractions of the screen).
            float dx=x-downX,dy=y-downY;
            if(dx*dx+dy*dy<24*24)tapListener.tap(x/getWidth(),y/getHeight());
        }return true;
    }
    /** Pinch gesture: incremental distance ratio → zoom factor; cancels held buttons on transition. */
    private boolean pinch(MotionEvent e){
        int a=e.getActionMasked();
        if(a==MotionEvent.ACTION_POINTER_DOWN){releaseAll();pinchStart=distance(e);}
        else if(a==MotionEvent.ACTION_POINTER_UP||a==MotionEvent.ACTION_UP){releaseAll();pinchStart=-1;}
        else if(a==MotionEvent.ACTION_MOVE&&pinchStart>0){float d=distance(e);if(d>0){if(zoomListener!=null)zoomListener.zoom(d/pinchStart);pinchStart=d;}}
        return true;
    }
    private float distance(MotionEvent e){float dx=e.getX(0)-e.getX(1),dy=e.getY(0)-e.getY(1);return (float)Math.sqrt(dx*dx+dy*dy);}
    private void releaseAll(){for(ControlAction ac:new ArrayList<>(held)){held.remove(ac);listener.action(ac,false);}}
}
