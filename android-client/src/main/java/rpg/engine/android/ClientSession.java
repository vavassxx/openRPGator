package rpg.engine.android;

import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import rpg.engine.network.*;

final class ClientSession {
 interface Listener { void connected(Welcome w); void snapshot(Snapshot s); void status(String s); }
 private final Listener listener; private Socket socket; private OutputStream out; private final Object lock=new Object();
 ClientSession(Listener l){listener=l;}
 void connect(String host,int port,String name){
  disconnect(); new Thread(()->{try{
   socket=new Socket();socket.connect(new InetSocketAddress(host,port),5000);socket.setTcpNoDelay(true);out=socket.getOutputStream();
   Protocol.write(out,new Hello(name));Packet p=Protocol.read(socket.getInputStream());if(!(p instanceof Welcome w))throw new IOException("server rejected Hello");listener.connected(w);
   while(!socket.isClosed()){Packet q=Protocol.read(socket.getInputStream());if(q instanceof Snapshot s)listener.snapshot(s);}
  }catch(Exception e){listener.status("Disconnected: "+e.getMessage());} }).start();
 }
 void input(double dx,double dy,int actions){try{ synchronized(lock){if(out!=null)Protocol.write(out,new Input(dx,dy,actions));}}catch(IOException e){listener.status("Send failed: "+e.getMessage());}}
 void disconnect(){try{if(socket!=null)socket.close();}catch(IOException ignored){}socket=null;out=null;}
}
