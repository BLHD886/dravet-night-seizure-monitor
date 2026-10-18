package org.dravet.guard;

import java.util.Arrays;

/** Protocol-fact implementation, not a vendor SDK. See protocol references in handoff. */
final class OxygenCodec {
    static double sfloat(byte[] raw,int offset){
        if(offset<0||raw.length<offset+2)throw new IllegalArgumentException("short SFLOAT");
        int word=(raw[offset]&255)|((raw[offset+1]&255)<<8),mantissa=word&4095,exp=word>>>12;
        if(mantissa>=0x7fe&&mantissa<=0x802)throw new IllegalArgumentException("unavailable SFLOAT");
        if(mantissa>=2048)mantissa-=4096;if(exp>=8)exp-=16;
        return mantissa*Math.pow(10,exp);
    }
    static double plx(byte[] raw){
        if(raw==null||raw.length<5)throw new IllegalArgumentException("short PLX measurement");
        double value=sfloat(raw,1);if(!Double.isFinite(value)||value<=0||value>100)throw new IllegalArgumentException("invalid SpO2");return value;
    }
    // JX630 MOYOUNG-V2 uses 0x20 even with default MTU.
    static byte[] command(int command,boolean stop){return new byte[]{(byte)0xfe,(byte)0xea,0x20,6,(byte)command,(byte)(stop?255:0)};}
    static byte[] startMoyoung(){return command(0x6b,false);}
    static final class Frame {
        final int command,value; final byte[] raw;
        Frame(byte[] bytes){raw=bytes;command=bytes[4]&255;value=bytes.length==6?bytes[5]&255:-1;}
    }
    private byte[] buffered=new byte[0];
    void reset(){buffered=new byte[0];}
    java.util.List<Frame> frames(byte[] fragment){
        java.util.List<Frame> result=new java.util.ArrayList<>();
        if(fragment==null||fragment.length==0)return result;
        if(buffered.length+fragment.length>8192){reset();return result;}
        byte[] joined=Arrays.copyOf(buffered,buffered.length+fragment.length);System.arraycopy(fragment,0,joined,buffered.length,fragment.length);buffered=joined;
        while(buffered.length>=4){
            int high=buffered[2]&255;
            if((buffered[0]&255)!=254||(buffered[1]&255)!=234||(high!=16&&(high<32||high>47))){buffered=Arrays.copyOfRange(buffered,1,buffered.length);continue;}
            int size=((high==16?0:high-32)<<8)|(buffered[3]&255);
            if(size<5||size>4095){buffered=Arrays.copyOfRange(buffered,1,buffered.length);continue;}
            if(buffered.length<size)break;
            result.add(new Frame(Arrays.copyOf(buffered,size)));buffered=Arrays.copyOfRange(buffered,size,buffered.length);
        }
        return result;
    }
    int feed(byte[] fragment){int value=-1;for(Frame f:frames(fragment))if(f.command==0x6b&&f.value>0&&f.value<=100)value=f.value;return value;}
}
