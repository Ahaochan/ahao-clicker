package com.ahao.clicker.acceptance.target;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrialGuardTest {
    @Test public void setupMustFinishBeforePlayMargin() {
        TrialGuard.setup(1000,1500);
        assertThrows(IllegalStateException.class,()->TrialGuard.setup(1001,1500));
    }
    @Test public void oldCompletedTimingCannotConfirmANewArm() {
        TrialGuard.arm(5000,5000,10000,6100,6000000000L,6200,"WAITING");
        assertThrows(IllegalStateException.class,()->TrialGuard.arm(5000,4999,10000,1000,6000000000L,6200,"COMPLETED"));
        assertThrows(IllegalStateException.class,()->TrialGuard.arm(5000,5000,10000,1000,6000000000L,6200,"WAITING"));
        assertThrows(IllegalStateException.class,()->TrialGuard.arm(5000,5000,10000,6100,6000000000L,9400,"COMPLETED"));
        assertThrows(IllegalStateException.class,()->TrialGuard.arm(5000,5000,10000,6100,6000000000L,6200,"STOPPED"));
    }
    @Test public void missingDuplicateCancelledOrWrongTargetEndsBatch() {
        TrialGuard.receipt(1,1,1,66,false,"COMPLETED");
        assertThrows(IllegalStateException.class,()->TrialGuard.receipt(0,0,0,0,false,"COMPLETED"));
        assertThrows(IllegalStateException.class,()->TrialGuard.receipt(1,0,0,0,true,"FAILED"));
        assertThrows(IllegalStateException.class,()->TrialGuard.receipt(2,2,2,66,false,"COMPLETED"));
        assertThrows(IllegalStateException.class,()->TrialGuard.receipt(1,1,1,65,false,"COMPLETED"));
        assertThrows(IllegalStateException.class,()->TrialGuard.receipt(0,0,0,0,false,"STOPPED"));
    }
}
