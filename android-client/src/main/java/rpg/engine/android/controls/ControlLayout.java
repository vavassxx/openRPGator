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
        // Landscape-friendly default: compact D-pad on the left, action diamond on the
        // right, and utility toggles kept in the top corners so they do not fight gameplay.
        l.add("up",ControlAction.MOVE_UP,ControlType.BUTTON,.14f,.69f,.12f,"↑");
        l.add("down",ControlAction.MOVE_DOWN,ControlType.BUTTON,.14f,.87f,.12f,"↓");
        l.add("left",ControlAction.MOVE_LEFT,ControlType.BUTTON,.05f,.78f,.12f,"←");
        l.add("right",ControlAction.MOVE_RIGHT,ControlType.BUTTON,.23f,.78f,.12f,"→");
        l.add("primary",ControlAction.PRIMARY,ControlType.BUTTON,.86f,.76f,.14f,"A");
        l.add("secondary",ControlAction.SECONDARY,ControlType.BUTTON,.72f,.86f,.11f,"B");
        l.add("interact",ControlAction.INTERACT,ControlType.BUTTON,.72f,.68f,.11f,"E");
        l.add("inventory",ControlAction.INVENTORY,ControlType.BUTTON,.89f,.17f,.09f,"I");
        l.add("camera-follow",ControlAction.CAMERA_FOLLOW,ControlType.BUTTON,.77f,.17f,.09f,"⌖");
        return l;
    }
    private void add(String id,ControlAction a,ControlType t,float x,float y,float s,String label){bindings.add(new ControlBinding(id,a,t,x,y,s,label));}

    public void cycleAction(ControlBinding binding){
        if(binding==null || !bindings.contains(binding)) return;
        ControlAction[] actions=ControlAction.values();
        int index=0;
        for(int i=0;i<actions.length;i++){
            if(actions[i]==binding.action){ index=i; break; }
        }
        binding.action=actions[(index+1)%actions.length];
    }

    public ControlBinding addCustom(ControlAction action,ControlType type,float x,float y,float size,String label){
        String id="custom-"+(bindings.size()+1);
        int suffix=bindings.size()+1;
        while(containsId(id)) id="custom-"+(++suffix);
        ControlBinding binding=new ControlBinding(id,action,type,clamp(x),clamp(y),clamp(size),label);
        bindings.add(binding);
        return binding;
    }

    public void remove(ControlBinding binding){
        if(binding!=null) bindings.remove(binding);
    }

    private boolean containsId(String id){
        for(ControlBinding b:bindings) if(b.id.equals(id)) return true;
        return false;
    }

    private static float clamp(float value){ return Math.max(0f,Math.min(1f,value)); }

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
            if(l.bindings.isEmpty()) return defaults();
            // Add newly introduced standard actions without disturbing an existing custom layout.
            boolean hasCameraFollow=false;
            for(ControlBinding v:l.bindings) if(v.action==ControlAction.CAMERA_FOLLOW){hasCameraFollow=true;break;}
            if(!hasCameraFollow)
                l.add("camera-follow",ControlAction.CAMERA_FOLLOW,ControlType.BUTTON,.77f,.17f,.09f,"⌖");
            return l;
        }catch(Exception e){return defaults();}
    }
    public void reset(){bindings.clear(); bindings.addAll(defaults().bindings);}
}
