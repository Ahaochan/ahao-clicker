package com.ahao.clicker.acceptance.target;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Independent measurement parser: deliberately does not import the product's parser or anchor. */
final class OracleHeaders {
    private static final Pattern ID=Pattern.compile("^[0-9]+-[0-9]+-([0-9]{13})$");
    static long epoch(int status, Map<String,List<String>> fields) {
        if(status<200 || status>=300) throw new IllegalArgumentException("http_status_"+status);
        String value=null;
        for(Map.Entry<String,List<String>> field:fields.entrySet()) {
            if(field.getKey()==null) continue;
            if(field.getKey().equalsIgnoreCase("Age")) {
                for(String age:field.getValue()) {
                    try { if(Long.parseLong(age.trim())!=0) throw new IllegalArgumentException("cached_or_invalid_age"); }
                    catch(NumberFormatException error) { throw new IllegalArgumentException("invalid_age",error); }
                }
            }
            if(field.getKey().equalsIgnoreCase("x-api-request-id")) {
                for(String raw:field.getValue()) {
                    String next=raw.trim();
                    if(value!=null && !value.equals(next)) throw new IllegalArgumentException("conflicting_request_id");
                    value=next;
                }
            }
        }
        Matcher match=ID.matcher(value==null?"":value);
        if(!match.matches()) throw new IllegalArgumentException("invalid_request_id");
        long result=Long.parseLong(match.group(1));
        if(result<946684800000L || result>=4102444800000L) throw new IllegalArgumentException("epoch_out_of_range");
        return result;
    }
}
