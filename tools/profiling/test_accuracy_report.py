import unittest
from accuracy_report import parse_time, nearest_epoch, quantiles, offset_interval, classify, aggregate

class AccuracyReportTests(unittest.TestCase):
    def test_parse_requires_actual_valid_clock(self):
        self.assertEqual(parse_time('23:59:59:999'),86399999)
        for value in ['24:00:00:000','00:60:00:000','00:00:60:000','1:00:00:000','00:00:00:1000','00:00:00:00x']:
            with self.assertRaises(ValueError):parse_time(value)
    def test_midnight_uses_nearest_day(self):
        self.assertEqual(nearest_epoch('00:00:00:010',86399990,0),86400010)
        self.assertEqual(nearest_epoch('23:59:59:990',86400010,0),86399990)
    def test_quantiles_keep_worst_sample(self):
        q=quantiles(list(range(1,101)))
        self.assertEqual([q[k] for k in ['p50','p95','p99','max']],[50,95,99,100])
        with self.assertRaises(ValueError):quantiles([])
    def test_reference_uses_full_request_not_post_connect_only(self):
        lo,hi=offset_interval([dict(sentNs=1000000000,connectedNs=1080000000,receivedNs=1100000000,serverEpoch=2000)],1050000000)
        self.assertLessEqual(lo,900);self.assertGreaterEqual(hi,1000)
    def test_conflicting_or_missing_reference_is_not_success(self):
        with self.assertRaises(ValueError):offset_interval([],1000000000)
        a=dict(sentNs=1000000000,receivedNs=1010000000,serverEpoch=2000)
        b=dict(sentNs=2000000000,receivedNs=2010000000,serverEpoch=4000)
        with self.assertRaises(ValueError):offset_interval([a,b],1500000000)
    def test_classification_retains_uncertain_case(self):
        self.assertEqual(classify(-49,50),'within')
        self.assertEqual(classify(51,80),'outside')
        self.assertEqual(classify(-80,-51),'outside')
        self.assertEqual(classify(40,60),'inconclusive')
        self.assertEqual(classify(-60,60),'inconclusive')
    def test_quality_failure_does_not_leak_partial_estimates_into_distribution(self):
        self.assertEqual(aggregate([dict(x=1),dict(x=999,analysisError='observer_active')],'x')['signed']['max'],1)
    def test_pixels_decode_without_using_a_clock_reference_and_reject_blank(self):
        from pathlib import Path
        import tempfile
        from PIL import Image,ImageDraw
        from accuracy_report import decode_image
        patterns=['111101101101111','010110010010111','111001111100111','111001111001111','101101111001001',
                  '111100111001111','111100111101111','111001001001001','111101111101111','111101111001111']
        def render(value):
            im=Image.new('RGB',(96,10),(0,0,0));draw=ImageDraw.Draw(im)
            for at,char in enumerate(value):
                bits='000010000010000' if char==':' else patterns[int(char)]
                for k,bit in enumerate(bits):
                    if bit=='1':draw.point((at*8+2+k%3,2+k//3),fill=(255,255,255))
            return im
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);g=dict(width=96,height=10,textWidth=96,x=0,y=0)
            for d in range(10):render(f'{d}{d}:{d}{d}:{d}{d}:{d}{d}{d}').save(root/f'template-{d}.png')
            render('09:18:27:345').save(root/'known.png')
            self.assertEqual(decode_image(root/'known.png',g,root,True)['text'],'09:18:27:345')
            Image.new('RGB',(96,10),(0,0,0)).save(root/'blank.png')
            with self.assertRaises(ValueError):decode_image(root/'blank.png',g,root,True)

if __name__=='__main__':unittest.main()
