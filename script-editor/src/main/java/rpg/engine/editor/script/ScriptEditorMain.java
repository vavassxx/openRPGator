package rpg.engine.editor.script;
import org.luaj.vm2.Globals;
import org.luaj.vm2.lib.jse.JsePlatform;
import javax.swing.*; import java.awt.*;
public final class ScriptEditorMain {
 public static void main(String[] args){ SwingUtilities.invokeLater(() -> {
  var f=new JFrame("Lua Script Editor"); var text=new JTextArea("engine.log('hello')\n"); var run=new JButton("Run check"); var out=new JLabel("Ready");
  run.addActionListener(e->{try{Globals g=JsePlatform.standardGlobals(); g.load(text.getText(),"editor"); out.setText("Syntax OK");}catch(Exception ex){out.setText(ex.getMessage());}});
  f.add(new JScrollPane(text),BorderLayout.CENTER); var b=new JPanel(); b.add(run); b.add(out); f.add(b,BorderLayout.SOUTH); f.setSize(800,600); f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); f.setVisible(true);
 }); }
}
