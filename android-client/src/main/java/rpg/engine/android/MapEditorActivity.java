package rpg.engine.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.map.*;

/** A deliberately dependency-light Android .rmap editor: no engine renderer, no OpenGL, no desktop UI. */
public final class MapEditorActivity extends Activity {
    private static final int OPEN = 10, SAVE = 11;
    private EditorView editor;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        applyImmersive();
        editor = new EditorView();
        buildUi();
    }

    private void applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getInsetsController().setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(24,24,27));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(8,6,8,6);

        Button open = button("Open"); open.setOnClickListener(v -> pick(OPEN, "application/octet-stream"));
        Button save = button("Save"); save.setOnClickListener(v -> pick(SAVE, "application/octet-stream"));
        Button newMap = button("New"); newMap.setOnClickListener(v -> newMapDialog());
        Button mode = button("Paint"); mode.setOnClickListener(v -> { editor.mode = editor.mode == Mode.PAINT ? Mode.COLLISION : editor.mode == Mode.COLLISION ? Mode.ENTITY : Mode.PAINT; mode.setText(editor.mode.label); });
        Button erase = button("Erase"); erase.setOnClickListener(v -> editor.erase = !editor.erase);
        bar.addView(open); bar.addView(save); bar.addView(newMap); bar.addView(mode); bar.addView(erase);

        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(52)));
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(palette(), new LinearLayout.LayoutParams(-1, dp(62)));
        setContentView(root);
    }

    private LinearLayout palette() {
        LinearLayout p = new LinearLayout(this); p.setGravity(Gravity.CENTER_VERTICAL); p.setPadding(6,4,6,4);
        for (int id = 0; id <= 9; id++) {
            final int tile = id;
            Button b = button("" + id); b.setOnClickListener(v -> { editor.selectedTile = tile; editor.invalidate(); });
            p.addView(b, new LinearLayout.LayoutParams(0, -1, 1));
        }
        return p;
    }

    private Button button(String text) { Button b = new Button(this); b.setText(text); b.setTextSize(11); return b; }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    private void pick(int action, String type) {
        Intent i = new Intent(action == OPEN ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT);
        i.setType(type); i.putExtra(Intent.EXTRA_TITLE, editor.map.name() + ".rmap");
        if (action == OPEN) i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i, action);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (result != RESULT_OK || data == null) return;
        try {
            Uri uri = data.getData();
            if (request == OPEN) { try (InputStream in = getContentResolver().openInputStream(uri)) { editor.map = RMapIO.read(in); editor.resetView(); } }
            else { try (OutputStream out = getContentResolver().openOutputStream(uri)) { RMapIO.write(editor.map, out); } }
            editor.invalidate();
        } catch (Exception e) { Toast.makeText(this, "RMAP: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void newMapDialog() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(32,8,32,0);
        EditText name = edit("map name", "new_map"); EditText width = edit("width", "32"); EditText height = edit("height", "32");
        box.addView(name); box.addView(width); box.addView(height);
        new android.app.AlertDialog.Builder(this).setTitle("New RMap").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Create", (d,w) -> {
            try { int x = Math.max(1, Math.min(512, Integer.parseInt(width.getText().toString()))); int y = Math.max(1, Math.min(512, Integer.parseInt(height.getText().toString()))); editor.map = EditorView.blank(name.getText().toString(), x, y); editor.resetView(); editor.invalidate(); }
            catch (Exception e) { Toast.makeText(this, "Bad map size", Toast.LENGTH_SHORT).show(); }
        }).show();
    }
    private EditText edit(String hint, String value) { EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(); return e; }

    enum Mode { PAINT("Paint"), COLLISION("Collision"), ENTITY("Entity"); final String label; Mode(String s){label=s;} }

    final class EditorView extends View {
        RMap map = blank("new_map", 32, 32);
        Mode mode = Mode.PAINT; int selectedTile = 1; boolean erase; float zoom = 1f, ox = 0, oy = 0; float downX, downY, lastX, lastY; boolean dragging;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); final int grid = 48;
        EditorView() { super(MapEditorActivity.this); paint.setTypeface(Typeface.create("sans", Typeface.NORMAL)); setFocusable(true); }
        static RMap blank(String name, int w, int h) { return new RMap(name == null || name.isBlank() ? "new_map" : name, 1, w, h, List.of(new TileLayer("ground", w, h, new int[w*h], false)), new ArrayList<>()); }
        void resetView(){ zoom=1; ox=getWidth()/2f; oy=80; }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); c.drawColor(Color.rgb(30,31,35));
            float s = grid * zoom;
            // world origin in the editor is top-left orthographic; the saved map stays coordinate-compatible with RMAP.
            for (int y=0;y<map.height();y++) for (int x=0;x<map.width();x++) {
                float l=ox+x*s, t=oy+y*s, r=l+s, b=t+s; int id=map.layers().get(0).tiles()[y*map.width()+x];
                paint.setStyle(Paint.Style.FILL); paint.setColor(tileColor(id)); c.drawRect(l,t,r,b,paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(Color.argb(55,255,255,255)); c.drawRect(l,t,r,b,paint);
                if (map.layers().get(0).collision() && id != 0) { paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(70,255,70,70)); c.drawRect(l,t,r,b,paint); }
            }
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(24); paint.setColor(Color.WHITE);
            c.drawText(map.name()+"  " + map.width()+"x"+map.height()+"  " + mode.label + "  tile="+selectedTile, 12, 30, paint);
            for (MapEntity e : map.entities()) {
                float x=ox+(float)e.position().x()*s+s/2, y=oy+(float)e.position().y()*s+s/2;
                paint.setColor(Color.MAGENTA); c.drawCircle(x,y,Math.max(5,s*.22f),paint);
                paint.setColor(Color.WHITE); paint.setTextSize(Math.max(10,s*.22f)); c.drawText(e.id(),x+8,y,paint);
            }
        }

        private int tileColor(int id) {
            if (id == 0) return Color.rgb(48,49,54);
            float h = (id * 37) % 360; return Color.HSVToColor(new float[]{h, .48f, .62f});
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            float x=e.getX(), y=e.getY();
            if (e.getActionMasked()==MotionEvent.ACTION_DOWN) { downX=lastX=x; downY=lastY=y; dragging=false; return true; }
            if (e.getActionMasked()==MotionEvent.ACTION_MOVE) {
                if (Math.abs(x-downX)+Math.abs(y-downY)>12) dragging=true;
                if (dragging) { ox += x-lastX; oy += y-lastY; lastX=x; lastY=y; invalidate(); } else paintAt(x,y);
                return true;
            }
            if (e.getActionMasked()==MotionEvent.ACTION_UP) { if (!dragging) paintAt(x,y); return true; }
            if (e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount()==2) return true;
            return true;
        }

        private void paintAt(float sx,float sy) {
            float s=grid*zoom; int x=(int)Math.floor((sx-ox)/s), y=(int)Math.floor((sy-oy)/s);
            if(x<0||y<0||x>=map.width()||y>=map.height()) return;
            if(mode==Mode.PAINT) {
                TileLayer l=map.layers().get(0); int[] t=l.tiles().clone(); t[y*map.width()+x]=erase?0:selectedTile;
                ArrayList<TileLayer> ls=new ArrayList<>(map.layers()); ls.set(0,new TileLayer(l.name(),l.width(),l.height(),t,l.collision())); map=new RMap(map.name(),map.tileSize(),map.width(),map.height(),ls,map.entities());
            } else if(mode==Mode.COLLISION) {
                TileLayer l=map.layers().get(0); int[] t=l.tiles().clone();
                if(erase) t[y*map.width()+x]=0; else if(t[y*map.width()+x]==0) t[y*map.width()+x]=selectedTile;
                ArrayList<TileLayer> ls=new ArrayList<>(map.layers()); ls.set(0,new TileLayer(l.name(),l.width(),l.height(),t,!l.collision())); map=new RMap(map.name(),map.tileSize(),map.width(),map.height(),ls,map.entities());
            } else {
                ArrayList<MapEntity> es=new ArrayList<>(map.entities()); String id="entity_"+(es.size()+1); es.add(new MapEntity(id,"entity",new WorldPosition(x,y,0),null)); map=new RMap(map.name(),map.tileSize(),map.width(),map.height(),map.layers(),es);
            }
            invalidate();
        }
    }
}
