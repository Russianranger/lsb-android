"""CI harness: run the normal probe plus an observed pixel fixture, same environment.

No production request field or environment toggle enables this fixture.
"""
import re,sys,json
from pathlib import Path
sys.path.insert(0,'/opt/lsb')
import supervisor
from graphics_diagnostics import GraphicsDiagnostics
original=supervisor.Supervisor.check_graphics_pixels

def checked(self):
    original(self)
    proc=self.spawn(self.wine_command(r'P:\graphics-check.exe','--trace-pixels'),'graphics-trace-fixture.log',fixed_output=True)
    self.wait(proc,60,'Observed D3D8 pixel fixture')
    self.logs[-1].thread.join(3)
    text=Path('/logs/graphics-trace-fixture.log').read_text(errors='replace')
    assert sorted(re.findall(r'^LSB_D3D8_PIXELS mode=(swvp|hwvp) frames=4 samples=64 PASS\s*$',text,re.M))==['hwvp','swvp'],text[-3000:]
    evidence=GraphicsDiagnostics()
    for line in text.lower().encode().splitlines():evidence.line(line)
    report=evidence.snapshot();assert report is not None,'No real D3D8 observation'
    rows=report['records'];frames=[r for r in rows if r['event']=='frame']
    assert frames and frames[-1]['frame']==4,report
    final=frames[-1]
    assert final['draws']==32 and final['up_draws']==24 and final['buffer_draws']==8,report
    assert final['sampled_vertices']==32 and final['buffer_vertices']==8,report
    for field in ('nonfinite_components','extreme_screen_vertices','invalid_rhw_vertices','unavailable_samples','failed_draws','other_thread_draws'):
        assert final[field]==0,(field,report)
    assert any(r['event']=='complete' and r['reason']==4 and r['hooks_restored']==1 for r in rows),report
    assert not any(r['event']=='coverage' for r in rows),report
    assert any(r['event']=='state' and r['alpha_blend']==1 for r in rows),report
    assert any(r['event']=='state' and r['alpha_test']==1 for r in rows),report
    Path('/logs/graphics-trace-fixture.json').write_text(json.dumps(report,indent=2))
    print('PASS: observed D3D8 pixels unchanged; actual UP/indexed-buffer geometry, state blocks, alpha and detach verified',flush=True)

supervisor.Supervisor.check_graphics_pixels=checked
supervisor.main()
