"""Distribution boundaries used by the timing evidence report."""
import importlib.util
from pathlib import Path
import unittest
import json
import tempfile

spec = importlib.util.spec_from_file_location('timing_report', Path(__file__).with_name('timing-report.py'))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class TimingReportTest(unittest.TestCase):
    def test_nearest_rank_tail_and_strict_fifty_millisecond_boundary(self):
        values = list(range(101))
        result = report.distribution(values, limit=50)
        self.assertEqual(95, result['p95'])
        self.assertEqual(99, result['p99'])
        self.assertEqual(50, result['overLimitCount'])
        self.assertAlmostEqual(100 * 50 / 101, result['overLimitPercent'])
        self.assertEqual(list(range(101)), values)

    def test_small_sample_p99_is_not_extrapolated(self):
        result = report.distribution([51, 50, 0], limit=50)
        self.assertEqual(51, result['p99'])
        self.assertEqual(1, result['overLimitCount'])

    def test_empty_distribution_is_unknown(self):
        self.assertIsNone(report.distribution([]))

    def test_completed_failure_checkpoint_keeps_actual_receipt_separate_from_successful_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'result.json').write_text(json.dumps({
                'passed': False, 'samples': [], 'requestedSamples': 3,
                'failureSnapshot': {'index': 0, 'targetEvents': [{'point': 'B'}]},
            }), encoding='utf-8')
            result = report.summarize(directory)
        self.assertEqual(1, result['attemptedTasks'])
        self.assertEqual(2, result['notAttemptedTasks'])
        self.assertEqual(1, result['failureObservedOnClicks'])
        self.assertEqual(0, result['received'])

    def test_old_failure_without_snapshot_does_not_invent_receipt_count(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'result.json').write_text(json.dumps({'passed': False, 'samples': []}), encoding='utf-8')
            result = report.summarize(directory)
        self.assertIsNone(result['attemptedTasks'])
        self.assertIsNone(result['failureObservedOnClicks'])


if __name__ == '__main__':
    unittest.main()
