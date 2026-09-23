"""Fixed numeric metadata from the optional, bounded D3D8 observer."""
import re
import threading

class GraphicsDiagnostics:
    FIELDS={
        'device':('behavior_flags','width','height'),
        'frame':('frame','draws','up_draws','buffer_draws','sampled_vertices',
                 'nonfinite_components','extreme_screen_vertices','invalid_rhw_vertices',
                 'unavailable_samples','failed_draws','first_hresult','buffer_vertices','other_thread_draws'),
        'state':('frame','fvf','fill_mode','cull_mode','z_enable','z_func','alpha_test',
                 'alpha_func','alpha_ref','alpha_blend','src_blend','dst_blend',
                 'color_write_mask','x87_control','mxcsr','projection_nonfinite',
                 'texture0_color_op','texture0_color_arg1','texture0_color_arg2','texture0_alpha_op',
                 'texture0_bound','texture0_width','texture0_height','texture0_format'),
        'complete':('reason','frame','draws','hooks_restored'),
        'coverage':('reason',),
    }
    def __init__(self):
        self.lock=threading.Lock();self.records=[];self.dropped=0
    def line(self,data):
        match=re.fullmatch(rb'lsb-d3d8-v1 (device|frame|state|complete|coverage)((?: [0-9a-f]{8})+)',data)
        if not match:return
        tag=match[1].decode();values=match[2].split();fields=self.FIELDS[tag]
        if len(values)!=len(fields):return
        row=dict(event=tag,**dict(zip(fields,(int(v,16) for v in values))))
        with self.lock:
            if len(self.records)>=64:
                # Retain creation and distinct states; replace oldest frame/coverage.
                victim=next((i for i,r in enumerate(self.records) if r['event'] in ('frame','coverage')),None)
                self.dropped=min(1000000,self.dropped+1)
                if victim is None:return
                self.records.pop(victim)
            self.records.append(row)
    def snapshot(self):
        with self.lock:
            if not self.records:return None
            return {'format':1,'policy':'fixed_numeric_metadata_only','records':[dict(r) for r in self.records],
                    'dropped_records':self.dropped,'limits':{'frames':1024,'seconds':60,'draws':65536,
                    'sample_period_frames':32,'sampled_draws_per_frame':8,'vertices_per_draw':8,
                    'tracked_buffers':32,'bytes_per_buffer':32768},
                    'coverage':'First device/creation thread; sampled FVF positions only. Indexed draws sample the declared vertex range, not decoded indices. Buffer copies cover the last observed write range; unavailable or other-thread samples are explicit. Counters flag clues, not proof of invalid game data.'}
