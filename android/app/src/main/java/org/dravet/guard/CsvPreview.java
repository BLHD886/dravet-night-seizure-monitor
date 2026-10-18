package org.dravet.guard;

import org.json.*;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.*;

/** Offline canonical CSV preview only; never runs detection or notifications. */
final class CsvPreview {
    static List<List<String>> rows(String text) throws Exception {
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        List<List<String>> result=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false,closed=false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(quoted){if(c=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}else cell.append(c);continue;}
            if(c=='"'){if(cell.length()>0||closed)throw new Exception("CSV 引号位置无效");quoted=true;}
            else if(c==','||c=='\r'||c=='\n'){
                row.add(cell.toString());cell.setLength(0);closed=false;
                if(c!=','){if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;if(row.size()>1||!row.get(0).trim().isEmpty())result.add(row);row=new ArrayList<>();}
            }else{if(closed)throw new Exception("引号闭合后存在多余字符");cell.append(c);}
        }
        if(quoted)throw new Exception("CSV 引号未闭合");
        if(cell.length()>0||closed||!row.isEmpty()){row.add(cell.toString());result.add(row);}
        return result;
    }
    static JSONObject summarize(String text) throws Exception {return summarize(text,null);}
    private static String first(Map<String,String> row,String... names){for(String name:names){String value=row.getOrDefault(name,"");if(!value.isEmpty())return value;}return "";}
    private static boolean present(String value){return !value.isEmpty()&&!value.equals("--");}
    private static double number(String text,double max) throws Exception {double v=Double.parseDouble(text);if(!Double.isFinite(v)||v<=0||v>max)throw new Exception("指标不是有效数值");return v;}
    private static JSONArray reduce(List<JSONObject> points){JSONArray out=new JSONArray();int n=points.size();for(int i=0;i<Math.min(200,n);i++){int index=n<=200?i:(int)Math.round(i*(n-1)/199.0);out.put(points.get(index));}return out;}
    static JSONObject summarize(String text,String offset) throws Exception {
        List<List<String>> rows=rows(text);if(rows.size()<2)throw new Exception("CSV需要表头和数据行");if(rows.size()>50001)throw new Exception("最多预览50000行");
        List<String> headers=rows.get(0);boolean kids=headers.contains("时间");if(new HashSet<>(headers).size()!=headers.size()||!(headers.contains("timestamp")||kids))throw new Exception("需要唯一表头和timestamp或时间列");
        if(kids&&(offset==null||!offset.matches("[+-](?:0[0-9]|1[0-3]):[0-5][0-9]|[+-]14:00")))throw new Exception("KidsO2无时区，请先选择导入时区");
        int pulse=0,oxygen=0,acc=0,radar=0,missing=0;Instant from=null,to=null;JSONArray errors=new JSONArray();List<JSONObject> pulses=new ArrayList<>(),oxygens=new ArrayList<>();
        for(int i=1;i<rows.size();i++){
            List<String> values=rows.get(i);try{
                if(values.size()!=headers.size())throw new Exception("列数与表头不符");Map<String,String> r=new HashMap<>();for(int j=0;j<headers.size();j++)r.put(headers.get(j),values.get(j).trim());
                Instant stamp=kids?java.time.LocalDateTime.parse(r.get("时间"),java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")).atOffset(java.time.ZoneOffset.of(offset)).toInstant():OffsetDateTime.parse(r.get("timestamp")).toInstant();
                String p=first(r,"pulse_rate","heart_rate","脉率","脉搏"),o=first(r,"spo2","blood_oxygen","血氧");Double pv=present(p)?number(p,65535):null,ov=present(o)?number(o,100):null;
                String x=r.getOrDefault("acc_x",""),y=r.getOrDefault("acc_y",""),z=r.getOrDefault("acc_z","");boolean hasAcc=false,hasArea=false;
                if(present(x)||present(y)||present(z)){String unit=r.getOrDefault("acc_unit","");if(!present(x)||!present(y)||!present(z)||!(unit.equals("g")||unit.equals("m/s2")))throw new Exception("ACC需三轴齐全及单位g/m/s2");for(String axis:new String[]{x,y,z})if(!Double.isFinite(Double.parseDouble(axis)))throw new Exception("ACC无效");hasAcc=true;}
                boolean anyArea=false;for(int a=0;a<4;a++)if(present(r.getOrDefault("area"+a,"")))anyArea=true;if(anyArea){for(int a=0;a<4;a++){String v=r.getOrDefault("area"+a,"");if(!(v.equals("0")||v.equals("1")))throw new Exception("四区须齐全且为0/1");}hasArea=true;}
                if(pv==null&&ov==null&&!hasAcc&&!hasArea){if(kids||p.equals("--")||o.equals("--")){missing++;}else throw new Exception("该行无可识别指标");}
                if(pv!=null){pulse++;pulses.add(new JSONObject().put("timestamp",stamp.toString()).put("value",pv));}if(ov!=null){oxygen++;oxygens.add(new JSONObject().put("timestamp",stamp.toString()).put("value",ov));}if(hasAcc)acc++;if(hasArea)radar++;
                if(from==null||stamp.isBefore(from))from=stamp;if(to==null||stamp.isAfter(to))to=stamp;
            }catch(Exception e){if(errors.length()<20)errors.put(new JSONObject().put("row",i+1).put("error",e.getMessage()==null?"无效数据":e.getMessage()));}
        }
        return new JSONObject().put("source","replay").put("rows",rows.size()-1).put("heart_rate_samples",pulse).put("spo2_samples",oxygen).put("acc_samples",acc).put("radar_area_samples",radar).put("missing_rows",missing)
            .put("window_from",from==null?JSONObject.NULL:from.toString()).put("window_to",to==null?JSONObject.NULL:to.toString()).put("acc_status",acc>0?"data_present_unvalidated":"unavailable")
            .put("errors_first_20",errors).put("heart_rate_series",reduce(pulses)).put("spo2_series",reduce(oxygens)).put("series_limit",200).put("format",kids?"KidsO2":"canonical").put("timezone_offset",offset==null?JSONObject.NULL:offset).put("origin_kind","unspecified").put("live_alerts_triggered",false).put("note","仅本机回放；曲线最多200个点，完整采样摘要不等于连续覆盖；不产生实时通知。");
    }
}
