package org.dravet.guard;

import android.app.*;
import android.content.*;
import android.os.Build;
import org.json.*;
import java.time.Instant;
import java.util.UUID;

final class PulseAlerts {
    private PulseAlerts(){}
    static JSONObject settings(LocalRepository repo) throws Exception {
        JSONObject value=repo.read("alert_settings");if(value.has("schema"))return value;
        return AlertSettings.defaults().put("enabled",repo.preferences().getBoolean("pulse_alerts",false));
    }
    static void reset(LocalRepository repo){synchronized(PulseAlerts.class){repo.save("pulse_rule",new JSONObject());repo.save("metric_rules",new JSONObject());}}
    static void resetPulse(LocalRepository repo){synchronized(PulseAlerts.class){JSONObject states=repo.read("metric_rules");states.remove("pulse_high");states.remove("pulse_low");repo.save("metric_rules",states);repo.save("pulse_rule",new JSONObject());}}
    static JSONObject saveSettings(LocalRepository repo,JSONObject input) throws Exception {
        synchronized(PulseAlerts.class){JSONObject normalized=AlertSettings.normalize(input);long now=System.currentTimeMillis();
            normalized.put("revision",UUID.randomUUID().toString()).put("saved_at",Instant.now().toString()).put("apply_from",now);
            JSONObject previous=settings(repo);repo.save("alert_settings_previous",previous);repo.save("alert_settings",normalized);
            repo.preferences().edit().putBoolean("pulse_alerts",normalized.getBoolean("enabled")).putLong("rules_from",now).commit();reset(repo);return normalized;
        }
    }
    static void setEnabled(LocalRepository repo,boolean enabled) throws Exception {saveSettings(repo,settings(repo).put("enabled",enabled));}
    static void setSource(LocalRepository repo,String source){synchronized(PulseAlerts.class){repo.preferences().edit().putString("view_source",source).putLong("rules_from",System.currentTimeMillis()).commit();reset(repo);}}
    static void ingest(Context context,LocalRepository repo,JSONArray samples) throws Exception {
        synchronized(PulseAlerts.class){JSONObject config=settings(repo);String selected=repo.preferences().getString("view_source","live_device");
            long from=Math.max(config.optLong("apply_from"),repo.preferences().getLong("rules_from",0));
            JSONObject result=MetricRuleEngine.process(config,selected,from,samples,repo.read("metric_rules"),System.currentTimeMillis());
            JSONArray events=result.getJSONArray("events");repo.saveRuleResult(result.getJSONObject("states"),events);
            for(int i=0;i<events.length();i++)notify(context,repo,events.getJSONObject(i));
        }
    }
    static void notify(Context context,LocalRepository repo,JSONObject event) throws Exception {
        int level=event.getInt("level");if(level==1)return;
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(!manager.areNotificationsEnabled()){event.put("notification","permission_or_system_blocked");repo.saveAlert(event);return;}
        String channel=level==3?"metrics_l3_v1":"metrics_l2_v1";
        NotificationChannel c=new NotificationChannel(channel,level==3?"L3 指标重点提醒":"L2 指标提醒",NotificationManager.IMPORTANCE_HIGH);c.enableLights(false);manager.createNotificationChannel(c);
        NotificationChannel applied=manager.getNotificationChannel(channel);
        if(applied.getImportance()==NotificationManager.IMPORTANCE_NONE){event.put("notification","channel_blocked");repo.saveAlert(event);return;}
        Intent intent=new Intent(context,MainActivity.class).putExtra("open_alerts",true);
        PendingIntent open=PendingIntent.getActivity(context,73,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        String title=event.optString("title","脉搏提醒"),unit=event.optString("unit","次/分"),value=String.valueOf(event.optDouble("value"));
        String text=title+" "+value+" "+unit+"；达到已保存的L"+level+"规则（采样确认"+(event.optLong("duration_ms")/1000.0)+"秒）。请查看并确认。";
        try{manager.notify(notificationId(event.getString("id")),new Notification.Builder(context,channel).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("L"+level+" · "+title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text+"不是发作诊断。")).setContentIntent(open).setAutoCancel(true).build());event.put("notification","submitted_to_android");}

        catch(SecurityException e){event.put("notification","permission_or_system_blocked");}
        repo.saveAlert(event);
    }
    static int notificationId(String id){return 60000+Math.floorMod(id.hashCode(),100000);}
}
