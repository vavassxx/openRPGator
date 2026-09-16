package rpg.engine.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import android.widget.*;
import java.io.*;
import java.util.*;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.map.*;

/**
 * A deliberately dependency-light Android .rmap editor: no engine renderer, no OpenGL, no desktop
 * UI. Tiles and entity sprites preview from the shared data folder ({@code data/paks}); entities
 * are placed with an explicit sprite/prefab binding and their prefab/script can be edited with a
 * long-press.
 */
public final class MapEditorActivity extends Activity {
    private AppStorage appStorage;
    private AppLogger logger;
    private static final int OPEN = 10, SAVE = 11;
    private EditorView editor;
    private final PakAtlas atlas = new PakAtlas();
    private LinearLayout paletteRow;
    private HorizontalScrollView paletteScroll;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        applyImmersive();
        appStorage = new AppStorage(this);
        logger = AppLogger.get(this);
        logger.info("Map editor opening");
        try {
            atlas.loadDir(appStorage.pakFiles());
            if (atlas.spriteCount() > 0 || atlas.tileCount() > 0)
                logger.info("Loaded atlases: " + atlas.tileCount() + " tiles, " + atlas.spriteCount() + " sprites");
            editor = new EditorView();
            buildUi();
            rebuildPalette();
            logger.info("Map editor UI ready");
        } catch (Throwable t) {
            logger.error("Map editor failed during initialization", t);
            Toast.makeText(this, "Map editor failed: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getDecorView().post(() -> {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller == null) return;
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            });
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

        Button open = button("Open"); open.setOnClickListener(v -> openMap());
        Button save = button("Save"); save.setOnClickListener(v -> saveMap());
        Button newMap = button("New"); newMap.setOnClickListener(v -> newMapDialog());
        Button mode = button("Paint"); mode.setOnClickListener(v -> {
            editor.mode = editor.mode == Mode.PAINT ? Mode.COLLISION : editor.mode == Mode.COLLISION ? Mode.ENTITY : Mode.PAINT;
            mode.setText(editor.mode.label);
            rebuildPalette();
            editor.invalidate();
        });
        Button erase = button("Erase"); erase.setOnClickListener(v -> editor.erase = !editor.erase);
        bar.addView(open); bar.addView(save); bar.addView(newMap); bar.addView(mode); bar.addView(erase);

