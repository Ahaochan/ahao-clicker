package com.ahao.clicker.acceptance.target;

/** Independent fault-control validation, never a performance admission rule. */
final class FailureControl {
    static void config(String mode,int count,long duration,long lead,long setup,boolean trace,boolean oracle,boolean calibrate) {
        if(!"off".equals(mode)||count!=1||duration!=0||lead!=4000||setup!=15000||trace||oracle||calibrate)
            throw new IllegalArgumentException("Failure controls require one off task, 4s lead, 15s setup and no trace/oracle/calibration");
    }
    static void rejection(long deadline,long startNs,long endNs,int downs,int ups,int clicks,boolean open,
                          String phase,int event,long reason,boolean dispatched) {
        boolean window=deadline>0 && startNs>0 && startNs<=(deadline-250)*1000000 &&
            endNs>=(deadline+100)*1000000 && endNs<=(deadline+220)*1000000 && endNs-startNs>=670000000;
        if(!window||downs!=0||ups!=0||clicks!=0||open||!"FAILED".equals(phase)||event!=107||reason!=4||dispatched)
            throw new IllegalStateException("Expected exact late-preparation rejection was not proven; preserve control without retry");
    }
    static void cancelled(long oldStart,long oldEnd,boolean oldPending,long newStart,long newEnd,boolean newPending) {
        if(oldStart!=0||oldEnd!=0||oldPending||newStart!=0||newEnd!=0||newPending)
            throw new IllegalStateException("Pending fault ran or leaked into a replacement session");
    }
}
