import json
from pathlib import Path
import tempfile
import unittest
from repair_report import batch, cpu_samples


class RepairReportTests(unittest.TestCase):
    def test_green_collector_does_not_hide_missing_or_failed_quality_metadata(self):
        with tempfile.TemporaryDirectory() as root:
            directory=Path(root);session=directory/'target-session';session.mkdir()
            app=directory/'app-session';app.mkdir()
            (app/'events.bin').write_bytes(b'');(session/'receipts.bin').write_bytes(b'')
            (session/'driver.jsonl').write_text('',encoding='utf-8')
            result=dict(requestedSamples=0,passed=True,targetDropped=0,collectorFailure='',
                        appRecorder=dict(mode='off',written=0,frames=0,dropped=0,observerFailures=0,error='',failureEvent=0))
            host=dict(stoppedReason=None,instrumentExitCode=0)
            def read():
                (session/'result.json').write_text(json.dumps(result),encoding='utf-8')
                (directory/'host-report.json').write_text(json.dumps(host),encoding='utf-8')
                return batch(directory)
            self.assertEqual(read()['qualityErrors'],[])
            result['appRecorder']['dropped']=1
            self.assertIn('app_dropped',read()['qualityErrors'])
            result['appRecorder']['dropped']=0;result['collectorFailure']='writer_failed'
            self.assertIn('target_collectorFailure',read()['qualityErrors'])
            result['collectorFailure']='';host['stoppedReason']='heartbeat_stale'
            self.assertIn('host_stoppedReason',read()['qualityErrors'])
            host['stoppedReason']=None;result['appRecorder'].pop('mode')
            self.assertIn('missing_app_mode',read()['qualityErrors'])
            result['appRecorder']['mode']='off';(app/'events.bin').write_bytes(b'unexpected')
            self.assertIn('off_app_journal_nonempty',read()['qualityErrors'])
            result['appRecorder']=[1]
            self.assertIn('invalid_app_metadata',read()['qualityErrors'])

    def test_missing_and_failed_receipts_stay_in_denominator(self):
        with tempfile.TemporaryDirectory() as root:
            directory=Path(root);session=directory/'target-session';session.mkdir()
            (session/'result.json').write_text(json.dumps(dict(requestedSamples=4,passed=False)))
            valid=dict(kind='receipt',attemptId=2,downs=1,ups=1,clicks=1,point=66,touchOpen=False,
                       downNs=1001000000,upNs=1002000000,clickNs=1003000000,
                       production=dict(plannedElapsed=1000,armedElapsed=500,dispatchedElapsed=1000,phase='COMPLETED'))
            # attempt 1 is warmup; 3 is missing; 4 duplicated. None silently becomes 0ms.
            rows=[dict(valid,attemptId=1),valid,dict(valid,attemptId=4),dict(valid,attemptId=4)]
            (session/'driver.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in rows))
            result=batch(directory,1)
            self.assertEqual((result['requested'],result['actualReceived'],result['missingOrInvalid']),(3,1,2))
            self.assertEqual(result['metrics']['clickMs']['n'],1)
            self.assertEqual(result['metrics']['clickMs']['max'],3)
            self.assertEqual(result['metrics']['armLeadMs']['max'],500)

    def test_process_cpu_requires_same_pid_and_device_clock(self):
        with tempfile.TemporaryDirectory() as root:
            directory=Path(root)
            for i,(stamp,pid,ticks) in enumerate([(12,1,100),(22,1,120),(22,2,900)]):
                fields=['S']+['0']*10+[str(ticks),'0']+['0']*10
                (directory/f'proc-{i}.txt').write_text(str(stamp)+' 0\n'+str(pid)+' (app) '+' '.join(fields)+'\n')
            result=cpu_samples(directory,10000,20000)
            one=next(r for r in result if r['pid']==1)
            self.assertEqual(one['cpuOneCorePercent'],2)
            self.assertIsNone(next(r for r in result if r['pid']==2)['cpuOneCorePercent'])


if __name__=='__main__':unittest.main()
