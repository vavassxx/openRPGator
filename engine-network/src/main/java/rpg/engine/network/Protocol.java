package rpg.engine.network;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;

public final class Protocol {
 private Protocol(){}
 public static void write(OutputStream raw,Packet p)throws IOException{
  var b=new ByteArrayOutputStream();var out=new DataOutputStream(b);out.writeByte(p.type());
  if(p instanceof Hello x) str(out,x.name());
  else if(p instanceof Welcome x) out.writeLong(x.entityId());
  else if(p instanceof Input x){out.writeDouble(x.dx());out.writeDouble(x.dy());out.writeInt(x.actions());}
  else if(p instanceof Snapshot x){out.writeInt(x.entities().size());for(var e:x.entities()){out.writeLong(e.id());out.writeDouble(e.x());out.writeDouble(e.y());out.writeDouble(e.elevation());}}
  out.flush();byte[] a=b.toByteArray();var h=new DataOutputStream(raw);h.writeInt(a.length);h.write(a);h.flush();
 }
 public static Packet read(InputStream raw)throws IOException{
  var in=new DataInputStream(raw);int n=in.readInt();if(n<1||n>4_000_000)throw new IOException("invalid packet");byte[] a=in.readNBytes(n);if(a.length!=n)throw new EOFException();var d=new DataInputStream(new ByteArrayInputStream(a));
  return switch(d.readByte()){
   case 1->new Hello(str(d)); case 2->new Welcome(d.readLong()); case 3->new Input(d.readDouble(),d.readDouble(),d.readInt());
   case 4->{int c=d.readInt();var es=new ArrayList<Snapshot.EntityState>(c);for(int i=0;i<c;i++)es.add(new Snapshot.EntityState(d.readLong(),d.readDouble(),d.readDouble(),d.readDouble()));yield new Snapshot(es);}
   default->throw new IOException("unknown packet");
  };
 }
 private static void str(DataOutput o,String s)throws IOException{byte[]b=s.getBytes(StandardCharsets.UTF_8);o.writeInt(b.length);o.write(b);}
 private static String str(DataInput i)throws IOException{int n=i.readInt();if(n<0||n>1_000_000)throw new IOException();byte[]b=new byte[n];i.readFully(b);return new String(b,StandardCharsets.UTF_8);}
}
