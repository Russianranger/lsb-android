#include "../native/presentation/capture-pacing.h"
#include <assert.h>
#include <stdio.h>
int main(void){
    for(unsigned fps=30;fps<=60;fps+=30){
        uint64_t step=1000000000ull/fps,start=1000000000ull,next=0;
        for(unsigned n=0;n<6000;n++){
            uint64_t expected=start+n*step,arrival=n?expected-step/3:start;
            uint64_t due=lsb_capture_deadline(next,arrival,step);
            assert(due==expected);
            next=lsb_capture_next(due,due+200000ull,step);
        }
        uint64_t stalled=next+5000000000ull;
        assert(lsb_capture_deadline(next,stalled,step)==stalled);
        assert(lsb_capture_next(next,stalled,step)==stalled+step);
        assert(lsb_capture_deadline(stalled+step,stalled+1,step)==stalled+step);
        assert(lsb_capture_deadline(next,next,step)==next);
    }
    puts("PASS: capture deadlines retain phase at 30/60Hz, bounded late-wake correction and no catch-up burst");
}
