#ifndef LSB_CAPTURE_PACING_H
#define LSB_CAPTURE_PACING_H
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

struct lsb_capture_options {
    int display_fps,capture_fps,no_shm,poll_metadata,deadline;
};
static inline int lsb_capture_options(int argc,char **argv,struct lsb_capture_options *out){
    if(argc<4||argc>7)return -1;
    int fps=atoi(argv[3]);if(fps!=30&&fps!=60)return -1;
    *out=(struct lsb_capture_options){.display_fps=fps,.capture_fps=fps};
    for(int i=4;i<argc;i++){
        if(!strcmp(argv[i],"--no-shm")&&!out->no_shm)out->no_shm=1;
        else if(!strcmp(argv[i],"--poll-metadata")&&!out->poll_metadata)out->poll_metadata=1;
        else if(!strcmp(argv[i],"--capture-rate=90")&&fps==60&&out->capture_fps==60&&!out->deadline)out->capture_fps=90;
        else if(!strcmp(argv[i],"--deadline-pacing")&&fps==60&&out->capture_fps==60&&!out->deadline)out->deadline=1;
        else return -1;
    }
    return 0;
}
static inline uint64_t lsb_capture_now_ns(void){
    struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);
    return (uint64_t)t.tv_sec*1000000000u+(uint64_t)t.tv_nsec;
}
struct lsb_capture_schedule {uint64_t next;};
static inline uint64_t lsb_capture_advance(uint64_t target,uint64_t now,uint64_t interval){
    return target+((now>=target?(now-target)/interval:0)+1)*interval;
}
/* Keep the original clock grid after a late consumer or sleep overshoot. Skip
 * obsolete slots to the first strictly future deadline; never replay them as
 * immediately serviced requests. Only a new connection establishes a phase. */
static inline int lsb_capture_deadline(struct lsb_capture_schedule *schedule,uint64_t interval){
    uint64_t now=lsb_capture_now_ns(),target=schedule->next;
    if(!target){schedule->next=now+interval;return 0;}
    if(now>=target)target=lsb_capture_advance(target,now,interval);
    struct timespec until={.tv_sec=(time_t)(target/1000000000u),.tv_nsec=(long)(target%1000000000u)};
    int error;do{error=clock_nanosleep(CLOCK_MONOTONIC,TIMER_ABSTIME,&until,NULL);}while(error==EINTR);
    if(error)return -1;
    now=lsb_capture_now_ns();schedule->next=lsb_capture_advance(target,now,interval);
    return 0;
}
#endif
