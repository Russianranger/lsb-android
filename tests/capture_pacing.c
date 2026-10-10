#define _POSIX_C_SOURCE 200809L
#include "../native/presentation/capture-pacing.h"
#include <assert.h>
#include <signal.h>
#include <stdio.h>
#include <sys/time.h>

static volatile sig_atomic_t interruptions;
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
    const uint64_t interval=12500000u;
    struct lsb_capture_schedule schedule={0};
    uint64_t started=lsb_capture_now_ns(),cpu_started=cpu_ns();
    assert(!lsb_capture_deadline(&schedule,interval));
    for(int n=0;n<5;n++)assert(!lsb_capture_deadline(&schedule,interval));
    uint64_t elapsed=lsb_capture_now_ns()-started,used=cpu_ns()-cpu_started;
    assert(elapsed>=4*interval);assert(used<elapsed/2); /* Waits block instead of spinning. */

    /* A consumer stalled across several slots must not trigger catch-up calls. */
    uint64_t origin=lsb_capture_now_ns()-3*interval;schedule.next=origin;
    assert(!lsb_capture_deadline(&schedule,interval));
    uint64_t future=schedule.next,recovered=lsb_capture_now_ns();assert(future>recovered);
    assert((future-origin)%interval==0);
    assert(!lsb_capture_deadline(&schedule,interval));
    assert(lsb_capture_now_ns()>=future);

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
