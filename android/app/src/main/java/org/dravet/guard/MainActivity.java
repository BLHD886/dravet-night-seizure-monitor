package org.dravet.guard;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.*;

/** Bundled network-isolated interface with an explicit native action bridge. */
public final class MainActivity extends Activity {
    private WebView web;
    private LocalRepository repository;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String pendingFileRequest, pendingExportRequest, exportText;

    private String csvOffset;
    private final String demoCsv="timestamp,pulse_rate,spo2\n2026-10-16T00:00:00+08:00,80,98\n2026-10-16T00:00:04+08:00,82,97\n2026-10-16T00:00:08+08:00,79,98\n2026-10-16T00:00:12+08:00,81,98\n";

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);repository=new LocalRepository(this);
        getWindow().setStatusBarColor(Color.rgb(246,248,250));getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(246,248,250));web=new WebView(this);root.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});root.requestApplyInsets();
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);settings.setSafeBrowsingEnabled(true);
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){if(getIntent().getBooleanExtra("open_alerts",false))view.evaluateJavascript("window.Guard&&window.Guard.openAlerts()",null);}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
        });
        web.addJavascriptInterface(new Bridge(),"GuardNative");
        try(InputStream input=getAssets().open("app.html")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);web.loadDataWithBaseURL("https://guard.invalid/",out.toString("UTF-8"),"text/html","UTF-8",null);}
        catch(Exception e){android.widget.TextView message=new android.widget.TextView(this);message.setText("界面资源无法加载，请重新安装完整 APK。");root.removeAllViews();root.addView(message);}
    }
    private final class Bridge {
        @JavascriptInterface public void closeActivity(){runOnUiThread(()->finish());}
        @JavascriptInterface public void dispatch(String raw){
            if(raw==null||raw.length()>200000)return;
            worker.execute(()->{String id="";try{JSONObject request=new JSONObject(raw);id=request.getString("id");String action=request.getString("action");JSONObject body=request.optJSONObject("body");if(body==null)body=new JSONObject();
                if(action.equals("demo")||action.equals("start")||action.equals("stop")||action.equals("pickCsv")||action.startsWith("ble")||action.equals("export")||(action.equals("alertsConfig")||action.equals("saveAlertSettings"))){final String callId=id;final JSONObject params=body;runOnUiThread(()->uiAction(callId,action,params));return;}
                reply(id,true,perform(action,body),null);
            }catch(Exception e){reply(id,false,null,e.getMessage()==null?"操作未完成":e.getMessage());}});
        }
    }
    private JSONObject state() throws Exception {
        JSONObject config=new JSONObject().put("base",repository.base()).put("configured",!repository.preferences().getString("encrypted","").isEmpty()).put("watch",repository.preferences().getBoolean("watch",false));
        return new JSONObject().put("status",repository.read("status")).put("observations",repository.observations()).put("csv",repository.read("csv_preview")).put("config",config).put("local_demo",repository.read("local_demo")).put("history",repository.samples(repository.preferences().getString("view_source","live_device"))).put("ble",BleService.snapshot()).put("ble_latest",repository.read("ble_latest")).put("source",repository.preferences().getString("view_source","live_device")).put("sync",repository.read("history_sync")).put("alerts",repository.alerts()).put("rule",repository.read("pulse_rule")).put("alerts_enabled",PulseAlerts.settings(repository).getBoolean("enabled")).put("alert_settings",PulseAlerts.settings(repository)).put("metric_states",repository.read("metric_rules"));
    }
    private JSONObject perform(String action,JSONObject body) throws Exception {
        switch(action){
            case "state":return state();
            case "source":{String source=body.getString("source");if(!(source.equals("live_device")||source.equals("synthetic")||source.equals("phone_ble")))throw new Exception("未知来源");PulseAlerts.setSource(repository,source);return new JSONObject();}
            case "history":{String source=repository.preferences().getString("view_source","live_device");if(source.equals("phone_ble"))return new JSONObject().put("message","已读取本机蓝牙记录");JSONObject result=new ApiClient(repository).request("/v1/history?source="+source,null);repository.saveSamples(result.getJSONArray("samples"));PulseAlerts.ingest(this,repository,result.getJSONArray("samples"));repository.save("history_sync",new JSONObject().put("at",Instant.now().toString()).put("possibly_truncated",result.optBoolean("possibly_truncated")));return result;}
            case "alertFeedback":{JSONObject event=repository.alert(body.getString("id"));String feedback=body.getString("feedback");if(!(feedback.equals("确认发作")||feedback.equals("已查看")||feedback.equals("已到场确认")||feedback.equals("误报")||feedback.equals("不确定")))throw new Exception("未知反馈");String note=body.optString("note");if(note.length()>1000)throw new Exception("备注最多1000字");event.put("feedback",feedback).put("note",note).put("state","handled").put("handled_at",Instant.now().toString());repository.saveAlert(event);getSystemService(NotificationManager.class).cancel(PulseAlerts.notificationId(event.getString("id")));return event;}
            case "alarmDemo":{int level=body.optInt("level",3);if(level<1||level>3)throw new Exception("演示等级无效");JSONObject event=new JSONObject().put("id",UUID.randomUUID().toString()).put("level",level).put("value",level==3?145:level==2?135:120).put("duration_ms",3000).put("created_at",Instant.now().toString()).put("observed_at",Instant.now().toString()).put("source","synthetic").put("is_demo",true).put("state","pending").put("rule_version","user-pulse-v1-3s").put("notification","in_app_demo_only");repository.saveAlert(event);return event;}
            case "diagnostics":return new JSONObject().put("version","0.9-engineering").put("android_api",Build.VERSION.SDK_INT).put("configured",!repository.preferences().getString("encrypted","").isEmpty()).put("receiver_running",repository.preferences().getBoolean("running",false)).put("selected_source",repository.preferences().getString("view_source","live_device")).put("stored_window_samples",repository.samples(repository.preferences().getString("view_source","live_device")).length()).put("automatic_detection","disabled_unvalidated").put("alarms_visible",true);

            case "demoCsv":{JSONObject preview=CsvPreview.summarize(demoCsv).put("origin_kind","synthetic");repository.save("csv_preview",preview);return preview;}
            case "configure":{String base=body.getString("base").trim();ApiClient.validate(base);String token=body.optString("token").trim();if(token.isEmpty())token=repository.token();if(token.length()>500)throw new Exception("访问码过长");repository.configure(base,token,body.optBoolean("watch"));return new JSONObject();}
            case "testConnection":{JSONObject result=new ApiClient(repository).request("/v1/live/status",null);result.put("phone_received_at",System.currentTimeMillis());repository.save("status",result);return result;}
            case "saveObservation":{String text=body.getString("text").trim();if(text.isEmpty()||text.length()>2000)throw new Exception("观察内容需1至2000字");String time=OffsetDateTime.parse(body.getString("occurred_at")).toInstant().toString();JSONObject record=new JSONObject().put("id",UUID.randomUUID().toString()).put("text",text).put("occurred_at",time).put("recorded_at",Instant.now().toString());repository.saveObservation(record);return record;}
            case "syncObservations":{ApiClient api=new ApiClient(repository);JSONArray rows=repository.pendingObservations();int count=0;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.optBoolean("synced"))continue;JSONObject payload=new JSONObject().put("id",row.getString("id")).put("text",row.getString("text")).put("occurred_at",row.getString("occurred_at"));api.request("/v1/observations",payload);repository.markSynced(row.getString("id"));count++;}return new JSONObject().put("count",count);}
            case "localFeedback":{String feedback=body.getString("feedback");if(!(feedback.equals("我已查看")||feedback.equals("我来接手")||feedback.equals("误报")||feedback.equals("不确定")))throw new Exception("未知反馈");JSONObject demo=repository.read("local_demo");if(!demo.has("created_at"))throw new Exception("尚无演练记录");demo.put("feedback",feedback);repository.save("local_demo",demo);return demo;}
            case "ask":{
                String question=body.optString("question");if(question.length()>2000)throw new Exception("问题过长");String scope=body.optString("scope","local");
                if(scope.equals("local")){JSONObject csv=repository.read("csv_preview"),status=repository.read("status");String text="本机观察记录："+repository.observations().length()+" 条。\n本机 CSV："+csv.optInt("rows",0)+" 行，脉搏 "+csv.optInt("heart_rate_samples",0)+" 条，血氧 "+csv.optInt("spo2_samples",0)+" 条，ACC "+csv.optInt("acc_samples",0)+" 条。\n"+(csv.optInt("acc_samples",0)>0?"ACC 来自 CSV，设备实时能力仍未验证。":"ACC 尚未提供，不能推断动作或节律。")+"\n\n"+(status.has("devices")?"设备状态为本机最近缓存，请留意接收时间和陈旧提示。":"尚无设备接收记录。")+"\n\n这是一份本机记录摘要，不是 AI 回答，也不判断患者是否安全或发作。";return new JSONObject().put("mode","local_summary").put("answer",text);}
                if(!(scope.equals("live_recent")||scope.equals("csv_latest")||scope.equals("demo_recent")))throw new Exception("未知摘要范围");
                return new ApiClient(repository).request("/v1/assistant",new JSONObject().put("question",question).put("scope",scope).put("consent",body.optBoolean("consent")));
            }
            case "serverDemo":{int level=body.getInt("level");if(level!=1&&level!=2)throw new Exception("只支持两级预警演练");return new ApiClient(repository).request("/v1/events",new JSONObject().put("level",level));}
            case "events":return new ApiClient(repository).request("/v1/snapshot",null);
            case "receipt":return new ApiClient(repository).request("/v1/receipts",new JSONObject().put("event_id",body.getString("event_id")).put("status",body.getString("status")));
            default:throw new Exception("未知操作");
        }
    }
    private void uiAction(String id,String action,JSONObject body){
        try{
            if(action.equals("saveAlertSettings")){JSONObject saved=PulseAlerts.saveSettings(repository,body.getJSONObject("settings"));boolean enabled=saved.getBoolean("enabled");if(enabled&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);reply(id,true,new JSONObject().put("settings",saved).put("message","设置已保存，各页面同步；仅对保存后的新采样生效"),null);return;}
            if(action.equals("alertsConfig")){boolean enabled=body.optBoolean("enabled");PulseAlerts.setEnabled(repository,enabled);if(enabled&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);reply(id,true,new JSONObject().put("message",enabled?"自定义脉搏提醒已启用；请确认系统通知权限。":"已停用自定义脉搏提醒"),null);return;}
            if(action.startsWith("ble")){
                if(action.equals("bleState")){reply(id,true,BleService.snapshot(),null);return;}
                if(action.equals("bleScan")){java.util.ArrayList<String> permissions=new java.util.ArrayList<>();if(Build.VERSION.SDK_INT>=31){for(String name:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})if(checkSelfPermission(name)!=PackageManager.PERMISSION_GRANTED)permissions.add(name);}else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);if(!permissions.isEmpty()){requestPermissions(permissions.toArray(new String[0]),31);throw new Exception("允许蓝牙所需权限后，请再次扫描");}startForegroundService(new Intent(this,BleService.class).putExtra("scan",true));reply(id,true,new JSONObject().put("message","蓝牙采集服务正在扫描，请稍候"),null);return;}
                BleService.action(action,body.optString("address"));reply(id,true,BleService.snapshot(),null);return;
            }
            if(action.equals("export")){if(pendingExportRequest!=null)throw new Exception("已有导出进行中");exportText=body.optBoolean("ble_diagnostics")?repository.read("ble_diagnostics").toString(2):exportCsv();pendingExportRequest=id;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(body.optBoolean("ble_diagnostics")?"application/json":"text/csv");intent.putExtra(Intent.EXTRA_TITLE,body.optBoolean("ble_diagnostics")?"Guard-BLE-"+System.currentTimeMillis()+".json":"Guard-records-"+System.currentTimeMillis()+".csv");startActivityForResult(intent,23);return;}
            if(action.equals("stop")){stopService(new Intent(this,MonitorService.class));reply(id,true,new JSONObject(),null);return;}
            if(action.equals("pickCsv")){if(pendingFileRequest!=null)throw new Exception("已有文件选择进行中");csvOffset=body.optString("timezone_offset");pendingFileRequest=id;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("*/*");intent.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,22);return;}
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);reply(id,true,new JSONObject().put("message","请先允许通知，然后再次点击。"),null);return;}
            NotificationManager manager=getSystemService(NotificationManager.class);if(!manager.areNotificationsEnabled())throw new Exception("系统通知已关闭，请先在手机设置中开启。");
            if(action.equals("start")){new ApiClient(repository);startForegroundService(new Intent(this,MonitorService.class));reply(id,true,new JSONObject().put("message","已请求启动。请检查常驻通知并实测锁屏送达。"),null);return;}
            int level=body.getInt("level");if(level!=1&&level!=2)throw new Exception("未知演练级别");String channel=level==1?"local_demo_quiet":"local_demo_attention";
            NotificationChannel c=new NotificationChannel(channel,level==1?"本机一级演练（低打扰）":"本机二级演练（提醒）",level==1?NotificationManager.IMPORTANCE_LOW:NotificationManager.IMPORTANCE_HIGH);c.enableLights(false);manager.createNotificationChannel(c);
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            manager.notify(40000+level,new Notification.Builder(this,channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("本机演练 · "+level+"级（非患者告警）").setContentText("由你手动触发；不是设备识别结果").setContentIntent(open).setAutoCancel(true).build());
            repository.save("local_demo",new JSONObject().put("level",level).put("created_at",Instant.now().toString()).put("source","manual_local_demo"));reply(id,true,new JSONObject().put("message","已发送本机演练通知；请检查声音与横幅。"),null);
        }catch(Exception e){reply(id,false,null,e.getMessage());}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==23){String exportId=pendingExportRequest;pendingExportRequest=null;final String text=exportText;exportText=null;if(result!=RESULT_OK||data==null||data.getData()==null){reply(exportId,true,new JSONObject(),null);return;}final android.net.Uri target=data.getData();worker.execute(()->{try(java.io.OutputStream output=getContentResolver().openOutputStream(target)){if(output==null)throw new Exception("无法打开导出文件");output.write(text.getBytes(StandardCharsets.UTF_8));reply(exportId,true,new JSONObject().put("message","CSV 已保存到所选位置"),null);}catch(Exception e){reply(exportId,false,null,"导出失败");}});return;}
        if(request!=22)return;String id=pendingFileRequest;pendingFileRequest=null;
        if(result!=RESULT_OK||data==null||data.getData()==null){reply(id,true,new JSONObject(),null);return;}android.net.Uri uri=data.getData();
        worker.execute(()->{try(InputStream input=getContentResolver().openInputStream(uri);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            if(input==null)throw new Exception("无法读取文件");byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(output.size()+count>8000000)throw new Exception("CSV最大8MB");output.write(buffer,0,count);}
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString();JSONObject preview=CsvPreview.summarize(text,csvOffset);repository.save("csv_preview",preview);reply(id,true,preview,null);runOnUiThread(()->{if(!isDestroyed())web.evaluateJavascript("window.Guard&&window.Guard.fileImported()",null);});
        }catch(Exception e){reply(id,false,null,"CSV 预览失败："+e.getMessage());}});
    }
    private static String cell(String text){return "\""+text.replace("\"","\"\"")+"\"";}
    private String exportCsv() throws Exception {
        StringBuilder out=new StringBuilder("\uFEFFkind,id,time,source,value_or_text,quality\n");
        JSONArray samples=repository.samples(repository.preferences().getString("view_source","live_device"));for(int i=0;i<samples.length();i++){JSONObject r=samples.getJSONObject(i);out.append("sample,").append(cell(r.optString("id"))).append(',').append(cell(r.optString("observed_at"))).append(',').append(cell(r.optString("source"))).append(',').append(cell(r.optJSONObject("payload").toString())).append(',').append(cell(r.optString("quality"))).append('\n');}
        JSONArray observations=repository.observations();for(int i=0;i<observations.length();i++){JSONObject r=observations.getJSONObject(i);out.append("observation,").append(cell(r.optString("id"))).append(',').append(cell(r.optString("occurred_at"))).append(",manual,").append(cell(r.optString("text"))).append(",unvalidated\n");}JSONArray events=repository.alerts();for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);out.append("alert,").append(cell(e.optString("id"))).append(',').append(cell(e.optString("created_at"))).append(',').append(cell(e.optString("source"))).append(',').append(cell(e.toString())).append(",user_defined_rule\n");}return out.toString();
    }
    private void reply(String id,boolean ok,JSONObject result,String error){
        if(id==null)return;try{JSONObject message=new JSONObject().put("id",id).put("ok",ok);if(ok)message.put("result",result==null?new JSONObject():result);else message.put("error",error==null?"操作未完成":error);String script="window.Guard&&window.Guard.receive("+message.toString().replace("\u2028","\\u2028").replace("\u2029","\\u2029")+")";runOnUiThread(()->{if(!isDestroyed())web.evaluateJavascript(script,null);});}catch(Exception ignored){}
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("open_alerts",false))web.evaluateJavascript("window.Guard&&window.Guard.openAlerts()",null);}
    @Override public void onBackPressed(){web.evaluateJavascript("window.Guard&&window.Guard.back()",null);}
    @Override public void onDestroy(){web.removeJavascriptInterface("GuardNative");web.destroy();worker.execute(repository::close);worker.shutdown();super.onDestroy();}
}
