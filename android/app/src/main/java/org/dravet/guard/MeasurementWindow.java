package org.dravet.guard;

/** Receive window uses elapsed time. An FF packet is status, not proof all results arrived. */
final class MeasurementWindow {
    static final long TIMEOUT_MS=90000;
    private int command=-1;
    private long deadline;
    void begin(int command,long now){this.command=command;deadline=now+TIMEOUT_MS;}
    void close(){command=-1;}
    boolean accepts(int command,long now){return this.command==command&&now<deadline;}
    boolean timedOut(long now){return command!=-1&&now>=deadline;}
}
