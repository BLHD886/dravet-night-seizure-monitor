package org.dravet.guard;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.JSONObject;

/** User-started foreground BLE collector. Survives closing the interface, not OS force-stop. */
public final class BleService extends Service {
    private static BleService instance;
    private static volatile String last="{}";
    private LocalRepository repository;
    private BleWatch watch;
    private final Handler handler=new Handler(Looper.getMainLooper());
    static JSONObject snapshot() throws Exception {return new JSONObject(last);}
    private void publish(){try{last=watch.state().toString();}catch(Exception ignored){}}
    private final Runnable publishState=new Runnable(){public void run(){publish();handler.postDelayed(this,500);}};
    static void action(String command,String address) throws Exception {
        if(instance==null)throw new Exception("蓝牙采集服务尚未就绪，请再次操作");
        if(command.equals("bleScan"))instance.watch.scan();
        else if(command.equals("bleConnect"))instance.watch.connect(address);
        else if(command.equals("bleOxygen"))instance.watch.measureOxygen();
        else if(command.equals("blePulse"))instance.watch.measurePulse();
        else if(command.equals("bleStopMeasurement"))instance.watch.stopMeasurement();
        else if(command.equals("bleDisconnect")){instance.watch.disconnect();instance.publish();instance.stopSelf();}
        else if(!command.equals("bleState"))throw new Exception("未知蓝牙操作");
        if(instance!=null)instance.publish();
    }
    @Override public void onCreate(){super.onCreate();repository=new LocalRepository(this);watch=new BleWatch(this,repository);instance=this;handler.post(publishState);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("ble_collection_v1","蓝牙采集运行状态",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,74,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this,"ble_collection_v1").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("腕表蓝牙采集运行中").setContentText("脉搏与血氧接收；自定义预警需在App内开启").setContentIntent(open).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(72,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(72,notification);
        repository.preferences().edit().putBoolean("ble_running",true).apply();
        if(intent!=null&&intent.getBooleanExtra("scan",false))try{watch.scan();}catch(Exception e){try{last=new JSONObject().put("message",e.getMessage()).toString();}catch(Exception ignored){}}
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){watch.close();publish();instance=null;handler.removeCallbacksAndMessages(null);repository.preferences().edit().putBoolean("ble_running",false).apply();repository.close();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
