#define _POSIX_C_SOURCE 200809L
#include <time.h>
static int capture_clock_gettime(clockid_t clock,struct timespec *value);
static int capture_clock_nanosleep(clockid_t clock,int flags,const struct timespec *until,struct timespec *remaining);
#define clock_gettime capture_clock_gettime
#define clock_nanosleep capture_clock_nanosleep
#include "../native/presentation/capture-pacing.h"
#undef clock_gettime
#undef clock_nanosleep
#include <assert.h>
#include <signal.h>
#include <stdio.h>
#include <sys/time.h>

static volatile sig_atomic_t interruptions;
struct capture_clock_model {int active,calls,interrupt;uint64_t now,target,overshoot;};
static struct capture_clock_model model;
static int capture_clock_gettime(clockid_t clock,struct timespec *value){
    if(!model.active)return clock_gettime(clock,value);
    assert(clock==CLOCK_MONOTONIC);
    *value=(struct timespec){.tv_sec=(time_t)(model.now/1000000000u),.tv_nsec=(long)(model.now%1000000000u)};
    return 0;
}
static int capture_clock_nanosleep(clockid_t clock,int flags,const struct timespec *until,struct timespec *remaining){
    if(!model.active)return clock_nanosleep(clock,flags,until,remaining);
    assert(clock==CLOCK_MONOTONIC&&flags==TIMER_ABSTIME);
    uint64_t target=(uint64_t)until->tv_sec*1000000000u+(uint64_t)until->tv_nsec;
    assert(target>model.now); /* Every initialized request awaits a future slot. */
    if(model.calls)assert(target==model.target); /* EINTR retains the same slot. */
    model.target=target;model.calls++;
    if(model.interrupt){model.interrupt=0;return EINTR;}
    model.now=target+model.overshoot;return 0;
}
static void interrupted(int number){(void)number;interruptions++;}
static uint64_t cpu_ns(void){
    struct timespec t;assert(!clock_gettime(CLOCK_THREAD_CPUTIME_ID,&t));
    return (uint64_t)t.tv_sec*1000000000u+(uint64_t)t.tv_nsec;
}
static void options(void){
    struct lsb_capture_options c;
    char *baseline[]={"bridge","socket","pixels","30"};
    assert(!lsb_capture_options(4,baseline,&c));
    assert(c.display_fps==30&&c.capture_fps==30&&!c.deadline&&!c.no_shm&&!c.poll_metadata);
    baseline[3]="60";assert(!lsb_capture_options(4,baseline,&c));
    assert(c.display_fps==60&&c.capture_fps==60&&!c.deadline);
    char *rate[]={"bridge","socket","pixels","60","--capture-rate=90","--no-shm","--poll-metadata"};
    assert(!lsb_capture_options(7,rate,&c));
    assert(c.display_fps==60&&c.capture_fps==90&&!c.deadline&&c.no_shm&&c.poll_metadata);
    char *deadline[]={"bridge","socket","pixels","60","--deadline-pacing"};
    assert(!lsb_capture_options(5,deadline,&c));
    assert(c.display_fps==60&&c.capture_fps==60&&c.deadline);
    const char *invalid[][3]={
        {"30","--capture-rate=90",NULL},{"30","--deadline-pacing",NULL},
        {"90",NULL,NULL},{"60","--capture-rate=120",NULL},{"60","--unknown",NULL},
        {"60","--capture-rate=90","--deadline-pacing"},
        {"60","--deadline-pacing","--capture-rate=90"},
        {"60","--capture-rate=90","--capture-rate=90"},
        {"60","--deadline-pacing","--deadline-pacing"},
        {"60","--no-shm","--no-shm"},{"60","--poll-metadata","--poll-metadata"}
    };
    for(size_t n=0;n<sizeof(invalid)/sizeof(invalid[0]);n++){
        char *args[]={"bridge","socket","pixels",(char*)invalid[n][0],(char*)invalid[n][1],(char*)invalid[n][2]};
        assert(lsb_capture_options(invalid[n][2]?6:invalid[n][1]?5:4,args,&c)==-1);
    }
    puts("PASS: isolated native capture options retain display 30/60; duplicate, combined and unsupported modes rejected");
}
static void deadlines(void){
    /* Independent clock examples: exact tick, fractional delay and many missed
     * slots all retain the established phase and leave one future credit. */
    const uint64_t clocks[][2]={{999,1100},{1000,1100},{1100,1200},{1349,1400},{12345678,12345700}};
    for(size_t n=0;n<sizeof(clocks)/sizeof(clocks[0]);n++){
        uint64_t next=lsb_capture_advance(1000,clocks[n][0],100);
        assert(next==clocks[n][1]&&next>clocks[n][0]&&(next-1000)%100==0);
    }
    /* Exercise the actual scheduler with an independent clock, not only its
     * next-slot arithmetic. Previously a late request at 1399 returned without
     * sleeping, followed by another response at 1400: a one-unit catch-up pair. */
    const uint64_t services[][2]={{999,1000},{1000,1100},{1099,1100},{1399,1400},{1400,1500},{12345678,12345700}};
    for(size_t n=0;n<sizeof(services)/sizeof(services[0]);n++){
        model=(struct capture_clock_model){.active=1,.now=services[n][0]};
        struct lsb_capture_schedule simulated={.next=1000};
        assert(!lsb_capture_deadline(&simulated,100));
        assert(model.calls==1&&model.now==services[n][1]&&model.target==services[n][1]);
        assert(simulated.next==services[n][1]+100);
        model.calls=0;
        assert(!lsb_capture_deadline(&simulated,100));
        assert(model.calls==1&&model.now==services[n][1]+100);
    }
    model=(struct capture_clock_model){.active=1,.now=1399};
    struct lsb_capture_schedule late={.next=1000};
    for(int n=0;n<5;n++){
        model.calls=0;assert(!lsb_capture_deadline(&late,100));
        assert(model.calls==1&&model.now==(uint64_t)(1400+n*100));
    }
    assert(model.now-1399==401); /* Five replies retain the unchanged four-period cap. */
    model=(struct capture_clock_model){.active=1,.now=999,.overshoot=349};
    struct lsb_capture_schedule simulated={.next=1000};
    assert(!lsb_capture_deadline(&simulated,100));
    assert(model.target==1000&&model.now==1349&&simulated.next==1400);
    model.calls=0;model.overshoot=0;model.interrupt=1;
    assert(!lsb_capture_deadline(&simulated,100));
    assert(model.calls==2&&model.target==1400&&model.now==1400&&simulated.next==1500);
    model=(struct capture_clock_model){.active=1,.now=900};simulated.next=0;
    assert(!lsb_capture_deadline(&simulated,100));
    assert(model.calls==0&&model.now==900&&simulated.next==1000);
    model.active=0;
    const uint64_t interval=12500000u;
    struct lsb_capture_schedule schedule={0};
    uint64_t started=lsb_capture_now_ns(),cpu_started=cpu_ns();
    assert(!lsb_capture_deadline(&schedule,interval));
    for(int n=0;n<5;n++)assert(!lsb_capture_deadline(&schedule,interval));
    uint64_t elapsed=lsb_capture_now_ns()-started,used=cpu_ns()-cpu_started;
    assert(elapsed>=4*interval);assert(used<elapsed/2); /* Waits block instead of spinning. */

    /* A consumer stalled across several slots must not trigger catch-up calls. */
    uint64_t origin=lsb_capture_now_ns()-3*interval;schedule.next=origin;
    started=lsb_capture_now_ns();
    assert(!lsb_capture_deadline(&schedule,interval));
    uint64_t future=schedule.next,recovered=lsb_capture_now_ns();assert(future>recovered);
    assert((future-origin)%interval==0);
    for(int n=0;n<4;n++)assert(!lsb_capture_deadline(&schedule,interval));
    assert(lsb_capture_now_ns()-started>=4*interval);

    /* Signals restart the same absolute sleep, rather than releasing early. */
    struct sigaction action={0};action.sa_handler=interrupted;sigemptyset(&action.sa_mask);
    assert(!sigaction(SIGALRM,&action,NULL));
    struct itimerval timer={.it_value={.tv_usec=3000}};
    started=lsb_capture_now_ns();schedule.next=started+interval;
    assert(!setitimer(ITIMER_REAL,&timer,NULL));
    assert(!lsb_capture_deadline(&schedule,interval));
    assert(interruptions==1&&lsb_capture_now_ns()-started>=interval);

    /* Reconnects create a new schedule, independent of an old future slot. */
    schedule.next=lsb_capture_now_ns()+1000000000u;
    struct lsb_capture_schedule fresh={0};
    assert(!lsb_capture_deadline(&fresh,interval));
    assert(fresh.next<schedule.next&&fresh.next>lsb_capture_now_ns());
    puts("PASS: actual monotonic deadline waits block, survive interruption, skip stale slots without bursts and reset for reconnect");
}
int main(void){options();deadlines();return 0;}
