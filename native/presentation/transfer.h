#ifndef LSB_TRANSFER_H
#define LSB_TRANSFER_H
#include <sys/socket.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#ifndef LSB_SEND
#define LSB_SEND send
#endif
/* Counts actual send attempts, including interruptions/partial writes. */
static inline int lsb_send_all(int fd,const void *data,size_t size,uint64_t *calls){
    const unsigned char *p=data;
    while(size){
        ++*calls;ssize_t n=LSB_SEND(fd,p,size,MSG_NOSIGNAL);
        if(n<0&&errno==EINTR)continue;
        if(n<=0)return -1;
        p+=n;size-=(size_t)n;
    }
    return 0;
}

#endif
