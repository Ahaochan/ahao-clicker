package com.ahao.clicker.acceptance.target;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OracleHeadersTest {
    private Map<String,List<String>> headers(String... ids) {
        Map<String,List<String>> result=new HashMap<>();
        result.put("X-API-Request-Id",Arrays.asList(ids)); return result;
    }
    @Test public void originalHeaderIsUnixMillisAndEquivalentDuplicatesAreAllowed() {
        assertEquals(1792980614483L,OracleHeaders.epoch(200,headers("10105444978-107668-1792980614483")));
        assertEquals(1792980614483L,OracleHeaders.epoch(204,headers(" 1-2-1792980614483 ","1-2-1792980614483")));
    }
    @Test public void conflictingOrAbsentOrMalformedHeaderCannotBecomeZeroTime() {
        assertThrows(IllegalArgumentException.class,()->OracleHeaders.epoch(200,Collections.emptyMap()));
        for(String value:new String[]{"1-2-1792980614","1-2-1792980614483x","1-2-0000000000000","1-2-4102444800000"}) {
            assertThrows(IllegalArgumentException.class,()->OracleHeaders.epoch(200,headers(value)));
        }
        assertThrows(IllegalArgumentException.class,()->OracleHeaders.epoch(200,headers("1-2-1792980614483","1-2-1792980614484")));
    }
    @Test public void cacheAndBadStatusRemainReferenceFailures() {
        Map<String,List<String>> map=headers("1-2-1792980614483");
        assertThrows(IllegalArgumentException.class,()->OracleHeaders.epoch(500,map));
        map.put("Age",Collections.singletonList("1"));
        assertThrows(IllegalArgumentException.class,()->OracleHeaders.epoch(200,map));
        map.put("Age",Collections.singletonList("invalid"));
        assertThrows(IllegalArgumentException.class,()->OracleHeaders.epoch(200,map));
    }
}
