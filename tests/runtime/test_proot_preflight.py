import importlib.util
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('proot_preflight',Path(__file__).resolve().parents[2]/'runtime/proot_preflight.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class PreflightTest(unittest.TestCase):
    def test_real_file_thread_socket_fork_and_shared_memory_checks_clean_up(self):
        with tempfile.TemporaryDirectory() as temporary:
            module.check(sysvipc=True,directory=temporary)
            self.assertEqual(list(Path(temporary).iterdir()),[])


if __name__=='__main__':unittest.main()
