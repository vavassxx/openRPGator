package rpg.engine.script;import org.junit.jupiter.api.*;import rpg.engine.script.runtime.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class ScriptTest{@Test void executesExpressions(){var h=new ScriptHost();h.function("add",a->((Number)a.get(0)).doubleValue()+((Number)a.get(1)).doubleValue());assertEquals(7.0,new ScriptEngine(h).execute("let x=3; return add(x,4);"));}}
