#ifndef LSB_CAPTURE_WAIT_H
#define LSB_CAPTURE_WAIT_H
#include <stdint.h>
#include <time.h>
#include <errno.h>
#include <poll.h>
/* Caller drains its Xlib queue before waiting. Do not consume either fd here:
 * Xlib owns X input; the producer still owes exactly one client response. */
static inline uint64_t lsb_wait_now(void){
    struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);
    return (uint64_t)t.tv_sec*1000000000ull+(uint64_t)t.tv_nsec;
}
static inline int lsb_capture_wait_event(int xfd,int client,uint64_t deadline){
    for(;;){
        uint64_t now=lsb_wait_now();if(now>=deadline)return 0;
        int ms=(int)((deadline-now+999999ull)/1000000ull);
        struct pollfd fds[2]={{.fd=xfd,.events=POLLIN},{.fd=client,.events=POLLIN}};
        int result=poll(fds,2,ms);
        if(result<0){if(errno==EINTR)continue;return -1;}
        if(fds[1].revents||(fds[0].revents&(POLLERR|POLLHUP|POLLNVAL)))return -1;
        if(fds[0].revents&POLLIN)return 1;
    }
}
#endif
