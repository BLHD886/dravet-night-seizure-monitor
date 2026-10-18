package org.dravet.guard;

import android.app.*;
import android.content.*;
import android.os.Build;
import org.json.*;
import java.time.Instant;
import java.util.UUID;

final class PulseAlerts {
    private PulseAlerts(){}
    static void reset(LocalRepository repo){synchronized(PulseAlerts.class){repo.save("pulse_rule",new JSONObject());}}
    static void setEnabled(LocalRepository repo,boolean enabled){synchronized(PulseAlerts.class){repo.preferences().edit().putBoolean("pulse_alerts",enabled).commit();repo.save("pulse_rule",new JSONObject());}}
    static void setSource(LocalRepository repo,String source){synchronized(PulseAlerts.class){repo.preferences().edit().putString("view_source",source).commit();repo.save("pulse_rule",new JSONObject());}}
    static void ingest(Context context,LocalRepository repo,JSONArray samples) throws Exception {
        synchronized(PulseAlerts.class){
            String selected=repo.preferences().getString("view_source","live_device");
            if(!repo.preferences().getBoolean("pulse_alerts",false)||!(selected.equals("phone_ble")||selected.equals("live_device")))return;
            JSONObject old=repo.read("pulse_rule");PulseRule rule=new PulseRule();
            rule.stream=old.optString("stream");rule.band=old.optInt("band",-1);rule.announced=old.optInt("announced",-1);rule.since=old.optLong("since",-1);rule.last=old.optLong("last",-1);
            java.util.ArrayList<JSONObject> sorted=new java.util.ArrayList<>();for(int i=0;i<samples.length();i++)sorted.add(samples.getJSONObject(i));
            sorted.sort((a,b)->Long.compare(Instant.parse(a.optString("observed_at")).toEpochMilli(),Instant.parse(b.optString("observed_at")).toEpochMilli()));
            long now=System.currentTimeMillis();
            for(JSONObject s:sorted){
                if(!selected.equals(s.optString("source"))||!s.optString("sensor_type").equals("watch_hr"))continue;
                if(s.getJSONObject("payload").optString("protocol").equals("JX630-final"))continue;
                long stamp=Instant.parse(s.getString("observed_at")).toEpochMilli();String quality=s.optString("quality");
                int event=rule.accept(s.getJSONObject("payload").optDouble("value",Double.NaN),stamp,now,!quality.equals("invalid")&&!quality.equals("poor"),selected+":"+s.optString("device_id"));
                if(event>0){JSONObject item=new JSONObject().put("id",UUID.randomUUID().toString()).put("level",event).put("created_at",Instant.now().toString()).put("observed_at",s.getString("observed_at")).put("started_at",Instant.ofEpochMilli(rule.since).toString()).put("duration_ms",stamp-rule.since).put("value",s.getJSONObject("payload").getDouble("value")).put("source",selected).put("device_id",s.optString("device_id")).put("quality",quality).put("rule_version","user-pulse-v1-3s").put("is_demo",false).put("state","pending").put("notification",event==1?"in_app_only":"pending");
                    repo.saveAlert(item); // Persist before asking Android to notify.
                    notify(context,repo,item);
                }
            }
            repo.save("pulse_rule",new JSONObject().put("stream",rule.stream).put("band",rule.band).put("announced",rule.announced).put("since",rule.since).put("last",rule.last));
        }
    }
    static void notify(Context context,LocalRepository repo,JSONObject event) throws Exception {
        int level=event.getInt("level");if(level==1)return;
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(!manager.areNotificationsEnabled()){event.put("notification","permission_or_system_blocked");repo.saveAlert(event);return;}
        String channel=level==3?"pulse_l3_v1":"pulse_l2_v1";
        NotificationChannel c=new NotificationChannel(channel,level==3?"L3 脉搏重点提醒":"L2 脉搏提醒",NotificationManager.IMPORTANCE_HIGH);c.enableLights(false);manager.createNotificationChannel(c);
        NotificationChannel applied=manager.getNotificationChannel(channel);
        if(applied.getImportance()==NotificationManager.IMPORTANCE_NONE){event.put("notification","channel_blocked");repo.saveAlert(event);return;}
        Intent intent=new Intent(context,MainActivity.class).putExtra("open_alerts",true);
        PendingIntent open=PendingIntent.getActivity(context,73,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        try{manager.notify(notificationId(event.getString("id")),new Notification.Builder(context,channel).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("L"+level+" · 脉搏自定义规则提醒").setContentText("脉搏 "+event.getInt("value")+" 次/分，所记录档位持续至少3秒。请查看并到场确认。").setStyle(new Notification.BigTextStyle().bigText("脉搏 "+event.getInt("value")+" 次/分；达到用户设置的L"+level+"档。不是发作诊断，请结合实际情况确认。")).setContentIntent(open).setAutoCancel(true).build());event.put("notification","submitted_to_android");}
        catch(SecurityException e){event.put("notification","permission_or_system_blocked");}
        repo.saveAlert(event);
    }
    static int notificationId(String id){return 60000+Math.floorMod(id.hashCode(),100000);}
}
