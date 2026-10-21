package org.dravet.guard;
import org.json.*;
import java.time.Instant;
import java.util.UUID;

final class MetricRuleEngine {
    private static long stamp(JSONObject sample){try{return Instant.parse(sample.optString("observed_at")).toEpochMilli();}catch(Exception ignored){return -1;}}
    static JSONObject process(JSONObject config,String selected,long from,JSONArray samples,JSONObject inputStates,long now) throws Exception {
        JSONObject states=new JSONObject(inputStates.toString());
        if(!config.getBoolean("enabled")||!(selected.equals("phone_ble")||selected.equals("live_device")))return new JSONObject().put("events",new JSONArray()).put("states",states);
            JSONObject profile=AlertSettings.profile(config);JSONArray events=new JSONArray();
            java.util.ArrayList<JSONObject> sorted=new java.util.ArrayList<>();for(int i=0;i<samples.length();i++)sorted.add(samples.getJSONObject(i));
            sorted.sort((a,b)->Long.compare(stamp(a),stamp(b)));
            
            for(JSONObject sample:sorted){
                if(!selected.equals(sample.optString("source"))||sample.optBoolean("is_demo"))continue;
                JSONObject payload=sample.optJSONObject("payload");if(payload==null||payload.optBoolean("is_demo")||payload.optBoolean("synthetic"))continue;
                String sensor=sample.optString("sensor_type");
                if(sensor.equals("watch_hr")&&payload.optString("protocol").equals("JX630-final"))continue;
                long stamp;try{stamp=Instant.parse(sample.getString("observed_at")).toEpochMilli();}catch(Exception ignored){continue;}
                if(stamp<=from)continue;
                double value=payload.optDouble("value",Double.NaN);String quality=sample.optString("quality");boolean valid=!quality.equals("invalid")&&!quality.equals("poor")&&Double.isFinite(value)&&value>0;
                if(sensor.equals("watch_hr")){
                    evaluate(events,config,states,profile.getJSONObject("high"),"pulse_high","脉搏过快","次/分",sample,value,valid&&value<=65535,stamp,now);
                    evaluate(events,config,states,profile.getJSONObject("low"),"pulse_low","脉搏过慢","次/分",sample,value,valid&&value<=65535,stamp,now);
                }else if(sensor.equals("watch_spo2"))evaluate(events,config,states,profile.getJSONObject("oxygen"),"oxygen_low","血氧偏低","%",sample,value,valid&&value<=100,stamp,now);
                else if(sensor.equals("radar_resp")){
                    evaluate(events,config,states,profile.getJSONObject("resp"),"resp_low","呼吸频率偏低","次/分",sample,value,valid&&value<=300,stamp,now);
                    evaluate(events,config,states,profile.getJSONObject("resp"),"resp_high","呼吸频率偏高","次/分",sample,value,valid&&value<=300,stamp,now);
                }
            }
        return new JSONObject().put("events",events).put("states",states);
    }
    private static void evaluate(JSONArray events,JSONObject config,JSONObject states,JSONObject threshold,String key,String title,String unit,JSONObject sample,double value,boolean valid,long stamp,long now) throws Exception {
        if(!threshold.getBoolean("enabled"))return;
        int next=0;
        if(key.equals("pulse_high"))next=AlertSettings.band(threshold,value);
        else{boolean crossed=key.equals("resp_high")?value>threshold.getDouble("upper"):value<threshold.getDouble(key.equals("resp_low")?"lower":"threshold");if(crossed)next=threshold.getInt("level");}
        if(!valid)next=-1;
        JSONObject old=states.optJSONObject(key);AlertTracker tracker=new AlertTracker();if(old!=null){tracker.stream=old.optString("stream");tracker.band=old.optInt("band",-1);tracker.announced=old.optInt("announced",-1);tracker.last=old.optLong("last",-1);tracker.since=old.optLong("since",-1);}
        String stream=config.getString("revision")+":"+config.getString("active")+":"+sample.optString("source")+":"+sample.optString("device_id")+":"+sample.getJSONObject("payload").optString("session_id");
        int event=tracker.accept(next,stamp,now,valid,stream,threshold.getLong("hold_s")*1000);
        JSONObject saved=new JSONObject().put("stream",tracker.stream).put("band",tracker.band).put("announced",tracker.announced).put("last",tracker.last).put("since",tracker.since).put("title",title).put("hold_s",threshold.getInt("hold_s"));states.put(key,saved);

        if(event>0){JSONObject item=new JSONObject().put("id",UUID.randomUUID().toString()).put("level",event).put("created_at",Instant.now().toString()).put("observed_at",sample.getString("observed_at")).put("started_at",Instant.ofEpochMilli(tracker.since).toString()).put("duration_ms",stamp-tracker.since).put("value",value).put("unit",unit).put("metric",key).put("title",title).put("source",sample.getString("source")).put("device_id",sample.optString("device_id")).put("quality",sample.optString("quality")).put("rule_version",config.getString("revision")).put("rule_snapshot",new JSONObject(threshold.toString())).put("profile",config.getString("active")).put("profile_label",config.getString("label")).put("is_demo",false).put("state","pending").put("notification",event==1?"in_app_only":"pending");events.put(item);}
    }
}
