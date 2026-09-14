package rpg.engine.android;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import rpg.engine.android.controls.*;
import rpg.engine.network.*;

public final class MainActivity extends Activity implements ClientSession.Listener {
    private ControlLayout controls;
    private ControlOverlay overlay;
    private GameView game;
    private ClientSession session;
    private final EnumSet<ControlAction> held = EnumSet.noneOf(ControlAction.class);
    private int actionBits;
    private boolean editing;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        controls=ControlLayout.load(this);
        session=new ClientSession(this);
        showGame();
    }

    private void showGame(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(18,19,22));
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(8,4,8,4);
        EditText host=field("server", "127.0.0.1"); EditText port=field("port","27991"); EditText name=field("name","player");
        Button connect=button("Connect"), edit=button("Controls"), save=button("Save layout"), map=button("Map editor");
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setText("Offline");
        bar.addView(host,new LinearLayout.LayoutParams(0,48,2));bar.addView(port,new LinearLayout.LayoutParams(0,48,1));bar.addView(name,new LinearLayout.LayoutParams(0,48,1));bar.addView(connect);
        bar.addView(edit);bar.addView(save);bar.addView(map);bar.addView(status,new LinearLayout.LayoutParams(0,48,1));root.addView(bar,new LinearLayout.LayoutParams(-1,56));
        FrameLayout stage=new FrameLayout(this);game=new GameView(this);stage.addView(game,new FrameLayout.LayoutParams(-1,-1));
        overlay=new ControlOverlay(this,controls,(a,pressed)->onAction(a,pressed));stage.addView(overlay,new FrameLayout.LayoutParams(-1,-1));root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        connect.setOnClickListener(v->{try{session.connect(host.getText().toString().trim(),Integer.parseInt(port.getText().toString().trim()),name.getText().toString().trim());status.setText("Connecting…");}catch(Exception e){status.setText("Bad address");}});
        edit.setOnClickListener(v->{editing=!editing;overlay.setEditMode(editing);edit.setText(editing?"Play":"Controls");status.setText(editing?"Drag controls; tap selected then use editor below":"Playing");});
        save.setOnClickListener(v->{controls.save(this);status.setText("Controls saved");});
        map.setOnClickListener(v->startActivity(new Intent(this,MapEditorActivity.class)));
        // A compact editor row appears while controls are being customized.
        Button action=button("Action +"), type=button("Type"), smaller=button("Size -"), larger=button("Size +"), add=button("Add"), remove=button("Delete");
        LinearLayout editbar=new LinearLayout(this);editbar.setGravity(Gravity.CENTER);editbar.addView(action);editbar.addView(type);editbar.addView(smaller);editbar.addView(larger);editbar.addView(add);editbar.addView(remove);root.addView(editbar,new LinearLayout.LayoutParams(-1,48));
        action.setOnClickListener(v->{if(overlay.selected()!=null){controls.cycleAction(overlay.selected());overlay.invalidate();}});
        type.setOnClickListener(v->{overlay.cycleType();});
        smaller.setOnClickListener(v->{if(overlay.selected()!=null){overlay.selected().size=Math.max(.05f,overlay.selected().size-.02f);overlay.invalidate();}});
        larger.setOnClickListener(v->{if(overlay.selected()!=null){overlay.selected().size=Math.min(.35f,overlay.selected().size+.02f);overlay.invalidate();}});
        add.setOnClickListener(v->{overlay.setEditMode(true);editing=true;ControlBinding b=controls.addCustom(ControlAction.PRIMARY,ControlType.BUTTON,.5f,.5f,.12f,"A");overlay.invalidate();status.setText("New control selected; drag it");});
        remove.setOnClickListener(v->{if(overlay.selected()!=null){controls.remove(overlay.selected());overlay.invalidate();}});
        setContentView(root);
    }
    private EditText field(String hint,String value){EditText e=new EditText(this);e.setHint(hint);e.setText(value);e.setTextColor(Color.WHITE);e.setSingleLine();return e;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(10);return b;}
    private void onAction(ControlAction a,boolean pressed){if(pressed)held.add(a);else held.remove(a);actionBits=0;if(held.contains(ControlAction.PRIMARY))actionBits|=Input.PRIMARY;if(held.contains(ControlAction.SECONDARY))actionBits|=Input.SECONDARY;if(held.contains(ControlAction.INTERACT))actionBits|=Input.INTERACT;if(held.contains(ControlAction.INVENTORY))actionBits|=Input.INVENTORY;sendMovement();}
    private void sendMovement(){double dx=(held.contains(ControlAction.MOVE_RIGHT)?1:0)-(held.contains(ControlAction.MOVE_LEFT)?1:0);double dy=(held.contains(ControlAction.MOVE_DOWN)?1:0)-(held.contains(ControlAction.MOVE_UP)?1:0);session.input(dx,dy,actionBits);}
    @Override public void connected(Welcome w){runOnUiThread(()->status.setText("Connected #"+w.entityId()));}
    @Override public void snapshot(Snapshot s){runOnUiThread(()->game.setSnapshot(s));}
    @Override public void status(String s){runOnUiThread(()->status.setText(s));}
    @Override protected void onDestroy(){session.disconnect();super.onDestroy();}

    final class GameView extends View{
        private Snapshot snapshot=new Snapshot(List.of());private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        GameView(Context c){super(c);p.setTypeface(Typeface.create("sans",Typeface.NORMAL));}
        void setSnapshot(Snapshot s){snapshot=s;invalidate();}
        @Override protected void onDraw(Canvas c){c.drawColor(Color.rgb(36,48,42));float tile=48;float ox=getWidth()/2f,oy=getHeight()/3f;
            p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(58,78,65));for(int y=-8;y<16;y++)for(int x=-12;x<14;x++){float sx=ox+(x-y)*tile*.5f,sy=oy+(x+y)*tile*.25f;c.drawRect(sx,sy,sx+tile*.5f,sy+tile*.25f,p);}
            p.setTextSize(28);p.setColor(Color.WHITE);c.drawText("openRPGator",20,34,p);
            for(Snapshot.EntityState e:snapshot.entities()){float sx=ox+(float)(e.x()-e.y())*tile*.5f,sy=oy+(float)(e.x()+e.y())*tile*.25f-(float)e.elevation()*12;p.setColor(Color.rgb(230,180,80));c.drawCircle(sx,sy,18,p);p.setColor(Color.BLACK);p.setTextSize(11);c.drawText(Long.toString(e.id()),sx-7,sy+4,p);}
            if(snapshot.entities().isEmpty()){p.setColor(Color.WHITE);p.setTextSize(18);c.drawText("Connect to a server",20,70,p);}
        }
    }
}