        HorizontalScrollView toolbarScroll = new HorizontalScrollView(this);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        toolbarScroll.addView(bar, new ViewGroup.LayoutParams(-2, dp(52)));
        root.addView(toolbarScroll, new LinearLayout.LayoutParams(-1, dp(52)));

        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));

        paletteRow = new LinearLayout(this);
        paletteRow.setOrientation(LinearLayout.HORIZONTAL);
        paletteRow.setGravity(Gravity.CENTER_VERTICAL);
        paletteRow.setPadding(6,4,6,4);
        paletteScroll = new HorizontalScrollView(this);
        paletteScroll.setHorizontalScrollBarEnabled(false);
        paletteScroll.addView(paletteRow, new ViewGroup.LayoutParams(-2, dp(64)));
        root.addView(paletteScroll, new LinearLayout.LayoutParams(-1, dp(64)));

        setContentView(root);
    }

    private void rebuildPalette() {
        paletteRow.removeAllViews();
        if (editor.mode == Mode.ENTITY) {
            paletteRow.addView(thumb(null, "✱ plain", editor.selectedPrefab == null || editor.selectedPrefab.isEmpty()));
            String[] keys = atlas.spriteKeys();
            for (final String key : keys)
                paletteRow.addView(thumb(atlas.spriteImage(key), key, key.equals(editor.selectedPrefab)));
        } else {
            int n = Math.max(9, atlas.tileCount());
            for (int id = 0; id < n; id++) {
                final int tile = id;
                Bitmap bmp = atlas.tile(id);
                Button b = thumb(bmp, String.valueOf(id), editor.selectedTile == id);
                b.setOnClickListener(v -> { editor.selectedTile = tile; rebuildPalette(); editor.invalidate(); });
                paletteRow.addView(b);
            }
        }
        paletteScroll.invalidate();
    }

    private Button thumb(final Bitmap src, String label, boolean selected) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(9);
        b.setPadding(2, 2, 2, 2);
        if (selected) b.setBackgroundColor(Color.rgb(90, 90, 120)); else b.setBackgroundColor(0);
        if (src != null) {
            Bitmap scaled = Bitmap.createScaledBitmap(src, dp(34), dp(34), true);
            b.setCompoundDrawablesWithIntrinsicBounds(null, new BitmapDrawable(getResources(), scaled), null, null);
            b.setHeight(0);
        }
        b.setOnClickListener(v -> {
            editor.selectedPrefab = label.equals("✱ plain") ? "" : label;
            rebuildPalette();
            editor.invalidate();
        });
        return b;
    }

    private Button button(String text) { Button b = new Button(this); b.setText(text); b.setTextSize(11); return b; }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    private void saveMap() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            RMapIO.write(editor.map, out);
            appStorage.saveMap(editor.map.name() + ".rmap", out.toByteArray());
            logger.info("Saved map: " + editor.map.name());
            Toast.makeText(this, "Saved to application data/maps", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            logger.error("Failed to save map: " + editor.map.name(), e);
            Toast.makeText(this, "RMAP save: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openMap() {
        final EditText name = edit("map file", editor.map.name() + ".rmap");
        new android.app.AlertDialog.Builder(this).setTitle("Open map from data/maps")
            .setView(name).setNegativeButton("Cancel", null).setPositiveButton("Open", (d,w) -> {
                try {
                    byte[] data = appStorage.loadMap(name.getText().toString().trim());
                    if(data == null) { Toast.makeText(this, "Map not found", Toast.LENGTH_SHORT).show(); return; }
                    try(InputStream in = new ByteArrayInputStream(data)) { editor.map = RMapIO.read(in); }
                    editor.resetView(); editor.invalidate();
                    logger.info("Opened map: " + editor.map.name());
                } catch(Exception e) {
                    logger.error("Failed to open map", e);
                    Toast.makeText(this, "RMAP open: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (result != RESULT_OK || data == null) return;
        try {
            Uri uri = data.getData();
            if (request == OPEN) { try (InputStream in = getContentResolver().openInputStream(uri)) { editor.map = RMapIO.read(in); editor.resetView(); } }
            else { try (OutputStream out = getContentResolver().openOutputStream(uri)) { RMapIO.write(editor.map, out); } }
            editor.invalidate();
        } catch (Exception e) { logger.error("RMAP file picker operation failed", e); Toast.makeText(this, "RMAP: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void newMapDialog() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(32,8,32,0);
        EditText name = edit("map name", "new_map"); EditText width = edit("width", "32"); EditText height = edit("height", "32");
        box.addView(name); box.addView(width); box.addView(height);
        new android.app.AlertDialog.Builder(this).setTitle("New RMap").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Create", (d,w) -> {
            try { int x = Math.max(1, Math.min(512, Integer.parseInt(width.getText().toString()))); int y = Math.max(1, Math.min(512, Integer.parseInt(height.getText().toString()))); editor.map = blankMap(name.getText().toString(), x, y); editor.resetView(); editor.invalidate(); }
            catch (Exception e) { Toast.makeText(this, "Bad map size", Toast.LENGTH_SHORT).show(); }
        }).show();
    }

    private void editorDialog(int gridX, int gridY) {
        int idx = editor.entityAt(gridX, gridY);
        if (idx < 0) return;
        final MapEntity e = editor.map.entities().get(idx);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(32,8,32,0);
        EditText id = edit("id", e.id());
        EditText prefab = edit("prefab (sprite key)", e.prefab());
        EditText script = edit("script file", e.script() == null ? "" : e.script());
        box.addView(id); box.addView(prefab); box.addView(script);
        new android.app.AlertDialog.Builder(this).setTitle("Edit entity @(" + gridX + "," + gridY + ")")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete", (d,w) -> {
                List<MapEntity> es = new ArrayList<>(editor.map.entities());
                es.remove(idx);
                editor.map = new RMap(editor.map.name(), editor.map.tileSize(), editor.map.width(), editor.map.height(), editor.map.layers(), es);
                editor.invalidate();
                Toast.makeText(this, "Entity deleted", Toast.LENGTH_SHORT).show();
            })
            .setPositiveButton("Apply", (d,w) -> {
                String pid = id.getText().toString().trim();
                String pprefab = prefab.getText().toString().trim();
                String pscript = script.getText().toString().trim();
                List<MapEntity> es = new ArrayList<>(editor.map.entities());
                es.set(idx, new MapEntity(pid.isEmpty() ? e.id() : pid,
                        pprefab.isEmpty() ? "entity" : pprefab, e.position(), pscript.isEmpty() ? null : pscript));
                editor.map = new RMap(editor.map.name(), editor.map.tileSize(), editor.map.width(), editor.map.height(), editor.map.layers(), es);
                editor.selectedPrefab = pprefab;
                editor.invalidate();
                Toast.makeText(this, "Entity updated", Toast.LENGTH_SHORT).show();
            }).show();
    }
    private EditText edit(String hint, String value) { EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(); return e; }

    private RMap blankMap(String name, int w, int h) {
        String n = name == null || name.trim().isEmpty() ? "new_map" : name;
        return new RMap(n, 32, w, h, Collections.singletonList(
            new TileLayer("ground", w, h, new int[w*h], false)),
            new ArrayList<MapEntity>());
    }

    enum Mode { PAINT("Paint"), COLLISION("Collision"), ENTITY("Entity"); final String label; Mode(String s){label=s;} }

    final class EditorView extends View {
        RMap map = blankMap("new_map", 32, 32);
        Mode mode = Mode.PAINT; int selectedTile = 1; String selectedPrefab = ""; boolean erase; float zoom = 1f, ox = 0, oy = 0; float downX, downY, lastX, lastY; boolean dragging;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); final int grid = 48;
        private boolean longPressFired;
        private final GestureDetector detector;
        EditorView() {
            super(MapEditorActivity.this);
            paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            setFocusable(true);
            detector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override public void onLongPress(android.view.MotionEvent ev) {
                    longPressFired = true;
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    onLongPressTap(ev.getX(), ev.getY());
                }
            });
        }
        void resetView(){ zoom=1; ox=getWidth()/2f; oy=80; }

        int entityAt(int gx, int gy) {
            List<MapEntity> es = map.entities();
            for (int i = es.size() - 1; i >= 0; i--) {
                WorldPosition p = es.get(i).position();
                if ((int)Math.floor(p.x()) == gx && (int)Math.floor(p.y()) == gy) return i;
            }
            return -1;
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); c.drawColor(Color.rgb(30,31,35));
            float s = grid * zoom;
            for (int y=0;y<map.height();y++) for (int x=0;x<map.width();x++) {
                float l=ox+x*s, t=oy+y*s, r=l+s, b=t+s; int id=map.layers().get(0).tiles()[y*map.width()+x];
                paint.setStyle(Paint.Style.FILL); paint.setColor(tileColor(id)); c.drawRect(l,t,r,b,paint);
                Bitmap tb = atlas.tile(id);
                if (tb != null) { Rect dst = new Rect((int)l, (int)t, (int)r, (int)b); c.drawBitmap(tb, null, dst, paint); }
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(Color.argb(55,255,255,255)); c.drawRect(l,t,r,b,paint);
                if (map.layers().get(0).collision() && id != 0) { paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(70,255,70,70)); c.drawRect(l,t,r,b,paint); }
            }
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(24); paint.setColor(Color.WHITE);
            c.drawText(map.name()+"  " + map.width()+"x"+map.height()+"  " + mode.label + "  tile="+selectedTile + (erase?"  erase":""), 12, 30, paint);
            for (MapEntity e : map.entities()) {
                float x=ox+(float)e.position().x()*s+s/2, y=oy+(float)e.position().y()*s+s/2;
                Bitmap sb = atlas.spriteImage(e.prefab());
                paint.setStyle(Paint.Style.FILL);
                if (sb != null) {
                    float sc = Math.min((s*.9f)/sb.getWidth(), (s*.9f)/sb.getHeight());
                    int w = (int)(sb.getWidth()*sc), h = (int)(sb.getHeight()*sc);
                    Rect dst = new Rect((int)(x-w/2f), (int)(y-h/2f), (int)(x+w/2f), (int)(y+h/2f));
                    c.drawBitmap(sb, null, dst, paint);
                } else {
                    paint.setColor(Color.MAGENTA); c.drawCircle(x,y,Math.max(5,s*.22f),paint);
                }
                paint.setColor(Color.WHITE); paint.setTextSize(Math.max(10,s*.22f)); c.drawText(e.id(),x+8,y,paint);
            }
        }

        private int tileColor(int id) {
            if (id == 0) return Color.rgb(48,49,54);
            float h = (id * 37) % 360; return Color.HSVToColor(new float[]{h, .48f, .62f});
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            detector.onTouchEvent(e);
            float x=e.getX(), y=e.getY();
            if (e.getActionMasked()==MotionEvent.ACTION_DOWN) {
                downX=lastX=x; downY=lastY=y; dragging=false; longPressFired=false; return true;
            }
            if (e.getActionMasked()==MotionEvent.ACTION_MOVE) {
                if (longPressFired) return true;
                if (Math.abs(x-downX)+Math.abs(y-downY)>12) dragging=true;
                if (dragging) { ox += x-lastX; oy += y-lastY; lastX=x; lastY=y; invalidate(); } else paintAt(x,y);
                return true;
            }
            if (e.getActionMasked()==MotionEvent.ACTION_UP) { if (!dragging && !longPressFired) paintAt(x,y); return true; }
            if (e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount()==2) return true;
            return true;
        }

        public void onLongPressTap(float sx, float sy) {
            float s=grid*zoom; int x=(int)Math.floor((sx-ox)/s), y=(int)Math.floor((sy-oy)/s);
            if(x<0||y<0||x>=map.width()||y>=map.height()) return;
            if(mode==Mode.ENTITY && entityAt(x,y)>=0) {
                editorDialog(x,y);
            } else if(mode==Mode.ENTITY) {
                addEntity(x,y);
            }
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
                addEntity(x,y);
            }
            invalidate();
        }

        private void addEntity(int x,int y) {
            int idx=entityAt(x,y);
            ArrayList<MapEntity> es=new ArrayList<>(map.entities());
            if(idx>=0) { editorDialog(x,y); return; }
            String id="entity_"+(es.size()+1); String prefab=selectedPrefab.isEmpty()?"entity":selectedPrefab;
            es.add(new MapEntity(id,prefab,new WorldPosition(x,y,0),null)); map=new RMap(map.name(),map.tileSize(),map.width(),map.height(),map.layers(),es);
        }
    }
}