#!/usr/bin/env python3
"""Resumable Android joystick + snapshot smoothing fix."""
import difflib
import subprocess
import sys
from pathlib import Path

ROOT = Path.cwd()
FILES = [
    Path("android-client/src/main/java/rpg/engine/android/controls/ControlOverlay.java"),
    Path("android-client/src/main/java/rpg/engine/android/MainActivity.java"),
]

def fail(message):
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)

def git(*args):
    p = subprocess.run(
        ["git", *args], cwd=ROOT, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    if p.returncode:
        fail(p.stderr.strip() or f"git {' '.join(args)} failed")
    return p.stdout

if not (ROOT / ".git").exists():
    fail("Запусти скрипт из корня репозитория.")

branch = git("branch", "--show-current").strip()
if branch != "asteria-regions-mainline":
    fail(f"Ожидалась ветка asteria-regions-mainline, сейчас {branch!r}.")

originals = {}
updated = {}
for path in FILES:
    target = ROOT / path
    if not target.is_file():
        fail(f"Не найден файл: {path}")
    originals[path] = target.read_text(encoding="utf-8")
    updated[path] = originals[path]

# --- Joystick: fields ---
p = FILES[0]
s = updated[p]
old = """    private boolean editMode; private ControlBinding selected; private float lastX,lastY; private final HashSet<ControlAction> held=new HashSet<>();
    private ZoomListener zoomListener; private float pinchStart = -1;
"""
new = """    private boolean editMode; private ControlBinding selected; private float lastX,lastY; private final HashSet<ControlAction> held=new HashSet<>();
    private final EnumSet<ControlAction> joystickHeld = EnumSet.noneOf(ControlAction.class);
    private ControlBinding activeJoystick;
    private int joystickPointerId = -1, secondaryPointerId = -1;
    private ControlBinding secondaryBinding;
    private boolean multiTouchPinch;
    private float joystickX, joystickY;
    private ZoomListener zoomListener; private float pinchStart = -1;
"""
if "private final EnumSet<ControlAction> joystickHeld" not in s:
    if old not in s:
        fail("Не найден якорь полей ControlOverlay.java. Файл не изменён.")
    s = s.replace(old, new, 1)

# --- Joystick: drawing ---
old = """        p.setStyle(Paint.Style.FILL);p.setColor(editMode?Color.argb(90,40,160,255):Color.argb(70,255,255,255));c.drawCircle(cx,cy,r,p); if(b.type==ControlType.JOYSTICK){p.setColor(Color.argb(150,255,255,255));c.drawCircle(cx,cy,r*.38f,p);}
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(editMode&&b==selected?5:2);p.setColor(b==selected?Color.WHITE:Color.argb(160,255,255,255));c.drawCircle(cx,cy,r,p);
        p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(Math.max(12,Math.min(22,r*.34f)));p.setColor(Color.WHITE);String text=(b.label==null||b.label.isBlank())?b.action.title:b.label; c.drawText(text,cx,cy-p.ascent()/3,p); if(editMode){p.setTextSize(Math.max(10,r*.22f));p.setColor(Color.argb(230,255,255,180));c.drawText(b.action.title,cx,cy+r*.72f,p);}
"""
new = """        p.setStyle(Paint.Style.FILL);
        p.setColor(editMode?Color.argb(90,40,160,255):Color.argb(70,255,255,255));
        c.drawCircle(cx,cy,r,p);
        if(b.type==ControlType.JOYSTICK){
            float kx=cx, ky=cy;
            if(activeJoystick==b){
                float dx=joystickX-cx, dy=joystickY-cy, len=(float)Math.sqrt(dx*dx+dy*dy);
                float max=r*.62f;
                if(len>max && len>0){dx*=max/len;dy*=max/len;}
                kx=cx+dx; ky=cy+dy;
            }
            p.setColor(Color.argb(150,255,255,255));
            c.drawCircle(kx,ky,r*.34f,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(editMode&&b==selected?5:2);
            p.setColor(b==selected?Color.WHITE:Color.argb(160,255,255,255));
            c.drawCircle(cx,cy,r,p);
            if(editMode){
                p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(Math.max(10,r*.22f));p.setColor(Color.argb(230,255,255,180));
                c.drawText("JOYSTICK",cx,cy+r*.78f,p);
            }
        } else {
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(editMode&&b==selected?5:2);
            p.setColor(b==selected?Color.WHITE:Color.argb(160,255,255,255));c.drawCircle(cx,cy,r,p);
            p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(Math.max(12,Math.min(22,r*.34f)));p.setColor(Color.WHITE);
            String text=(b.label==null||b.label.isBlank())?b.action.title:b.label;
            c.drawText(text,cx,cy-p.ascent()/3,p);
            if(editMode){p.setTextSize(Math.max(10,r*.22f));p.setColor(Color.argb(230,255,255,180));c.drawText(b.action.title,cx,cy+r*.72f,p);}
        }
"""
if "if(activeJoystick==b)" not in s:
    if old not in s:
        fail("Не найден якорь отрисовки контролов. Файл не изменён.")
    s = s.replace(old, new, 1)

# --- Joystick: touch handling ---
old = """    @Override public boolean onTouchEvent(MotionEvent e){
        // Two or more pointers = pinch-to-zoom; release button presses so they never stick.
        if (e.getPointerCount() >= 2) return multiTouch(e);
        float x=e.getX(),y=e.getY();
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){selected=hit(x,y);lastX=x;lastY=y;downX=x;downY=y;downMiss=selected==null;if(selected!=null){if(editMode){invalidate();}else if(held.add(selected.action))listener.action(selected.action,true);}return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&selected!=null&&editMode){float dx=(x-lastX)/getWidth(),dy=(y-lastY)/getHeight();selected.x=Math.max(0,Math.min(1,selected.x+dx));selected.y=Math.max(0,Math.min(1,selected.y+dy));lastX=x;lastY=y;invalidate();return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP&&selected!=null&&!editMode){if(held.remove(selected.action))listener.action(selected.action,false);}
        else if(e.getActionMasked()==MotionEvent.ACTION_UP&&tapListener!=null&&!editMode&&downMiss){
            // Tap that hit no control and did not drift — the HUD widget layer (custom screens)
            // decides what it means (fractions of the screen).
            float dx=x-downX,dy=y-downY;
            if(dx*dx+dy*dy<24*24)tapListener.tap(x, y);
        }return true;
    }
"""
new = """    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getPointerCount()>=2)return multiTouch(e);
        float x=e.getX(),y=e.getY();int a=e.getActionMasked();
        if(a==MotionEvent.ACTION_DOWN){
            selected=hit(x,y);lastX=x;lastY=y;downX=x;downY=y;downMiss=selected==null;
            if(selected!=null){
                if(editMode)invalidate();
                else if(selected.type==ControlType.JOYSTICK){
                    activeJoystick=selected;joystickPointerId=e.getPointerId(0);
                    joystickX=x;joystickY=y;updateJoystick(x,y);invalidate();
                } else if(held.add(selected.action))listener.action(selected.action,true);
            }
            return true;
        }
        if(a==MotionEvent.ACTION_MOVE&&selected!=null&&editMode){
            float dx=(x-lastX)/Math.max(1,getWidth()),dy=(y-lastY)/Math.max(1,getHeight());
            selected.x=Math.max(0,Math.min(1,selected.x+dx));selected.y=Math.max(0,Math.min(1,selected.y+dy));
            lastX=x;lastY=y;invalidate();return true;
        }
        if(a==MotionEvent.ACTION_MOVE&&activeJoystick!=null&&!editMode){
            joystickX=x;joystickY=y;updateJoystick(x,y);invalidate();return true;
        }
        if((a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL)&&activeJoystick!=null){
            releaseJoystick();selected=null;invalidate();return true;
        }
        if((a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL)&&selected!=null&&!editMode&&selected.type==ControlType.BUTTON){
            if(held.remove(selected.action))listener.action(selected.action,false);
        } else if(a==MotionEvent.ACTION_UP&&tapListener!=null&&!editMode&&downMiss){
            float dx=x-downX,dy=y-downY;
            if(dx*dx+dy*dy<24*24)tapListener.tap(x,y);
        }
        if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){
            if(secondaryBinding!=null&&held.remove(secondaryBinding.action))listener.action(secondaryBinding.action,false);
            secondaryBinding=null;secondaryPointerId=-1;selected=null;invalidate();
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
        activeJoystick=null;joystickPointerId=-1;joystickX=0;joystickY=0;
    }

    private boolean multiTouch(MotionEvent e){
        int a=e.getActionMasked();
        if(multiTouchPinch){
            boolean result=pinch(e);
            if(a==MotionEvent.ACTION_POINTER_UP||a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL)multiTouchPinch=false;
            return result;
        }
        if(a==MotionEvent.ACTION_POINTER_DOWN&&activeJoystick!=null&&secondaryPointerId==-1){
            int i=e.getActionIndex();ControlBinding b=hit(e.getX(i),e.getY(i));
            if(b!=null&&b.type==ControlType.BUTTON){
                secondaryBinding=b;secondaryPointerId=e.getPointerId(i);
                if(held.add(b.action))listener.action(b.action,true);
                return true;
            }
        }
        if(a==MotionEvent.ACTION_MOVE&&activeJoystick!=null){
            int i=e.findPointerIndex(joystickPointerId);
            if(i>=0){joystickX=e.getX(i);joystickY=e.getY(i);updateJoystick(joystickX,joystickY);invalidate();}
            return true;
        }
        if(a==MotionEvent.ACTION_POINTER_UP){
            int id=e.getPointerId(e.getActionIndex());
            if(id==secondaryPointerId){
                if(secondaryBinding!=null&&held.remove(secondaryBinding.action))listener.action(secondaryBinding.action,false);
                secondaryBinding=null;secondaryPointerId=-1;return true;
            }
            if(id==joystickPointerId){releaseJoystick();selected=null;invalidate();return true;}
        }
        if(a==MotionEvent.ACTION_POINTER_DOWN){multiTouchPinch=true;return pinch(e);}
        if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){
            if(secondaryBinding!=null&&held.remove(secondaryBinding.action))listener.action(secondaryBinding.action,false);
            secondaryBinding=null;secondaryPointerId=-1;releaseAll();releaseJoystick();selected=null;invalidate();
            multiTouchPinch=false;return true;
        }
        return true;
    }
"""
if "private void updateJoystick(float x,float y)" not in s:
    if old not in s:
        fail("Не найден обработчик касаний. Файл не изменён.")
    s=s.replace(old,new,1)

old = """    private boolean pinch(MotionEvent e){
        int a=e.getActionMasked();
        if(a==MotionEvent.ACTION_POINTER_DOWN){releaseAll();pinchStart=distance(e);}
        else if(a==MotionEvent.ACTION_POINTER_UP||a==MotionEvent.ACTION_UP){releaseAll();pinchStart=-1;}
        else if(a==MotionEvent.ACTION_MOVE&&pinchStart>0){float d=distance(e);if(d>0){if(zoomListener!=null)zoomListener.zoom(d/pinchStart);pinchStart=d;}}
        return true;
    }
"""
new = """    private boolean pinch(MotionEvent e){
        int a=e.getActionMasked();
        if(a==MotionEvent.ACTION_POINTER_DOWN){releaseAll();releaseJoystick();pinchStart=distance(e);}
        else if(a==MotionEvent.ACTION_POINTER_UP||a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){releaseAll();releaseJoystick();pinchStart=-1;selected=null;}
        else if(a==MotionEvent.ACTION_MOVE&&pinchStart>0){float d=distance(e);if(d>0){if(zoomListener!=null)zoomListener.zoom(d/pinchStart);pinchStart=d;}}
        invalidate();return true;
    }
"""
if "releaseJoystick();pinchStart=-1" not in s:
    if old not in s:
        fail("Не найден обработчик pinch-to-zoom. Файл не изменён.")
    s=s.replace(old,new,1)
updated[p]=s

# --- Add joystick option in Android control editor ---
p=FILES[1]
s=updated[p]
old = """        add.setOnClickListener(v -> {
            ControlBinding b = controls.addCustom(ControlAction.PRIMARY, ControlType.BUTTON, .5f, .5f, .12f, "A");
            overlay.select(b);
            status.setText("New button: drag to position");
        });
"""
new = """        add.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Add touch control")
            .setItems(new String[]{"Action button", "Movement joystick"}, (dialog, which) -> {
                ControlBinding b;
                if(which==1){
                    b=controls.addCustom(ControlAction.MOVE_UP,ControlType.JOYSTICK,.14f,.78f,.23f,"◎");
                    status.setText("New joystick: drag to position; save controls when done");
                }else{
                    b=controls.addCustom(ControlAction.PRIMARY,ControlType.BUTTON,.5f,.5f,.12f,"A");
                    status.setText("New button: drag to position");
                }
                overlay.select(b);overlay.invalidate();
            }).show());
"""
if 'setItems(new String[]{"Action button", "Movement joystick"}' not in s:
    if old not in s:
        fail("Не найдено место добавления кнопки в MainActivity.java.")
    s=s.replace(old,new,1)

# --- Do not overwrite authoritative network snapshot with interpolated positions ---
s=s.replace(
    """            snapshot = new Snapshot(displayed);
            c.drawColor(Color.rgb(36, 48, 42));
""",
    """            // Keep the authoritative network snapshot separate from interpolated render positions.
            c.drawColor(Color.rgb(36, 48, 42));
""",
    1,
)
if "snapshot = new Snapshot(displayed);" in s:
    fail("Осталась запись сглаженных координат в snapshot; проверь MainActivity.java.")
s=s.replace(
    "for (Snapshot.EntityState e : snapshot.entities()) {",
    "for (Snapshot.EntityState e : displayed) {",
)
if "for (Snapshot.EntityState e : snapshot.entities()) {" in s:
    fail("Остались циклы onDraw по исходному snapshot; проверь MainActivity.java.")
updated[p]=s

patch_parts=[]
for path in FILES:
    if originals[path]==updated[path]:
        continue
    diff=difflib.unified_diff(
        originals[path].splitlines(keepends=True),
        updated[path].splitlines(keepends=True),
        fromfile=f"a/{path.as_posix()}",
        tofile=f"b/{path.as_posix()}",
    )
    body="".join(diff)
    if body:
        patch_parts.append(f"diff --git a/{path.as_posix()} b/{path.as_posix()}\n"+body)

if not patch_parts:
    print("Изменения уже применены.")
    print("Зум остаётся под управлением Lua-хоста через engine.set_zoom(...).")
    raise SystemExit(0)

patch_file=ROOT/".android-joystick-generated.patch"
patch_file.write_text("".join(patch_parts),encoding="utf-8")
try:
    git("apply","--check",str(patch_file))
    git("apply",str(patch_file))
    git("diff","--check")
finally:
    patch_file.unlink(missing_ok=True)

print("Патч применён. Изменены:")
for path in FILES:
    if originals[path]!=updated[path]:
        print("  "+str(path))
print("\nДальше запусти:")
print("  ./gradlew :android-client:assembleDebug")
print("  ./gradlew test")
print("\nВ редакторе сенсорных контролов выбери Add -> Movement joystick.")
print("Зум задаётся Lua-хостом через engine.set_zoom(...).")
