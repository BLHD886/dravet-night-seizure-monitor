package org.dravet.guard;
/** Duration is proved by distinct new samples, never by a UI timer or repeated cache. */
final class AlertTracker {
    int band=-1,announced=-1;long since=-1,last=-1;String stream="";
    void reset(){band=-1;announced=-1;since=-1;last=-1;}
    int accept(int next,long stamp,long now,boolean valid,String identity,long hold){
        if(!stream.equals(identity)){reset();stream=identity;}
        if(stamp<=last)return -1;
        if(stamp-now>5000){reset();return -1;}
        if(!valid||next<0||now-stamp>15000){reset();last=stamp;return -1;}
        if(last>=0&&stamp-last>10000)reset();last=stamp;
        if(next!=band){band=next;since=stamp;}
        if(next==0){announced=0;return -1;}
        if(stamp-since>=hold&&announced!=next){announced=next;return next;}return -1;
    }
}
