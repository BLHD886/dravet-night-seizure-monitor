package org.dravet.guard;
import org.json.*;
import java.time.Instant;

public final class AdjustableRuleChecks {
    static int checks;
    static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    static JSONObject sample(String sensor,double value,long stamp) throws Exception {return new JSONObject().put("source","phone_ble").put("device_id","fixture-watch").put("sensor_type",sensor).put("observed_at",Instant.ofEpochMilli(stamp).toString()).put("quality","unknown").put("payload",new JSONObject().put("value",value).put("session_id","s1").put("protocol","fixture"));}
    static JSONObject run(JSONObject c,JSONObject states,long now,JSONObject...samples) throws Exception {return MetricRuleEngine.process(c,"phone_ble",c.optLong("apply_from"),new JSONArray(java.util.Arrays.asList(samples)),states,now);}
    interface Edit {void apply(JSONObject c) throws Exception;}
    static void rejects(Edit e,String why) throws Exception {JSONObject c=AlertSettings.defaults();e.apply(c);boolean bad=false;try{AlertSettings.normalize(c);}catch(Exception x){bad=true;}check(bad,why);}
    public static void main(String[]args) throws Exception {
        JSONObject c=AlertSettings.defaults().put("enabled",true).put("revision","r1");JSONObject p=AlertSettings.profile(c);
        check(!p.getJSONObject("low").getBoolean("enabled")&&p.getJSONObject("low").isNull("threshold"),"no invented low threshold");
        check(!p.getJSONObject("oxygen").getBoolean("enabled")&&p.getJSONObject("oxygen").isNull("threshold"),"no invented oxygen threshold");
        check(!p.getJSONObject("resp").getBoolean("enabled"),"resp disabled until explicitly configured");
        check(AlertSettings.normalize(c).getInt("schema")==1,"round trip schema");
        rejects(v->AlertSettings.profile(v).getJSONObject("high").put("l2",114),"overlap rejected");
        rejects(v->AlertSettings.profile(v).getJSONObject("low").put("enabled",true),"missing low threshold rejected");
        rejects(v->AlertSettings.profile(v).getJSONObject("oxygen").put("threshold",101),"invalid oxygen rejected even disabled");
        rejects(v->AlertSettings.profile(v).getJSONObject("oxygen").put("threshold","95"),"string number rejected");
        rejects(v->AlertSettings.profile(v).getJSONObject("low").put("threshold",114),"low must below high");
        rejects(v->AlertSettings.profile(v).getJSONObject("high").put("hold_s",0),"pulse hold cannot be zero");
        rejects(v->AlertSettings.profile(v).getJSONObject("resp").put("enabled",true).put("lower",20).put("upper",10),"resp interval invalid");
        rejects(v->v.getJSONObject("profiles").getJSONObject("sleep").getJSONObject("high").put("hold_s",301),"inactive profile validated too");
        rejects(v->v.put("active","automatic"),"manual profile only");
        long now=1000000;
        JSONObject result=run(c,new JSONObject(),now,sample("watch_hr",120,now-4000),sample("watch_hr",120,now));
        check(result.getJSONArray("events").length()==1,"default high pulse hold alerts");
        JSONObject event=result.getJSONArray("events").getJSONObject(0);
        check(event.getInt("level")==1&&event.getString("metric").equals("pulse_high"),"default band correct");
        check(event.getJSONObject("rule_snapshot").getInt("l1")==114&&event.getString("profile").equals("awake"),"immutable threshold and profile recorded");
        JSONObject again=run(c,result.getJSONObject("states"),now,sample("watch_hr",120,now));check(again.getJSONArray("events").length()==0,"duplicate poll cannot alert twice");
        p.getJSONObject("high").put("l1",125).put("l2",135).put("l3",145);
        result=run(c,new JSONObject(),now,sample("watch_hr",120,now-4000),sample("watch_hr",120,now));check(result.getJSONArray("events").length()==0,"edited threshold actually changes engine");
        check(event.getJSONObject("rule_snapshot").getInt("l1")==114,"old event unaffected by edits");
        p.getJSONObject("low").put("enabled",true).put("threshold",100).put("level",3).put("hold_s",4);
        result=run(c,new JSONObject(),now,sample("watch_hr",90,now-4000),sample("watch_hr",90,now));
        check(result.getJSONArray("events").length()==1&&result.getJSONArray("events").getJSONObject(0).getInt("level")==3,"low pulse configurable L3");
        check(result.getJSONArray("events").getJSONObject(0).getString("title").equals("脉搏过慢"),"low pulse distinct title");
        p.getJSONObject("oxygen").put("enabled",true).put("threshold",95).put("level",2).put("hold_s",0);
        result=run(c,new JSONObject(),now,sample("watch_spo2",94,now));check(result.getJSONArray("events").length()==1,"explicit spot result oxygen alerts");
        check(result.getJSONArray("events").getJSONObject(0).getString("unit").equals("%"),"oxygen event correct unit");
        result=run(c,new JSONObject(),now,sample("watch_spo2",95,now));check(result.getJSONArray("events").length()==0,"low is strict below");
        p.getJSONObject("oxygen").put("hold_s",3);
        result=run(c,new JSONObject(),now,sample("watch_spo2",94,now));check(result.getJSONArray("events").length()==0,"one reading cannot prove duration");
        result=run(c,result.getJSONObject("states"),now+52000,sample("watch_spo2",94,now+52000));check(result.getJSONArray("events").length()==0,"measurement gap cannot count as continuous oxygen");
        p.getJSONObject("resp").put("enabled",true).put("lower",10).put("upper",30).put("level",2).put("hold_s",3);
        result=run(c,new JSONObject(),now,sample("radar_resp",31,now-4000),sample("radar_resp",31,now));check(result.getJSONArray("events").getJSONObject(0).getString("metric").equals("resp_high"),"future real resp high route");
        result=run(c,new JSONObject(),now,sample("radar_resp",9,now-4000),sample("radar_resp",9,now));check(result.getJSONArray("events").getJSONObject(0).getString("metric").equals("resp_low"),"future real resp low route");
        JSONObject fake=sample("radar_resp",31,now).put("source","synthetic");
        result=MetricRuleEngine.process(c,"synthetic",0,new JSONArray().put(fake),new JSONObject(),now);check(result.getJSONArray("events").length()==0,"synthetic selected source never alerts");
        fake.put("source","phone_ble").put("is_demo",true);result=run(c,new JSONObject(),now,fake,fake);check(result.getJSONArray("events").length()==0,"demo tag excluded even if misrouted");
        fake.remove("is_demo");fake.getJSONObject("payload").put("synthetic",true);result=run(c,new JSONObject(),now,fake);check(result.getJSONObject("states").length()==0,"payload synthetic excluded");
        c.put("apply_from",now);result=run(c,new JSONObject(),now,sample("watch_hr",150,now-4000),sample("watch_hr",150,now));check(result.getJSONArray("events").length()==0,"save cutoff excludes old history");c.put("apply_from",0);
        result=run(c,new JSONObject(),now,sample("watch_hr",150,now-4000));JSONObject oldStates=result.getJSONObject("states");c.put("revision","r2");result=run(c,oldStates,now,sample("watch_hr",150,now));check(result.getJSONArray("events").length()==0,"revision resets hold");
        c.put("active","sleep");result=run(c,new JSONObject(),now,sample("watch_hr",120,now-4000),sample("watch_hr",120,now));check(result.getJSONArray("events").getJSONObject(0).getInt("level")==1,"sleep thresholds independently retained");
        c.put("active","awake");result=run(c,new JSONObject(),now,sample("watch_hr",150,now-4000),sample("watch_hr",0,now-2000),sample("watch_hr",150,now));check(result.getJSONArray("events").length()==0,"invalid zero breaks hold");
        result=run(c,new JSONObject(),now,sample("watch_hr",150,now-20000),sample("watch_hr",150,now));check(result.getJSONArray("events").length()==0,"stale sample not hold evidence");
        JSONObject a=sample("watch_hr",150,now-4000),b=sample("watch_hr",150,now);a.getJSONObject("payload").put("protocol","JX630-final");b.getJSONObject("payload").put("protocol","JX630-final");result=run(c,new JSONObject(),now,a,b);check(result.getJSONArray("events").length()==0,"6D summaries cannot fake live pulse sequence");
        c.put("enabled",false);result=run(c,new JSONObject(),now,sample("watch_hr",150,now-4000),sample("watch_hr",150,now));check(result.getJSONArray("events").length()==0,"master off prevents all events");
        System.out.println("Adjustable rule engine: "+checks+" checks passed (synthetic fixtures only)");
    }
}
