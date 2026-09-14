package rpg.engine.android.controls;

public final class ControlBinding {
    public final String id;
    public ControlAction action;
    public ControlType type;
    public float x, y, size;
    public String label;

    public ControlBinding(String id, ControlAction action, ControlType type, float x, float y, float size, String label) {
        this.id=id; this.action=action; this.type=type; this.x=x; this.y=y; this.size=size; this.label=label;
    }
}
