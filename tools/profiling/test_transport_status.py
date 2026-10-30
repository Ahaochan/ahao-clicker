import unittest
from transport_status import transport_errors

class TransportStatusTests(unittest.TestCase):
    def test_success_requires_all_transport_fields(self):
        good=dict(stoppedReason=None,exitCode=0,targetPull=0,appPull=0)
        self.assertEqual(transport_errors(good,'exitCode',['targetPull','appPull']),[])
        for key in good:
            bad=good.copy();bad.pop(key)
            self.assertTrue(transport_errors(bad,'exitCode',['targetPull','appPull']))
    def test_adb_exit_and_failed_pull_are_not_normal_completion(self):
        bad=dict(stoppedReason=None,exitCode=255,targetPull=1,appPull=0)
        self.assertEqual(transport_errors(bad,'exitCode',['targetPull','appPull']),['exitCode','targetPull'])
    def test_watchdog_failure_and_bool_exit_are_not_success(self):
        self.assertTrue(transport_errors(dict(stoppedReason='heartbeat_stale',exitCode=0),'exitCode',[]))
        self.assertTrue(transport_errors(dict(stoppedReason=None,exitCode=False),'exitCode',[]))
    def test_invalid_report_is_rejected(self):
        self.assertEqual(transport_errors(None,'exitCode',[]),['invalid_report'])
