SELECT json_object('kind','bounds','start',start_ts,'end',end_ts) AS row FROM trace_bounds
UNION ALL
SELECT json_object('kind','quality','name',name,'value',value,'severity',severity,'source',source)
FROM stats WHERE value>0
UNION ALL
SELECT json_object('kind','frame','id',s.id,'ts',s.ts,'dur',s.dur,'displayToken',s.display_frame_token,
 'surfaceToken',s.surface_frame_token,'process',p.name,'pid',p.pid,'layer',s.layer_name,
 'presentType',s.present_type,'jank',s.jank_type,'isBuffer',EXTRACT_ARG(s.arg_set_id,'Is Buffer?'))
FROM actual_frame_timeline_slice s JOIN process p USING(upid)
WHERE p.name IN ('com.ahao.clicker','com.ahao.clicker.acceptance.target','/system/bin/surfaceflinger')
UNION ALL
SELECT json_object('kind','slice','id',s.id,'parent',s.parent_id,'ts',s.ts,'dur',s.dur,'name',s.name,'tid',t.tid,'pid',p.pid)
FROM slice s JOIN thread_track tt ON tt.id=s.track_id JOIN thread t USING(utid) JOIN process p USING(upid)
WHERE p.name='com.ahao.clicker' AND t.tid=p.pid
UNION ALL
SELECT json_object('kind','counter','name',ct.name,'ts',c.ts,'value',c.value,'pid',p.pid)
FROM counter c JOIN process_counter_track ct ON ct.id=c.track_id JOIN process p USING(upid)
WHERE p.name='com.ahao.clicker' AND ct.name IN ('clock_draw_id','clock_epoch','clock_bracket')
UNION ALL
SELECT json_object('kind','clock','ts',ts,'clockId',clock_id,'value',clock_value,'snapshotId',snapshot_id)
FROM clock_snapshot;
