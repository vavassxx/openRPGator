package rpg.engine.android;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Diagnostic logger. Always keeps a private copy for reliable log export and also mirrors to the configured data folder. */
public final class AppLogger {
    private static final String TAG="openRPGator";
    private static AppLogger instance;
    private final AppStorage storage;
    private final SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US);
    private final Object lock=new Object();

    private AppLogger(Context context){ storage=new AppStorage(context); }
    public static synchronized AppLogger get(Context context){
        if(instance==null) instance=new AppLogger(context.getApplicationContext());
        return instance;
    }

    public void info(String message){ write("INFO",message,null); }
    public void warn(String message){ write("WARN",message,null); }
    public void error(String message,Throwable error){ write("ERROR",message,error); }

    /** Returns the private log file that is always kept inside app storage. */
    public File privateLogFile(){
        return storage.privateLogFile("openrpgator.log");
    }

    private void write(String level,String message,Throwable error){
        String text=format.format(new Date())+" ["+level+"] "+message+(error==null?"":"\n"+Log.getStackTraceString(error))+"\n";
        if("ERROR".equals(level)) Log.e(TAG,message,error);
        else if("WARN".equals(level)) Log.w(TAG,message);
        else Log.i(TAG,message);

        synchronized(lock){
            // This copy is independent from SAF and is the source used by "Share log".
            try(OutputStream out=new FileOutputStream(privateLogFile(),true)){
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }catch(Exception e){
                Log.e(TAG,"Unable to persist private log",e);
            }

            // Best-effort copy to the user-selected data folder.
            try(OutputStream out=storage.createLog("openrpgator.log")){
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }catch(Exception e){
                Log.e(TAG,"Unable to persist log to selected data folder",e);
            }
        }
    }
}
