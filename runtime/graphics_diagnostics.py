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
        'batch':('group','phase','fvf','stride','primitive','indexed','texture0_type',
                 'texture0_width','texture0_height','texture0_format','alpha_test','alpha_func',
                 'alpha_ref','alpha_blend','src_blend','dst_blend','texture0_color_op','texture0_alpha_op',
                 'texture0_coordinate_index','texture0_transform_flags','texture0_color_arg1',
                 'texture0_color_arg2','texture0_alpha_arg1','texture0_alpha_arg2'),
        'batch_stats':('group','first_frame','last_frame','draws','sampled_vertices','omitted_vertices',
                 'invalid_indices','unsupported_draws','nonfinite_positions','invalid_rhw',
                 'uv_vertices','nonfinite_uv','large_uv','zero_uv','color_vertices','zero_alpha','opaque_alpha'),
        'batch_bounds':('group','range_mask','min_x_bits','max_x_bits','min_y_bits','max_y_bits',
                 'min_z_bits','max_z_bits','min_rhw_bits','max_rhw_bits',
                 'min_u_bits','max_u_bits','min_v_bits','max_v_bits'),
        'batch_limit':('frame','reason'),
    }
    def __init__(self):
        self.lock=threading.Lock();self.records=[];self.dropped=0;self.batches={};self.batch_limits=[]
    def line(self,data):
        match=re.fullmatch(rb'lsb-d3d8-v1 (device|frame|state|complete|coverage|batch|batch_stats|batch_bounds|batch_limit)((?: [0-9a-f]{8})+)',data)
        if not match:return
        tag=match[1].decode();values=match[2].split();fields=self.FIELDS[tag]
        if len(values)!=len(fields):return
        row=dict(event=tag,**dict(zip(fields,(int(v,16) for v in values))))
        with self.lock:
            if tag.startswith('batch'):
                if tag=='batch_limit':
                    if len(self.batch_limits)<4:self.batch_limits.append(row)
                elif row['group']<96:
                    self.batches[(tag,row['group'])]=row
                return
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
            return {'format':2,'policy':'fixed_numeric_metadata_only','records':[dict(r) for r in self.records],
                    'batches':[dict(r) for r in self.batches.values()],
                    'batch_limits':[dict(r) for r in self.batch_limits],
                    'dropped_records':self.dropped,'limits':{'frames':8192,'seconds':180,'draws':2097152,
                    'sample_period_frames':32,'sampled_draws_per_frame':8,'vertices_per_draw':8,
                    'tracked_buffers':32,'bytes_per_buffer':32768,'batch_draws_per_frame':1024,
                    'batch_vertices_per_draw':64,'batch_groups_per_phase':24,'batch_phase_seconds':[0,30,60,120,180]},
                    'coverage':'Creation thread only. Original records sample first-eight-draw positions; indexed records sample the declared vertex range. Batches additionally sample up to 1024 UP draws every 32 frames with up to 64 distributed vertices, decoding submitted UP indices. They summarize finite position/UV bounds and diffuse alpha by layout, texture descriptor and state in four time windows. No buffered-draw batches or texture contents. Unsupported layouts, omitted vertices and coverage limits are explicit. Large UV/zero alpha values can be intentional; summaries do not prove correct game data.'}
