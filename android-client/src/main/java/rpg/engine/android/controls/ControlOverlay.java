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
    public interface TapListener { void tap(float x, float y); }
    private final ControlLayout layout; private final Listener listener; private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean editMode; private ControlBinding selected; private float lastX,lastY; private final HashSet<ControlAction> held=new HashSet<>();
    private ControlBinding activeJoystick;
    private float joystickX, joystickY;
    private final EnumSet<ControlAction> joystickHeld = EnumSet.noneOf(ControlAction.class);
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
        p.setStyle(Paint.Style.FILL);p.setColor(editMode?Color.argb(90,40,160,255):Color.argb(70,255,255,255));c.drawCircle(cx,cy,r,p);
        if(b.type==ControlType.JOYSTICK){
            float kx=cx,ky=cy;
            if(activeJoystick==b){float dx=joystickX-cx,dy=joystickY-cy,len=(float)Math.sqrt(dx*dx+dy*dy),max=r*.62f;if(len>max&&len>0){dx*=max/len;dy*=max/len;}kx=cx+dx;ky=cy+dy;}
            p.setColor(Color.argb(150,255,255,255));c.drawCircle(kx,ky,r*.38f,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(editMode&&b==selected?5:2);p.setColor(b==selected?Color.WHITE:Color.argb(160,255,255,255));c.drawCircle(cx,cy,r,p);
            if(editMode){p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(Math.max(10,r*.22f));p.setColor(Color.WHITE);c.drawText("JOYSTICK",cx,cy+r*.72f,p);}
        }else{
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(editMode&&b==selected?5:2);p.setColor(b==selected?Color.WHITE:Color.argb(160,255,255,255));c.drawCircle(cx,cy,r,p);
            p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(Math.max(12,Math.min(22,r*.34f)));p.setColor(Color.WHITE);String text=(b.label==null||b.label.isBlank())?b.action.title:b.label;c.drawText(text,cx,cy-p.ascent()/3,p);
            if(editMode){p.setTextSize(Math.max(10,r*.22f));p.setColor(Color.argb(230,255,255,180));c.drawText(b.action.title,cx,cy+r*.72f,p);}
        }
    }}
    private ControlBinding hit(float x,float y){ControlBinding best=null;float bd=Float.MAX_VALUE;for(ControlBinding b:layout.bindings()){
        float dx=x-px(b.x),dy=y-py(b.y),r=b.size*Math.min(getWidth(),getHeight())*.5f,d=dx*dx+dy*dy;if(d<=r*r&&d<bd){best=b;bd=d;}}
        return best;
    }
    public void cycleType(){ if(selected!=null){ selected.type=selected.type==ControlType.BUTTON?ControlType.JOYSTICK:ControlType.BUTTON; invalidate(); } }
    @Override public boolean onTouchEvent(MotionEvent e){
        int a=e.getActionMasked();
        if(e.getPointerCount()>=2)return pinch(e);
        float x=e.getX(),y=e.getY();
        if(a==MotionEvent.ACTION_DOWN){
            selected=hit(x,y);lastX=x;lastY=y;downX=x;downY=y;downMiss=selected==null;
            if(selected!=null){
                if(editMode){invalidate();}
                else if(selected.type==ControlType.JOYSTICK){
                    activeJoystick=selected;joystickX=x;joystickY=y;updateJoystick(x,y);invalidate();
                }else if(held.add(selected.action))listener.action(selected.action,true);
            }
            return true;
        }
        if(a==MotionEvent.ACTION_MOVE&&selected!=null&&editMode){
            float dx=(x-lastX)/Math.max(1,getWidth()),dy=(y-lastY)/Math.max(1,getHeight());
            selected.x=Math.max(0,Math.min(1,selected.x+dx));selected.y=Math.max(0,Math.min(1,selected.y+dy));
            lastX=x;lastY=y;invalidate();return true;
        }
        if(a==MotionEvent.ACTION_MOVE&&activeJoystick!=null){
            joystickX=x;joystickY=y;updateJoystick(x,y);invalidate();return true;
        }
        if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){
            if(activeJoystick!=null)releaseJoystick();
            else if(selected!=null&&!editMode&&held.remove(selected.action))listener.action(selected.action,false);
            if(a==MotionEvent.ACTION_UP&&tapListener!=null&&!editMode&&downMiss){
                float dx=x-downX,dy=y-downY;
                if(dx*dx+dy*dy<24*24)tapListener.tap(x,y);
            }
            selected=null;invalidate();
        }
        return true;
    }

    private void updateJoystick(float x,float y){
        if(activeJoystick==null)return;
        float cx=px(activeJoystick.x),cy=py(activeJoystick.y);
        float r=activeJoystick.size*Math.min(getWidth(),getHeight())*.5f;
        float dx=x-cx,dy=y-cy,len=(float)Math.sqrt(dx*dx+dy*dy),max=r*.62f;
        if(len>max&&len>0){dx*=max/len;dy*=max/len;}
        joystickX=cx+dx;joystickY=cy+dy;
        float nx=dx/Math.max(1,max),ny=dy/Math.max(1,max),dead=.22f;
        setJoystickAction(ControlAction.MOVE_LEFT,nx < -dead);
        setJoystickAction(ControlAction.MOVE_RIGHT,nx > dead);
        setJoystickAction(ControlAction.MOVE_UP,ny < -dead);
        setJoystickAction(ControlAction.MOVE_DOWN,ny > dead);
    }

    private void setJoystickAction(ControlAction action,boolean pressed){
        boolean was=joystickHeld.contains(action);
        if(pressed&&!was){joystickHeld.add(action);listener.action(action,true);}
        else if(!pressed&&was){joystickHeld.remove(action);listener.action(action,false);}
    }

    private void releaseJoystick(){
        for(ControlAction action:new ArrayList<>(joystickHeld)){
            joystickHeld.remove(action);listener.action(action,false);
        }
        activeJoystick=null;joystickX=0;joystickY=0;
    }

    /** Pinch gesture: incremental distance ratio → zoom factor; cancels held buttons on transition. */
    private boolean pinch(MotionEvent e){
        int a=e.getActionMasked();
        if(a==MotionEvent.ACTION_POINTER_DOWN){releaseAll();releaseJoystick();pinchStart=distance(e);}
        else if(a==MotionEvent.ACTION_POINTER_UP||a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){releaseAll();releaseJoystick();pinchStart=-1;}
        else if(a==MotionEvent.ACTION_MOVE&&pinchStart>0){float d=distance(e);if(d>0){if(zoomListener!=null)zoomListener.zoom(d/pinchStart);pinchStart=d;}}
        return true;
    }
    private float distance(MotionEvent e){float dx=e.getX(0)-e.getX(1),dy=e.getY(0)-e.getY(1);return (float)Math.sqrt(dx*dx+dy*dy);}
    private void releaseAll(){for(ControlAction ac:new ArrayList<>(held)){held.remove(ac);listener.action(ac,false);}}
}
