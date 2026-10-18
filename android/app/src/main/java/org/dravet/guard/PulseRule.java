package org.dravet.guard;

/** User-defined engineering rule. Classifies pulse bands, never diagnoses seizures. */
final class PulseRule {
    static final long HOLD_MS=3000, MAX_GAP_MS=10000, STALE_MS=15000;
    int band=-1, announced=-1;
    long since=-1, last=-1;
    String stream="";
    static int band(double value){if(!Double.isFinite(value)||value<=0||value>65535)return -1;return value>=140?3:value>=130?2:value>=114?1:0;}
    void reset(){band=-1;announced=-1;since=-1;last=-1;}
    int accept(double value,long stamp,long now,boolean valid,String device){
        if(!stream.equals(device)){reset();stream=device;}
        if(stamp<=last)return -1; // Duplicate polling and out-of-order records cannot advance the timer.
        int next=band(value);
        if(stamp-now>5000){reset();return -1;}
        if(!valid||next<0||now-stamp>STALE_MS){reset();last=stamp;return -1;}
        if(last>=0&&stamp-last>MAX_GAP_MS)reset();
        last=stamp;
        if(next!=band){band=next;since=stamp;}
        if(next==0){announced=0;return -1;}
        if(stamp-since>=HOLD_MS&&announced!=next){announced=next;return next;}
        return -1;
    }
}
