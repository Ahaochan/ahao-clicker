package com.ahao.clicker.acceptance.target;

import org.junit.Test;
import static org.junit.Assert.*;

public class FailureControlTest {
    @Test public void controlsCannotBeMixedWithPerformanceOrTracing() {
        FailureControl.config("off",1,0,4000,15000,false,false,false);
        assertThrows(IllegalArgumentException.class,()->FailureControl.config("light",1,0,4000,15000,false,false,false));
        assertThrows(IllegalArgumentException.class,()->FailureControl.config("off",2,0,4000,15000,false,false,false));
        assertThrows(IllegalArgumentException.class,()->FailureControl.config("off",1,0,3999,15000,false,false,false));
        assertThrows(IllegalArgumentException.class,()->FailureControl.config("off",1,0,4000,15000,true,false,false));
        assertThrows(IllegalArgumentException.class,()->FailureControl.config("off",1,0,4000,15000,false,true,false));
        assertThrows(IllegalArgumentException.class,()->FailureControl.config("off",1,0,4000,15000,false,false,true));
    }

    @Test public void expectedFailureNeedsRealBlockWindowExactReasonAndZeroReception() {
        FailureControl.rejection(10000,9500000000L,10170000000L,0,0,0,false,"FAILED",107,4,false);
        assertThrows(IllegalStateException.class,()->FailureControl.rejection(10000,9500000000L,10170000000L,0,0,0,false,"EXPIRED",107,4,false));
        assertThrows(IllegalStateException.class,()->FailureControl.rejection(10000,9500000000L,10170000000L,0,0,0,false,"FAILED",107,5,false));
        assertThrows(IllegalStateException.class,()->FailureControl.rejection(10000,9500000000L,10170000000L,1,1,1,false,"FAILED",107,4,false));
        assertThrows(IllegalStateException.class,()->FailureControl.rejection(10000,9500000000L,10170000000L,0,0,0,false,"FAILED",107,4,true));
        assertThrows(IllegalStateException.class,()->FailureControl.rejection(10000,9800000000L,10470000000L,0,0,0,false,"FAILED",107,4,false));
        assertThrows(IllegalStateException.class,()->FailureControl.rejection(10000,9500000000L,10090000000L,0,0,0,false,"FAILED",107,4,false));
    }

    @Test public void cancelledPendingBlockCannotLeakIntoReplacementSession() {
        FailureControl.cancelled(0,0,false,0,0,false);
        assertThrows(IllegalStateException.class,()->FailureControl.cancelled(1,2,false,0,0,false));
        assertThrows(IllegalStateException.class,()->FailureControl.cancelled(0,0,true,0,0,false));
        assertThrows(IllegalStateException.class,()->FailureControl.cancelled(0,0,false,1,2,false));
        assertThrows(IllegalStateException.class,()->FailureControl.cancelled(0,0,false,0,0,true));
    }
}
