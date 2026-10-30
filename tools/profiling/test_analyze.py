import unittest

from analyze import arm_observation, clipped_states, distribution, target_wait_chains


class TraceAnalysisTest(unittest.TestCase):
    def test_wait_chain_uses_following_runnable_waker_not_sleep_row(self):
        data={'threads':[{'utid':1,'tid':10,'pid':10,'thread':'main','process':'com.ahao.clicker.acceptance.target'},
                         {'utid':2,'tid':11,'pid':10,'thread':'RenderThread','process':'com.ahao.clicker.acceptance.target'}],
              'slices':[{'utid':1,'name':'postAndWait','ts':0,'dur':10_000_000}],
              'states':[{'utid':1,'ts':1_000_000,'dur':8_000_000,'state':'S','waker_utid':None},
                        {'utid':1,'ts':9_000_000,'dur':100_000,'state':'R','waker_utid':2},
                        {'utid':2,'ts':1_000_000,'dur':8_000_000,'state':'Running','waker_utid':None}],
              'samples':[{'utid':2,'ts':2_000_000,'callsite_id':0}],
              'callsites':[{'id':0,'parent_id':None,'frame_id':0}],
              'symbols':[{'id':0,'name':'qemu_pipe_read','mapping':'emulation.so'}]}
        result=target_wait_chains(data,0,10_000_000)[0]
        self.assertEqual('RenderThread',result['waker']['thread'])
        self.assertEqual(.1,result['wakeRunnableMillis'])
        self.assertEqual(8,result['wakerStates']['totalMillis']['Running'])
        self.assertEqual(1,result['wakerSamplesWithStack'])
        self.assertEqual([('qemu_pipe_read',1)],result['wakerInclusiveFunctions'])

    def test_wait_chain_without_wake_evidence_does_not_invent_render_cause(self):
        data={'threads':[{'utid':1,'tid':10,'pid':10,'thread':'main','process':'com.ahao.clicker.acceptance.target'}],
              'slices':[{'utid':1,'name':'postAndWait','ts':0,'dur':10}],
              'states':[{'utid':1,'ts':0,'dur':10,'state':'S','waker_utid':None}],
              'samples':[],'callsites':[],'symbols':[]}
        result=target_wait_chains(data,0,10)[0]
        self.assertIsNone(result['waker'])
        self.assertEqual(0,result['wakerSamplesWithStack'])

    def test_historical_failure_without_snapshot_stays_unknown(self):
        self.assertIsNone(arm_observation({'error': 'Did not arm'}))

    def test_system_completion_without_target_receipt_is_not_actual_success(self):
        report={'failureSnapshot':{'appState':{'phase':'COMPLETED'},'targetEvents':[]}}
        self.assertEqual('completed_without_expected_receipt',arm_observation(report)['classification'])

    def test_late_observation_keeps_actual_completed_receipt(self):
        report={'failureSnapshot':{'appState':{'phase':'COMPLETED'},'targetEvents':[{'point':'B'}]}}
        data=arm_observation(report)
        self.assertEqual('completed_before_observation',data['classification'])
        self.assertEqual(1,data['receivedEvents'])

    def test_accepted_play_can_arrive_after_prepared_deadline(self):
        report={'stageHistory':[{'index':0,'stage':'prepared','appState':{'initialPlannedElapsed':1000}}],
                'failureSnapshot':{'index':0,'appState':{'phase':'STOPPED'},'targetEvents':[],
                                   'lastPlayControl':{'actionAccepted':True,'actionStartedElapsed':1300}}}
        data=arm_observation(report)
        self.assertEqual(300,data['playStartMinusInitialDeadlineMillis'])
        self.assertEqual('stopped_at_observation',data['classification'])

    def test_missing_control_is_not_a_scheduler_dispatch_failure(self):
        report={'failureSnapshot':{'stage':'play_action','appState':{'phase':'STOPPED'},'lastPlayControl':{}}}
        self.assertEqual('no_accepted_play_action',arm_observation(report)['classification'])

    def test_window_clips_both_edges_and_excludes_other_threads(self):
        rows = [
            {'utid': 1, 'ts': 0, 'dur': 15_000_000, 'state': 'Running'},
            {'utid': 1, 'ts': 15_000_000, 'dur': 30_000_000, 'state': 'R'},
            {'utid': 2, 'ts': 0, 'dur': 60_000_000, 'state': 'Running'},
            {'utid': 1, 'ts': 45_000_000, 'dur': 10_000_000, 'state': 'Running'},
        ]
        data = clipped_states(rows, {1}, 10_000_000, 40_000_000)
        self.assertEqual({'Running': 5.0, 'R': 25.0}, data['totalMillis'])
        self.assertEqual(30, sum(data['totalMillis'].values()))

    def test_separate_runnable_segments_do_not_become_one_long_wait(self):
        rows = [
            {'utid': 1, 'ts': 0, 'dur': 3_000_000, 'state': 'R'},
            {'utid': 1, 'ts': 3_000_000, 'dur': 1_000_000, 'state': 'Running'},
            {'utid': 1, 'ts': 4_000_000, 'dur': 2_000_000, 'state': 'R'},
        ]
        data = clipped_states(rows, {1}, 0, 6_000_000)
        self.assertEqual(5, data['totalMillis']['R'])
        self.assertEqual(3, data['longestSegmentMillis']['R'])

    def test_zero_length_window_has_no_attributed_time(self):
        rows = [{'utid': 1, 'ts': 0, 'dur': 10, 'state': 'Running'}]
        self.assertEqual({}, clipped_states(rows, {1}, 5, 5)['totalMillis'])

    def test_percentile_retains_single_extreme_sample(self):
        data = distribution([0] * 19 + [297])
        self.assertEqual(0, data['p95'])
        self.assertEqual(297, data['max'])
        self.assertEqual(20, data['count'])


if __name__ == '__main__':
    unittest.main()
