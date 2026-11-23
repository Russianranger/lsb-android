from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from display_fixture_ready import wait_for_ready


class DisplayReadinessTest(unittest.TestCase):
    def run_fixture(self, completed_at=(), ready_at=None, exited_at=None):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            elapsed = 0

            def sleep(_):
                nonlocal elapsed
                elapsed += 1
                if elapsed in completed_at:
                    (folder / f'native-{elapsed}.passed').touch()
                if elapsed == ready_at:
                    (folder / 'ready').touch()

            process = Mock()
            process.poll.side_effect = lambda: 1 if elapsed == exited_at else None
            with patch('display_fixture_ready.time.monotonic', side_effect=lambda: elapsed), \
                 patch('display_fixture_ready.time.sleep', side_effect=sleep):
                wait_for_ready(folder, process, 'test.log', stall_seconds=3, maximum_seconds=8)

    def test_completed_configurations_extend_readiness(self):
        self.run_fixture(completed_at=[2, 4], ready_at=6)

    def test_no_progress_and_stalled_configuration(self):
        with self.assertRaisesRegex(TimeoutError, 'stalled.*0 configurations passed'):
            self.run_fixture()
        with self.assertRaisesRegex(TimeoutError, 'stalled.*1 configurations passed'):
            self.run_fixture(completed_at=[2])

    def test_progress_cannot_exceed_hard_deadline(self):
        with self.assertRaisesRegex(TimeoutError, 'exceeded 8s'):
            self.run_fixture(completed_at=[2, 4, 6, 7])

    def test_fixture_exit_is_reported_immediately(self):
        with self.assertRaisesRegex(RuntimeError, 'fixture stopped'):
            self.run_fixture(exited_at=1)
