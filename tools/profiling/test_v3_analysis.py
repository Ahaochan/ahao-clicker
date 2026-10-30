"""Evidence integrity and conservative reference fixtures; no Android/device required."""
import struct
import unittest

from v3_analysis import (decode_records, distribution, parse_reference, reference_interval,
                         paired_interval, classify_interval, task_chains, associate_frames, clock_brackets)


class EvidenceTests(unittest.TestCase):
    def test_nearest_rank_and_empty(self):
        self.assertIsNone(distribution([]))
        self.assertEqual(distribution([1, 2, 3, 4])["p50"], 2)
        self.assertEqual(distribution(range(1, 101))["p99"], 99)

    def test_signed_early_samples_are_kept(self):
        summary = distribution([-2, 0, 50, 51])
        self.assertEqual(summary["early"], 1)
        self.assertEqual(summary["over50"], 1)

    def test_binary_sequence_truncation_and_task_bits(self):
        one = struct.pack("<8q", 1, 123000000, 9, (42 << 32) | 114, 1, 2, 3, 4)
        rows = decode_records(one)
        self.assertEqual(rows[0]["attemptId"], 42)
        self.assertEqual(rows[0]["event"], 114)
        with self.assertRaisesRegex(ValueError, "truncated"):
            decode_records(one + b"x")
        with self.assertRaisesRegex(ValueError, "sequence"):
            decode_records(one + one)
        with self.assertRaisesRegex(ValueError, "sequence"):
            decode_records(struct.pack("<8q", 2, 123, 9, 1, 0, 0, 0, 0))

    def test_reference_protocol(self):
        raw = "10105444978-107668-1792980614483"
        self.assertEqual(parse_reference(200, [raw, " \t" + raw + "\r\n"], None), 1792980614483)
        for status, ids, age in [(500, [raw], None), (200, [], None), (200, [raw + "x"], None),
                                 (200, [raw, raw[:-1] + "4"], None), (200, [raw], "1"),
                                 (200, [raw], "invalid"), (200, ["1-2-1792980614"], None),
                                 (200, ["1-2-0000000000000"], None)]:
            with self.assertRaises(ValueError):
                parse_reference(status, ids, age)

    def test_full_connect_interval_and_quantization(self):
        row = dict(sentNs=1_000_000_000, connectedNs=1_080_000_000, receivedNs=1_100_000_000,
                   httpStatus=200, rawRequestIdValues=["1-2-1792980614483"])
        interval = reference_interval(row, 1_200_000_000)
        self.assertAlmostEqual(interval["upper"] - interval["lower"], 102)
        self.assertEqual(interval["ageMillis"], 100)
        with self.assertRaisesRegex(ValueError, "age"):
            reference_interval(row, 7_000_000_000)

    def test_pairs_use_hull_never_intersection(self):
        left = dict(lower=10, upper=30, estimate=20)
        right = dict(lower=20, upper=40, estimate=30)
        combined = paired_interval(left, right)
        self.assertEqual((combined["lower"], combined["upper"]), (10, 40))
        with self.assertRaisesRegex(ValueError, "conflict"):
            paired_interval(left, dict(lower=31, upper=50, estimate=40))

    def test_no_false_pass_for_wide_or_missing_reference(self):
        self.assertEqual(classify_interval(-1, 20, 0, 50), "inconclusive")
        self.assertEqual(classify_interval(1, 49, 0, 50), "conditional_within")
        self.assertEqual(classify_interval(51, 80, 0, 50), "conditional_outside")
        self.assertEqual(classify_interval(-100, 100, -50, 50), "inconclusive")

    def test_chain_uses_final_deadline_and_real_receipts(self):
        def event(code, ns, a=0, b=0):
            return dict(attemptId=1,event=code,elapsedNs=ns,a=a,b=b,c=0,d=0)
        app=[event(60,0,4),event(10,100_000_000,1792980614483,1000),
             event(12,200_000_000,1000,1100),event(114,1_102_000_000),event(60,1_115_000_000,3),event(60,1_200_000_000,4)]
        target=[event(400,1_108_000_000),event(401,1_110_000_000),event(420,1_117_000_000,66)]
        row=task_chains(app,target)[0]
        self.assertEqual(row['finalDeadlineMs'],1100)
        self.assertEqual(row['localMillis'],dict(dispatch=2,down=8,click=17))
        self.assertEqual(row['outcome'],'exactly_once_B')
        self.assertEqual(row['engineTerminal']['a'],3)
        missing=task_chains(app,[])[0]
        self.assertEqual(missing['outcome'],'zero_receipts')
        self.assertIsNone(missing['localMillis']['down'])

    def test_frame_link_is_token_based_and_rejects_ambiguity(self):
        rows=[dict(kind='slice',id=1,parent=None,name='Choreographer#doFrame 10',ts=0,dur=20,pid=5),
              dict(kind='slice',id=2,parent=1,name='clock_draw',ts=1,dur=5,pid=5),
              dict(kind='counter',name='clock_draw_id',ts=2,value=7,pid=5),
              dict(kind='frame',id=3,ts=0,dur=10,process='com.ahao.clicker',surfaceToken=10,displayToken=20,layer='clock',pid=5),
              dict(kind='frame',id=4,ts=5,dur=10,process='/system/bin/surfaceflinger',surfaceToken=None,displayToken=20,layer=None,pid=6)]
        draw=dict(frameId=7,drawBeginNs=1,drawEndNs=6,epoch=1792980614483)
        linked=associate_frames(rows,[draw])
        self.assertEqual(linked[0]['presentedNs'],15)
        self.assertEqual(linked[0]['displayToken'],20)
        rows.append(dict(rows[3],id=8,layer='other'))
        self.assertEqual(associate_frames(rows,[draw])[0]['reason'],'ambiguous_surface_frame')

    def test_resync_is_animation_metadata_not_hwui_frameinfo_token(self):
        rows=[dict(kind='slice',id=1,parent=None,name='Choreographer#doFrame 10',ts=0,dur=40,pid=5),
              dict(kind='slice',id=2,parent=1,name='Choreographer#doFrame - resynced to 11',ts=1,dur=30,pid=5),
              dict(kind='slice',id=3,parent=2,name='draw-VRI[clock-session]',ts=2,dur=20,pid=5),
              dict(kind='slice',id=4,parent=3,name='clock_draw',ts=3,dur=5,pid=5),
              dict(kind='counter',name='clock_draw_id',ts=4,value=7,pid=5),
              dict(kind='frame',id=5,ts=0,dur=10,process='com.ahao.clicker',surfaceToken=10,displayToken=21,layer='TX - VRI-clock-session#1',pid=5,presentType='On-time Present'),
              dict(kind='frame',id=6,ts=0,dur=10,process='com.ahao.clicker',surfaceToken=11,displayToken=21,layer='TX - VRI-clock-session#1',pid=5,presentType='On-time Present'),
              dict(kind='frame',id=7,ts=0,dur=10,process='com.ahao.clicker',surfaceToken=11,displayToken=21,layer='other',pid=5),
              dict(kind='frame',id=8,ts=10,dur=10,process='/system/bin/surfaceflinger',surfaceToken=None,displayToken=21,layer=None,pid=6,presentType='On-time Present')]
        draw=dict(frameId=7,drawBeginNs=3,drawEndNs=8,epoch=1)
        linked=associate_frames(rows,[draw],window='clock-session')[0]
        self.assertEqual(linked['surfaceFrameId'],5)
        self.assertEqual(linked['presentedNs'],20)
        rows[5]['presentType']='Unspecified Present'
        self.assertEqual(associate_frames(rows,[draw],window='clock-session')[0]['reason'],'unknown_surface_present_type')
        self.assertEqual(associate_frames(rows,[draw],window='wrong')[0]['reason'],'missing_window_ancestry')

    def test_clock_brackets_do_not_call_descheduling_clock_drift(self):
        brackets=[dict(id=1,beforeNs=100,afterNs=300,uptimeMs=0),dict(id=2,beforeNs=1000,afterNs=3000000,uptimeMs=1)]
        counters=[dict(value=1,ts=200),dict(value=2,ts=2000000)]
        snapshots=[dict(clockId=6,ts=50,value=50)]
        result=clock_brackets(brackets,counters,snapshots)
        self.assertTrue(result['boottimeIdentity'])
        self.assertEqual(result['outsideBracket'],0)
        self.assertEqual(result['narrowCount'],1)
        counters[0]['ts']=400
        self.assertEqual(clock_brackets(brackets,counters,snapshots)['outsideBracket'],1)


if __name__ == "__main__":
    unittest.main()
