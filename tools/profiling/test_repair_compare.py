import unittest

from repair_compare import compare


def report(latency):
    def attempt(index, warmup):
        return dict(attemptId=index, warmup=warmup, outcome='exactly_once_B', phase='COMPLETED',
                    downMs=latency, clickMs=latency+10, armLeadMs=3990)
    return dict(collectorPassed=True, qualityErrors=[],recordingMode='off',failureDiagnosticsPresent=True,failureEvent=0,
                requested=25, warmupRequested=10,
                attempts=[attempt(i, i <= 10) for i in range(1, 36)])


class ComparisonTests(unittest.TestCase):
    def test_complete_receipts_do_not_override_recorder_or_host_quality_failure(self):
        runs=[dict(variant=v,collected=True,bootId='same',report=report(10 if v=='A' else 8))
              for v in 'ABBABAAB']
        self.assertTrue(compare(runs)['eligible'])
        runs[0]['report']['qualityErrors']=['app_dropped']
        self.assertFalse(compare(runs)['eligible'])
        runs[0]['report']['qualityErrors']=[];runs[0]['report']['recordingMode']='frame'
        self.assertFalse(compare(runs)['eligible'])
        runs[0]['report']['recordingMode']='off';runs[0]['report'].pop('qualityErrors')
        self.assertFalse(compare(runs)['eligible'])

    def test_only_complete_independent_batches_can_enter_comparison(self):
        runs=[dict(variant=v, collected=True, bootId='same', report=report(10 if v=='A' else 8))
              for v in 'ABBABAAB']
        missing=compare(runs[:-1])
        self.assertFalse(missing['eligible'])
        runs[0]['report']['attempts'][3]['outcome']='invalid_receipt'
        result=compare(runs)
        self.assertFalse(result['eligible'])
        self.assertIn('invalid_or_unclosed_attempt', result['reasons'])

    def test_improved_percentiles_cannot_hide_early_receipt_or_maximum_regression(self):
        runs=[dict(variant=v, collected=True, bootId='same', report=report(10 if v=='A' else 8))
              for v in 'ABBABAAB']
        result=compare(runs)
        self.assertTrue(result['discoveryPassed'])
        self.assertFalse(result['retentionApproved'])
        self.assertEqual(result['variants']['A']['downMs']['n'],100)
        runs[1]['report']['attempts'][-1]['clickMs']=100
        result=compare(runs)
        self.assertFalse(result['discoveryPassed'])
        self.assertIn('clickMs_max_regression',result['reasons'])
        runs[1]['report']['attempts'][-1]['clickMs']=-1
        result=compare(runs)
        self.assertFalse(result['discoveryPassed'])
        self.assertIn('early_receipt',result['reasons'])

    def test_reboot_or_changed_order_invalidates_a_b_comparison(self):
        runs=[dict(variant=v, collected=True, bootId='same', report=report(10 if v=='A' else 8))
              for v in 'ABBABAAB']
        runs[1]['bootId']='new'
        self.assertFalse(compare(runs)['eligible'])
        runs[1]['bootId']='same';runs[1]['variant']='A'
        self.assertFalse(compare(runs)['eligible'])


if __name__=='__main__':unittest.main()
