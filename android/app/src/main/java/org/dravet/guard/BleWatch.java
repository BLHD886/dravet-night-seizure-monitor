package org.dravet.guard;

import android.app.Activity;
import android.content.Context;
import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.os.*;
import org.json.*;
import java.time.Instant;
import java.util.*;

/** Foreground commissioning adapter. Standard pulse/PLX plus explicitly requested experimental Moyoung SpO2. */
final class BleWatch {
    private final Context activity;
    private final LocalRepository repository;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,BluetoothDevice> discovered=new LinkedHashMap<>();
    private final Map<String,Integer> signals=new HashMap<>();
    private BluetoothGatt gatt;
    private BluetoothLeScanner scanner;
    private boolean scanning, wanted, closed;
    private int retries;
    private boolean connected;
    private String mode="idle",measurementId="",sessionId="",measurementStatus="NOT_SCHEDULED";
    private long measurementStarted,lastPulse,lastOxygen,lastFragment;
    private boolean roundHasPulse;
    private int measurementTicket;
    private final MeasurementWindow receiveWindow=new MeasurementWindow();
    private final ArrayDeque<String> trace=new ArrayDeque<>();
    private void trace(String text){String line=Instant.now()+" +"+SystemClock.elapsedRealtime()+"ms "+text;trace.add(line);while(trace.size()>100)trace.remove();try{repository.save("ble_diagnostics",new JSONObject().put("events",new JSONArray(trace)).put("session_id",sessionId).put("manufacturer",manufacturer).put("firmware",firmware).put("software",software));}catch(Exception ignored){}}

    private String firmware="",software="";
    private void clearMeasurement(String status){measurementTicket++;receiveWindow.close();mode="idle";measurementStatus=status;PulseAlerts.resetPulse(repository);}
    private String metricStatus(long at,boolean scheduled){if(!connected)return "DISCONNECTED";if(!scheduled)return "NOT_SCHEDULED";if(at==0)return "WAITING";return SystemClock.elapsedRealtime()-at>15000?"STALE":"LIVE_VALUE";}

    private String manufacturer="",oxygenMessage="血氧待发现服务";
    private boolean hrSubscribed,plxSubscribed,vendorSubscribed;
    private final OxygenCodec vendorCodec=new OxygenCodec();
    private final ArrayDeque<Operation> operations=new ArrayDeque<>();
    private Operation pending;
    private int operationSerial;
    private static final UUID PLX=uuid("1822"),SPOT=uuid("2a5e"),CONTINUOUS=uuid("2a5f"),VENDOR=uuid("feea"),VENDOR_IN=uuid("fee3"),VENDOR_OUT=uuid("fee2"),INFO=uuid("180a"),MANUFACTURER=uuid("2a29");
    private static UUID uuid(String shortId){return UUID.fromString("0000"+shortId+"-0000-1000-8000-00805f9b34fb");}
    private static final class Operation{final BluetoothGattCharacteristic c;final boolean read;byte[] write;Runnable done;Operation(BluetoothGattCharacteristic c,boolean read){this.c=c;this.read=read;}}

