#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys
import shutil

ROOT = Path.cwd()
OVERLAY = Path("android-client/src/main/java/rpg/engine/android/controls/ControlOverlay.java")
MAIN = Path("android-client/src/main/java/rpg/engine/android/MainActivity.java")

def abort(message):
    print("ОШИБКА:", message, file=sys.stderr)
    raise SystemExit(1)

def git(*args):
    p = subprocess.run(["git", *args], text=True, capture_output=True)
    if p.returncode:
        abort(p.stderr.strip() or "Ошибка Git: " + " ".join(args))
    return p.stdout.strip()

if not (ROOT / ".git").exists():
    abort("Запусти скрипт из корня репозитория.")
branch = git("branch", "--show-current")
if branch != "asteria-regions-mainline":
    abort(f"Ожидалась ветка asteria-regions-mainline, сейчас {branch!r}.")

for f in (OVERLAY, MAIN):
    if not (ROOT / f).is_file():
        abort(f"Не найден файл: {f}")

overlay = (ROOT / OVERLAY).read_text()
main = (ROOT / MAIN).read_text()
new_overlay = overlay
new_main = main

# 1. Данные активного джойстика.
if "private ControlBinding activeJoystick;" not in new_overlay:
    anchor = "private ZoomListener zoomListener; private float pinchStart = -1;"
    if anchor not in new_overlay:
        abort("Не найдено поле zoomListener; исходники оставлены без изменений.")
    new_overlay = new_overlay.replace(
        anchor,
        """private ControlBinding activeJoystick;
    private float joystickX, joystickY;
    private final EnumSet<ControlAction> joystickHeld = EnumSet.noneOf(ControlAction.class);
    private ZoomListener zoomListener; private float pinchStart = -1;""",
        1
    )

# 2. Отрисовка рукоятки джойстика.
old_draw = """p.setStyle(Paint.Style.FILL);p.setColor(editMode?Color.argb(90,40,160,255):Color.argb(70,255,255,255));c.drawCircle(cx,cy,r,p); if(b.type==ControlType.JOYSTICK){p.setColor(Color.argb(150,255,255,255));c.drawCircle(cx,cy,r*.38f,p);}
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(editMode&&b==selected?5:2);p.setColor(b==selected?Color.WHITE:Color.argb(160,255,255,255));c.drawCircle(cx,cy,r,p);
        p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(Math.max(12,Math.min(22,r*.34f)));p.setColor(Color.WHITE);String text=(b.label==null||b.label.isBlank())?b.action.title:b.label; c.drawText(text,cx,cy-p.ascent()/3,p); if(editMode){p.setTextSize(Math.max(10,r*.22f));p.setColor(Color.argb(230,255,255,180));c.drawText(b.action.title,cx,cy+r*.72f,p);}"""
new_draw = """p.setStyle(Paint.Style.FILL);p.setColor(editMode?Color.argb(90,40,160,255):Color.argb(70,255,255,255));c.drawCircle(cx,cy,r,p);
        if(b.type==ControlType.JOYSTICK){
            float kx=cx,ky=cy;
            if(activeJoystick==b){float dx=joystickX-cx,dy=joystickY-cy,len=(float)Math.sqrt(dx*dx+dy*dy),max=r*.62f;if(len>max&&len>0){dx*=max/len;dy*=max/len;}kx=cx+dx;ky=cy+dy;}
            p.setColor(Color.argb(150,255,255,255));c.drawCircle(kx,ky,r*.38f,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(editMode&&b==selected?5:2);p.setColor(b==selected?Color.WHITE:Color.argb(160,255,255,255));c.drawCircle(cx,cy,r,p);
            if(editMode){p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(Math.max(10,r*.22f));p.setColor(Color.WHITE);c.drawText("JOYSTICK",cx,cy+r*.72f,p);}
        }else{
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(editMode&&b==selected?5:2);p.setColor(b==selected?Color.WHITE:Color.argb(160,255,255,255));c.drawCircle(cx,cy,r,p);
            p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(Math.max(12,Math.min(22,r*.34f));p.setColor(Color.WHITE);String text=(b.label==null||b.label.isBlank())?b.action.title:b.label;c.drawText(text,cx,cy-p.ascent()/3,p);
            if(editMode){p.setTextSize(Math.max(10,r*.22f));p.setColor(Color.argb(230,255,255,180));c.drawText(b.action.title,cx,cy+r*.72f,p);}
        }"""
