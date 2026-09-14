package rpg.engine.script.runtime;import java.util.List;@FunctionalInterface public interface ScriptFunction{Object call(List<Object> args);}
