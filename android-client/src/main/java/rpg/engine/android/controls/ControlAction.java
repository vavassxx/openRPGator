package rpg.engine.android.controls;
public enum ControlAction {
 MOVE_UP("Вверх"), MOVE_DOWN("Вниз"), MOVE_LEFT("Влево"), MOVE_RIGHT("Вправо"), PRIMARY("Основное действие"), SECONDARY("Второе действие"), INTERACT("Взаимодействие"), INVENTORY("Инвентарь");
 public final String title; ControlAction(String title){this.title=title;}
}
