package rpg.engine.android.controls;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/** User-editable logical control layout. Coordinates are normalized to [0,1]. */
public final class ControlLayout {
    private static final String PREFS="openrpgator.controls.v1";
    private final ArrayList<ControlBinding> bindings = new ArrayList<>();
    public List<ControlBinding> bindings(){ return Collections.unmodifiableList(bindings); }

    public static ControlLayout defaults(){
        ControlLayout l=new ControlLayout();
        l.add("up",ControlAction.MOVE_UP,ControlType.BUTTON,.12f,.68f,.13f,"↑");
        l.add("down",ControlAction.MOVE_DOWN,ControlType.BUTTON,.12f,.88f,.13f,"↓");
        l.add("left",ControlAction.MOVE_LEFT,ControlType.BUTTON,.02f,.78f,.13f,"←");
        l.add("right",ControlAction.MOVE_RIGHT,ControlType.BUTTON,.22f,.78f,.13f,"→");
        l.add("primary",ControlAction.PRIMARY,ControlType.BUTTON,.84f,.76f,.15f,"A");
        l.add("secondary",ControlAction.SECONDARY,ControlType.BUTTON,.69f,.88f,.12f,"B");
        l.add("interact",ControlAction.INTERACT,ControlType.BUTTON,.69f,.70f,.12f,"E");
        l.add("inventory",ControlAction.INVENTORY,ControlType.BUTTON,.88f,.56f,.09f,"I");
        return l;
    }
    private void add(String id,ControlAction a,ControlType t,float x,float y,float s,String label){bindings.add(new ControlBinding(id,a,t,x,y,s,label));}

    public void save(Context c){
        StringBuilder b=new StringBuilder();
        for(ControlBinding v:bindings){
            if(b.length()>0)b.append(';');
            b.append(v.id).append('|').append(v.action.name()).append('|').append(v.type.name()).append('|')
             .append(v.x).append('|').append(v.y).append('|').append(v.size).append('|').append(v.label.replace("|",""));
        }
        c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("layout",b.toString()).apply();
    }
    public static ControlLayout load(Context c){
        String raw=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("layout",null);
        if(raw==null||raw.isBlank())return defaults();
        ControlLayout l=new ControlLayout();
        try{
            for(String row:raw.split(";")){String[] p=row.split("\\|",-1); if(p.length<7)continue;
                l.add(p[0],ControlAction.valueOf(p[1]),ControlType.valueOf(p[2]),Float.parseFloat(p[3]),Float.parseFloat(p[4]),Float.parseFloat(p[5]),p[6]);
            }
            return l.bindings.isEmpty()?defaults():l;
        }catch(Exception e){return defaults();}
    }
    public void reset(){bindings.clear(); bindings.addAll(defaults().bindings);}
}
