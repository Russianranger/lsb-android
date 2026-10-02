import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('proot_preflight',Path(__file__).resolve().parents[2]/'runtime/proot_preflight.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class PreflightTest(unittest.TestCase):
    def test_real_file_thread_socket_fork_and_shared_memory_checks_clean_up(self):
        with tempfile.TemporaryDirectory() as temporary:
            module.check(sysvipc=True,directory=temporary)
            self.assertEqual(list(Path(temporary).iterdir()),[])

    def test_server_checks_private_tcp_and_fsync_and_native_exec_without_starting_database(self):
        real_run=module.subprocess.run
        def run(command,**kwargs):
            if command[0]=='/usr/sbin/mariadbd':
                self.assertEqual(command,['/usr/sbin/mariadbd','--no-defaults','--version'])
                self.assertEqual(kwargs['stdout'],module.subprocess.DEVNULL)
                return module.subprocess.CompletedProcess(command,0)
            return real_run(command,**kwargs)
        with tempfile.TemporaryDirectory() as temporary,patch.object(module.subprocess,'run',side_effect=run) as native:
            module.check(server=True,directory=temporary)
            self.assertEqual(list(Path(temporary).iterdir()),[])
            self.assertEqual(native.call_count,2)


if __name__=='__main__':unittest.main()
