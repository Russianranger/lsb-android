import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('accelerated_start',Path(__file__).resolve().parents[2]/'server/accelerated_start.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class AcceleratedStartTest(unittest.TestCase):
    def test_closed_or_incorrect_pipe_never_opens_manager(self):
        for value in (b'',b'wrong\n',module.RELEASE.rstrip(b'\n'),b'x'*128+module.RELEASE):
            with patch.object(module.sys,'stdin',io.TextIOWrapper(io.BytesIO(value))),patch.object(module.runpy,'run_path') as manager:
                self.assertEqual(module.main(),1);manager.assert_not_called()

    def test_exact_parent_release_executes_the_existing_manager(self):
        with patch.object(module.sys,'stdin',io.TextIOWrapper(io.BytesIO(module.RELEASE))),patch.object(module.runpy,'run_path') as manager:
            self.assertEqual(module.main(),0)
            manager.assert_called_once_with('/opt/lsb-server/manager.py',run_name='__main__')
