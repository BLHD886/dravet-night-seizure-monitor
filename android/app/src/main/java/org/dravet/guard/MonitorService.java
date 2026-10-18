package org.dravet.guard;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

public final class MonitorService extends Service {
    private LocalRepository repository;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> pollTask;
    private final Set<String> faults=new HashSet<>();
    private long started, lastSuccess;
    private static final String STATUS="guard_status_v1", FAULT="guard_fault_v1", DEMO="guard_demo_v1";
    private NotificationManager notifications;
    @Override public void onCreate(){super.onCreate(); repository=new LocalRepository(this);notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(STATUS,"接收状态",NotificationManager.IMPORTANCE_LOW));
        NotificationChannel fault=new NotificationChannel(FAULT,"采集或连接故障（非患者告警）",NotificationManager.IMPORTANCE_HIGH); fault.enableLights(false); notifications.createNotificationChannel(fault);
        NotificationChannel demo=new NotificationChannel(DEMO,"手动演练提醒",NotificationManager.IMPORTANCE_HIGH);demo.enableLights(false);notifications.createNotificationChannel(demo);
    }
    private Notification notice(String channel,String title,String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(!channel.equals(STATUS)).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(executor!=null)return START_NOT_STICKY;
        Notification n=notice(STATUS,"工程接收运行中","仅监测数据链路；自动发作识别未启用");
        if(Build.VERSION.SDK_INT>=29)startForeground(1,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(1,n);
        started=SystemClock.elapsedRealtime();lastSuccess=started;
        repository.preferences().edit().putBoolean("running",true).apply();
        executor=Executors.newSingleThreadScheduledExecutor();pollTask=executor.scheduleWithFixedDelay(this::poll,0,5,TimeUnit.SECONDS);
        return START_NOT_STICKY;
    }
    private void fault(String key,String text){if(faults.add(key))notifications.notify(100+Math.abs(key.hashCode()%10000),notice(FAULT,"技术故障 · 请检查设备",text+"；不能据此判断患者状态"));}
    private void clearFault(String key){if(faults.remove(key))notifications.cancel(100+Math.abs(key.hashCode()%10000));}
    private void poll(){
        try{
            JSONObject status=new ApiClient(repository).request("/v1/live/status",null); status.put("phone_received_at",System.currentTimeMillis());
            repository.save("status",status); lastSuccess=SystemClock.elapsedRealtime();clearFault("network");
            String source=repository.preferences().getString("view_source","live_device");
            if(!source.equals("phone_ble")){JSONObject history=new ApiClient(repository).request("/v1/history?source="+source,null);repository.saveSamples(history.getJSONArray("samples"));PulseAlerts.ingest(this,repository,history.getJSONArray("samples"));repository.save("history_sync",new JSONObject().put("at",java.time.Instant.now().toString()).put("possibly_truncated",history.optBoolean("possibly_truncated")));}
            JSONArray devices=status.getJSONArray("devices");
            for(int i=0;i<devices.length();i++){
                JSONObject d=devices.getJSONObject(i);String id=d.getString("device_id"),state=d.getString("state");
                if(id.equals("watch-standard-hr")&&!repository.preferences().getBoolean("watch",false))continue;
                if(repository.preferences().getBoolean("internal_alerts",false)&&!state.equals("receiving_unvalidated")&&SystemClock.elapsedRealtime()-started>30000)fault(id,id+"："+state);else if(state.equals("receiving_unvalidated"))clearFault(id);
            }
            JSONArray events=status.optJSONArray("events");
            if(repository.preferences().getBoolean("internal_alerts",false)&&events!=null)for(int i=0;i<events.length();i++){
                JSONObject event=events.getJSONObject(i);String id=event.getString("id");
                if(!repository.seen(id)){
                    long timestamp=java.time.Instant.parse(event.getString("created_at")).toEpochMilli();
                    if(System.currentTimeMillis()-timestamp<600000 && System.currentTimeMillis()>=timestamp){
                        int level=event.getInt("level");String channel=level==1?STATUS:DEMO;
                        notifications.notify(20000+Math.abs(id.hashCode()%10000),notice(channel,"手动演练 · "+level+"级","不是设备自动识别；请在事件页反馈"));
                    }
                    repository.markSeen(id);
                }
            }
        }catch(Exception e){
            try{JSONObject state=new JSONObject();state.put("error","手机与服务连接失败或响应无效");state.put("phone_received_at",System.currentTimeMillis());repository.save("status",state);}catch(Exception ignored){}
            if(repository.preferences().getBoolean("internal_alerts",false)&&SystemClock.elapsedRealtime()-lastSuccess>15000)fault("network","手机未收到服务更新");
        }
    }
    @Override public void onDestroy(){repository.preferences().edit().putBoolean("running",false).apply();if(executor!=null){if(pollTask!=null)pollTask.cancel(false);executor.execute(repository::close);executor.shutdown();}else repository.close();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
