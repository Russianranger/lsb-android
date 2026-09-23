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
    proc=self.spawn(self.wine_command(r'Z:\fixtures\cpu-dispatch.exe'),'cpu-dispatch.log',fixed_output=True)
    self.wait(proc,45,'CPU dispatch and affine math fixture')
    self.logs[-1].thread.join(3)
    cpu=Path('/logs/cpu-dispatch.log').read_text(errors='replace')
    assert 'LSB_CPU_MATH samples=72 failures=0 PASS' in cpu and 'MISMATCH' not in cpu and ' FAIL' not in cpu,cpu[-2000:]
    print('PASS: real Windows/CPUID feature agreement and 72 full-stack x87/SSE affine samples',flush=True)
    proc=self.spawn(self.wine_command(r'Z:\fixtures\game-math.exe'),'game-math.log',fixed_output=True)
    self.wait(proc,45,'Game math diagnostic isolation fixture')
    self.logs[-1].thread.join(3)
    math=Path('/logs/game-math.log').read_text(errors='replace')
    assert 'LSB_GAME_MATH samples=192 guards=4 fp_preserved=1 corruptions_detected=16 PASS' in math and ' FAIL' not in math,math[-2000:]
    print('PASS: game math oracle, invalid-image rejection, injected error detection and floating-state preservation',flush=True)
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
    # The working and broken Thor captures share these texture formats/states.
    # Exercise their data path with synthetic assets and an independent oracle.
    texture_log='texture-submission-'+self.req['dxvk_version']+'.log'
    proc=self.spawn(self.wine_command(r'Z:\fixtures\texture-submission.exe'),texture_log,fixed_output=True)
    self.wait(proc,60,'Compressed texture and UP submission fixture')
    self.logs[-1].thread.join(3)
    text=Path('/logs',texture_log).read_text(errors='replace')
    match=re.search(r'^LSB_TEXTURE_SUBMISSIONS formats=3 frames=4 draws=1512 samples=144 x87=([0-9a-f]{4}) PASS\s*$',text,re.M)
    assert match and ' FAIL' not in text,text[-4000:]
    Path('/logs',texture_log.replace('.log','.json')).write_text(json.dumps({
        'engine':self.engine,'dxvk':self.state['dxvk_selected'],
        'fex_mode':self.state.get('fex_arithmetic',{}).get('mode'),
        'formats':['A8R8G8B8','DXT1','DXT3'],'texture_size':[1024,1024],
        'frames':4,'draws':1512,'samples':144,'x87_control':int(match[1],16),
        'vertex_processing':'hardware','pixel_checks':'passed',
        'coverage':'Synthetic point-filtered MODULATE2X UP tiles; opaque, alpha-test/blend and additive blending. Not a game or Adreno capture.'},indent=2))
    print('PASS: compressed DXT1/DXT3 and ARGB updates; 1512 UP draws and 144 MODULATE2X/alpha/additive pixels',flush=True)

supervisor.Supervisor.check_graphics_pixels=checked
supervisor.main()