    private String selected="", message="尚未连接", connection="disconnected";
    private static final UUID HR_SERVICE=UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"), HR=UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb"), CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    BleWatch(Context a,LocalRepository r){activity=a;repository=r;}
    private BluetoothAdapter adapter() throws Exception {BluetoothManager m=activity.getSystemService(BluetoothManager.class);BluetoothAdapter a=m==null?null:m.getAdapter();if(a==null)throw new Exception("手机不支持蓝牙");if(!a.isEnabled())throw new Exception("请先在系统设置开启蓝牙");return a;}
    private boolean permission(){return Build.VERSION.SDK_INT>=31?activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED:activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private void requirePermission() throws Exception {if(!permission()){if(activity instanceof Activity)((Activity)activity).requestPermissions(Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION},31);throw new Exception("请允许蓝牙扫描所需权限，再次点击扫描。旧版安卓需系统定位权限，不采集位置。");}}
    void scan() throws Exception {requirePermission();BluetoothAdapter a=adapter();stopScan();discovered.clear();signals.clear();scanner=a.getBluetoothLeScanner();if(scanner==null)throw new Exception("蓝牙扫描器不可用");scanning=true;message="扫描中，请选择目标腕表";scanner.startScan(callback);handler.postDelayed(this::stopScan,10000);}
    void stopScan(){if(scanning&&scanner!=null)try{scanner.stopScan(callback);}catch(SecurityException ignored){}scanning=false;}
    private final ScanCallback callback=new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult result){handler.post(()->{if(!scanning)return;BluetoothDevice d=result.getDevice();if(discovered.size()<80||discovered.containsKey(d.getAddress())){discovered.put(d.getAddress(),d);signals.put(d.getAddress(),result.getRssi());}});}
        @Override public void onScanFailed(int code){handler.post(()->{scanning=false;message="扫描失败，代码 "+code;});}
    };
    JSONObject state() throws Exception {JSONArray devices=new JSONArray();if(permission())for(Map.Entry<String,BluetoothDevice> e:discovered.entrySet()){String name=e.getValue().getName();devices.put(new JSONObject().put("address",e.getKey()).put("name",name==null?"未命名蓝牙设备":name).put("rssi",signals.get(e.getKey())));}return new JSONObject().put("trace",new JSONArray(trace)).put("transport_connected",connected).put("pulse_subscribed",hrSubscribed).put("vendor_subscribed",vendorSubscribed).put("mode",mode).put("measurement_status",measurementStatus).put("session_id",sessionId).put("firmware",firmware).put("software",software).put("pulse_status",metricStatus(lastPulse,mode.equals("pulse")||(mode.equals("idle")&&(measurementStatus.equals("NOT_SCHEDULED")||measurementStatus.equals("PASSIVE_RECEIVING"))))).put("oxygen_status",metricStatus(lastOxygen,true)).put("pulse_age_ms",lastPulse==0?-1:SystemClock.elapsedRealtime()-lastPulse).put("oxygen_age_ms",lastOxygen==0?-1:SystemClock.elapsedRealtime()-lastOxygen).put("devices",devices).put("scanning",scanning).put("connection",connection).put("message",message).put("selected",selected).put("scope","foreground_service_unverified").put("manufacturer",manufacturer).put("oxygen_message",oxygenMessage).put("oxygen_standard",plxSubscribed).put("oxygen_moyoung",vendorSubscribed&&manufacturer.equals("MOYOUNG-V2"));}
    void connect(String address) throws Exception {requirePermission();if(!discovered.containsKey(address))throw new Exception("请先扫描并选择本次发现的设备");stopScan();disconnect();selected=address;PulseAlerts.setSource(repository,"phone_ble");wanted=true;retries=0;open();}
    private void open(){if(closed||!wanted)return;try{BluetoothDevice device=discovered.get(selected);if(device==null)return;connected=false;lastPulse=0;lastOxygen=0;sessionId=UUID.randomUUID().toString();clearMeasurement("NOT_SCHEDULED");manufacturer="";firmware="";software="";hrSubscribed=false;plxSubscribed=false;vendorSubscribed=false;operations.clear();pending=null;vendorCodec.reset();oxygenMessage="血氧待发现服务";connection="connecting";message="连接中，正在检查标准脉搏服务";gatt=device.connectGatt(activity,false,gattCallback,BluetoothDevice.TRANSPORT_LE);final BluetoothGatt attempt=gatt;handler.postDelayed(()->{if(gatt==attempt&&(connection.equals("connecting")||connection.equals("discovering"))){message="连接超时";attempt.disconnect();attempt.close();gatt=null;retry();}},15000);}catch(Exception e){connection="error";message="连接失败，请重新扫描";}}
    private void retry(){PulseAlerts.reset(repository);connected=false;clearMeasurement("DISCONNECTED");vendorCodec.reset();connection="disconnected";if(wanted&&!closed&&retries<3){retries++;message="连接中断，准备重连 "+retries+"/3";final int ticket=measurementTicket;handler.postDelayed(()->{if(ticket==measurementTicket)open();},3000L*retries);}else message="已断开，请重新连接";}
    void disconnect(){PulseAlerts.reset(repository);connected=false;clearMeasurement("DISCONNECTED");wanted=false;operations.clear();pending=null;vendorCodec.reset();oxygenMessage="血氧连接已断开";if(gatt!=null){try{gatt.disconnect();gatt.close();}catch(SecurityException ignored){}gatt=null;}connection="disconnected";message="已断开";}
    private final BluetoothGattCallback gattCallback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int newState){handler.post(()->{if(g!=gatt)return;if(status==BluetoothGatt.GATT_SUCCESS&&newState==BluetoothProfile.STATE_CONNECTED){connected=true;connection="discovering";message="已连接，发现服务中";if(!g.discoverServices()){message="无法发现服务";connection="error";}}else if(newState==BluetoothProfile.STATE_DISCONNECTED||status!=BluetoothGatt.GATT_SUCCESS){g.close();gatt=null;retry();}});}
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){handler.post(()->{
            if(g!=gatt)return;if(status!=0){connection="error";message="服务发现失败";return;}
            connection="subscribing";enqueue(g,INFO,MANUFACTURER,true);enqueue(g,INFO,uuid("2a26"),true);enqueue(g,INFO,uuid("2a28"),true);enqueue(g,HR_SERVICE,HR,false);enqueue(g,PLX,SPOT,false);enqueue(g,PLX,CONTINUOUS,false);enqueue(g,VENDOR,VENDOR_IN,false);nextOperation();
        });}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){handler.post(()->{if(g!=gatt||pending==null||pending.read||pending.write!=null||!d.getCharacteristic().getUuid().equals(pending.c.getUuid()))return;UUID id=pending.c.getUuid();if(status==0){if(id.equals(HR))hrSubscribed=true;if(id.equals(SPOT)||id.equals(CONTINUOUS))plxSubscribed=true;if(id.equals(VENDOR_IN))vendorSubscribed=true;}pending=null;nextOperation();});}
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int status){if(Build.VERSION.SDK_INT<33)readResult(g,c,c.getValue(),status);}
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int status){readResult(g,c,value,status);}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){handler.post(()->{if(g!=gatt||pending==null||pending.write==null||!pending.c.getUuid().equals(c.getUuid()))return;trace("WRITE_CALLBACK status="+status+" uuid="+c.getUuid());Runnable done=pending.done;pending=null;if(status!=0){clearMeasurement("WRITE_FAILED");oxygenMessage="测量命令写入失败，代码 "+status;operations.clear();}else if(done!=null)done.run();nextOperation();});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){if(Build.VERSION.SDK_INT<33)receive(g,c,c.getValue());}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){receive(g,c,value);}
    };
    private void enqueue(BluetoothGatt g,UUID service,UUID characteristic,boolean read){BluetoothGattService found=g.getService(service);BluetoothGattCharacteristic c=found==null?null:found.getCharacteristic(characteristic);if(c!=null)operations.add(new Operation(c,read));}
    private void nextOperation(){
        if(gatt==null||closed||pending!=null)return;
        if(operations.isEmpty()){if(!connection.equals("subscribing"))return;connection=hrSubscribed||plxSubscribed||vendorSubscribed?"subscribed":"unsupported";message=hrSubscribed?"脉搏通知已订阅，等待测量数据":"未发现可订阅的标准脉搏接口";oxygenMessage=plxSubscribed?"标准血氧已订阅，等待真实测量":vendorSubscribed&&manufacturer.equals("MOYOUNG-V2")?"Moyoung 血氧通道已订阅，可手动启动单次测量（待本App实物验证）":"未发现可用血氧接口；私有协议待适配";return;}
        pending=operations.remove();final int ticket=++operationSerial;final BluetoothGatt current=gatt;
        boolean sent=false;try{if(pending.write!=null){trace("TX "+hex(pending.write));if(Build.VERSION.SDK_INT>=33)sent=current.writeCharacteristic(pending.c,pending.write,BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)==BluetoothStatusCodes.SUCCESS;else{pending.c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);pending.c.setValue(pending.write);sent=current.writeCharacteristic(pending.c);}}else if(pending.read)sent=current.readCharacteristic(pending.c);else{int properties=pending.c.getProperties();boolean notify=(properties&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0,indicate=(properties&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0;BluetoothGattDescriptor descriptor=pending.c.getDescriptor(CCCD);if((notify||indicate)&&descriptor!=null&&current.setCharacteristicNotification(pending.c,true)){byte[] value=notify?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;if(Build.VERSION.SDK_INT>=33)sent=current.writeDescriptor(descriptor,value)==BluetoothStatusCodes.SUCCESS;else{descriptor.setValue(value);sent=current.writeDescriptor(descriptor);}}}}catch(SecurityException ignored){}
        if(!sent){boolean command=pending.write!=null;pending=null;if(command){clearMeasurement("WRITE_FAILED");trace("WRITE_START_FAILED");oxygenMessage="测量命令未发出";operations.clear();}nextOperation();return;}
        handler.postDelayed(()->{if(current==gatt&&pending!=null&&ticket==operationSerial){message="GATT 操作超时，重新连接";current.disconnect();current.close();gatt=null;pending=null;operations.clear();retry();}},7000);
    }
    private void readResult(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int status){final byte[] bytes=value==null?new byte[0]:value.clone();handler.post(()->{if(g!=gatt||pending==null||!pending.read||!pending.c.getUuid().equals(c.getUuid()))return;if(status==0&&c.getUuid().equals(MANUFACTURER))manufacturer=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace(String.valueOf((char)0),"").trim();if(status==0&&c.getUuid().equals(uuid("2a26")))firmware=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).trim();if(status==0&&c.getUuid().equals(uuid("2a28")))software=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).trim();pending=null;nextOperation();});}
    private void sendCommand(int command,boolean stop,Runnable done) throws Exception {
        BluetoothGattService service=gatt==null?null:gatt.getService(VENDOR);BluetoothGattCharacteristic out=service==null?null:service.getCharacteristic(VENDOR_OUT);
        if(out==null||(out.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)==0)throw new Exception("测量写通道不可用");
        trace("QUEUE TX "+hex(OxygenCodec.command(command,stop)));Operation op=new Operation(out,false);op.write=OxygenCodec.command(command,stop);op.done=done;operations.add(op);nextOperation();
    }
    void measureOxygen() throws Exception {if(plxSubscribed){oxygenMessage="标准血氧已订阅，请在手表上启动测量";return;}startMeasurement("oxygen");}
    void measurePulse() throws Exception {startMeasurement("pulse");}
    void stopMeasurement() throws Exception {
        if(pending!=null||!operations.isEmpty())throw new Exception("蓝牙操作进行中，请稍后再试");
        String old=mode;clearMeasurement("STOPPED");
        if(old.equals("oxygen")||old.equals("pulse"))sendCommand(old.equals("oxygen")?0x6b:0x6d,true,null);
        oxygenMessage="已停止主动测量；历史值不代表实时状态";
    }
    private void startMeasurement(String target) throws Exception {
        requirePermission();if(!connected||gatt==null)throw new Exception("请先连接腕表");
        if(pending!=null||!operations.isEmpty()||mode.equals("switching"))throw new Exception("蓝牙操作进行中，请稍后再试");
        if(!vendorSubscribed||!manufacturer.equals("MOYOUNG-V2"))throw new Exception("未确认Moyoung测量通道，请先完成服务订阅");
        if(mode.equals(target))throw new Exception("该项正在测量，请等待结果");
        clearMeasurement("NOT_SCHEDULED");mode="switching";final int ticket=measurementTicket;
        trace("SWITCH target="+target+"; stop both modes before start");
        Runnable start=()->handler.postDelayed(()->{if(!connected||ticket!=measurementTicket)return;try{beginMeasurement(target);}catch(Exception e){clearMeasurement("WRITE_FAILED");oxygenMessage=e.getMessage();trace("START_FAILED "+e.getMessage());}},1000);
        // The watch may have been started from its own screen or another app; local mode is insufficient.
        sendCommand(0x6d,true,()->{if(ticket!=measurementTicket)return;try{sendCommand(0x6b,true,start);}catch(Exception e){clearMeasurement("WRITE_FAILED");oxygenMessage=e.getMessage();}});
    }

    private void beginMeasurement(String target) throws Exception {
        mode=target;measurementStatus="WAITING";measurementId=UUID.randomUUID().toString();measurementStarted=SystemClock.elapsedRealtime();roundHasPulse=false;receiveWindow.begin(target.equals("oxygen")?0x6b:0x6d,measurementStarted);trace("BEGIN "+target+" id="+measurementId);final int ticket=measurementTicket;
        oxygenMessage=target.equals("oxygen")?"血氧测量中，通常约50–59秒；脉搏采集暂停，90秒无结果标记超时":"脉搏测量中；同档持续3秒需由新采样确认";
        sendCommand(target.equals("oxygen")?0x6b:0x6d,false,null);
        handler.postDelayed(()->{if(ticket!=measurementTicket||!receiveWindow.timedOut(SystemClock.elapsedRealtime()))return;trace("TIMEOUT no valid result");String old=mode;clearMeasurement("MEASUREMENT_TIMEOUT");oxygenMessage="测量超时：没有有效结果，请重新测量";try{sendCommand(old.equals("oxygen")?0x6b:0x6d,true,null);}catch(Exception ignored){}},90000);
    }
    private void receive(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] raw){if(raw==null)return;final UUID characteristic=c.getUuid();final byte[] copy=raw.clone();handler.post(()->{if(g!=gatt||closed)return;try{
        if(characteristic.equals(HR)){if(!mode.equals("pulse")&&!(mode.equals("idle")&&(measurementStatus.equals("NOT_SCHEDULED")||measurementStatus.equals("PASSIVE_RECEIVING"))))return;if(mode.equals("idle"))measurementStatus="PASSIVE_RECEIVING";int value=decode(copy);if(value<=0)throw new IllegalArgumentException();roundHasPulse=true;saveValue("watch_hr",value,"Bluetooth-2A37",characteristic,copy,true);}
        else if(characteristic.equals(SPOT)||characteristic.equals(CONTINUOUS)){saveValue("watch_spo2",OxygenCodec.plx(copy),"Bluetooth-PLX",characteristic,copy,false);}
        else if(characteristic.equals(VENDOR_IN)&&manufacturer.equals("MOYOUNG-V2")){
            trace("RX FEE3 "+hex(copy));
            long now=SystemClock.elapsedRealtime();if(now-lastFragment>7000)vendorCodec.reset();lastFragment=now;
            for(OxygenCodec.Frame f:vendorCodec.frames(copy)){
                repository.save("ble_last_frame",new JSONObject().put("raw_hex",hex(f.raw)).put("received_at",Instant.now().toString()).put("session_id",sessionId));
                if(f.command!=0x6b&&f.command!=0x6d||f.value<0)continue;
                String target=f.command==0x6b?"oxygen":"pulse";
                if(!mode.equals(target)||!receiveWindow.accepts(f.command,SystemClock.elapsedRealtime())){trace("FRAME outside current receive window cmd="+f.command);continue;}
                if(f.value==255){measurementStatus="END_STATUS_WAITING";trace("FF status; keep receive window open");oxygenMessage="收到设备结束状态，尚无有效数值；继续等待至本轮90秒截止";continue;}
                if(f.value==0||f.command==0x6b&&f.value>100){measurementStatus="INVALID_VALUE";oxygenMessage="收到无效测量值，继续等待；不填0";continue;}
                // 6D is a round summary, never a second timestamp for the 3-second rule.
                if(f.command==0x6b||!roundHasPulse)saveValue(f.command==0x6b?"watch_spo2":"watch_hr",f.value,"JX630-final",characteristic,f.raw,false);
                trace("RESULT "+target+" value="+f.value);clearMeasurement("COMPLETED");oxygenMessage=target.equals("oxygen")?"血氧结果已保存；请点击启动脉搏恢复采集":"本轮脉搏已完成；请启动下一轮";
            }
        }
    }catch(Exception e){if(characteristic.equals(HR)){PulseAlerts.reset(repository);message="无效脉搏帧已忽略";}else oxygenMessage="无效或暂不可用的血氧数据已忽略";}});}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02X",b&255));return out.toString();}
    private void saveValue(String sensor,double value,String protocol,UUID characteristic,byte[] raw,boolean alertInput) throws Exception {
        long now=SystemClock.elapsedRealtime();String received=Instant.now().toString();
        JSONObject item=new JSONObject().put("id",UUID.randomUUID().toString()).put("source","phone_ble").put("device_id",selected).put("sensor_type",sensor).put("observed_at",received).put("quality","unknown").put("payload",new JSONObject().put("value",value).put("unit",sensor.equals("watch_hr")?"bpm":"%").put("protocol",protocol).put("source_uuid",characteristic.toString()).put("raw_hex",hex(raw)).put("session_id",sessionId).put("measurement_id",measurementId).put("mode",mode).put("received_elapsed_ms",now).put("measurement_start_elapsed_ms",measurementStarted).put("manufacturer",manufacturer).put("firmware",firmware).put("software",software));
        JSONArray batch=new JSONArray().put(item);repository.saveSamples(batch);
        if(sensor.equals("watch_hr")){lastPulse=now;repository.save("ble_latest",item);if(alertInput)PulseAlerts.ingest(activity,repository,batch);message="已收到脉搏记录；规则结果不代表发作诊断";}
        else{lastOxygen=now;repository.save("ble_oxygen_latest",item);PulseAlerts.ingest(activity,repository,batch);oxygenMessage="已收到血氧 "+value+"%；按已保存的血氧规则处理（未启用则仅展示）";}
        connection="receiving_unvalidated";retries=0;
    }
    static int decode(byte[] raw){if(raw.length<2)throw new IllegalArgumentException("short HR frame");if((raw[0]&1)==0)return raw[1]&255;if(raw.length<3)throw new IllegalArgumentException("short uint16 HR frame");return(raw[1]&255)|((raw[2]&255)<<8);}
    void close(){closed=true;stopScan();disconnect();handler.removeCallbacksAndMessages(null);}
}
