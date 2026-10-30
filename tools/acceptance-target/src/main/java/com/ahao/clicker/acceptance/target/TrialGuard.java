package com.ahao.clicker.acceptance.target;

/** Pure measurement admission checks. Never starts or retries a product task. */
final class TrialGuard {
    static void setup(long now,long plannedPlay) {
        if(now>plannedPlay-500)throw new IllegalStateException("Picker/control preparation exceeded quiet budget");
    }
    static void arm(long expectedTarget,long observedTarget,long deadline,long armed,long playStartedNs,long observedMs,String phase) {
        if(observedTarget!=expectedTarget || armed<playStartedNs/1000000-1 || deadline<=0)
            throw new IllegalStateException("No matching new ARM; previous timing cannot confirm this trial");
        // A closed state may mean the task finished already, not that it never armed.
        if(observedMs>deadline-750)throw new IllegalStateException("ARM read outside quiet budget; keep actual receipt, no performance admission");
        if(!"WAITING".equals(phase))throw new IllegalStateException("Task already closed/interrupted: "+phase);
    }
    static void receipt(int downs,int ups,int clicks,int point,boolean open,String phase) {
        if(downs!=1 || ups!=1 || clicks!=1 || point!=66 || open || !"COMPLETED".equals(phase))
            throw new IllegalStateException("Unclosed, missing or interrupted actual receipt; batch ends without retry");
    }
}