if "if(activeJoystick==b)" not in new_overlay:
    if old_draw not in new_overlay:
        abort("Не совпадает блок отрисовки. Файлы не изменены.")
    new_overlay = new_overlay.replace(old_draw, new_draw, 1)

# 3. Touch handling, based on the actual handler from the user's source.
start = new_overlay.find("    @Override public boolean onTouchEvent(MotionEvent e){")
end = new_overlay.find("    /** Pinch gesture:", start)
if start < 0 or end < 0:
    abort("Не удалось определить границы обработчика касаний.")
handler = new_overlay[start:end]
if "private void updateJoystick" not in new_overlay:
    replacement = """    @Override public boolean onTouchEvent(MotionEvent e){
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

"""
    new_overlay = new_overlay[:start] + replacement + new_overlay[end:]

# Make pinch cancellation release joystick as well.
new_overlay = new_overlay.replace(
    "if(a==MotionEvent.ACTION_POINTER_DOWN){releaseAll();pinchStart=distance(e);}",
    "if(a==MotionEvent.ACTION_POINTER_DOWN){releaseAll();releaseJoystick();pinchStart=distance(e);}"
)
new_overlay = new_overlay.replace(
    "else if(a==MotionEvent.ACTION_POINTER_UP||a==MotionEvent.ACTION_UP){releaseAll();pinchStart=-1;}",
    "else if(a==MotionEvent.ACTION_POINTER_UP||a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){releaseAll();releaseJoystick();pinchStart=-1;}"
)
updated_handler = new_overlay[start:new_overlay.find("    /** Pinch gesture:", start)]
if "private void updateJoystick" not in new_overlay:
    abort("Обработчик джойстика не сформирован.")

# 4. Editor adds either an action button or a movement joystick.
if 'setItems(new String[]{"Action button", "Movement joystick"}' not in new_main:
    old_add = """        add.setOnClickListener(v -> {
            ControlBinding b = controls.addCustom(ControlAction.PRIMARY, ControlType.BUTTON, .5f, .5f, .12f, "A");
            overlay.select(b);
            status.setText("New button: drag to position");
        });"""
    new_add = """        add.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Add touch control")
            .setItems(new String[]{"Action button", "Movement joystick"}, (dialog, which) -> {
                ControlBinding b = which == 1
                    ? controls.addCustom(ControlAction.MOVE_UP, ControlType.JOYSTICK, .14f, .78f, .23f, "◎")
                    : controls.addCustom(ControlAction.PRIMARY, ControlType.BUTTON, .5f, .5f, .12f, "A");
                overlay.select(b);
                overlay.invalidate();
                status.setText(which == 1 ? "New joystick: drag to position" : "New button: drag to position");
            }).show());"""
    if old_add not in new_main:
        abort("Не найден обработчик кнопки Add в MainActivity.java.")
    new_main = new_main.replace(old_add, new_add, 1)

# 5. Keep the authoritative network snapshot unchanged; render the interpolated list.
new_main = new_main.replace("            snapshot = new Snapshot(displayed);\n", "")
new_main = new_main.replace("for (Snapshot.EntityState e : snapshot.entities()) {",
                            "for (Snapshot.EntityState e : displayed) {")
if "snapshot = new Snapshot(displayed);" in new_main:
    abort("Не удалось удалить перезапись snapshot.")

if new_overlay == overlay and new_main == main:
    print("Изменения уже применены.")
    raise SystemExit(0)

# Refuse to overwrite a pre-existing backup.
for f in (OVERLAY, MAIN):
    backup = ROOT / (str(f) + ".before-joystick")
    if backup.exists():
        abort(f"Резервная копия уже существует: {backup}; проверь её перед продолжением.")

for f in (OVERLAY, MAIN):
    shutil.copy2(ROOT / f, ROOT / (str(f) + ".before-joystick"))

(ROOT / OVERLAY).write_text(new_overlay, encoding="utf-8")
(ROOT / MAIN).write_text(new_main, encoding="utf-8")

try:
    git("diff", "--check")
except SystemExit:
    for f in (OVERLAY, MAIN):
        backup = ROOT / (str(f) + ".before-joystick")
        shutil.copy2(backup, ROOT / f)
    abort("git diff --check не прошёл; файлы восстановлены из резервных копий.")

print("Изменения записаны; резервные копии сохранены с суффиксом .before-joystick.")
print("Проверь изменения: git diff --")
print("Сборка: ./gradlew :android-client:assembleDebug")
print("Тесты: ./gradlew test")
