package org.dravet.guard;
import org.json.*;

/** Structural validation only. Ranges here are encoding bounds, not clinical recommendations. */
final class AlertSettings {
    static JSONObject defaults() throws Exception {
        JSONObject profile=new JSONObject().put("high",new JSONObject().put("enabled",true).put("l1",114).put("l2",130).put("l3",140).put("hold_s",3))
          .put("low",single(false)).put("oxygen",single(false))
          .put("resp",new JSONObject().put("enabled",false).put("lower",JSONObject.NULL).put("upper",JSONObject.NULL).put("level",2).put("hold_s",3));
        return new JSONObject().put("schema",1).put("revision","initial").put("apply_from",0).put("enabled",false).put("label","当前孩子").put("active","awake")
          .put("profiles",new JSONObject().put("awake",profile).put("sleep",new JSONObject(profile.toString())));
    }
    private static JSONObject single(boolean enabled) throws Exception {return new JSONObject().put("enabled",enabled).put("threshold",JSONObject.NULL).put("level",2).put("hold_s",3);}
    static JSONObject normalize(JSONObject input) throws Exception {
        JSONObject out=new JSONObject(input.toString());String active=out.getString("active");
        if(!active.equals("awake")&&!active.equals("sleep"))throw new Exception("请选择清醒或睡眠方案");
        String label=out.optString("label","当前孩子").trim();if(label.isEmpty()||label.length()>30)throw new Exception("方案名称需1至30字");out.put("label",label);
        if(!(out.opt("enabled") instanceof Boolean))throw new Exception("提醒总开关无效");
        JSONObject profiles=out.getJSONObject("profiles");
        for(String name:new String[]{"awake","sleep"}){
            JSONObject p=profiles.getJSONObject(name),h=p.getJSONObject("high");enabled(h);number(h,"l1",1,65535,true);number(h,"l2",1,65535,true);number(h,"l3",1,65535,true);hold(h,false);
            if(h.getDouble("l1")>=h.getDouble("l2")||h.getDouble("l2")>=h.getDouble("l3"))throw new Exception("脉搏三档起始值须满足L1 < L2 < L3");
            JSONObject low=p.getJSONObject("low");checkSingle(low,65535,false);if(!low.isNull("threshold")&&low.getDouble("threshold")>=h.getDouble("l1"))throw new Exception("脉搏过慢下限须小于L1起始值");
            checkSingle(p.getJSONObject("oxygen"),100,true);
            JSONObject resp=p.getJSONObject("resp");enabled(resp);level(resp);hold(resp,false);number(resp,"lower",1,300,resp.getBoolean("enabled"));number(resp,"upper",1,300,resp.getBoolean("enabled"));
            if(!resp.isNull("lower")&&!resp.isNull("upper")&&resp.getDouble("lower")>=resp.getDouble("upper"))throw new Exception("呼吸频率下限须小于上限");
        }
        return out.put("schema",1);
    }
    private static void enabled(JSONObject o) throws Exception {if(!(o.opt("enabled") instanceof Boolean))throw new Exception("指标开关无效");}
    private static void level(JSONObject o) throws Exception {number(o,"level",1,3,true);}
    private static void hold(JSONObject o,boolean spot) throws Exception {number(o,"hold_s",spot?0:1,300,true);}
    private static void checkSingle(JSONObject o,int max,boolean spot) throws Exception {enabled(o);number(o,"threshold",1,max,o.getBoolean("enabled"));level(o);hold(o,spot);}
    private static void number(JSONObject o,String key,int min,int max,boolean required) throws Exception {
        if(!o.has(key)||o.isNull(key)){if(required)throw new Exception("启用前请填写完整阈值和持续时间："+key);o.put(key,JSONObject.NULL);return;}
        Object value=o.get(key);if(!(value instanceof Number))throw new Exception("阈值必须是数字："+key);
        double d=((Number)value).doubleValue();if(!Double.isFinite(d)||d!=Math.rint(d)||d<min||d>max)throw new Exception("数值格式或范围无效："+key+"（"+min+"–"+max+"）");
    }
    static JSONObject profile(JSONObject settings) throws Exception {return settings.getJSONObject("profiles").getJSONObject(settings.getString("active"));}
    static int band(JSONObject rule,double value) throws Exception {return value>=rule.getDouble("l3")?3:value>=rule.getDouble("l2")?2:value>=rule.getDouble("l1")?1:0;}
}
