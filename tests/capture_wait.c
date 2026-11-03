#define _POSIX_C_SOURCE 200809L
#include "../native/presentation/capture-wait.h"
#include <assert.h>
#include <pthread.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include <stdio.h>
static volatile sig_atomic_t signals;
static void interrupt_wait(int signal){(void)signal;signals++;}
struct action {int fd,close_fd;};
static void *later(void *arg){
    struct action *a=arg;struct timespec delay={.tv_nsec=10000000};nanosleep(&delay,NULL);
    if(a->close_fd)close(a->fd);else assert(write(a->fd,"x",1)==1);return NULL;
}
static void pair(int fds[2]){assert(!socketpair(AF_UNIX,SOCK_STREAM,0,fds));}
int main(void){
    int x[2],client[2];pair(x);pair(client);
    struct sigaction action={.sa_handler=interrupt_wait};sigemptyset(&action.sa_mask);assert(!sigaction(SIGALRM,&action,NULL));
    struct itimerval timer={.it_interval={.tv_usec=1000},.it_value={.tv_usec=1000}},stopped={0};
    uint64_t start=lsb_wait_now();assert(!setitimer(ITIMER_REAL,&timer,NULL));
    assert(lsb_capture_wait_event(x[0],client[0],start+30000000ull)==0);
    assert(!setitimer(ITIMER_REAL,&stopped,NULL));assert(signals>1);
    assert(lsb_wait_now()-start>=30000000ull);
    assert(lsb_capture_wait_event(x[0],client[0],lsb_wait_now())==0);
    pthread_t thread;struct action a={.fd=x[1]};assert(!pthread_create(&thread,NULL,later,&a));
    assert(lsb_capture_wait_event(x[0],client[0],lsb_wait_now()+1000000000ull)==1);
    assert(!pthread_join(thread,NULL));
    assert(lsb_capture_wait_event(x[0],client[0],lsb_wait_now()+1000000000ull)==1);
    char byte;assert(read(x[0],&byte,1)==1);
    a.fd=client[1];a.close_fd=1;assert(!pthread_create(&thread,NULL,later,&a));
    assert(lsb_capture_wait_event(x[0],client[0],lsb_wait_now()+1000000000ull)==-1);
    assert(!pthread_join(thread,NULL));close(client[0]);pair(client);
    assert(write(client[1],"x",1)==1);
    assert(lsb_capture_wait_event(x[0],client[0],lsb_wait_now()+1000000000ull)==-1);
    assert(read(client[0],&byte,1)==1);close(x[1]);
    assert(lsb_capture_wait_event(x[0],client[0],lsb_wait_now()+1000000000ull)==-1);
    close(x[0]);close(client[0]);close(client[1]);
    puts("PASS: event waiter absolute timeout under EINTR, queued/late X input, disconnect and extra-request rejection");
}
