"""Exercise the on-device world lifetime, independently of phone or server clock speed."""
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'server'))
from solo_world import WorldSession, auction, conquest


class SoloWorldLifecycleTests(unittest.TestCase):
    def test_one_market_tick_per_interval_and_stamp_after_play(self):
        tick_times = [100, 100, 100, 399, 400, 401, 700]
        query = Mock()
        with patch('solo_world.time.monotonic', side_effect=tick_times), \
                patch.object(conquest, 'start', return_value=87) as start, \
                patch.object(conquest, 'heartbeat') as heartbeat, \
                patch.object(conquest, 'stop') as stop, patch.object(auction, 'tick') as tick:
            world = WorldSession(query)
            world.start()
            for _ in range(5):
                world.service()
            world.stop()
        start.assert_called_once_with(query)
        self.assertEqual(tick.call_count, 3)
        self.assertEqual(heartbeat.call_count, 2)
        stop.assert_called_once_with(query, 87)

    def test_transient_market_error_keeps_running_and_retries_after_cooldown(self):
        with patch('solo_world.time.monotonic', side_effect=[0, 0, 1, 300]), \
                patch.object(conquest, 'start', return_value=1), \
                patch.object(conquest, 'heartbeat', side_effect=RuntimeError('busy')), \
                patch.object(conquest, 'stop') as stop, \
                patch.object(auction, 'tick', side_effect=[RuntimeError('busy'), {}]) as tick:
            world = WorldSession(Mock())
            world.start()
            world.service()
            world.service()
            world.stop()
            self.assertEqual(tick.call_count, 2)
            stop.assert_called_once()
