package org.dravet.guard;
public final class EngineeringChecks {
 static int assertions=0;static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
 public static void main(String[] args) throws Exception {
  check(PulseRule.band(113)==0,"below first band");check(PulseRule.band(114)==1,"114");check(PulseRule.band(129)==1,"129");check(PulseRule.band(130)==2,"130");check(PulseRule.band(139)==2,"139");check(PulseRule.band(140)==3,"140");check(PulseRule.band(0)==-1,"zero invalid");check(PulseRule.band(Double.NaN)==-1,"NaN invalid");
  long t=1000000;PulseRule r=new PulseRule();check(r.accept(120,t,t,true,"a")==-1,"first point does not alert");check(r.accept(120,t,t+4000,true,"a")==-1,"duplicate does not count");check(r.accept(120,t+2999,t+2999,true,"a")==-1,"not yet three seconds");check(r.accept(120,t+3000,t+3000,true,"a")==1,"exactly 3 seconds");check(r.accept(120,t+4000,t+4000,true,"a")==-1,"no duplicate event");check(r.accept(135,t+5000,t+5000,true,"a")==-1,"new band resets timer");check(r.accept(135,t+8000,t+8000,true,"a")==2,"escalation after hold");check(r.accept(145,t+9000,t+9000,true,"a")==-1,"L3 wait");check(r.accept(145,t+12000,t+12000,true,"a")==3,"L3 event");
  r=new PulseRule();r.accept(145,t,t,true,"a");check(r.accept(145,t+11000,t+11000,true,"a")==-1,"sample gap resets");r.accept(145,t+12000,t+12000,false,"a");check(r.band==-1,"invalid is unknown, not L0");check(r.accept(145,t+15000,t+15000,true,"a")==-1,"invalid breaks streak");r=new PulseRule();r.accept(145,t,t,true,"a");check(r.accept(145,t+3000,t+3000,true,"b")==-1,"different device resets");r=new PulseRule();check(r.accept(145,t,t+16000,true,"a")==-1&&r.band==-1,"stale ignored");
  r=new PulseRule();r.accept(145,t+999999,t,true,"a");r.accept(145,t,t,true,"a");check(r.accept(145,t+3000,t+3000,true,"a")==3,"future frame cannot poison later stream");
  check(OxygenCodec.plx(new byte[]{0,98,0,80,0})==98,"PLX SpO2");check(Math.abs(OxygenCodec.sfloat(new byte[]{(byte)0xc6,(byte)0xf3},0)-96.6)<0.00001,"signed exponent SFLOAT");boolean bad=false;try{OxygenCodec.plx(new byte[]{0,(byte)0xff,7,80,0});}catch(IllegalArgumentException e){bad=true;}check(bad,"PLX NaN ignored");
  OxygenCodec codec=new OxygenCodec();check(codec.feed(new byte[]{(byte)0xfe,(byte)0xea,0x10})==-1,"fragment wait");check(codec.feed(new byte[]{6,0x6b,97})==97,"fragmented vendor oxygen");check(codec.feed(new byte[]{(byte)0xfe,(byte)0xea,0x20,6,0x6b,98})==98,"V2 length");check(codec.feed(new byte[]{(byte)0xfe,(byte)0xea,0x10,6,0x6b,(byte)255})==-1,"vendor invalid ignored");check(codec.feed(new byte[]{(byte)0xfe,(byte)0xea,0x10,6,0x0b,98})==-1,"wrong command never called oxygen");check(java.util.Arrays.equals(OxygenCodec.startMoyoung(),new byte[]{(byte)0xfe,(byte)0xea,0x20,6,0x6b,0}),"documented spot request");
  check(codec.frames(new byte[]{(byte)254,(byte)234,32,6,107,98,(byte)254,(byte)234,32,6,109,72}).size()==2,"coalesced frames preserved");
  check(codec.frames(new byte[]{(byte)254,(byte)234,32,6,107,(byte)255}).get(0).value==255,"end status preserved not a sample");
  check(codec.frames(new byte[]{9,8,(byte)254,(byte)234,32,6,107,97}).get(0).value==97,"resynchronizes junk");
  codec.frames(new byte[]{(byte)254,(byte)234,32});codec.reset();check(codec.frames(new byte[]{6,107,98}).isEmpty(),"disconnect resets partial frame");
  codec.reset();check(codec.frames(new byte[]{(byte)254,(byte)234,32,7,107,98,99}).get(0).value==-1,"strict one byte payload");
  MeasurementWindow window=new MeasurementWindow();window.begin(107,1000);
  check(window.accepts(107,2000),"status FF does not close window");
  check(window.accepts(107,60000),"late oxygen result accepted before deadline");
  check(!window.accepts(109,60000),"other measurement cannot finish oxygen");
  check(window.timedOut(91000)&&!window.accepts(107,91000),"90 second deadline");
  window.close();check(!window.accepts(107,2000),"closed window rejects delayed result");
  window.begin(109,100000);check(!window.accepts(107,100001)&&window.accepts(109,100001),"new round replaces old metric");
  org.json.JSONObject csv=CsvPreview.summarize("timestamp,pulse_rate,spo2\n2026-10-16T00:00:00Z,120,98\n2026-10-16T00:00:04Z,121,97\n");check(csv.getInt("heart_rate_samples")==2&&csv.getInt("spo2_samples")==2,"dual metric CSV");
  csv=CsvPreview.summarize("时间,血氧,脉率,体动,血氧提醒,脉率提醒\n2024/03/31 21:00:40,98,138,1,0,0\n2024/03/31 21:00:44,--,--,0,0,0\n","+08:00");check(csv.getInt("missing_rows")==1&&csv.getInt("spo2_samples")==1&&csv.getInt("acc_samples")==0,"KidsO2 missing and motion");check(csv.getString("window_from").equals("2024-03-31T13:00:40Z"),"explicit timezone");bad=false;try{CsvPreview.summarize("时间,血氧,脉率\n2024/03/31 21:00:40,98,138\n");}catch(Exception e){bad=true;}check(bad,"timezone not silently assumed");check(!csv.getBoolean("live_alerts_triggered"),"CSV never notifies");
  System.out.println("Engineering checks passed: "+assertions+" assertions (bands, hold, stale/duplicates, BLE oxygen, KidsO2)");
 }
}
