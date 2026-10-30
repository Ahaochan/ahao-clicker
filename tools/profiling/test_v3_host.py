import unittest
from v3_host import HeartbeatWatchdog


class WatchdogTests(unittest.TestCase):
    def test_startup_and_stall_boundaries(self):
        watch=HeartbeatWatchdog(0)
        self.assertFalse(watch.expired(4.9))
        self.assertTrue(watch.expired(5.1))
        watch.observe(5.1)
        self.assertFalse(watch.expired(10.0))
        self.assertTrue(watch.expired(10.2))

    def test_backwards_time_rejected(self):
        watch=HeartbeatWatchdog(2)
        with self.assertRaises(ValueError):
            watch.observe(1)


if __name__=='__main__':
    unittest.main()
